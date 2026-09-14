package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.referendum.ReferendumMenus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Commande /referendum : ouvre le bulletin de la consultation en cours. Ouverte a tous. */
public final class ReferendumCommand {

    private ReferendumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("referendum")
                .requires(src -> com.utopia.Config.ADMIN_REFERENDUM.get())
                .executes(ctx -> {
                    ReferendumMenus.openVote(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
