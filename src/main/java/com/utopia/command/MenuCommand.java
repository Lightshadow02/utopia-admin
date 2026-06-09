package com.utopia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.utopia.menu.MainMenu;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Commande /menu : ouvre le hub central des actions joueur. */
public final class MenuCommand {

    private MenuCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("menu").executes(ctx -> {
            MainMenu.open(ctx.getSource().getPlayerOrException());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }));
    }
}
