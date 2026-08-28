package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.bet.BetAdminMenus;
import com.utopia.bet.BetMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /paris : ouvre la creation d'un pari, comme le bouton du menu principal. {@code /paris
 * admin} ouvre le registre, reserve aux op.
 *
 * <p>Il n'existe volontairement aucune commande pour consulter ou rejoindre un pari a distance : il
 * faut se rendre aupres du Bookmaker.
 */
public final class BetCommand {

    private BetCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("paris")
                .executes(ctx -> {
                    BetMenus.openCreate(ctx.getSource().getPlayerOrException());
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
                            BetAdminMenus.open(player);
                            return Command.SINGLE_SUCCESS;
                        })));
    }
}
