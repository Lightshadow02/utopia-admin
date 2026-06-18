package com.utopia.inventory;

import java.util.UUID;

import com.utopia.data.InventoryData;
import com.utopia.util.Messages;

import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Bascule entre les deux inventaires persistes d'un joueur ({@link InventoryData}). L'inventaire courant
 * est sauvegarde dans le slot actif, puis le slot cible est charge (vide s'il n'a jamais ete utilise).
 */
public final class InventoryManager {

    private InventoryManager() {
    }

    /** Slot d'inventaire 1 = survie, slot 2 = creatif (convention partagee avec /staff). */
    public static final int SLOT_SURVIE = 1;
    public static final int SLOT_CREATIF = 2;

    public static void switchTo(ServerPlayer player, int target) {
        if (target != 1 && target != 2) {
            return;
        }
        InventoryData data = InventoryData.get(player.server);
        UUID id = player.getUUID();
        int active = data.getActive(id);
        if (active == target) {
            player.sendSystemMessage(Messages.info("Tu es deja sur l'inventaire " + target + "."));
            return;
        }
        saveInto(player, active);
        loadFrom(player, target);
        data.setActive(id, target);
        player.sendSystemMessage(Messages.success("Inventaire " + target + " charge (l'inventaire "
                + active + " a ete sauvegarde)."));
    }

    /**
     * Bascule "mode staff" liee au gamemode et a l'op serveur :
     * <ul>
     *   <li>en survie : sauvegarde l'inventaire survie, charge l'inventaire creatif, passe en creatif, OP ;</li>
     *   <li>sinon (creatif) : sauvegarde l'inventaire creatif, charge l'inventaire survie, passe en survie, DEOP.</li>
     * </ul>
     */
    public static void staffToggle(ServerPlayer player) {
        MinecraftServer server = player.server;
        InventoryData data = InventoryData.get(server);
        UUID id = player.getUUID();
        boolean survival = player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;

        if (survival) {
            saveInto(player, SLOT_SURVIE);
            loadFrom(player, SLOT_CREATIF);
            data.setActive(id, SLOT_CREATIF);
            player.setGameMode(GameType.CREATIVE);
            server.getPlayerList().op(player.getGameProfile());
            player.sendSystemMessage(Messages.success(
                    "Mode STAFF active : creatif + op. Ton inventaire de survie est sauvegarde."));
        } else {
            saveInto(player, SLOT_CREATIF);
            loadFrom(player, SLOT_SURVIE);
            data.setActive(id, SLOT_SURVIE);
            player.setGameMode(GameType.SURVIVAL);
            server.getPlayerList().deop(player.getGameProfile());
            player.sendSystemMessage(Messages.success(
                    "Mode STAFF desactive : survie + op retire. Ton inventaire creatif est sauvegarde."));
        }
        // Rafraichit l'arbre de commandes (les commandes op apparaissent/disparaissent).
        server.getCommands().sendCommands(player);
    }

    /** Sauvegarde l'inventaire courant du joueur dans le slot donne. */
    private static void saveInto(ServerPlayer player, int slot) {
        ListTag tag = new ListTag();
        player.getInventory().save(tag);
        InventoryData.get(player.server).setSlot(player.getUUID(), slot, tag);
    }

    /** Vide l'inventaire puis charge le slot donne (vide s'il n'existe pas), et synchronise le client. */
    private static void loadFrom(ServerPlayer player, int slot) {
        player.getInventory().clearContent();
        ListTag tag = InventoryData.get(player.server).getSlot(player.getUUID(), slot);
        if (tag != null) {
            player.getInventory().load(tag);
        }
        player.inventoryMenu.broadcastFullState();
    }
}
