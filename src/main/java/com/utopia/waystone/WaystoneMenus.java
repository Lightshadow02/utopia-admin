package com.utopia.waystone;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.WaystoneData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Ecrans des balises : le reseau vu depuis une stele, et le registre d'administration. */
public final class WaystoneMenus {

    private static final int PAGE_SIZE = 12;

    private WaystoneMenus() {
    }

    // ==============================================================================================
    //  Reseau vu depuis une balise
    // ==============================================================================================

    public static void openStone(ServerPlayer player, String id) {
        openStone(player, id, 0);
    }

    public static void openStone(ServerPlayer player, String id, int page) {
        WaystoneData data = WaystoneData.get(player.server);
        WaystoneData.Waystone here = data.get(id);
        if (here == null) {
            player.sendSystemMessage(Messages.warn(
                    WaystoneManager.reason(WaystoneManager.TravelResult.UNKNOWN)));
            return;
        }
        boolean canEdit = player.hasPermissions(2)
                || (here.owner != null && here.owner.equals(player.getUUID()));

        List<WaystoneData.Waystone> known = data.availableTo(player.getUUID());
        Component title = Icons.screenTitle(here.name, ChatFormatting.AQUA);
        List<Component> stats = new ArrayList<>();
        stats.add(Icons.lore(WaystoneManager.worldLabel(here.dim) + " - "
                + here.x + " " + here.y + " " + here.z, ChatFormatting.GRAY));
        stats.add(Icons.lore(known.size() + " balise(s) dans votre reseau sur "
                + data.all().size() + " posee(s)", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (WaystoneData.Waystone stone : known) {
            if (stone.id.equals(id)) {
                continue; // on ne voyage pas vers l'endroit ou l'on se tient
            }
            String target = stone.id;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(stone.global ? Items.BEACON : Items.ENDER_PEARL),
                    Icons.label(stone.name, stone.global ? ChatFormatting.GOLD : ChatFormatting.AQUA),
                    Icons.lore(WaystoneManager.worldLabel(stone.dim) + " - " + stone.x + " "
                                    + stone.y + " " + stone.z
                                    + (stone.global ? " - publique" : ""),
                            ChatFormatting.GRAY),
                    sp -> {
                        WaystoneManager.TravelResult r = WaystoneManager.travel(sp, target);
                        if (r != WaystoneManager.TravelResult.OK) {
                            sp.sendSystemMessage(Messages.warn(WaystoneManager.reason(r)));
                            openStone(sp, id, page);
                        } else {
                            Menus.close(sp);
                        }
                    }));
        }
        if (entries.isEmpty()) {
            stats.add(Icons.lore("Trouvez d'autres balises pour pouvoir voyager.", ChatFormatting.RED));
        }

        List<OwoMenuServer.HubEntry> pinned = new ArrayList<>();
        if (canEdit) {
            pinned.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                    Icons.label("Renommer cette balise", ChatFormatting.YELLOW),
                    Icons.lore("Le nom que verront tous ceux qui la trouvent", ChatFormatting.GRAY),
                    sp -> promptRename(sp, id)));
        }
        if (player.hasPermissions(2)) {
            pinned.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BEACON),
                    Icons.label(here.global ? "Rendre privee" : "Rendre publique",
                            here.global ? ChatFormatting.GRAY : ChatFormatting.GOLD),
                    Icons.lore(here.global
                                    ? "Elle redevient a trouver pour en profiter"
                                    : "Elle sera connue de tous, sans avoir a la trouver",
                            ChatFormatting.GRAY),
                    sp -> {
                        WaystoneData d = WaystoneData.get(sp.server);
                        WaystoneData.Waystone fresh = d.get(id);
                        if (fresh != null) {
                            fresh.global = !fresh.global;
                            d.setDirty();
                        }
                        openStone(sp, id, page);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, pinned, entries, page, PAGE_SIZE,
                (sp, p) -> openStone(sp, id, p), null);
    }

    /** Nommage d'une balise : a la pose, puis a la demande. */
    public static void promptRename(ServerPlayer player, String id) {
        WaystoneData.Waystone stone = WaystoneData.get(player.server).get(id);
        if (stone == null) {
            return;
        }
        Menus.promptFreeText(player, Icons.title("Nom de la balise", ChatFormatting.AQUA),
                List.of(Icons.lore("Ce nom sera lu par tous ceux qui la trouveront",
                                ChatFormatting.GRAY),
                        Icons.lore("Ex : Port du Nord, Mine profonde...", ChatFormatting.DARK_GRAY)),
                Icons.label("Valider", ChatFormatting.GREEN), stone.name, 32,
                text -> {
                    if (text != null && !text.isBlank()) {
                        WaystoneData d = WaystoneData.get(player.server);
                        WaystoneData.Waystone fresh = d.get(id);
                        if (fresh != null) {
                            fresh.name = text.trim();
                            d.setDirty();
                            player.sendSystemMessage(Messages.success("Balise nommee \""
                                    + fresh.name + "\"."));
                        }
                    }
                    openStone(player, id, 0);
                });
    }

    // ==============================================================================================
    //  Registre d'administration
    // ==============================================================================================

    public static void openAdmin(ServerPlayer admin) {
        openAdmin(admin, 0);
    }

    public static void openAdmin(ServerPlayer admin, int page) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration."));
            return;
        }
        WaystoneData data = WaystoneData.get(admin.server);

        Component title = Icons.screenTitle("Balises de voyage", ChatFormatting.AQUA);
        List<Component> stats = List.of(
                Icons.lore(data.all().size() + " balise(s) posee(s) sur le serveur", ChatFormatting.GRAY),
                Icons.lore("Le bloc n'a aucune recette : il ne s'obtient qu'ici.",
                        ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> pinned = List.of(
                new OwoMenuServer.HubEntry(
                        new ItemStack(com.utopia.block.UtopiaBlocks.WAYSTONE_ITEM.get()),
                        Icons.label("Obtenir une balise", ChatFormatting.GREEN),
                        Icons.lore("Ajoute le bloc a votre inventaire", ChatFormatting.GRAY),
                        sp -> {
                            net.neoforged.neoforge.items.ItemHandlerHelper.giveItemToPlayer(sp,
                                    new ItemStack(com.utopia.block.UtopiaBlocks.WAYSTONE_ITEM.get()));
                            sp.sendSystemMessage(Messages.success("Balise recue : posez-la ou vous voulez."));
                            openAdmin(sp, page);
                        }));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (WaystoneData.Waystone stone : data.all()) {
            String id = stone.id;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(stone.global ? Items.BEACON : Items.ENDER_PEARL),
                    Icons.label(stone.name, stone.global ? ChatFormatting.GOLD : ChatFormatting.AQUA),
                    Icons.lore(WaystoneManager.worldLabel(stone.dim) + " " + stone.x + " " + stone.y
                                    + " " + stone.z
                                    + (stone.ownerName.isBlank() ? "" : " - " + stone.ownerName)
                                    + (stone.global ? " - publique" : ""),
                            ChatFormatting.GRAY),
                    sp -> openAdminStone(sp, id)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, pinned, entries, page, PAGE_SIZE,
                WaystoneMenus::openAdmin, com.utopia.menu.AdminMenu::open);
    }

    public static void openAdminStone(ServerPlayer admin, String id) {
        WaystoneData data = WaystoneData.get(admin.server);
        WaystoneData.Waystone stone = data.get(id);
        if (stone == null) {
            openAdmin(admin);
            return;
        }
        Component title = Icons.title(stone.name, ChatFormatting.AQUA);

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(Icons.label("Monde", ChatFormatting.GRAY),
                        Icons.label(WaystoneManager.worldLabel(stone.dim), ChatFormatting.WHITE),
                        null, null),
                new OwoMenuServer.PanelRow(Icons.label("Position", ChatFormatting.GRAY),
                        Icons.label(stone.x + " " + stone.y + " " + stone.z, ChatFormatting.WHITE),
                        null, null),
                new OwoMenuServer.PanelRow(Icons.label("Posee par", ChatFormatting.GRAY),
                        Icons.label(stone.ownerName.isBlank() ? "l'administration" : stone.ownerName,
                                ChatFormatting.AQUA), null, null),
                new OwoMenuServer.PanelRow(Icons.label("Nom", ChatFormatting.GRAY),
                        Icons.label(stone.name, ChatFormatting.WHITE),
                        Icons.label("Renommer", ChatFormatting.YELLOW),
                        sp -> promptRename(sp, id)),
                new OwoMenuServer.PanelRow(Icons.label("Acces", ChatFormatting.GRAY),
                        Icons.label(stone.global ? "publique - connue de tous" : "a trouver",
                                stone.global ? ChatFormatting.GOLD : ChatFormatting.GRAY),
                        Icons.label(stone.global ? "Rendre privee" : "Rendre publique",
                                ChatFormatting.YELLOW),
                        sp -> {
                            WaystoneData d = WaystoneData.get(sp.server);
                            WaystoneData.Waystone fresh = d.get(id);
                            if (fresh != null) {
                                fresh.global = !fresh.global;
                                d.setDirty();
                            }
                            openAdminStone(sp, id);
                        }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Se teleporter", ChatFormatting.LIGHT_PURPLE),
                        sp -> {
                            WaystoneData d = WaystoneData.get(sp.server);
                            d.discover(sp.getUUID(), id); // un administrateur voit tout le reseau
                            if (WaystoneManager.travel(sp, id) == WaystoneManager.TravelResult.OK) {
                                Menus.close(sp);
                            } else {
                                openAdminStone(sp, id);
                            }
                        }),
                new OwoMenuServer.PanelAction(Icons.label("Retirer du reseau", ChatFormatting.RED),
                        sp -> {
                            // Le bloc reste dans le monde : on ne casse rien a distance, on oublie.
                            WaystoneData.get(sp.server).remove(id);
                            sp.sendSystemMessage(Messages.success("Balise retiree du reseau "
                                    + "(le bloc est toujours en place)."));
                            openAdmin(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openAdminStone(sp, id),
                WaystoneMenus::openAdmin);
    }
}
