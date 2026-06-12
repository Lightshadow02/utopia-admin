package com.utopia.parcel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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

        // Style "panneau de reglages" (comme le menu admin) : libelle + valeur + bouton.
        int total = owned.size();
        Component title = Icons.label("Parcelle " + p.id() + " (" + (i + 1) + "/" + total + ")", ChatFormatting.GREEN);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Type", ChatFormatting.GRAY),
                Icons.label(p.type().label(), p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                null, null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("En vente", ChatFormatting.GRAY),
                Icons.label(p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non",
                        p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                null, null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Membres / regions", ChatFormatting.GRAY),
                Icons.label(p.members().size() + " / " + p.regionCount(), ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openMembersMenu(sp, parcelId)));
        if (p.forSale()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Hologramme", ChatFormatting.GRAY),
                    Icons.label("a vendre", ChatFormatting.GREEN),
                    Icons.label("Deplacer", ChatFormatting.YELLOW),
                    sp -> openHoloMove(sp, parcelId, false)));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Vendre", ChatFormatting.GOLD),
                sp -> openSellMenu(sp, parcelId)));
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Delimitations", ChatFormatting.YELLOW),
                sp -> {
                    Parcel cur = getParcel(sp.server, parcelId);
                    if (cur != null) {
                        ParcelHolograms.startPreview(sp, cur);
                    }
                    com.utopia.gui.Menus.close(sp);
                }));
        if (total > 1) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("< Prec.", ChatFormatting.YELLOW),
                    sp -> openMyParcels(sp, (i - 1 + total) % total)));
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Suiv. >", ChatFormatting.YELLOW),
                    sp -> openMyParcels(sp, (i + 1) % total)));
        }

        final int idx = i;
        OwoMenuServer.openPanel(player, title, rows, footer,
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
        long refund = Math.round(p.lastPaid() * ParcelManager.SERVER_BUYBACK_RATE);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("A la Mairie", ChatFormatting.GRAY),
                Icons.label(EconomyManager.format(refund) + " (75%)", ChatFormatting.GOLD),
                Icons.label("Vendre", ChatFormatting.YELLOW),
                sp -> openConfirm(sp, Icons.label("Vendre a la Mairie ?", ChatFormatting.GOLD),
                        List.of(Icons.lore("Rembourse " + EconomyManager.format(refund) + " ; la parcelle repart en vente Mairie", ChatFormatting.GRAY)),
                        s2 -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur == null || !cur.isOwner(s2.getUUID())) {
                                s2.sendSystemMessage(Messages.error("Vous n'etes pas proprietaire."));
                                return;
                            }
                            long r = ParcelManager.sellToServer(s2, cur);
                            s2.sendSystemMessage(Messages.success("Parcelle vendue a la Mairie. Rembourse : " + EconomyManager.format(r) + "."));
                            com.utopia.gui.Menus.close(s2);
                        },
                        s2 -> openSellMenu(s2, parcelId))));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Aux joueurs", ChatFormatting.GRAY),
                Icons.label(p.forSale() ? EconomyManager.format(p.price()) : "prix libre",
                        p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label("Definir", ChatFormatting.YELLOW),
                sp -> openListPriceMenu(sp, parcelId)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("En vente", ChatFormatting.GRAY),
                Icons.label(p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non",
                        p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                p.forSale() ? Icons.label("Retirer", ChatFormatting.RED) : null,
                p.forSale() ? sp -> {
                    Parcel cur = getParcel(server, parcelId);
                    if (cur != null && canManage(sp, cur)) {
                        cur.setForSale(false);
                        ParcelData.get(server).setDirty();
                        sp.sendSystemMessage(Messages.success("Parcelle retiree de la vente."));
                    }
                    openSellMenu(sp, parcelId);
                } : null));

        OwoMenuServer.openPanel(player, Icons.label("Vendre - " + p.id(), ChatFormatting.GOLD),
                rows, List.of(), sp -> openSellMenu(sp, parcelId), sp -> backToParcelMenu(sp, parcelId));
    }

    /** Retour contextuel : proprietaire -> "Mes parcelles" ; sinon (admin) -> panneau admin. */
    private static void backToParcelMenu(ServerPlayer player, String parcelId) {
        Parcel p = getParcel(player.server, parcelId);
        if (p != null && p.isOwner(player.getUUID())) {
            List<Parcel> owned = new ArrayList<>(ParcelData.get(player.server).ownedBy(player.getUUID()));
            owned.sort(Comparator.comparing(Parcel::id, String.CASE_INSENSITIVE_ORDER));
            int idx = 0;
            for (int i = 0; i < owned.size(); i++) {
                if (owned.get(i).id().equalsIgnoreCase(parcelId)) {
                    idx = i;
                    break;
                }
            }
            openMyParcels(player, idx);
        } else {
            openAdminParcel(player, parcelId);
        }
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
        Component title = Icons.label("Membres - " + p.id(), ChatFormatting.DARK_AQUA);
        List<Component> stats = List.of(Component.literal(p.members().size() + " membre(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                Icons.label("+ Ajouter un membre", ChatFormatting.GREEN),
                Icons.lore("Choisir un joueur en ligne", ChatFormatting.GRAY),
                sp -> openAddMemberMenu(sp, parcelId)));
        for (var entry : p.members().entrySet()) {
            UUID memberId = entry.getKey();
            int count = entry.getValue().size();
            entries.add(new OwoMenuServer.HubEntry(
                    head(server, memberId, Icons.label(nameOf(server, memberId), ChatFormatting.WHITE), List.of()),
                    Icons.label(nameOf(server, memberId), ChatFormatting.WHITE),
                    Icons.lore(count + " droit(s)", ChatFormatting.AQUA),
                    sp -> openMemberEditMenu(sp, parcelId, memberId)));
        }

        OwoMenuServer.openHub(player, title, stats, entries,
                sp -> openMembersMenu(sp, parcelId), sp -> backToParcelMenu(sp, parcelId));
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

        Component title = Icons.label("Droits de " + nameOf(server, memberId), ChatFormatting.DARK_AQUA);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (Parcel.Flag flag : FLAGS) {
            boolean on = flags.contains(flag);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(flagLabel(flag), ChatFormatting.GRAY),
                    Icons.label(on ? "OUI" : "non", on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    Icons.label("Changer", ChatFormatting.YELLOW),
                    sp -> toggleFlag(sp, parcelId, memberId, flag)));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Retirer le membre", ChatFormatting.RED),
                        sp -> openConfirm(sp, Icons.label("Retirer ce membre ?", ChatFormatting.RED),
                                List.of(Icons.lore(nameOf(server, memberId) + " perdra tous ses droits", ChatFormatting.GRAY)),
                                s2 -> {
                                    Parcel cur = getParcel(server, parcelId);
                                    if (cur != null && canManage(s2, cur)) {
                                        cur.setMember(memberId, EnumSet.noneOf(Parcel.Flag.class));
                                        ParcelData.get(server).setDirty();
                                        s2.sendSystemMessage(Messages.success(nameOf(server, memberId) + " retire de la parcelle."));
                                    }
                                    openMembersMenu(s2, parcelId);
                                },
                                s2 -> openMemberEditMenu(s2, parcelId, memberId))));

        OwoMenuServer.openPanel(player, title, rows, footer,
                sp -> openMemberEditMenu(sp, parcelId, memberId), sp -> openMembersMenu(sp, parcelId));
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
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            UUID id = online.getUUID();
            if (p.isOwner(id) || p.members().containsKey(id)) {
                continue; // deja proprietaire ou membre
            }
            String oname = online.getGameProfile().getName();
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(online, Icons.label(oname, ChatFormatting.WHITE), List.of()),
                    Icons.label(oname, ChatFormatting.WHITE),
                    Icons.lore("Ajouter (tous les droits)", ChatFormatting.GRAY),
                    sp -> {
                        Parcel cur = getParcel(server, parcelId);
                        if (cur != null && canManage(sp, cur)) {
                            cur.setMember(id, EnumSet.allOf(Parcel.Flag.class));
                            ParcelData.get(server).setDirty();
                            sp.sendSystemMessage(Messages.success(oname + " ajoute. Ajustez ses droits."));
                            openMemberEditMenu(sp, parcelId, id);
                        }
                    }));
        }

        OwoMenuServer.openHub(player, Icons.label("Ajouter un membre", ChatFormatting.DARK_AQUA),
                List.of(), entries, sp -> openAddMemberMenu(sp, parcelId), sp -> openMembersMenu(sp, parcelId));
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
        // Rangee du bas : outil + creation par type (trace d'abord la zone au wand).
        gui.button(45, Icons.icon(ParcelManager.wandItem(), Icons.label("Recevoir le wand parcelle", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Clic droit au sol pour tracer les points", ChatFormatting.GRAY),
                        Icons.lore("Puis utilise un bouton Creer ci-dessous", ChatFormatting.DARK_GRAY))),
                sp -> {
                    sp.getInventory().add(new ItemStack(ParcelManager.wandItem()));
                    sp.sendSystemMessage(Messages.success("Wand parcelle recu. Clic droit au sol pour tracer."));
                    com.utopia.gui.Menus.close(sp);
                });
        gui.button(47, Icons.icon(Items.BLUE_WOOL, Icons.label("Creer : Habitation", ChatFormatting.AQUA),
                List.of(Icons.lore("Parcelle classique (contour bleu)", ChatFormatting.GRAY))),
                sp -> promptCreate(sp, "Habitation", false, Parcel.Type.HABITATION));
        gui.button(48, Icons.icon(Items.YELLOW_WOOL, Icons.label("Creer : Commerce", ChatFormatting.YELLOW),
                List.of(Icons.lore("Parcelle classique (contour jaune)", ChatFormatting.GRAY))),
                sp -> promptCreate(sp, "Commerce", false, Parcel.Type.COMMERCE));
        gui.button(49, Icons.icon(Items.BEDROCK, Icons.label("Creer : Admin", ChatFormatting.RED),
                List.of(Icons.lore("Protegee anti-grief, hors shop", ChatFormatting.GRAY))),
                sp -> promptCreate(sp, "Admin", true, null));
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Demande un identifiant puis cree une parcelle du type voulu a partir du trace au wand. */
    private static void promptCreate(ServerPlayer admin, String typeLabel, boolean adminParcel, Parcel.Type type) {
        Menus.promptText(admin,
                Icons.label("Nouvelle parcelle " + typeLabel, ChatFormatting.DARK_AQUA),
                List.of(Icons.lore("Identifiant (lettres, chiffres, _ , -)", ChatFormatting.GRAY),
                        Icons.lore("La zone = ton trace au wand", ChatFormatting.DARK_GRAY)),
                Icons.label("Creer", ChatFormatting.GREEN), "", 24,
                id -> {
                    Parcel created = ParcelManager.createFromTrace(admin, id, adminParcel);
                    if (created != null) {
                        if (!adminParcel && type != null) {
                            created.setType(type);
                            ParcelData.get(admin.server).setDirty();
                        }
                        admin.sendSystemMessage(Messages.success("Parcelle " + typeLabel + " '" + created.id() + "' creee."));
                        openAdminParcel(admin, created.id());
                    } else {
                        openAdminAll(admin);
                    }
                });
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
        // Style "panneau de reglages" : une ligne = libelle + valeur actuelle + bouton.
        Parcel.Type other = p.type() == Parcel.Type.COMMERCE ? Parcel.Type.HABITATION : Parcel.Type.COMMERCE;
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();

        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Categorie", ChatFormatting.GRAY),
                Icons.label(p.type().label(), p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> openConfirm(sp, Icons.label("Passer en " + other.label() + " ?", ChatFormatting.GOLD),
                        List.of(Icons.lore(p.type().label() + " -> " + other.label(), ChatFormatting.GRAY)),
                        s2 -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                cur.setType(cur.type() == Parcel.Type.COMMERCE ? Parcel.Type.HABITATION : Parcel.Type.COMMERCE);
                                ParcelData.get(server).setDirty();
                            }
                            openAdminParcel(s2, parcelId);
                        },
                        s2 -> openAdminParcel(s2, parcelId))));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Proprietaire", ChatFormatting.GRAY),
                Icons.label(p.isOwned() ? p.ownerName() : "Mairie", p.isOwned() ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                Icons.label("Transferer", ChatFormatting.YELLOW),
                sp -> openTransferPicker(sp, parcelId)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Prix", ChatFormatting.GRAY),
                Icons.label(EconomyManager.format(p.price()), ChatFormatting.GOLD),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> openAdminPriceMenu(sp, parcelId)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("En vente", ChatFormatting.GRAY),
                Icons.label(p.forSale() ? "oui" : "non", p.forSale() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                null, null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Membres / regions", ChatFormatting.GRAY),
                Icons.label(p.members().size() + " / " + p.regionCount(), ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openMembersMenu(sp, parcelId)));
        if (p.forSale()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Hologramme", ChatFormatting.GRAY),
                    Icons.label("a vendre", ChatFormatting.GREEN),
                    Icons.label("Deplacer", ChatFormatting.YELLOW),
                    sp -> openHoloMove(sp, parcelId, true)));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Statut", ChatFormatting.GRAY),
                Icons.label("Normale", ChatFormatting.WHITE),
                Icons.label("-> Admin", ChatFormatting.RED),
                sp -> openConfirm(sp, Icons.label("Marquer comme Admin ?", ChatFormatting.RED),
                        List.of(Icons.lore("Protegee, hors shop, sans proprietaire", ChatFormatting.GRAY)),
                        s2 -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                ParcelManager.makeAdmin(cur, true);
                                ParcelData.get(server).setDirty();
                                s2.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " marquee ADMIN."));
                            }
                            openAdminParcel(s2, parcelId);
                        },
                        s2 -> openAdminParcel(s2, parcelId))));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Teleporter", ChatFormatting.LIGHT_PURPLE),
                        sp -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                teleportTo(sp, cur);
                            }
                            com.utopia.gui.Menus.close(sp);
                        }),
                new OwoMenuServer.PanelAction(Icons.label("Remettre en vente", ChatFormatting.GOLD),
                        sp -> openConfirm(sp, Icons.label("Remettre en vente ?", ChatFormatting.GOLD),
                                List.of(Icons.lore("Reliste a " + EconomyManager.format(p.lastPaid()), ChatFormatting.GRAY)),
                                s2 -> {
                                    Parcel cur = getParcel(server, parcelId);
                                    if (cur != null) {
                                        ParcelManager.repossess(server, cur);
                                        s2.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " remise en vente."));
                                    }
                                    openAdminParcel(s2, parcelId);
                                },
                                s2 -> openAdminParcel(s2, parcelId))),
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> openDeleteConfirm(sp, parcelId)));

        OwoMenuServer.openPanel(admin, Icons.label("Parcelle " + p.id(), ChatFormatting.DARK_RED),
                rows, footer, sp -> openAdminParcel(sp, parcelId), ParcelMenus::openAdminAll);
    }

    /** Petit ecran de confirmation generique (Confirmer / Annuler) avec une icone d'info. */
    private static void openConfirm(ServerPlayer admin, Component title, List<Component> lore,
                                    Consumer<ServerPlayer> onYes, Consumer<ServerPlayer> onCancel) {
        UtopiaGui gui = new UtopiaGui(3, title);
        gui.set(4, Icons.icon(Items.PAPER, title, lore));
        gui.button(11, Icons.icon(Items.LIME_DYE, Icons.label("Confirmer", ChatFormatting.GREEN), List.of()),
                onYes::accept);
        gui.button(15, Icons.icon(Items.RED_DYE, Icons.label("Annuler", ChatFormatting.RED), List.of()),
                onCancel::accept);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Menu compact d'une zone Admin : apercu, TP, droits globaux, statut, suppression. */
    private static void openAdminZoneMenu(ServerPlayer admin, String parcelId, Parcel p) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Zone Admin : " + p.id(), ChatFormatting.DARK_RED)).iconOnly(true);

        gui.set(4, Icons.icon(Items.BEDROCK, Icons.label("Zone Admin : " + p.id(), ChatFormatting.RED), List.of(
                Icons.lore("Protegee (anti-grief), hors shop", ChatFormatting.GRAY),
                Icons.lore("Regions : " + p.regionCount(), ChatFormatting.DARK_GRAY),
                Icons.lore("Droits de tous : " + publicFlagsSummary(p), ChatFormatting.AQUA))));

        // Supprimer : icone en haut a DROITE (slot 8), avec confirmation.
        gui.button(8, Icons.icon(Items.BARRIER, Icons.label("Supprimer la zone", ChatFormatting.RED),
                List.of(Icons.lore("Suppression definitive (confirmation)", ChatFormatting.GRAY))),
                sp -> openDeleteConfirm(sp, parcelId));

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
                sp -> openConfirm(sp, Icons.label("Retirer le statut Admin ?", ChatFormatting.YELLOW),
                        List.of(Icons.lore("Redevient une parcelle normale (Mairie)", ChatFormatting.GRAY)),
                        s2 -> {
                            Parcel cur = getParcel(server, parcelId);
                            if (cur != null) {
                                ParcelManager.makeAdmin(cur, false);
                                ParcelData.get(server).setDirty();
                                s2.sendSystemMessage(Messages.success("Parcelle " + cur.id() + " n'est plus admin."));
                            }
                            openAdminParcel(s2, parcelId);
                        },
                        s2 -> openAdminParcel(s2, parcelId)));

        // Retour : fleche en bas a gauche (slot 18).
        gui.button(18, Icons.icon(Items.ARROW, Icons.label("< Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openAdminAll(sp));
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
