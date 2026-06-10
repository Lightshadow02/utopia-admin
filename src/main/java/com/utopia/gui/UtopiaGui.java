package com.utopia.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Description d'une interface de type coffre : un conteneur d'icones, des actions de clic par slot,
 * d'eventuels slots editables (mode editeur) et un rappel a la fermeture.
 *
 * <p>Construite cote serveur ; rendue par le client vanilla via un {@code MenuType.GENERIC_9xN}.
 */
public final class UtopiaGui {

    private final int rows;
    private final Component title;
    private final SimpleContainer container;
    private final Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();
    private final Map<Integer, Consumer<ServerPlayer>> rightActions = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private boolean editor = false;
    private boolean gridLayout = false;
    private boolean iconOnly = false;
    private Consumer<ServerPlayer> onClose = sp -> { };
    private boolean finalized = false;

    public UtopiaGui(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
        this.container = new SimpleContainer(this.rows * 9);
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    public Component title() {
        return title;
    }

    public SimpleContainer container() {
        return container;
    }

    /** Place une icone (sans action). */
    public UtopiaGui set(int slot, ItemStack icon) {
        if (slot >= 0 && slot < size()) {
            container.setItem(slot, icon);
        }
        return this;
    }

    /** Place une icone cliquable avec son action (clic gauche). */
    public UtopiaGui button(int slot, ItemStack icon, Consumer<ServerPlayer> action) {
        set(slot, icon);
        if (slot >= 0 && slot < size()) {
            actions.put(slot, action);
        }
        return this;
    }

    /** Place une icone avec action de clic gauche ET de clic droit distinctes. */
    public UtopiaGui button(int slot, ItemStack icon, Consumer<ServerPlayer> leftAction, Consumer<ServerPlayer> rightAction) {
        button(slot, icon, leftAction);
        if (slot >= 0 && slot < size() && rightAction != null) {
            rightActions.put(slot, rightAction);
        }
        return this;
    }

    /** Declare un slot du haut comme editable (l'utilisateur peut y deposer/retirer des items). */
    public UtopiaGui editableSlot(int slot) {
        if (slot >= 0 && slot < size()) {
            editable.add(slot);
            editor = true;
        }
        return this;
    }

    public UtopiaGui onClose(Consumer<ServerPlayer> callback) {
        this.onClose = callback == null ? sp -> { } : callback;
        return this;
    }

    /** Remplit tous les slots vides avec un panneau de remplissage. */
    public UtopiaGui fillEmpty() {
        for (int i = 0; i < size(); i++) {
            if (container.getItem(i).isEmpty() && !editable.contains(i)) {
                container.setItem(i, Icons.filler());
            }
        }
        return this;
    }

    public Set<Integer> editableSlots() {
        return editable;
    }

    /** Active le rendu en grille (respecte les positions ligne/colonne) au lieu de lignes centrees. */
    public UtopiaGui gridLayout(boolean grid) {
        this.gridLayout = grid;
        return this;
    }

    public boolean gridLayout() {
        return gridLayout;
    }

    /** Rendu en icones seules (sans libelle, infobulle au survol) ; implique le mode grille. */
    public UtopiaGui iconOnly(boolean v) {
        this.iconOnly = v;
        if (v) {
            this.gridLayout = true;
        }
        return this;
    }

    public boolean iconOnly() {
        return iconOnly;
    }

    boolean isEditable(int slot) {
        return editable.contains(slot);
    }

    boolean isEditor() {
        return editor;
    }

    public Consumer<ServerPlayer> action(int slot) {
        return actions.get(slot);
    }

    public Consumer<ServerPlayer> rightAction(int slot) {
        return rightActions.get(slot);
    }

    /** Declenche le rappel de fermeture une seule fois. */
    public void fireClose(ServerPlayer player) {
        if (!finalized) {
            finalized = true;
            onClose.accept(player);
        }
    }

    /** Marque la GUI comme finalisee pour empecher le rappel de fermeture (ex: apres sauvegarde manuelle). */
    public void markFinalized() {
        this.finalized = true;
    }
}
