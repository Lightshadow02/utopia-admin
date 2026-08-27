package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.savings.SavingsManager;
import com.utopia.savings.SavingsMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /epargne : chaque joueur consulte son livret ; le banquier, le maire et les op ouvrent en
 * plus le registre de tous les livrets via /epargne registre.
 */
public final class SavingsCommand {

    private SavingsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("epargne")
                .executes(ctx -> {
                    SavingsMenus.openOwn(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("registre")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!SavingsManager.canKeepRegistry(player)) {
                                player.sendSystemMessage(Messages.warn(
                                        "Seul le banquier tient le registre des livrets."));
                                return 0;
                            }
                            SavingsMenus.openRegistry(player);
                            return Command.SINGLE_SUCCESS;
                        })));
    }
}
