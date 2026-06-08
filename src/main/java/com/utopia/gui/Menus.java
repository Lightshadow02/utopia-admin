package com.utopia.gui;

import com.utopia.net.OwoMenuServer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** Point d'entree pour ouvrir/fermer une {@link UtopiaGui} a un joueur. */
public final class Menus {

    private Menus() {
    }

    public static void open(ServerPlayer player, UtopiaGui gui) {
        if (gui.editableSlots().isEmpty()) {
            // Menu a clics -> rendu owo-ui cote client.
            OwoMenuServer.open(player, gui);
        } else {
            // Menu avec slots editables (depot d'items) -> conteneur vanilla custom.
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inv, p) -> new UtopiaMenu(containerId, inv, gui),
                    gui.title()), buf -> buf.writeByte(gui.rows()));
        }
    }

    /**
     * Ouvre un ecran de saisie de montant (champ a remplir). {@code onConfirm} recoit la valeur
     * saisie, deja bornee a [min, max].
     */
    public static void promptAmount(ServerPlayer player, net.minecraft.network.chat.Component title,
                                    java.util.List<net.minecraft.network.chat.Component> info,
                                    net.minecraft.network.chat.Component confirmLabel,
                                    long defaultValue, long min, long max,
                                    java.util.function.LongConsumer onConfirm) {
        OwoMenuServer.openAmount(player, title, info, confirmLabel, defaultValue, min, max, onConfirm);
    }

    /**
     * Ouvre un ecran de saisie de texte (champ a remplir). {@code onConfirm} recoit la chaine saisie
     * (non vide, filtree cote client : lettres/chiffres/_/-).
     */
    public static void promptText(ServerPlayer player, net.minecraft.network.chat.Component title,
                                  java.util.List<net.minecraft.network.chat.Component> info,
                                  net.minecraft.network.chat.Component confirmLabel,
                                  String defaultText, int maxLength,
                                  java.util.function.Consumer<String> onConfirm) {
        OwoMenuServer.openText(player, title, info, confirmLabel, defaultText, maxLength, onConfirm);
    }

    /** Ferme le menu courant du joueur (conteneur vanilla de l'editeur, ou ecran owo). */
    public static void close(ServerPlayer player) {
        if (player.containerMenu instanceof UtopiaMenu) {
            player.closeContainer();
        } else {
            OwoMenuServer.close(player);
        }
    }
}
