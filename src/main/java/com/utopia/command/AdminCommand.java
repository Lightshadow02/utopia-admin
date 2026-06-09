package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.menu.AdminMenu;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Commande /admin : hub d'administration, reserve aux operateurs (op niveau 2). */
public final class AdminCommand {

    private AdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("admin")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    AdminMenu.open(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
