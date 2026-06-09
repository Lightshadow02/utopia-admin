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
        // Outil de creation : reserve aux op (la creation de chambre passe par /room create).
        if (op) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(RoomManager.wandItem()),
                    Icons.label("Recevoir l'outil chambre", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Trace, puis /room create <id>", ChatFormatting.GRAY),
                    sp -> {
                        sp.getInventory().add(new ItemStack(RoomManager.wandItem()));
                        sp.sendSystemMessage(Messages.success("Outil chambre recu."));
                        com.utopia.gui.Menus.close(sp);
                    }));
        }

        OwoMenuServer.openHub(admin, title, stats, entries,
                RoomMenus::openAuberge, op ? com.utopia.menu.AdminMenu::open : null);
    }

    /** Gestion d'une chambre (ecran riche). */
    public static void openRoom(ServerPlayer admin, String roomId) {
        MinecraftServer server = admin.server;
        Room r = get(server, roomId);
        if (r == null) {
            admin.sendSystemMessage(Messages.error("Chambre introuvable."));
            return;
        }
        Component title = Icons.label("Chambre : " + r.name(), ChatFormatting.GOLD);
        List<Component> stats = List.of(
                stat("Occupant : ", r.isAssigned() ? r.occupantName() : "libre",
                        r.isAssigned() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                stat("Prix : ", EconomyManager.format(r.pricePerDay()) + "/jour", ChatFormatting.GOLD),
                stat("Duree : ", r.days() + " jours (cout " + EconomyManager.format(r.totalCost()) + ")", ChatFormatting.AQUA),
                stat("Etat : ", r.frozen() ? "GELEE (acces coupe)" : "active",
                        r.frozen() ? ChatFormatting.RED : ChatFormatting.GREEN));

        boolean frozen = r.frozen();
        long curPrice = r.pricePerDay();
        int curDays = r.days();
        boolean assigned = r.isAssigned();

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Attribuer a un joueur", ChatFormatting.YELLOW),
                Icons.lore("Tu avances le cout ; le joueur rembourse", ChatFormatting.GRAY),
                sp -> openAssignPicker(sp, roomId)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(frozen ? Items.GREEN_DYE : Items.BLUE_ICE),
                Icons.label(frozen ? "Degeler" : "Geler (freeze)", frozen ? ChatFormatting.GREEN : ChatFormatting.AQUA),
                Icons.lore(frozen ? "Rend l'acces au joueur" : "Coupe l'acces du joueur", ChatFormatting.GRAY),
                sp -> {
                    Room cur = get(sp.server, roomId);
                    if (cur != null) {
                        cur.setFrozen(!cur.frozen());
                        RoomData.get(sp.server).setDirty();
                        notifyFreeze(sp.server, cur);
                    }
                    openRoom(sp, roomId);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.SUNFLOWER),
                Icons.label("Prix / jour", ChatFormatting.GOLD),
                Icons.lore("Actuel : " + EconomyManager.format(curPrice), ChatFormatting.GRAY),
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
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CLOCK),
                Icons.label("Duree (jours)", ChatFormatting.YELLOW),
                Icons.lore("Actuel : " + curDays + " jours", ChatFormatting.GRAY),
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
        if (assigned) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Liberer la chambre", ChatFormatting.RED),
                    Icons.lore("Retire l'occupant", ChatFormatting.GRAY),
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
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LAVA_BUCKET),
                Icons.label("Supprimer la chambre", ChatFormatting.RED),
                Icons.lore("Definitif", ChatFormatting.GRAY),
                sp -> {
                    RoomData.get(sp.server).remove(roomId);
                    sp.sendSystemMessage(Messages.success("Chambre supprimee."));
                    openAuberge(sp);
                }));

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openRoom(sp, roomId), RoomMenus::openAuberge);
    }

    private static void openAssignPicker(ServerPlayer admin, String roomId) {
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

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openAssignPicker(sp, roomId), sp -> openRoom(sp, roomId));
    }

    /** Ligne de stat "label: valeur" (label gris, valeur coloree). */
    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
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
