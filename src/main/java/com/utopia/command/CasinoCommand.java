package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.casino.CasinoManager;
import com.utopia.casino.CasinoMenus;
import com.utopia.data.CasinoData;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Commande /casino : le pupitre du gerant de la salle d'arcade. Operateurs et gerants designes. */
public final class CasinoCommand {

    private CasinoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("casino")
                .requires(CasinoCommand::canOpen)
                .executes(ctx -> {
                    CasinoMenus.open(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static boolean canOpen(CommandSourceStack source) {
        if (!com.utopia.Config.CASINO_ENABLED.get()) {
            return false;
        }
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p
                && CasinoData.get(p.server).isManager(p.getUUID());
    }
}
