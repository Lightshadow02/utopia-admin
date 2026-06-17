package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.data.MarketData;
import com.utopia.market.MarketMenus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Commande /maire : ouvre le menu du compte de la mairie. Reservee au maire designe (et aux op). */
public final class MaireCommand {

    private MaireCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maire")
                .requires(MaireCommand::canOpen)
                .executes(ctx -> {
                    MarketMenus.openMaire(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    /** Vrai si la source est op (niveau 2) ou un maire designe. */
    private static boolean canOpen(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p
                && MarketData.get(p.server).isMaire(p.getUUID());
    }
}
