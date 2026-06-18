package com.utopia.market;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Menus du marche public : reservation / gestion de son stand, achat sur un stand, recuperation (admin). */
public final class MarketMenus {

    private MarketMenus() {
    }

    /** Clic droit sur un stand : reserve (si libre), gere (le sien) ou achete (celui d'un autre). */
    public static void openStall(ServerPlayer player, MarketData.Stall stall) {
        if (stall.isFree()) {
            MarketManager.ClaimResult r = MarketManager.claim(player, stall);
            switch (r) {
                case ALREADY_OWNS -> player.sendSystemMessage(Messages.warn("Tu as deja un emplacement de vente actif."));
                case TAKEN, NO_FREE -> player.sendSystemMessage(Messages.warn("Cet emplacement n'est plus libre."));
                default -> {
                    player.sendSystemMessage(Messages.success("Emplacement reserve ! Tiens un objet en main et clique 'Ajouter'."));
                    openManage(player, stall);
                }
            }
            return;
        }
        if (stall.owner.equals(player.getUUID())) {
            openManage(player, stall);
        } else {
            openBuy(player, stall);
        }
    }

    // -------- Gestion de son stand --------

    private static void openManage(ServerPlayer player, MarketData.Stall stall) {
        if (stall.owner == null || !stall.owner.equals(player.getUUID())) {
            openStall(player, stall);
            return;
        }
        Component title = Icons.label("Mon emplacement (" + stall.offers.size() + "/"
                + MarketManager.MAX_OFFERS_PER_STALL + ")", ChatFormatting.GOLD);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int i = 0; i < stall.offers.size(); i++) {
            final int idx = i;
            MarketData.Offer o = stall.offers.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(o.stack.getCount() + "x " + o.stack.getHoverName().getString(), ChatFormatting.AQUA),
                    Icons.label(o.price + " Utopieces /u (" + remaining(o) + ")", ChatFormatting.GOLD),
                    Icons.label("Retirer", ChatFormatting.RED),
                    sp -> {
                        MarketManager.cancelOffer(sp, stall, idx);
                        openManage(sp, stall);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucune offre", ChatFormatting.GRAY),
                    Icons.label("tiens un objet en main", ChatFormatting.DARK_GRAY), null, null));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Ajouter (objet en main)", ChatFormatting.GREEN),
                sp -> promptAddOffer(sp, stall)));
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Liberer", ChatFormatting.RED),
                sp -> {
                    MarketManager.release(sp, stall);
                    sp.sendSystemMessage(Messages.success("Emplacement libere (objets invendus recuperes)."));
                    Menus.close(sp);
                }));

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openManage(sp, stall), null);
    }

    private static void promptAddOffer(ServerPlayer player, MarketData.Stall stall) {
        if (stall.offers.size() >= MarketManager.MAX_OFFERS_PER_STALL) {
            player.sendSystemMessage(Messages.warn("Emplacement plein (10 offres maximum)."));
            openManage(player, stall);
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            player.sendSystemMessage(Messages.warn("Tiens l'objet a vendre dans ta main, puis reessaie."));
            openManage(player, stall);
            return;
        }
        String desc = held.getCount() + "x " + held.getHoverName().getString();
        Menus.promptAmount(player, Icons.label("Prix UNITAIRE (par objet)", ChatFormatting.GOLD),
                List.of(Icons.lore("Objet : " + desc, ChatFormatting.GRAY),
                        Icons.lore("Prix pour 1 objet (l'acheteur choisit la quantite).", ChatFormatting.DARK_GRAY),
                        Icons.lore("Le vendeur touche 75%, la mairie 25%.", ChatFormatting.DARK_GRAY)),
                Icons.label("Mettre en vente", ChatFormatting.GREEN), 1, 0, 1_000_000_000L,
                price -> {
                    MarketManager.OfferResult r = MarketManager.addOfferFromHand(player, stall, price);
                    switch (r) {
                        case FULL -> player.sendSystemMessage(Messages.warn("Emplacement plein."));
                        case EMPTY_HAND -> player.sendSystemMessage(Messages.warn("Plus rien en main."));
                        case NOT_OWNER -> player.sendSystemMessage(Messages.error("Ce n'est pas ton emplacement."));
                        default -> player.sendSystemMessage(Messages.success("Offre creee : " + desc
                                + " a " + price + " Utopieces l'unite."));
                    }
                    openManage(player, stall);
                });
    }

    // -------- Achat sur le stand d'un autre --------

    private static void openBuy(ServerPlayer player, MarketData.Stall stall) {
        if (stall.owner == null) {
            player.sendSystemMessage(Messages.warn("Cet emplacement est vide."));
            return;
        }
        Component title = Icons.label("Stand de " + stall.ownerName, ChatFormatting.GOLD);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int i = 0; i < stall.offers.size(); i++) {
            final int idx = i;
            MarketData.Offer o = stall.offers.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(o.stack.getCount() + "x " + o.stack.getHoverName().getString(), ChatFormatting.AQUA),
                    Icons.label(o.price + " Utopieces /unite", ChatFormatting.GOLD),
                    Icons.label("Acheter", ChatFormatting.GREEN),
                    sp -> promptBuyQty(sp, stall, idx)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucune offre", ChatFormatting.GRAY),
                    Icons.label("", ChatFormatting.WHITE), null, null));
        }
        OwoMenuServer.openPanel(player, title, rows, List.of(), sp -> openBuy(sp, stall), null);
    }

    /** Demande la quantite a acheter (1..disponible) puis effectue l'achat au prix unitaire. */
    private static void promptBuyQty(ServerPlayer player, MarketData.Stall stall, int idx) {
        if (stall.owner == null || idx < 0 || idx >= stall.offers.size()) {
            player.sendSystemMessage(Messages.warn("Cette offre n'est plus disponible."));
            Menus.close(player);
            return;
        }
        MarketData.Offer o = stall.offers.get(idx);
        int available = o.stack.getCount();
        long unit = o.price;
        String name = o.stack.getHoverName().getString();
        Menus.promptAmount(player, Icons.label("Quantite a acheter (" + name + ")", ChatFormatting.GOLD),
                List.of(Icons.lore("Prix unitaire : " + unit + " Utopieces", ChatFormatting.GRAY),
                        Icons.lore("Disponible : " + available, ChatFormatting.GRAY)),
                Icons.label("Acheter", ChatFormatting.GREEN), available, 1, available,
                qty -> {
                    MarketManager.BuyResult r = MarketManager.buy(player, stall, idx, (int) qty);
                    switch (r) {
                        case POOR -> player.sendSystemMessage(Messages.error("Solde insuffisant."));
                        case GONE -> player.sendSystemMessage(Messages.warn("Cette offre n'est plus disponible."));
                        case OWN -> player.sendSystemMessage(Messages.warn("C'est ton propre stand."));
                        case INVALID -> player.sendSystemMessage(Messages.warn("Quantite invalide."));
                        default -> player.sendSystemMessage(Messages.success("Achat effectue : " + qty + "x " + name
                                + " pour " + (unit * qty) + " Utopieces."));
                    }
                    if (stall.owner == null || stall.offers.isEmpty()) {
                        Menus.close(player);
                    } else {
                        openBuy(player, stall);
                    }
                });
    }

    // -------- Recuperation (admin / maire) --------

    public static void openRecoveryAdmin(ServerPlayer admin) {
        openRecovery(admin, com.utopia.menu.AdminMenu::open);
    }

    /** Liste de la recuperation ; {@code onBack} = ou revient le bouton Retour (admin ou maire). */
    public static void openRecovery(ServerPlayer player, Consumer<ServerPlayer> onBack) {
        MarketData data = MarketData.get(player.server);
        List<MarketData.RecoveryEntry> rec = new ArrayList<>(data.recovery());

        Component title = Component.literal("Recuperation - Mairie")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (MarketData.RecoveryEntry e : rec) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(e.ownerName(), ChatFormatting.WHITE),
                    Icons.label(e.stack().getCount() + "x " + e.stack().getHoverName().getString(), ChatFormatting.AQUA),
                    Icons.label("Rendre", ChatFormatting.GREEN),
                    sp -> {
                        returnRecovery(sp, e);
                        openRecovery(sp, onBack);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucun objet en attente", ChatFormatting.GRAY),
                    Icons.label("", ChatFormatting.WHITE), null, null));
        }
        OwoMenuServer.openPanel(player, title, rows, List.of(),
                sp -> openRecovery(sp, onBack), onBack);
    }

    // -------- Menu du compte de la mairie (/maire) --------

    public static void openMaire(ServerPlayer player) {
        long balance = EconomyManager.getBalance(player.server, MarketData.MAIRIE_UUID);
        Component title = Component.literal("MAIRIE - Compte")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Solde de la mairie : ")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                        .append(Component.literal(balance + " Utopieces")
                                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false))),
                Component.literal("Alimente par la taxe du marche (25%).")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT),
                Icons.label("Retirer vers mon solde", ChatFormatting.GREEN),
                Icons.lore("Transfere des Utopieces de la mairie vers ton compte", ChatFormatting.GRAY),
                sp -> {
                    long max = EconomyManager.getBalance(sp.server, MarketData.MAIRIE_UUID);
                    if (max <= 0) {
                        sp.sendSystemMessage(Messages.warn("Le compte de la mairie est vide."));
                        openMaire(sp);
                        return;
                    }
                    Menus.promptAmount(sp, Icons.label("Montant a retirer de la mairie", ChatFormatting.GOLD),
                            List.of(Icons.lore("Disponible : " + max + " Utopieces", ChatFormatting.GRAY)),
                            Icons.label("Retirer", ChatFormatting.GREEN), Math.min(100, max), 1, max,
                            v -> {
                                long cur = EconomyManager.getBalance(sp.server, MarketData.MAIRIE_UUID);
                                long amount = Math.min(v, cur);
                                if (amount > 0) {
                                    EconomyManager.remove(sp.server, MarketData.MAIRIE_UUID, amount);
                                    EconomyManager.add(sp.server, sp.getUUID(), amount);
                                    sp.sendSystemMessage(Messages.success("Retire " + EconomyManager.format(amount)
                                            + " de la mairie vers ton solde."));
                                }
                                openMaire(sp);
                            });
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.EMERALD),
                Icons.label("Deposer sur le compte", ChatFormatting.GREEN),
                Icons.lore("Transfere des Utopieces de ton solde vers la mairie", ChatFormatting.GRAY),
                sp -> {
                    long mine = EconomyManager.getBalance(sp.server, sp.getUUID());
                    if (mine <= 0) {
                        sp.sendSystemMessage(Messages.warn("Ton solde est vide."));
                        openMaire(sp);
                        return;
                    }
                    Menus.promptAmount(sp, Icons.label("Montant a deposer a la mairie", ChatFormatting.GOLD),
                            List.of(Icons.lore("Ton solde : " + mine + " Utopieces", ChatFormatting.GRAY)),
                            Icons.label("Deposer", ChatFormatting.GREEN), Math.min(100, mine), 1, mine,
                            v -> {
                                long cur = EconomyManager.getBalance(sp.server, sp.getUUID());
                                long amount = Math.min(v, cur);
                                if (amount > 0) {
                                    EconomyManager.remove(sp.server, sp.getUUID(), amount);
                                    EconomyManager.add(sp.server, MarketData.MAIRIE_UUID, amount);
                                    sp.sendSystemMessage(Messages.success("Depose " + EconomyManager.format(amount)
                                            + " de ton solde vers la mairie."));
                                }
                                openMaire(sp);
                            });
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.CHEST_MINECART),
                Icons.label("Objets expires (recuperation)", ChatFormatting.GOLD),
                Icons.lore("Rendre aux joueurs les objets expires du marche", ChatFormatting.GRAY),
                sp -> openRecovery(sp, MarketMenus::openMaire)));

        OwoMenuServer.openHub(player, title, stats, entries, MarketMenus::openMaire, null);
    }

    // -------- Menu admin d'un stand (op : Shift + clic droit sur le bloc) --------

    /** Cle "dim;x;y;z" d'un stand (identique a celle de MarketData). */
    private static String stallKey(MarketData.Stall stall) {
        return stall.dim + ";" + stall.x + ";" + stall.y + ";" + stall.z;
    }

    /** Menu de configuration d'un stand (Shift + clic droit) : op = emplacements + expiration ; maire = expiration. */
    public static void openStallAdmin(ServerPlayer player, MarketData.Stall stall) {
        boolean isOp = player.hasPermissions(2);
        Component title = Component.literal("Stand - configuration")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true));
        List<Component> stats = List.of(
                Component.literal((stall.isFree() ? "Libre" : "Occupe par " + stall.ownerName)
                        + " - " + stall.offers.size() + " offre(s)")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal(stall.displaySpots.size() + " emplacement(s) d'affichage")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        String key = stallKey(stall);
        boolean selecting = MarketManager.isSelectingSpot(player.getUUID())
                && key.equals(MarketManager.spotSelectStall(player.getUUID()));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();

        // Expiration forcee : disponible aux op ET au maire (moderation des ventes de n'importe quel shop).
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.CLOCK),
                Icons.label("Expirer les offres", ChatFormatting.GOLD),
                Icons.lore("Met fin a toutes les ventes du stand (objets -> recuperation du proprietaire)", ChatFormatting.GRAY),
                sp -> {
                    int n = MarketManager.forceExpire(sp.server, stall);
                    sp.sendSystemMessage(n > 0
                            ? Messages.success(n + " offre(s) expiree(s) ; objets places en recuperation.")
                            : Messages.warn("Aucune offre a expirer sur ce stand."));
                    openStallAdmin(sp, stall);
                }));

        if (isOp) {
            if (selecting) {
                entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.LIME_DYE),
                        Icons.label("Terminer le placement", ChatFormatting.GREEN),
                        Icons.lore("Arrete le mode de definition des emplacements", ChatFormatting.GRAY),
                        sp -> {
                            MarketManager.clearSpotSelect(sp.getUUID());
                            sp.sendSystemMessage(Messages.success("Placement termine : "
                                    + stall.displaySpots.size() + " emplacement(s)."));
                            openStallAdmin(sp, stall);
                        }));
            } else {
                entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.ITEM_FRAME),
                        Icons.label("Definir les emplacements", ChatFormatting.AQUA),
                        Icons.lore("Active le mode, puis CASSE un bloc par emplacement (recasse pour retirer)", ChatFormatting.GRAY),
                        sp -> {
                            MarketManager.startSpotSelect(sp.getUUID(), key);
                            sp.sendSystemMessage(Messages.info("Mode actif : casse un bloc pour chaque emplacement d'affichage. "
                                    + "Recasse un emplacement pour le retirer. Re-Shift-clic droit sur le stand pour terminer."));
                            Menus.close(sp);
                        }));
            }
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.BARRIER),
                    Icons.label("Effacer les emplacements", ChatFormatting.RED),
                    Icons.lore("Repasse l'affichage au-dessus du bloc", ChatFormatting.GRAY),
                    sp -> {
                        stall.displaySpots.clear();
                        MarketData.get(sp.server).setDirty();
                        sp.sendSystemMessage(Messages.success("Emplacements d'affichage effaces."));
                        openStallAdmin(sp, stall);
                    }));
        }

        OwoMenuServer.openHub(player, title, stats, entries, sp -> openStallAdmin(sp, stall), null);
    }

    private static void returnRecovery(ServerPlayer admin, MarketData.RecoveryEntry entry) {
        ServerPlayer owner = admin.server.getPlayerList().getPlayer(entry.owner());
        if (owner == null) {
            admin.sendSystemMessage(Messages.warn(entry.ownerName() + " est hors ligne (restitution quand il revient)."));
            return;
        }
        ItemHandlerHelper.giveItemToPlayer(owner, entry.stack().copy());
        MarketData.get(admin.server).removeRecovery(entry);
        owner.sendSystemMessage(Messages.info("Objets expires du marche rendus : "
                + entry.stack().getCount() + "x " + entry.stack().getHoverName().getString() + "."));
        admin.sendSystemMessage(Messages.success("Rendu a " + entry.ownerName() + "."));
    }

    // -------- util --------

    /** Temps restant d'une offre, format lisible. */
    private static String remaining(MarketData.Offer o) {
        long ms = o.expiryMillis - System.currentTimeMillis();
        return Messages.formatDuration(Math.max(0, ms) / 1000);
    }
}
