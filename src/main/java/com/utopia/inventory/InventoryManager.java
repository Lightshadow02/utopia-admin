package com.utopia.inventory;

import java.util.UUID;

import com.utopia.data.InventoryData;
import com.utopia.util.Messages;

import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bascule entre les deux inventaires persistes d'un joueur ({@link InventoryData}). L'inventaire courant
 * est sauvegarde dans le slot actif, puis le slot cible est charge (vide s'il n'a jamais ete utilise).
 */
public final class InventoryManager {

    private InventoryManager() {
    }

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

        // Sauvegarde l'inventaire actuel dans le slot actif.
        ListTag current = new ListTag();
        player.getInventory().save(current);
        data.setSlot(id, active, current);

        // Charge le slot cible (vide s'il n'existe pas encore).
        player.getInventory().clearContent();
        ListTag tgt = data.getSlot(id, target);
        if (tgt != null) {
            player.getInventory().load(tgt);
        }
        data.setActive(id, target);

        // Synchronise l'inventaire avec le client.
        player.inventoryMenu.broadcastFullState();
        player.sendSystemMessage(Messages.success("Inventaire " + target + " charge (l'inventaire "
                + active + " a ete sauvegarde)."));
    }
}
