package com.utopia.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** Point d'entree pour ouvrir une {@link UtopiaGui} a un joueur. */
public final class Menus {

    private Menus() {
    }

    public static void open(ServerPlayer player, UtopiaGui gui) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inv, p) -> new UtopiaMenu(containerId, inv, gui),
                gui.title()));
    }
}
