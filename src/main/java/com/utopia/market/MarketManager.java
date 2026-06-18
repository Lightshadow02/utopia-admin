package com.utopia.market;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.util.Messages;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Logique du marche public : reservation d'un emplacement, gestion des offres, achat (avec taxe
 * municipale), expiration des offres (48 h) et transfert vers la recuperation de la mairie.
 *
 * <p>Taxe : vendeur = floor(prix x 0,75) = prix*3/4 ; mairie = prix - vendeur. Aucune piece n'est
 * jamais creee ni detruite. Toutes les operations passent par le thread serveur (pas de concurrence).
 */
public final class MarketManager {

    public static final int MAX_OFFERS_PER_STALL = 10;
    /** Duree de vie d'une offre : 48 heures (heure murale, robuste aux redemarrages). */
    public static final long OFFER_DURATION_MS = 48L * 3600L * 1000L;

    /** Admins en mode "definir un stand" : le prochain bloc casse devient un emplacement de vente. */
    private static final Set<UUID> STALL_SELECT = ConcurrentHashMap.newKeySet();

    /** Admins en mode "definir les emplacements d'affichage" d'un stand donne (cle "dim;x;y;z"). */
    private static final Map<UUID, String> SPOT_SELECT = new ConcurrentHashMap<>();

    private MarketManager() {
    }

    public static void startStallSelect(UUID id) {
        STALL_SELECT.add(id);
    }

    public static boolean isSelectingStall(UUID id) {
        return STALL_SELECT.contains(id);
    }

    public static void clearStallSelect(UUID id) {
        STALL_SELECT.remove(id);
    }

    // -------- Emplacements d'affichage (spots) --------

    public static void startSpotSelect(UUID id, String stallKey) {
        SPOT_SELECT.put(id, stallKey);
    }

    public static boolean isSelectingSpot(UUID id) {
        return SPOT_SELECT.containsKey(id);
    }

    /** La cle du stand pour lequel ce joueur definit des emplacements, ou null. */
    public static String spotSelectStall(UUID id) {
        return SPOT_SELECT.get(id);
    }

    public static void clearSpotSelect(UUID id) {
        SPOT_SELECT.remove(id);
    }

    /** Ajoute ou retire (bascule) un emplacement d'affichage sur ce stand ; renvoie true si ajoute. */
    public static boolean toggleDisplaySpot(MinecraftServer server, MarketData.Stall stall, BlockPos pos) {
        BlockPos p = pos.immutable();
        boolean added;
        if (stall.displaySpots.removeIf(s -> s.equals(p))) {
            added = false;
        } else {
            stall.displaySpots.add(p);
            added = true;
        }
        MarketData.get(server).setDirty();
        return added;
    }

    /** Part du vendeur (75 %, arrondi inferieur). */
    public static long sellerShare(long price) {
        return price * 3L / 4L;
    }

    /** Part de la mairie (15 %, arrondi inferieur). */
    public static long mairieShare(long price) {
        return price * 15L / 100L;
    }

    /** Part detruite (le reste, ~10 %) : ces pieces disparaissent de l'economie. */
    public static long burnedShare(long price) {
        return price - sellerShare(price) - mairieShare(price);
    }

    // -------- Reservation --------

    public enum ClaimResult { OK, NO_FREE, ALREADY_OWNS, TAKEN }

    public static ClaimResult claim(ServerPlayer player, MarketData.Stall stall) {
        MarketData data = MarketData.get(player.server);
        if (!stall.isFree()) {
            return ClaimResult.TAKEN;
        }
        if (data.stallOf(player.getUUID()) != null) {
            return ClaimResult.ALREADY_OWNS;
        }
        stall.owner = player.getUUID();
        stall.ownerName = player.getGameProfile().getName();
        data.setDirty();
        return ClaimResult.OK;
    }

    /** Libere un emplacement : remet les objets invendus au proprietaire (ou recuperation si absent). */
    public static void release(ServerPlayer actor, MarketData.Stall stall) {
        MinecraftServer server = actor.server;
        ServerPlayer owner = stall.owner != null ? server.getPlayerList().getPlayer(stall.owner) : null;
        for (MarketData.Offer o : stall.offers) {
            if (owner != null) {
                ItemHandlerHelper.giveItemToPlayer(owner, o.stack.copy());
            } else if (stall.owner != null) {
                MarketData.get(server).addRecovery(new MarketData.RecoveryEntry(
                        stall.owner, stall.ownerName, o.stack.copy(), System.currentTimeMillis()));
            }
        }
        freeStall(server, stall);
    }

    /** Retire un stand (bloc casse) : les offres invendues partent en recuperation pour le proprietaire. */
    public static void removeStall(MinecraftServer server, net.minecraft.resources.ResourceLocation dim,
                                   net.minecraft.core.BlockPos pos) {
        MarketData data = MarketData.get(server);
        MarketData.Stall stall = data.stallAt(dim, pos);
        if (stall != null && stall.owner != null) {
            long now = System.currentTimeMillis();
            for (MarketData.Offer o : stall.offers) {
                data.addRecovery(new MarketData.RecoveryEntry(stall.owner, stall.ownerName, o.stack.copy(), now));
            }
        }
        data.removeStall(dim, pos);
    }

