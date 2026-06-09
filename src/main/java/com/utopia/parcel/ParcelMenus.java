package com.utopia.parcel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.util.Messages;

import com.utopia.net.OwoMenuServer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Interfaces (GUI coffre) de gestion d'une parcelle : membres et permissions. */
public final class ParcelMenus {

    private ParcelMenus() {
    }

    private static final Parcel.Flag[] FLAGS = Parcel.Flag.values();

    private static String flagLabel(Parcel.Flag flag) {
        return switch (flag) {
            case BUILD -> "Construire";
            case CONTAINERS -> "Coffres / conteneurs";
            case DOORS -> "Portes / boutons";
            case MACHINES -> "Machines / redstone";
            case CREATE -> "Blocs Create";
        };
    }

    // ----------------------------------------------------------------------------------- helpers

    private static Parcel getParcel(MinecraftServer server, String id) {
        return ParcelData.get(server).get(id);
    }

    /** Icone (laine bleue/jaune) selon le type de parcelle. */
    private static net.minecraft.world.level.ItemLike typeIcon(Parcel p) {
        return p.type() == Parcel.Type.COMMERCE ? Items.YELLOW_WOOL : Items.BLUE_WOOL;
    }

    /** Ligne de lore "Requis : <item>" pour l'achat, ou une ligne vide si aucun item exige. */
    private static Component typeReqLore(Parcel p) {
        var req = ParcelManager.requiredBuyItem(p);
        return req == null ? Icons.lore(" ", ChatFormatting.DARK_GRAY)
                : Icons.lore("Requis : " + new ItemStack(req).getHoverName().getString(), ChatFormatting.LIGHT_PURPLE);
    }

    private static boolean canManage(ServerPlayer player, Parcel parcel) {
        return parcel.isOwner(player.getUUID()) || ParcelManager.canBypass(player);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        if (gp.isPresent() && gp.get().getName() != null) {
            return gp.get().getName();
        }
        return id.toString().substring(0, 8);
    }

