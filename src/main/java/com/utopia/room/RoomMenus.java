package com.utopia.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.utopia.data.RoomData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
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

    /** Liste de toutes les chambres. */
    public static void openAuberge(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        List<Room> rooms = new ArrayList<>(RoomData.get(server).all());
        rooms.sort(Comparator.comparing(Room::id, String.CASE_INSENSITIVE_ORDER));

        UtopiaGui gui = new UtopiaGui(6, Icons.label("Auberge - chambres", ChatFormatting.GOLD));
        int slot = 0;
        for (Room r : rooms) {
            if (slot > 44) {
                break;
            }
            String rid = r.id();
            gui.button(slot++, Icons.icon(r.frozen() ? Items.BLUE_ICE : r.isAssigned() ? Items.RED_BED : Items.WHITE_BED,
                            Icons.label((r.frozen() ? "[GELEE] " : "") + "Chambre " + rid,
                                    r.frozen() ? ChatFormatting.AQUA : ChatFormatting.WHITE), List.of(
                            Icons.lore("Occupant : " + (r.isAssigned() ? r.occupantName() : "libre"),
                                    r.isAssigned() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                            Icons.lore("Prix : " + EconomyManager.format(r.pricePerDay()) + "/jour x " + r.days(), ChatFormatting.GOLD),
                            Icons.lore("Clic : gerer", ChatFormatting.YELLOW))),
                    sp -> openRoom(sp, rid));
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucune chambre", ChatFormatting.RED),
                    List.of(Icons.lore("Recois l'outil, trace, puis /room create <id>", ChatFormatting.GRAY))));
        }
        gui.button(49, Icons.icon(Items.BLAZE_ROD, Icons.label("Recevoir l'outil chambre", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Clic gauche = coin 1, clic droit = coin 2 (Y compris)", ChatFormatting.GRAY),
                        Icons.lore("Puis /room create <id>", ChatFormatting.DARK_GRAY))),
                sp -> {
                    sp.getInventory().add(new ItemStack(RoomManager.wandItem()));
                    sp.sendSystemMessage(Messages.success("Outil chambre recu."));
                    com.utopia.gui.Menus.close(sp);
                });
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Gestion d'une chambre. */
    public static void openRoom(ServerPlayer admin, String roomId) {
        MinecraftServer server = admin.server;
        Room r = get(server, roomId);
        if (r == null) {
            admin.sendSystemMessage(Messages.error("Chambre introuvable."));
            return;
        }
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Chambre : " + r.name(), ChatFormatting.GOLD));
        gui.set(4, Icons.icon(r.frozen() ? Items.BLUE_ICE : Items.WHITE_BED, Icons.label("Chambre " + r.id(), ChatFormatting.AQUA), List.of(
                Icons.lore("Occupant : " + (r.isAssigned() ? r.occupantName() : "libre"),
                        r.isAssigned() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.lore("Prix : " + EconomyManager.format(r.pricePerDay()) + "/jour", ChatFormatting.GOLD),
                Icons.lore("Duree : " + r.days() + " jours (cout " + EconomyManager.format(r.totalCost()) + ")", ChatFormatting.GRAY),
                Icons.lore("Etat : " + (r.frozen() ? "GELEE (acces coupe)" : "active"),
                        r.frozen() ? ChatFormatting.RED : ChatFormatting.GREEN))));

        gui.button(10, Icons.icon(Items.PLAYER_HEAD, Icons.label("Attribuer a un joueur", ChatFormatting.YELLOW),
                List.of(Icons.lore("Tu avances le cout ; le joueur rembourse", ChatFormatting.GRAY))),
                sp -> openAssignPicker(sp, roomId));
        gui.button(12, r.frozen()
                        ? Icons.icon(Items.GREEN_DYE, Icons.label("Degeler", ChatFormatting.GREEN),
                                List.of(Icons.lore("Rend l'acces au joueur", ChatFormatting.GRAY)))
                        : Icons.icon(Items.BLUE_ICE, Icons.label("Geler (freeze)", ChatFormatting.AQUA),
                                List.of(Icons.lore("Coupe l'acces du joueur a sa chambre", ChatFormatting.GRAY))),
                sp -> {
                    Room cur = get(server, roomId);
                    if (cur != null) {
                        cur.setFrozen(!cur.frozen());
                        RoomData.get(server).setDirty();
                        notifyFreeze(server, cur);
                    }
                    openRoom(sp, roomId);
                });
        gui.button(14, Icons.icon(Items.SUNFLOWER, Icons.label("Prix / jour", ChatFormatting.GOLD),
                List.of(Icons.lore("Actuel : " + EconomyManager.format(r.pricePerDay()), ChatFormatting.GRAY))),
                sp -> Menus.promptAmount(sp, Icons.label("Prix par jour", ChatFormatting.GOLD), List.of(),
                        Icons.label("Definir", ChatFormatting.GREEN), Math.max(1, r.pricePerDay()), 0, 100_000_000L,
                        v -> {
                            Room cur = get(server, roomId);
                            if (cur != null) {
                                cur.setPricePerDay(v);
                                RoomData.get(server).setDirty();
                            }
                            openRoom(sp, roomId);
                        }));
        gui.button(16, Icons.icon(Items.CLOCK, Icons.label("Duree (jours)", ChatFormatting.YELLOW),
                List.of(Icons.lore("Actuel : " + r.days() + " jours", ChatFormatting.GRAY))),
                sp -> Menus.promptAmount(sp, Icons.label("Nombre de jours", ChatFormatting.YELLOW), List.of(),
                        Icons.label("Definir", ChatFormatting.GREEN), Math.max(1, r.days()), 0, 100000L,
                        v -> {
                            Room cur = get(server, roomId);
                            if (cur != null) {
                                cur.setDays((int) v);
                                RoomData.get(server).setDirty();
                            }
                            openRoom(sp, roomId);
                        }));
        if (r.isAssigned()) {
            gui.button(20, Icons.icon(Items.BARRIER, Icons.label("Liberer la chambre", ChatFormatting.RED),
                    List.of(Icons.lore("Retire l'occupant", ChatFormatting.GRAY))),
                    sp -> {
                        Room cur = get(server, roomId);
                        if (cur != null) {
                            cur.setOccupant(null, null);
                            cur.setFrozen(false);
                            RoomData.get(server).setDirty();
                        }
                        openRoom(sp, roomId);
                    });
        }
        gui.button(24, Icons.icon(Items.LAVA_BUCKET, Icons.label("Supprimer la chambre", ChatFormatting.RED), List.of()),
                sp -> {
                    RoomData.get(server).remove(roomId);
                    sp.sendSystemMessage(Messages.success("Chambre supprimee."));
                    openAuberge(sp);
                });
        gui.button(18, Icons.icon(Items.OAK_DOOR, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                RoomMenus::openAuberge);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void openAssignPicker(ServerPlayer admin, String roomId) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Attribuer a quel joueur ?", ChatFormatting.GOLD));
        int slot = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            UUID id = target.getUUID();
            String tname = target.getGameProfile().getName();
            gui.button(slot++, Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE),
                    List.of(Icons.lore("Clic : definir prix puis jours", ChatFormatting.GRAY))),
                    sp -> openAssignPrice(sp, roomId, id, tname));
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun joueur en ligne", ChatFormatting.RED),
                    List.of(Icons.lore("Utilise /room assign <id> <joueur> <prix> <jours>", ChatFormatting.GRAY))));
        }
        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                sp -> openRoom(sp, roomId));
        gui.fillEmpty();
        Menus.open(admin, gui);
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