    /**
     * Force l'expiration de toutes les offres d'un stand (action maire/op) : les objets invendus partent
     * en recuperation pour le proprietaire et l'emplacement est libere. Renvoie le nombre d'offres expirees.
     */
    public static int forceExpire(MinecraftServer server, MarketData.Stall stall) {
        if (stall.owner == null || stall.offers.isEmpty()) {
            return 0;
        }
        MarketData data = MarketData.get(server);
        long now = System.currentTimeMillis();
        int n = stall.offers.size();
        for (MarketData.Offer o : stall.offers) {
            data.addRecovery(new MarketData.RecoveryEntry(stall.owner, stall.ownerName, o.stack.copy(), now));
        }
        freeStall(server, stall);
        return n;
    }

    private static void freeStall(MinecraftServer server, MarketData.Stall stall) {
        stall.owner = null;
        stall.ownerName = null;
        stall.offers.clear();
        MarketData.get(server).setDirty();
    }

    // -------- Offres --------

    public enum OfferResult { OK, NOT_OWNER, FULL, EMPTY_HAND }

    /**
     * Ajoute une offre a partir de l'objet en main du joueur (consomme la pile) au prix donne.
     */
    public static OfferResult addOfferFromHand(ServerPlayer player, MarketData.Stall stall, long price) {
        if (stall.owner == null || !stall.owner.equals(player.getUUID())) {
            return OfferResult.NOT_OWNER;
        }
        if (stall.offers.size() >= MAX_OFFERS_PER_STALL) {
            return OfferResult.FULL;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return OfferResult.EMPTY_HAND;
        }
        ItemStack sold = held.copy();
        player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        stall.offers.add(new MarketData.Offer(sold, Math.max(0, price),
                System.currentTimeMillis() + OFFER_DURATION_MS));
        MarketData.get(player.server).setDirty();
        return OfferResult.OK;
    }

    /** Retire une offre et rend les objets au proprietaire (ne libere pas l'emplacement). */
    public static void cancelOffer(ServerPlayer player, MarketData.Stall stall, int index) {
        if (index < 0 || index >= stall.offers.size()) {
            return;
        }
        if (stall.owner == null || !stall.owner.equals(player.getUUID())) {
            return;
        }
        MarketData.Offer o = stall.offers.remove(index);
        ItemHandlerHelper.giveItemToPlayer(player, o.stack.copy());
        MarketData.get(player.server).setDirty();
    }

    // -------- Achat --------

    public enum BuyResult { OK, GONE, OWN, POOR, INVALID }

    /**
     * Achat de {@code qty} objets d'une offre au PRIX UNITAIRE ({@code offer.price} = prix par objet).
     * L'offre est decrementee de {@code qty} (retiree si tout est achete).
     */
    public static BuyResult buy(ServerPlayer buyer, MarketData.Stall stall, int index, int qty) {
        MinecraftServer server = buyer.server;
        if (stall.owner == null || index < 0 || index >= stall.offers.size()) {
            return BuyResult.GONE;
        }
        if (buyer.getUUID().equals(stall.owner)) {
            return BuyResult.OWN;
        }
        MarketData.Offer offer = stall.offers.get(index);
        int available = offer.stack.getCount();
        if (qty < 1 || qty > available) {
            return BuyResult.INVALID;
        }
        long total = offer.price * (long) qty;
        if (EconomyManager.getBalance(server, buyer.getUUID()) < total) {
            return BuyResult.POOR;
        }
        // Prelevement AVANT toute remise (atomique sur le thread serveur : pas de double-achat).
        UUID seller = stall.owner;
        ItemStack bought = offer.stack.copy();
        bought.setCount(qty);
        if (qty >= available) {
            stall.offers.remove(index);
        } else {
            offer.stack.shrink(qty);
        }

        EconomyManager.remove(server, buyer.getUUID(), total);
        long sShare = sellerShare(total);
        long mShare = mairieShare(total);
        // Le reste (~10 %) n'est credite a personne : ces pieces sont detruites (deflation).
        EconomyManager.add(server, seller, sShare);
        EconomyManager.add(server, MarketData.MAIRIE_UUID, mShare);
        ItemHandlerHelper.giveItemToPlayer(buyer, bought);

        ServerPlayer sellerOnline = server.getPlayerList().getPlayer(seller);
        if (sellerOnline != null) {
            sellerOnline.sendSystemMessage(Messages.success("Vente : "
                    + qty + "x " + bought.getHoverName().getString()
                    + " a " + buyer.getGameProfile().getName() + " (+" + EconomyManager.format(sShare)
                    + " ; mairie " + EconomyManager.format(mShare)
                    + ", detruit " + EconomyManager.format(burnedShare(total)) + ")."));
        }

        if (stall.offers.isEmpty()) {
            freeStall(server, stall); // toutes les offres vendues -> emplacement libere
        } else {
            MarketData.get(server).setDirty();
        }
        return BuyResult.OK;
    }

    // -------- Expiration (48 h) --------

    /** A appeler periodiquement : deplace les offres expirees vers la recuperation, libere les stands vides. */
    public static void tickExpiry(MinecraftServer server) {
        MarketData data = MarketData.get(server);
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (MarketData.Stall stall : data.stalls()) {
            if (stall.owner == null || stall.offers.isEmpty()) {
                continue;
            }
            boolean removedAny = stall.offers.removeIf(o -> {
                if (now >= o.expiryMillis) {
                    data.addRecovery(new MarketData.RecoveryEntry(stall.owner, stall.ownerName, o.stack.copy(), now));
                    return true;
                }
                return false;
            });
            if (removedAny) {
                dirty = true;
                if (stall.offers.isEmpty()) {
                    stall.owner = null;
                    stall.ownerName = null;
                }
            }
        }
        if (dirty) {
            data.setDirty();
        }
    }
}
