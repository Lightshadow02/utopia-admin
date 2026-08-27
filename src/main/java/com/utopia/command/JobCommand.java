package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.data.JobData;
import com.utopia.data.MarketData;
import com.utopia.job.JobMenus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /metiers : ouvre le panel des metiers et des salaires. Accessible aux op, au maire et aux
 * banquiers designes ; ces derniers n'obtiennent aucun autre droit d'administration.
 */
public final class JobCommand {

    private JobCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("metiers")
                .requires(JobCommand::canOpen)
                .executes(ctx -> {
                    JobMenus.open(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    /** Op (niveau 2), maire designe, ou banquier designe. */
    private static boolean canOpen(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer p)) {
            return false;
        }
        return MarketData.get(p.server).isMaire(p.getUUID())
                || JobData.get(p.server).isBanker(p.getUUID());
    }
}
