package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.quote.QuoteManager;
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
                        .requires(QuoteCommand::canAdminister)
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!QuoteManager.canAdminister(player)) {
                                player.sendSystemMessage(Messages.warn(
                                        "Reserve a l'administration et au maire."));
                                return 0;
                            }
                            QuoteMenus.openAdmin(player);
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    /** Op (niveau 2) ou maire designe. */
    private static boolean canAdminister(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p && QuoteManager.canAdminister(p);
    }
}