    private static ItemStack head(MinecraftServer server, UUID id, Component name, List<Component> lore) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Icons.playerHead(online, name, lore);
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        if (gp.isPresent()) {
            return Icons.playerHead(gp.get(), name, lore);
        }
        return Icons.icon(Items.PLAYER_HEAD, name, lore);
    }

    // ----------------------------------------------------------------------------------- menu principal

    public static void openParcelMenu(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        Parcel here = ParcelData.get(player.server).parcelAt(player.serverLevel().dimension().location(),
                pos.getX(), pos.getY(), pos.getZ());
        if (here != null) {
            openParcelMenuFor(player, here.id());
            return;
        }
        // Hors parcelle : ouvre la liste de ses parcelles (navigable), sinon un message.
        if (!ParcelData.get(player.server).ownedBy(player.getUUID()).isEmpty()) {
            openMyParcels(player, 0);
        } else {
            player.sendSystemMessage(Messages.warn("Vous n'etes sur aucune parcelle et n'en possedez aucune."));
            player.sendSystemMessage(Messages.info("Utilisez /parcel shop pour en acheter une."));
        }
    }

    /** Vue d'une de ses parcelles avec navigation (fleches) entre toutes ses parcelles. */
    public static void openMyParcels(ServerPlayer player, int index) {
        MinecraftServer server = player.server;
        List<Parcel> owned = new ArrayList<>(ParcelData.get(server).ownedBy(player.getUUID()));
        owned.sort(Comparator.comparing(Parcel::id, String.CASE_INSENSITIVE_ORDER));
        if (owned.isEmpty()) {
            player.sendSystemMessage(Messages.warn("Vous ne possedez aucune parcelle."));
            return;
        }
        int i = Math.max(0, Math.min(index, owned.size() - 1));
        Parcel p = owned.get(i);
        String parcelId = p.id();

        Component title = Icons.label("Mes parcelles (" + (i + 1) + "/" + owned.size() + ") : " + p.id(),
                ChatFormatting.GREEN);
        List<Component> stats = List.of(
                stat("Type : ", p.type().label(),
                        p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                stat("En vente : ", p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non",
                        p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                stat("Regions : ", p.regionCount() + "  |  Membres : " + p.members().size(), ChatFormatting.AQUA));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Gerer les membres", ChatFormatting.YELLOW),
                Icons.lore("Ajouter / retirer des membres", ChatFormatting.GRAY),
                sp -> openMembersMenu(sp, parcelId)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(EconomyManager.coinItem()),
                Icons.label("Vendre", ChatFormatting.GOLD),
                Icons.lore("Mairie (75%) ou aux joueurs", ChatFormatting.GRAY),
                sp -> openSellMenu(sp, parcelId)));
        if (p.forSale()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARMOR_STAND),
                    Icons.label("Deplacer l'hologramme", ChatFormatting.AQUA),
                    Icons.lore("Ajuster X / Y / Z", ChatFormatting.GRAY),
                    sp -> openHoloMove(sp, parcelId, false)));
        }
        // TP vers la parcelle : reserve aux admins (op niveau 2).
        if (player.hasPermissions(2)) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_PEARL),
                    Icons.label("Se teleporter", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Vers la parcelle", ChatFormatting.GRAY),
                    sp -> {
                        Parcel cur = getParcel(sp.server, parcelId);
                        if (cur != null) {
                            teleportTo(sp, cur);
                        }
                        com.utopia.gui.Menus.close(sp);
                    }));
        }
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GLOWSTONE_DUST),
                Icons.label("Voir les delimitations", ChatFormatting.YELLOW),
                Icons.lore("Affiche le contour 30 s", ChatFormatting.GRAY),
                sp -> {
                    Parcel cur = getParcel(sp.server, parcelId);
                    if (cur != null) {
                        ParcelHolograms.startPreview(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                }));
        if (owned.size() > 1) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARROW),
                    Icons.label("< Precedente", ChatFormatting.YELLOW), Component.empty(),
                    sp -> openMyParcels(sp, (i - 1 + owned.size()) % owned.size())));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARROW),
                    Icons.label("Suivante >", ChatFormatting.YELLOW), Component.empty(),
                    sp -> openMyParcels(sp, (i + 1) % owned.size())));
        }

        final int idx = i;
        OwoMenuServer.openHub(player, title, stats, entries,
                sp -> openMyParcels(sp, idx), com.utopia.menu.MainMenu::open);
    }

    /** Ligne de stat "label: valeur" (label gris, valeur coloree). */
    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }

    public static void openParcelMenuFor(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Parcelle introuvable."));
            return;
        }
        boolean manage = canManage(player, p);
        // Parcelle administrative : invisible/inaccessible aux joueurs (zone protegee).
        if (p.isAdmin() && !manage) {
            player.sendSystemMessage(Messages.warn("Zone protegee par l'administration."));
            return;
        }

        UtopiaGui gui = new UtopiaGui(3, Icons.label("Parcelle : " + p.name(), ChatFormatting.DARK_AQUA));

        List<Component> info = new ArrayList<>();
        info.add(Icons.lore("Categorie : " + p.type().label(),
                p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
        info.add(Icons.lore("Proprietaire : " + (p.isOwned() ? p.ownerName() : "Mairie"), ChatFormatting.GRAY));
        info.add(Icons.lore("En vente : " + (p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non"),
                p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        info.add(Icons.lore("Regions : " + p.regionCount() + " | membres : " + p.members().size(), ChatFormatting.DARK_GRAY));
        gui.set(4, Icons.icon(Items.PAPER, Icons.label("Parcelle " + p.id(), ChatFormatting.AQUA), info));

        if (manage) {
            gui.button(11, Icons.icon(Items.PLAYER_HEAD, Icons.label("Gerer les membres", ChatFormatting.YELLOW),
                    List.of(Icons.lore("Ajouter/retirer des joueurs et leurs droits", ChatFormatting.GRAY))),
                    sp -> openMembersMenu(sp, parcelId));
            gui.button(15, Icons.icon(EconomyManager.coinItem(), Icons.label("Vendre la parcelle", ChatFormatting.GOLD),
                    List.of(Icons.lore("A la Mairie (75%) ou via les joueurs", ChatFormatting.GRAY))),
                    sp -> openSellMenu(sp, parcelId));
            if (p.forSale()) {
                gui.button(13, Icons.icon(Items.ARMOR_STAND, Icons.label("Deplacer l'hologramme", ChatFormatting.AQUA),
                        List.of(Icons.lore("Fleches pour ajuster X / Y / Z", ChatFormatting.GRAY))),
                        sp -> openHoloMove(sp, parcelId, false));
            }
        } else if (p.forSale()) {
            gui.button(15, Icons.icon(Items.EMERALD, Icons.label("Acheter", ChatFormatting.GREEN),
                    List.of(Icons.lore("Prix : " + EconomyManager.format(p.price()), ChatFormatting.GOLD),
                            Icons.lore("Clic pour acheter", ChatFormatting.GRAY))),
                    sp -> openBuyConfirm(sp, parcelId));
        }
        // Apercu des delimitations (pour tous).
        gui.button(22, Icons.icon(Items.GLOWSTONE_DUST, Icons.label("Voir les delimitations", ChatFormatting.YELLOW),
                List.of(Icons.lore("Affiche le contour 30 s (particules)", ChatFormatting.GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        ParcelHolograms.startPreview(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                });

        gui.fillEmpty();
        Menus.open(player, gui);
    }

    // ----------------------------------------------------------------------------------- vente

    public static void openSellMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Vendre : " + p.name(), ChatFormatting.GOLD));

        long refund = Math.round(p.lastPaid() * ParcelManager.SERVER_BUYBACK_RATE);
        gui.button(10, Icons.icon(Items.HOPPER, Icons.label("Vendre a la Mairie", ChatFormatting.YELLOW), List.of(
                Icons.lore("Immediat. Remboursement : " + EconomyManager.format(refund) + " (75%)", ChatFormatting.GRAY),
                Icons.lore("La parcelle repart en vente cote Mairie.", ChatFormatting.DARK_GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur == null || !cur.isOwner(sp.getUUID())) {
                        sp.sendSystemMessage(Messages.error("Vous n'etes pas proprietaire."));
                        return;
                    }
                    long r = ParcelManager.sellToServer(sp, cur);
                    sp.sendSystemMessage(Messages.success("Parcelle vendue a la Mairie. Rembourse : " + EconomyManager.format(r) + "."));
                    com.utopia.gui.Menus.close(sp);
                });

        gui.button(13, Icons.icon(EconomyManager.coinItem(), Icons.label("Mettre en vente (joueurs)", ChatFormatting.GOLD),
                List.of(Icons.lore("Choisir un prix ; un joueur pourra l'acheter", ChatFormatting.GRAY))),
                sp -> openListPriceMenu(sp, parcelId));

        if (p.forSale()) {
            gui.button(15, Icons.icon(Items.BARRIER, Icons.label("Retirer de la vente", ChatFormatting.RED), List.of()),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null && canManage(sp, cur)) {
                            cur.setForSale(false);
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success("Parcelle retiree de la vente."));
                        }
                        openParcelMenuFor(sp, parcelId);
                    });
        }

        gui.button(26, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openParcelMenuFor(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    /** Reglage du prix de mise en vente (joueurs) par paliers. */
    public static void openListPriceMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        long def = p.price() > 0 ? p.price() : (p.lastPaid() > 0 ? p.lastPaid() : 100);
        List<Component> info = List.of(
                Icons.lore("Parcelle " + p.id(), ChatFormatting.GRAY),
                Icons.lore("Prix de mise en vente (ton prix)", ChatFormatting.GRAY),
                Icons.lore("Astuce : /parcel sell <prix> pour un montant exact", ChatFormatting.DARK_GRAY));
        Menus.promptAmount(player,
                Icons.label("Prix de vente", ChatFormatting.GOLD),
                info, Icons.label("Confirmer la mise en vente", ChatFormatting.GREEN),
                def, 1, 100_000_000L,
                price -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null && canManage(player, cur)) {
                        cur.setPrice(price);
                        cur.setForSale(true);
                        ParcelData.get(server).setDirty();
                        player.sendSystemMessage(Messages.success(cur.name() + " mise en vente pour " + EconomyManager.format(price) + "."));
                        server.getPlayerList().broadcastSystemMessage(Messages.info("La parcelle " + cur.id()
                                + " est en vente pour " + EconomyManager.format(price) + " ! (/parcel shop)"), false);
                    }
                    openParcelMenuFor(player, parcelId);
                });
    }

    // ----------------------------------------------------------------------------------- achat (confirmation)

    public static void openBuyConfirm(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !p.forSale()) {
            player.sendSystemMessage(Messages.error("Cette parcelle n'est plus en vente."));
            return;
        }
        long price = p.price();
        var reqItem = ParcelManager.requiredBuyItem(p);
        List<Component> info = new ArrayList<>();
        info.add(Icons.lore("Type : " + p.type().label(),
                p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
        info.add(Icons.lore("Votre solde : " + EconomyManager.format(EconomyManager.getBalance(server, player.getUUID())), ChatFormatting.GRAY));
        if (reqItem != null) {
            info.add(Icons.lore("Requis : " + new ItemStack(reqItem).getHoverName().getString() + " (consomme)", ChatFormatting.LIGHT_PURPLE));
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Acheter " + p.id() + " ?", ChatFormatting.DARK_AQUA));
        gui.set(4, Icons.icon(EconomyManager.coinItem(), Icons.label("Prix : " + EconomyManager.format(price), ChatFormatting.GOLD), info));
        gui.button(11, Icons.icon(Items.LIME_DYE, Icons.label("OUI, acheter pour " + EconomyManager.format(price), ChatFormatting.GREEN), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur == null) {
                        return;
                    }
                    long pr = cur.price();
                    var req = ParcelManager.requiredBuyItem(cur);
                    switch (ParcelManager.purchase(sp, cur)) {
                        case INSUFFICIENT -> sp.sendSystemMessage(Messages.error("Solde insuffisant (" + EconomyManager.format(pr) + ")."));
                        case NOT_FOR_SALE -> sp.sendSystemMessage(Messages.error("Plus en vente."));
                        case ALREADY_OWNER -> sp.sendSystemMessage(Messages.error("Vous la possedez deja."));
                        case MISSING_ITEM -> sp.sendSystemMessage(Messages.error("Il te faut "
                                + (req != null ? new ItemStack(req).getHoverName().getString() : "le document requis")
                                + " pour acheter cette parcelle " + cur.type().label() + "."));
                        default -> sp.sendSystemMessage(Messages.success("Vous avez achete " + cur.name() + " pour " + EconomyManager.format(pr) + " !"));
                    }
                    com.utopia.gui.Menus.close(sp);
                });
        gui.button(15, Icons.icon(Items.BARRIER, Icons.label("Annuler", ChatFormatting.RED), List.of()),
                com.utopia.gui.Menus::close);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    // ----------------------------------------------------------------------------------- membres

    public static void openMembersMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Membres : " + p.name(), ChatFormatting.DARK_AQUA));

        int slot = 0;
        for (var entry : p.members().entrySet()) {
            if (slot > 44) {
                break;
            }
            UUID memberId = entry.getKey();
            List<Component> lore = new ArrayList<>();
            for (Parcel.Flag f : FLAGS) {
                boolean on = entry.getValue().contains(f);
                lore.add(Icons.lore((on ? "[x] " : "[ ] ") + flagLabel(f), on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
            }
            lore.add(Icons.lore("Clic pour modifier", ChatFormatting.YELLOW));
            gui.button(slot++, head(server, memberId, Icons.label(nameOf(server, memberId), ChatFormatting.WHITE), lore),
                    sp -> openMemberEditMenu(sp, parcelId, memberId));
        }

        gui.button(49, Icons.icon(Items.LIME_DYE, Icons.label("Ajouter un membre", ChatFormatting.GREEN),
                List.of(Icons.lore("Choisir un joueur en ligne", ChatFormatting.GRAY))),
                sp -> openAddMemberMenu(sp, parcelId));
        gui.button(53, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openParcelMenuFor(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    public static void openMemberEditMenu(ServerPlayer player, String parcelId, UUID memberId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        EnumSet<Parcel.Flag> flags = p.members().get(memberId);
        if (flags == null) {
            openMembersMenu(player, parcelId);
            return;
        }

        UtopiaGui gui = new UtopiaGui(3,
                Icons.label("Droits : " + nameOf(server, memberId), ChatFormatting.DARK_AQUA));
        gui.set(4, head(server, memberId, Icons.label(nameOf(server, memberId), ChatFormatting.WHITE),
                List.of(Icons.lore("Cliquez les boutons pour activer/desactiver", ChatFormatting.GRAY))));

        int[] slots = { 10, 11, 12, 13, 14 };
        for (int i = 0; i < FLAGS.length; i++) {
            Parcel.Flag flag = FLAGS[i];
            boolean on = flags.contains(flag);
            gui.button(slots[i], Icons.icon(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    Icons.label(flagLabel(flag), on ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    List.of(Icons.lore(on ? "Active - clic pour desactiver" : "Desactive - clic pour activer", ChatFormatting.GRAY))),
                    sp -> toggleFlag(sp, parcelId, memberId, flag));
        }

        gui.button(21, Icons.icon(Items.BARRIER, Icons.label("Retirer ce membre", ChatFormatting.RED), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null && canManage(sp, cur)) {
                        cur.setMember(memberId, EnumSet.noneOf(Parcel.Flag.class));
                        ParcelData.get(server).setDirty();
                        sp.sendSystemMessage(Messages.success(nameOf(server, memberId) + " retire de la parcelle."));
                    }
                    openMembersMenu(sp, parcelId);
                });
        gui.button(23, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openMembersMenu(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void toggleFlag(ServerPlayer player, String parcelId, UUID memberId, Parcel.Flag flag) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            return;
        }
        EnumSet<Parcel.Flag> set = EnumSet.noneOf(Parcel.Flag.class);
        EnumSet<Parcel.Flag> current = p.members().get(memberId);
        if (current != null) {
            set.addAll(current);
        }
        if (set.contains(flag)) {
            set.remove(flag);
        } else {
            set.add(flag);
        }
        p.setMember(memberId, set);
        ParcelData.get(server).setDirty();
        if (set.isEmpty()) {
            player.sendSystemMessage(Messages.info(nameOf(server, memberId) + " n'a plus aucun droit (retire)."));
            openMembersMenu(player, parcelId);
        } else {
            openMemberEditMenu(player, parcelId, memberId);
        }
    }

    public static void openAddMemberMenu(ServerPlayer player, String parcelId) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null || !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Ajouter un membre", ChatFormatting.DARK_AQUA));

        int slot = 0;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            UUID id = online.getUUID();
            if (p.isOwner(id) || p.members().containsKey(id)) {
                continue; // deja proprietaire ou membre
            }
            gui.button(slot++, Icons.playerHead(online, Icons.label(online.getGameProfile().getName(), ChatFormatting.WHITE),
                    List.of(Icons.lore("Clic pour ajouter (tous les droits par defaut)", ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null && canManage(sp, cur)) {
                            cur.setMember(id, EnumSet.allOf(Parcel.Flag.class));
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success(online.getGameProfile().getName() + " ajoute. Ajustez ses droits."));
                            openMemberEditMenu(sp, parcelId, id);
                        }
                    });
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun joueur disponible", ChatFormatting.RED),
                    List.of(Icons.lore("Les joueurs hors ligne s'ajoutent via /parcel trust", ChatFormatting.GRAY))));
        }

        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openMembersMenu(sp, parcelId));
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    // ----------------------------------------------------------------------------------- shop joueur

    /** Liste des parcelles en vente (triees par ID). Clic gauche = acheter (confirmation), clic droit = apercu. */
    public static void openShop(ServerPlayer player) {
        MinecraftServer server = player.server;
        List<Parcel> sale = new ArrayList<>(ParcelData.get(server).forSale());
        sale.sort(Comparator.comparing(Parcel::id, String.CASE_INSENSITIVE_ORDER));

        Component title = Icons.label("Parcelles en vente", ChatFormatting.GREEN);
        List<Component> stats = List.of(Component.literal(sale.size() + " parcelle(s) en vente")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (Parcel p : sale) {
            String pid = p.id();
            ChatFormatting color = p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
            String vendor = p.isOwned() ? p.ownerName() + " (joueur)" : "Mairie";
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(typeIcon(p)),
                    Icons.label("[" + p.type().label() + "] " + pid, color),
                    Icons.lore("Prix : " + EconomyManager.format(p.price()) + " - " + vendor, ChatFormatting.GRAY),
                    sp -> openBuyConfirm(sp, pid)));
        }

        OwoMenuServer.openHub(player, title, stats, entries,
                ParcelMenus::openShop, com.utopia.menu.MainMenu::open);
    }

    // ----------------------------------------------------------------------------------- admin : toutes les parcelles

    /** Toutes les parcelles (triees par ID). Clic gauche = menu admin, clic droit = teleportation. */
    public static void openAdminAll(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        List<Parcel> all = new ArrayList<>(ParcelData.get(server).all());
        all.sort(Comparator.comparing(Parcel::id, String.CASE_INSENSITIVE_ORDER));

        UtopiaGui gui = new UtopiaGui(6, Icons.label("Admin - toutes les parcelles", ChatFormatting.DARK_AQUA));
        int slot = 0;
        for (Parcel p : all) {
            if (slot > 44) { // derniere rangee reservee aux controles
                break;
            }
            String pid = p.id();
            gui.button(slot++, Icons.icon(p.isAdmin() ? Items.BEDROCK : p.forSale() ? Items.GOLD_BLOCK : Items.GRASS_BLOCK,
                            Icons.label((p.isAdmin() ? "[ADMIN] " : "") + "Parcelle " + pid,
                                    p.isAdmin() ? ChatFormatting.RED : ChatFormatting.AQUA), List.of(
                            Icons.lore(p.isAdmin() ? "Type : ADMIN (protegee, hors shop)" : "Proprietaire : " + (p.isOwned() ? p.ownerName() : "Mairie"),
                                    p.isAdmin() ? ChatFormatting.RED : ChatFormatting.GRAY),
                            Icons.lore("En vente : " + (p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non"),
                                    p.forSale() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                            Icons.lore("Clic GAUCHE : gerer", ChatFormatting.YELLOW),
                            Icons.lore("Clic DROIT : se teleporter", ChatFormatting.YELLOW))),
                    sp -> openAdminParcel(sp, pid),
                    sp -> {
                        Parcel cur = getParcel(sp.server, pid);
                        if (cur != null) {
                            teleportTo(sp, cur);
                        }
                        com.utopia.gui.Menus.close(sp);
                    });
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucune parcelle", ChatFormatting.RED), List.of()));
        }
        // Creer une parcelle Admin depuis le trace en cours (rangee du bas).
        gui.button(49, Icons.icon(Items.BEDROCK, Icons.label("Creer une parcelle Admin", ChatFormatting.RED),
                List.of(Icons.lore("Trace d'abord la zone au wand (clic droit au sol)", ChatFormatting.GRAY),
                        Icons.lore("Puis saisis un identifiant", ChatFormatting.GRAY))),
                sp -> Menus.promptText(sp,
                        Icons.label("Nouvelle parcelle Admin", ChatFormatting.DARK_RED),
                        List.of(Icons.lore("Identifiant (lettres, chiffres, _ , -)", ChatFormatting.GRAY),
                                Icons.lore("La zone = ton trace au wand", ChatFormatting.DARK_GRAY)),
                        Icons.label("Creer", ChatFormatting.GREEN), "", 24,
                        id -> {
                            Parcel created = ParcelManager.createFromTrace(sp, id, true);
                            if (created != null) {
                                sp.sendSystemMessage(Messages.success("Parcelle Admin '" + created.id() + "' creee."));
                                openAdminParcel(sp, created.id());
                            } else {
                                openAdminAll(sp);
                            }
                        }));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    // ----------------------------------------------------------------------------------- admin : menu parcelle

    public static void openAdminParcel(ServerPlayer admin, String parcelId) {
        MinecraftServer server = admin.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            admin.sendSystemMessage(Messages.error("Parcelle introuvable."));
            return;
        }
        // Parcelle Admin : menu simplifie (TP, apercu, droits globaux, statut, supprimer).
        if (p.isAdmin()) {
            openAdminZoneMenu(admin, parcelId, p);
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Admin : " + p.name(), ChatFormatting.DARK_RED));

        gui.set(4, Icons.icon(Items.PAPER, Icons.label("Parcelle " + p.id(), ChatFormatting.AQUA), List.of(
                Icons.lore(p.isAdmin() ? "Categorie : ADMIN (protegee, hors shop)" : "Categorie : " + p.type().label(),
                        p.isAdmin() ? ChatFormatting.RED : p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                Icons.lore("Proprietaire : " + (p.isOwned() ? p.ownerName() : "Mairie"), ChatFormatting.GOLD),
                Icons.lore("En vente : " + (p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non"),
                        p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.lore("Dernier prix paye : " + EconomyManager.format(p.lastPaid()), ChatFormatting.DARK_GRAY),
                Icons.lore("Regions : " + p.regionCount() + " | membres : " + p.members().size(), ChatFormatting.DARK_GRAY))));

        // Bascule Habitation / Commerce (couleur du contour + item exige a l'achat).
        gui.button(6, Icons.icon(p.type() == Parcel.Type.COMMERCE ? Items.YELLOW_WOOL : Items.BLUE_WOOL,
                Icons.label("Categorie : " + p.type().label(), p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                List.of(Icons.lore("Clic : basculer Habitation / Commerce", ChatFormatting.GRAY),
                        Icons.lore("Contour bleu (habitation) / jaune (commerce)", ChatFormatting.DARK_GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        cur.setType(cur.type() == Parcel.Type.COMMERCE ? Parcel.Type.HABITATION : Parcel.Type.COMMERCE);
                        ParcelData.get(server).setDirty();
                    }
                    openAdminParcel(sp, parcelId);
                });
        gui.button(10, Icons.icon(Items.PLAYER_HEAD, Icons.label("Gerer les membres", ChatFormatting.YELLOW), List.of()),
                sp -> openMembersMenu(sp, parcelId));
        gui.button(12, Icons.icon(Items.NAME_TAG, Icons.label("Transferer (changer proprio)", ChatFormatting.YELLOW),
                List.of(Icons.lore("Choisir un nouveau proprietaire", ChatFormatting.GRAY))),
                sp -> openTransferPicker(sp, parcelId));
        gui.button(14, Icons.icon(Items.GOLD_BLOCK, Icons.label("Remettre en vente (Mairie)", ChatFormatting.GOLD),
                List.of(Icons.lore("Retire au proprio, reliste au dernier prix paye", ChatFormatting.GRAY),
                        Icons.lore("Prix : " + EconomyManager.format(p.lastPaid()), ChatFormatting.GREEN))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        ParcelManager.repossess(server, cur);
                        sp.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " remise en vente (" + EconomyManager.format(cur.price()) + ")."));
                    }
                    openAdminParcel(sp, parcelId);
                });
        gui.button(16, Icons.icon(Items.ENDER_PEARL, Icons.label("Se teleporter", ChatFormatting.LIGHT_PURPLE), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        teleportTo(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                });
        // Bascule parcelle Admin : protection serveur, hors shop, sans proprietaire.
        gui.button(24, p.isAdmin()
                        ? Icons.icon(Items.GRASS_BLOCK, Icons.label("Retirer le statut Admin", ChatFormatting.YELLOW),
                                List.of(Icons.lore("Redevient une parcelle normale (Mairie)", ChatFormatting.GRAY)))
                        : Icons.icon(Items.BEDROCK, Icons.label("Marquer comme Admin", ChatFormatting.RED),
                                List.of(Icons.lore("Protegee anti-grief, hors shop, sans proprio", ChatFormatting.GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        ParcelManager.makeAdmin(cur, !cur.isAdmin());
                        ParcelData.get(server).setDirty();
                        sp.sendSystemMessage(Messages.success(cur.isAdmin()
                                ? "Parcelle " + cur.id() + " marquee ADMIN (protegee, hors shop)."
                                : "Parcelle " + cur.id() + " n'est plus admin."));
                    }
                    openAdminParcel(sp, parcelId);
                });
        if (p.forSale()) {
            gui.button(22, Icons.icon(Items.ARMOR_STAND, Icons.label("Deplacer l'hologramme", ChatFormatting.AQUA),
                    List.of(Icons.lore("Fleches pour ajuster X / Y / Z", ChatFormatting.GRAY))),
                    sp -> openHoloMove(sp, parcelId, true));
        }
        gui.button(20, Icons.icon(Items.SUNFLOWER, Icons.label("Changer le prix", ChatFormatting.GOLD),
                List.of(Icons.lore("Prix actuel : " + EconomyManager.format(p.price()), ChatFormatting.GRAY))),
                sp -> openAdminPriceMenu(sp, parcelId));
        gui.button(8, Icons.icon(Items.BARRIER, Icons.label("Supprimer la parcelle", ChatFormatting.RED),
                List.of(Icons.lore("Suppression definitive (avec confirmation)", ChatFormatting.GRAY))),
                sp -> openDeleteConfirm(sp, parcelId));
        gui.button(18, Icons.icon(Items.ARROW, Icons.label("Retour (liste)", ChatFormatting.YELLOW), List.of()),
                ParcelMenus::openAdminAll);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Menu simplifie d'une zone Admin : apercu, TP, droits globaux, statut, suppression. */
    private static void openAdminZoneMenu(ServerPlayer admin, String parcelId, Parcel p) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Zone Admin : " + p.id(), ChatFormatting.DARK_RED));
        gui.set(4, Icons.icon(Items.BEDROCK, Icons.label("Zone Admin : " + p.id(), ChatFormatting.RED), List.of(
                Icons.lore("Protegee (anti-grief), hors shop", ChatFormatting.GRAY),
                Icons.lore("Regions : " + p.regionCount(), ChatFormatting.DARK_GRAY),
                Icons.lore("Droits de tous : " + publicFlagsSummary(p), ChatFormatting.AQUA))));

        gui.button(10, Icons.icon(Items.GLOWSTONE_DUST, Icons.label("Voir les delimitations (30s)", ChatFormatting.YELLOW), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        ParcelHolograms.startPreview(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                });
        gui.button(12, Icons.icon(Items.ENDER_PEARL, Icons.label("Se teleporter", ChatFormatting.LIGHT_PURPLE), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        teleportTo(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                });
        gui.button(14, Icons.icon(Items.PLAYER_HEAD, Icons.label("Droits de TOUS les joueurs", ChatFormatting.YELLOW),
                List.of(Icons.lore("Ce que tout le monde peut faire ici", ChatFormatting.GRAY))),
                sp -> openAdminPublicFlags(sp, parcelId));
        gui.button(16, Icons.icon(Items.GRASS_BLOCK, Icons.label("Retirer le statut Admin", ChatFormatting.YELLOW),
                List.of(Icons.lore("Redevient une parcelle normale (Mairie)", ChatFormatting.GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        ParcelManager.makeAdmin(cur, false);
                        ParcelData.get(server).setDirty();
                        sp.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " n'est plus admin."));
                    }
                    openAdminParcel(sp, parcelId);
                });
        gui.button(21, Icons.icon(Items.BARRIER, Icons.label("Supprimer la zone", ChatFormatting.RED), List.of()),
                sp -> openDeleteConfirm(sp, parcelId));
        gui.button(23, Icons.icon(Items.ARROW, Icons.label("Retour (liste)", ChatFormatting.YELLOW), List.of()),
                sp -> openAdminAll(sp));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Droits accordes a TOUS les joueurs sur une zone Admin (interagir, detruire, etc.). */
    public static void openAdminPublicFlags(ServerPlayer admin, String parcelId) {
        MinecraftServer server = admin.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            admin.sendSystemMessage(Messages.error("Parcelle introuvable."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Droits de tous : " + p.id(), ChatFormatting.DARK_RED));
        gui.set(4, Icons.icon(Items.PAPER, Icons.label("Ce que TOUT LE MONDE peut faire", ChatFormatting.AQUA), List.of(
                Icons.lore("Clique un droit pour l'activer / le couper", ChatFormatting.GRAY),
                Icons.lore("Par defaut : interagir OUI, detruire NON", ChatFormatting.DARK_GRAY))));
        int[] slots = { 10, 11, 12, 13, 14 };
        Parcel.Flag[] flags = Parcel.Flag.values();
        for (int i = 0; i < flags.length; i++) {
            Parcel.Flag flag = flags[i];
            boolean on = p.publicAllows(flag);
            gui.button(slots[i], Icons.icon(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    Icons.label(flagLabel(flag) + " : " + (on ? "OUI" : "non"), on ? ChatFormatting.GREEN : ChatFormatting.RED),
                    List.of(Icons.lore(flagDesc(flag), ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null) {
                            cur.setPublicFlag(flag, !cur.publicAllows(flag));
                            ParcelData.get(server).setDirty();
                        }
                        openAdminPublicFlags(sp, parcelId);
                    });
        }
        gui.button(22, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openAdminParcel(sp, parcelId));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static String publicFlagsSummary(Parcel p) {
        if (p.publicFlags().isEmpty()) {
            return "aucun";
        }
        StringBuilder sb = new StringBuilder();
        for (Parcel.Flag f : Parcel.Flag.values()) {
            if (p.publicAllows(f)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(flagLabel(f));
            }
        }
        return sb.toString();
    }

    private static String flagDesc(Parcel.Flag f) {
        return switch (f) {
            case BUILD -> "Casser / poser des blocs";
            case CONTAINERS -> "Ouvrir coffres / conteneurs";
            case DOORS -> "Portes / boutons / leviers";
            case MACHINES -> "Fours / etablis / redstone";
            case CREATE -> "Blocs du mod Create";
        };
    }

    private static void openDeleteConfirm(ServerPlayer admin, String parcelId) {
        MinecraftServer server = admin.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            openAdminAll(admin);
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Supprimer " + p.id() + " ?", ChatFormatting.DARK_RED));
        gui.set(4, Icons.icon(Items.BARRIER, Icons.label("Suppression definitive", ChatFormatting.RED), List.of(
                Icons.lore("Proprietaire : " + (p.isOwned() ? p.ownerName() : "Mairie"), ChatFormatting.GRAY),
                Icons.lore("Regions : " + p.regionCount() + " | membres : " + p.members().size(), ChatFormatting.DARK_GRAY),
                Icons.lore("Cette action est irreversible.", ChatFormatting.RED))));
        gui.button(11, Icons.icon(Items.LIME_DYE, Icons.label("OUI, supprimer", ChatFormatting.GREEN), List.of()),
                sp -> {
                    if (getParcel(server, parcelId) != null) {
                        ParcelData.get(server).remove(parcelId);
                        sp.sendSystemMessage(Messages.success("Parcelle " + parcelId + " supprimee."));
                    }
                    openAdminAll(sp);
                });
        gui.button(15, Icons.icon(Items.RED_DYE, Icons.label("Annuler", ChatFormatting.RED), List.of()),
                sp -> openAdminParcel(sp, parcelId));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void openTransferPicker(ServerPlayer admin, String parcelId) {
        MinecraftServer server = admin.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            return;
        }
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Transferer " + p.id() + " a...", ChatFormatting.DARK_RED));
        int slot = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            UUID id = target.getUUID();
            String tname = target.getGameProfile().getName();
            gui.button(slot++, Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE),
                    List.of(Icons.lore("Clic : donner la parcelle a ce joueur", ChatFormatting.GRAY))),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null) {
                            cur.members().clear();
                            cur.setOwner(id, tname);
                            cur.setForSale(false);
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " transferee a " + tname + "."));
                        }
                        openAdminParcel(sp, parcelId);
                    });
        }
        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openAdminParcel(sp, parcelId));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void openHoloMove(ServerPlayer player, String parcelId, boolean fromAdmin) {
        MinecraftServer server = player.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            return;
        }
        if (!fromAdmin && !canManage(player, p)) {
            player.sendSystemMessage(Messages.error("Acces refuse."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(4, Icons.label("Deplacer l'hologramme : " + p.id(), ChatFormatting.DARK_AQUA))
                .gridLayout(true);
        // Centre de la boussole : le decalage actuel (compact, une seule ligne).
        gui.set(12, Icons.icon(Items.ARMOR_STAND,
                Icons.label(String.format("X %.1f Y %.1f Z %.1f", p.holoDx(), p.holoDy(), p.holoDz()), ChatFormatting.AQUA),
                List.of()));

        // Boussole compacte : Nord/Sud (Z) en col 3, Ouest/Est (X) autour, Monter/Descendre (Y) a droite.
        holoBtn(gui, 3, "↑ Nord", Items.ARROW, 0, 0, -1, parcelId, server, fromAdmin);
        holoBtn(gui, 21, "↓ Sud", Items.ARROW, 0, 0, 1, parcelId, server, fromAdmin);
        holoBtn(gui, 11, "← Ouest", Items.ARROW, -1, 0, 0, parcelId, server, fromAdmin);
        holoBtn(gui, 13, "→ Est", Items.ARROW, 1, 0, 0, parcelId, server, fromAdmin);
        holoBtn(gui, 5, "↑ Monter", Items.SPECTRAL_ARROW, 0, 0.5, 0, parcelId, server, fromAdmin);
        holoBtn(gui, 23, "↓ Descendre", Items.SPECTRAL_ARROW, 0, -0.5, 0, parcelId, server, fromAdmin);

        gui.button(29, Icons.icon(Items.LIME_DYE, Icons.label("Reinitialiser", ChatFormatting.GREEN), List.of()),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        cur.setHoloOffset(0, 0, 0);
                        ParcelData.get(server).setDirty();
                    }
                    openHoloMove(sp, parcelId, fromAdmin);
                });
        gui.button(31, Icons.icon(Items.OAK_DOOR, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> {
                    if (fromAdmin) {
                        openAdminParcel(sp, parcelId);
                    } else {
                        openParcelMenuFor(sp, parcelId);
                    }
                });
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void holoBtn(UtopiaGui gui, int slot, String label, net.minecraft.world.level.ItemLike icon,
                                double dx, double dy, double dz, String parcelId, MinecraftServer server, boolean fromAdmin) {
        gui.button(slot, Icons.icon(icon, Icons.label(label, ChatFormatting.YELLOW),
                List.of(Icons.lore(String.format("%+.1f %+.1f %+.1f (X Y Z)", dx, dy, dz), ChatFormatting.DARK_GRAY))),
                sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null) {
                        cur.setHoloOffset(cur.holoDx() + dx, cur.holoDy() + dy, cur.holoDz() + dz);
                        ParcelData.get(server).setDirty();
                    }
                    openHoloMove(sp, parcelId, fromAdmin);
                });
    }

    /** Reglage du prix d'une parcelle par l'admin (par paliers). */
    private static void openAdminPriceMenu(ServerPlayer admin, String parcelId) {
        MinecraftServer server = admin.server;
        Parcel p = getParcel(server, parcelId);
        if (p == null) {
            openAdminAll(admin);
            return;
        }
        long def = p.price() > 0 ? p.price() : (p.lastPaid() > 0 ? p.lastPaid() : 100);
        List<Component> info = List.of(
                Icons.lore("Parcelle " + p.id(), ChatFormatting.GRAY),
                Icons.lore("Prix actuel : " + EconomyManager.format(p.price()), ChatFormatting.GRAY));

        UtopiaGui gui = new UtopiaGui(3, Icons.label("Prix de " + p.id(), ChatFormatting.DARK_RED));
        gui.set(4, Icons.icon(EconomyManager.coinItem(),
                Icons.label("Prix actuel : " + EconomyManager.format(p.price()), ChatFormatting.GOLD), List.of()));

        gui.button(11, Icons.icon(Items.PAPER, Icons.label("Definir le prix", ChatFormatting.YELLOW),
                List.of(Icons.lore("Change le prix sans toucher a l'etat de vente", ChatFormatting.GRAY))),
                sp -> Menus.promptAmount(sp, Icons.label("Definir le prix de " + parcelId, ChatFormatting.DARK_RED),
                        info, Icons.label("Definir", ChatFormatting.GREEN), def, 1, 100_000_000L,
                        price -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                cur.setPrice(price);
                                ParcelData.get(server).setDirty();
                                sp.sendSystemMessage(Messages.success("Prix de " + cur.id() + " : " + EconomyManager.format(price) + "."));
                            }
                            openAdminParcel(sp, parcelId);
                        }));
        gui.button(15, Icons.icon(Items.GOLD_BLOCK, Icons.label("Definir + mettre en vente", ChatFormatting.GOLD), List.of()),
                sp -> Menus.promptAmount(sp, Icons.label("Prix + vente de " + parcelId, ChatFormatting.DARK_RED),
                        info, Icons.label("Definir + vente", ChatFormatting.GREEN), def, 1, 100_000_000L,
                        price -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                cur.setPrice(price);
                                cur.setForSale(true);
                                ParcelData.get(server).setDirty();
                                sp.sendSystemMessage(Messages.success(cur.id() + " en vente pour " + EconomyManager.format(price) + "."));
                            }
                            openAdminParcel(sp, parcelId);
                        }));
        gui.button(22, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openAdminParcel(sp, parcelId));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void teleportTo(ServerPlayer player, Parcel parcel) {
        ParcelManager.teleport(player, parcel);
    }
}
