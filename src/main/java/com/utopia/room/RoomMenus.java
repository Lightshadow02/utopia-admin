package com.utopia.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.utopia.data.RoomData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menus de l'auberge (admin/aubergiste) : liste des chambres + gestion d'une chambre. */
public final class RoomMenus {

    private RoomMenus() {
    }

    private static Room get(MinecraftServer server, String id) {
        return RoomData.get(server).get(id);
    }

    /** Liste de toutes les chambres (ecran riche). */
    public static void openAuberge(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        List<Room> rooms = new ArrayList<>(RoomData.get(server).all());
        rooms.sort(Comparator.comparing(Room::id, String.CASE_INSENSITIVE_ORDER));
        boolean op = admin.hasPermissions(2);

        Component title = Icons.label("Auberge - chambres", ChatFormatting.GOLD);
        List<Component> stats = List.of(Component.literal(rooms.size() + " chambre(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (Room r : rooms) {
            String rid = r.id();
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(r.frozen() ? Items.BLUE_ICE : r.isAssigned() ? Items.RED_BED : Items.WHITE_BED),
                    Icons.label((r.frozen() ? "[GELEE] " : "") + "Chambre " + rid,
                            r.frozen() ? ChatFormatting.AQUA : ChatFormatting.WHITE),
                    Icons.lore("Occupant : " + (r.isAssigned() ? r.occupantName() : "libre")
                            + " - " + EconomyManager.format(r.pricePerDay()) + "/j x " + r.days(), ChatFormatting.GRAY),
                    sp -> openRoom(sp, rid)));
        }
        // La configuration (outil chambre, bloc d'acces) est dans /admin -> Auberge, pas ici.
        OwoMenuServer.openHub(admin, title, stats, entries,
                RoomMenus::openAuberge, op ? com.utopia.menu.AdminMenu::openAubergeAdmin : null);
    }

    /** Gestion d'une chambre (ecran riche). */
    public static void openRoom(ServerPlayer admin, String roomId) {
        MinecraftServer server = admin.server;
        Room r = get(server, roomId);
        if (r == null) {
            admin.sendSystemMessage(Messages.error("Chambre introuvable."));
            return;
        }
        Component title = Icons.label("Chambre " + r.id(), ChatFormatting.GOLD);
        boolean frozen = r.frozen();
        long curPrice = r.pricePerDay();
        int curDays = r.days();
        boolean assigned = r.isAssigned();

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Occupant", ChatFormatting.GRAY),
                Icons.label(assigned ? r.occupantName() : "libre", assigned ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label("Attribuer", ChatFormatting.YELLOW),
                sp -> openAssignPicker(sp, roomId)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Prix / jour", ChatFormatting.GRAY),
                Icons.label(EconomyManager.format(curPrice), ChatFormatting.GOLD),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Prix par jour", ChatFormatting.GOLD), List.of(),
                        Icons.label("Definir", ChatFormatting.GREEN), Math.max(1, curPrice), 0, 100_000_000L,
                        v -> {
                            Room cur = get(sp.server, roomId);
                            if (cur != null) {
                                cur.setPricePerDay(v);
                                RoomData.get(sp.server).setDirty();
                            }
                            openRoom(sp, roomId);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Duree (jours)", ChatFormatting.GRAY),
                Icons.label(curDays + " j (cout " + EconomyManager.format(r.totalCost()) + ")", ChatFormatting.AQUA),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Nombre de jours", ChatFormatting.YELLOW), List.of(),
                        Icons.label("Definir", ChatFormatting.GREEN), Math.max(1, curDays), 0, 100000L,
                        v -> {
                            Room cur = get(sp.server, roomId);
                            if (cur != null) {
                                cur.setDays((int) v);
                                RoomData.get(sp.server).setDirty();
                            }
                            openRoom(sp, roomId);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                Icons.label(frozen ? "GELEE (acces coupe)" : "active", frozen ? ChatFormatting.RED : ChatFormatting.GREEN),
                Icons.label(frozen ? "Degeler" : "Geler", frozen ? ChatFormatting.GREEN : ChatFormatting.AQUA),
                sp -> {
                    Room cur = get(sp.server, roomId);
                    if (cur != null) {
                        cur.setFrozen(!cur.frozen());
                        RoomData.get(sp.server).setDirty();
                        notifyFreeze(sp.server, cur);
                    }
                    openRoom(sp, roomId);
                }));

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (assigned) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Liberer", ChatFormatting.GOLD),
                    sp -> {
                        Room cur = get(sp.server, roomId);
                        if (cur != null) {
                            cur.setOccupant(null, null);
                            cur.setFrozen(false);
                            RoomData.get(sp.server).setDirty();
                        }
                        openRoom(sp, roomId);
                    }));
        }
        // Supprimer : reserve aux op (un aubergiste ne peut pas supprimer une chambre).
        if (admin.hasPermissions(2)) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                    sp -> {
                        RoomData.get(sp.server).remove(roomId);
                        sp.sendSystemMessage(Messages.success("Chambre supprimee."));
                        openAuberge(sp);
                    }));
        }

        OwoMenuServer.openPanel(admin, title, rows, footer,
                sp -> openRoom(sp, roomId), RoomMenus::openAuberge);
    }

    /** Entrees par page dans le selecteur de joueurs. */
    private static final int PICKER_PAGE_SIZE = 12;

    private static void openAssignPicker(ServerPlayer admin, String roomId) {
        openAssignPicker(admin, roomId, 0);
    }

    private static void openAssignPicker(ServerPlayer admin, String roomId, int page) {
        MinecraftServer server = admin.server;
        Component title = Icons.label("Attribuer - choisir un joueur", ChatFormatting.GOLD);
        List<Component> stats = List.of(Component.literal("Chambre : " + roomId)
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID id = target.getUUID();
            String tname = target.getGameProfile().getName();
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, ChatFormatting.WHITE),
                    Icons.lore("Definir prix puis jours", ChatFormatting.GRAY),
                    sp -> openAssignPrice(sp, roomId, id, tname)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PICKER_PAGE_SIZE,
                (sp, p) -> openAssignPicker(sp, roomId, p), sp -> openRoom(sp, roomId));
    }

    private static void openAssignPrice(ServerPlayer admin, String roomId, UUID targetId, String targetName) {
        Room r = get(admin.server, roomId);
        long defPrice = r != null && r.pricePerDay() > 0 ? r.pricePerDay() : 10;
        Menus.promptAmount(admin, Icons.label("Prix par jour", ChatFormatting.GOLD),
                List.of(Icons.lore("Locataire : " + targetName, ChatFormatting.GRAY)),
                Icons.label("Suivant : jours", ChatFormatting.GREEN), defPrice, 0, 100_000_000L,
                price -> openAssignDays(admin, roomId, targetId, targetName, price));
    }

    private static void openAssignDays(ServerPlayer admin, String roomId, UUID targetId, String targetName, long pricePerDay) {
        Room r = get(admin.server, roomId);
        long defDays = r != null && r.days() > 0 ? r.days() : 7;
        Menus.promptAmount(admin, Icons.label("Nombre de jours", ChatFormatting.YELLOW),
                List.of(Icons.lore("Locataire : " + targetName, ChatFormatting.GRAY),
                        Icons.lore("Prix/jour : " + EconomyManager.format(pricePerDay), ChatFormatting.GOLD)),
                Icons.label("Confirmer (tu avances le cout)", ChatFormatting.GREEN), defDays, 1, 100000L,
                days -> finishAssign(admin, roomId, targetId, targetName, pricePerDay, (int) days));
    }

    private static void finishAssign(ServerPlayer admin, String roomId, UUID targetId, String targetName, long pricePerDay, int days) {
        MinecraftServer server = admin.server;
        Room r = get(server, roomId);
        if (r == null) {
            admin.sendSystemMessage(Messages.error("Chambre introuvable."));
            return;
        }
        long cost = pricePerDay * days;
        if (!EconomyManager.payCombined(admin, cost)) {
            admin.sendSystemMessage(Messages.error("Fonds insuffisants pour avancer " + EconomyManager.format(cost) + " (pieces + solde)."));
            openRoom(admin, roomId);
            return;
        }
        r.setOccupant(targetId, targetName);
        r.setPricePerDay(pricePerDay);
        r.setDays(days);
        r.setFrozen(false);
        RoomData.get(server).setDirty();
        admin.sendSystemMessage(Messages.success("Chambre '" + r.id() + "' attribuee a " + targetName
                + " (avance " + EconomyManager.format(cost) + ")."));
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        if (online != null) {
            online.sendSystemMessage(Messages.info("La chambre " + r.name() + " vous a ete attribuee."));
        }
        openRoom(admin, roomId);
    }

    private static void notifyFreeze(MinecraftServer server, Room r) {
        if (r.occupant() == null) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(r.occupant());
        if (online != null) {
            online.sendSystemMessage(r.frozen()
                    ? Messages.warn("Votre chambre " + r.name() + " a ete gelee.")
                    : Messages.success("Votre chambre " + r.name() + " est de nouveau accessible."));
        }
    }
}
