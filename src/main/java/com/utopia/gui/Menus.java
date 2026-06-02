package com.utopia.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** Point d'entree pour ouvrir une {@link UtopiaGui} a un joueur. */
public final class Menus {

    private Menus() {
    }

    public static void open(ServerPlayer player, UtopiaGui gui) {
        // On transmet le nombre de rangees au client (qui reconstruit le menu via le MenuType custom).
        player.openMenu(new SimpleMenuProvider(
                (containerId, inv, p) -> new UtopiaMenu(containerId, inv, gui),
                gui.title()), buf -> buf.writeByte(gui.rows()));
    }
}
