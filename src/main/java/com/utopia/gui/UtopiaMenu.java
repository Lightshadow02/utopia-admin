package com.utopia.gui;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu adosse a une {@link UtopiaGui}, base sur un {@code MenuType} custom (rendu par un ecran
 * client custom). Cote serveur, {@code gui} porte les icones et actions ; cote client, {@code gui}
 * est nul (le contenu des slots arrive par la synchro vanilla des conteneurs).
 */
public final class UtopiaMenu extends ChestMenu {

    /** Non nul cote serveur ; nul cote client. */
    private final UtopiaGui gui;

    /** Construction cote serveur (avec la GUI et ses actions). */
    public UtopiaMenu(int containerId, Inventory playerInventory, UtopiaGui gui) {
        super(UtopiaMenuType.UTOPIA.get(), containerId, playerInventory, gui.container(), gui.rows());
        this.gui = gui;
    }

    /** Construction cote client (depuis le buffer d'ouverture) : conteneur vide, rempli par la synchro. */
    public UtopiaMenu(int containerId, Inventory playerInventory, int rows) {
        super(UtopiaMenuType.UTOPIA.get(), containerId, playerInventory,
                new SimpleContainer(Math.max(1, Math.min(6, rows)) * 9), Math.max(1, Math.min(6, rows)));
        this.gui = null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Desactive le shift-clic (deplacement automatique) pour garder un comportement previsible.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        // Empeche le double-clic (PICKUP_ALL) de siphonner les icones des slots non editables.
        return gui != null && slot.container == gui.container() && gui.isEditable(slot.getContainerSlot());
    }

    /**
     * A appeler lors de la deconnexion du joueur (l'event arrive AVANT la sauvegarde de l'inventaire) :
     * declenche le rappel de fermeture de la GUI (ex: rendre les items de l'editeur) une seule fois.
     */
    public void handleLogout(ServerPlayer player) {
        if (gui != null) {
            gui.fireClose(player);
        }
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (gui == null) {
            super.clicked(slotId, dragType, clickType, player);
            return;
        }
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
        if (gui != null && player instanceof ServerPlayer sp) {
            gui.fireClose(sp);
        }
    }
}
