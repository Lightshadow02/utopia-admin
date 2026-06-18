package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.election.ElectionMenus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Commande /vote : ouvre le menu de vote de l'election en cours. Accessible a tous les joueurs. */
public final class VoteCommand {

    private VoteCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vote")
                .executes(ctx -> {
                    ElectionMenus.openVote(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
