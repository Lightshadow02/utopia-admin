package com.utopia.gui;

import java.util.function.Consumer;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Menu coffre cote serveur adosse a une {@link UtopiaGui}. Reutilise un {@code MenuType.GENERIC_9xN}
 * vanilla (rendu par le client sans code cote client). Les clics sur les slots-boutons declenchent
 * une action sans deplacer d'item ; en mode editeur, les slots editables se comportent normalement.
 */
public final class UtopiaMenu extends ChestMenu {

    private final UtopiaGui gui;

    public UtopiaMenu(int containerId, Inventory playerInventory, UtopiaGui gui) {
        super(typeForRows(gui.rows()), containerId, playerInventory, gui.container(), gui.rows());
        this.gui = gui;
    }

    private static MenuType<ChestMenu> typeForRows(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Desactive le shift-clic (deplacement automatique) pour garder un comportement previsible.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        // Empeche le double-clic (PICKUP_ALL) de siphonner les icones des slots non editables
        // (boutons, decoration, ligne de reference) et de l'inventaire du joueur.
        return slot.container == gui.container() && gui.isEditable(slot.getContainerSlot());
    }

    /**
     * A appeler lors de la deconnexion du joueur (l'event arrive AVANT la sauvegarde de l'inventaire) :
     * declenche le rappel de fermeture de la GUI (ex: rendre les items de l'editeur) une seule fois.
     */
    public void handleLogout(ServerPlayer player) {
        gui.fireClose(player);
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        int topSize = gui.rows() * 9;

        if (slotId >= 0 && slotId < topSize) {
            if (gui.isEditable(slotId)) {
                super.clicked(slotId, dragType, clickType, player);
                return;
            }
            // Distingue clic droit (PICKUP avec bouton 1) du clic gauche.
            boolean rightClick = clickType == ClickType.PICKUP && dragType == 1;
            Consumer<ServerPlayer> action = rightClick ? gui.rightAction(slotId) : gui.action(slotId);
            if (action == null) {
                action = gui.action(slotId); // repli sur l'action principale
            }
            if (action != null && player instanceof ServerPlayer sp) {
                action.accept(sp);
            }
            return; // slot-bouton ou decoration : aucun deplacement d'item
        }

        // Slots de l'inventaire du joueur : autorises uniquement en mode editeur (pour deposer des items).
        if (gui.isEditor()) {
            super.clicked(slotId, dragType, clickType, player);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer sp) {
            gui.fireClose(sp);
        }
    }
}
