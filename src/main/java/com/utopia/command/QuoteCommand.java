package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.quote.QuoteMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /devis : chaque joueur redige, envoie, consulte et regle ses devis. {@code /devis admin}
 * ouvre l'historique de tous les joueurs, reserve aux op.
 */
public final class QuoteCommand {

    private QuoteCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("devis")
                .executes(ctx -> {
                    QuoteMenus.openHome(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!player.hasPermissions(2)) {
                                player.sendSystemMessage(Messages.warn("Reserve a l'administration."));
                                return 0;
                            }
                            QuoteMenus.openAdmin(player);
                            return Command.SINGLE_SUCCESS;
                        })));
    }
}
