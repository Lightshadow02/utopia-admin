package com.utopia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.utopia.clearlag.ClearLagConfig;
import com.utopia.clearlag.ClearLagManager;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

/** Commande d'administration du nettoyage des objets au sol : /clearlag [info|reload|now]. */
public final class ClearLagCommand {

    private ClearLagCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("clearlag")
                .requires(src -> src.hasPermission(2))
                .executes(ClearLagCommand::info)
                .then(Commands.literal("info").executes(ClearLagCommand::info))
                .then(Commands.literal("reload").executes(ClearLagCommand::reload))
                .then(Commands.literal("now").executes(ClearLagCommand::now)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ClearLagConfig cfg = ClearLagManager.getConfig();
        int ground = ClearLagManager.countGroundItems(server);

        ctx.getSource().sendSuccess(() -> Messages.info("ClearLag : "
                + (cfg.enabled ? "active" : "desactive")
                + " | duree par defaut " + cfg.defaultLifetimeSeconds + "s"
                + " | scan toutes les " + cfg.scanIntervalSeconds + "s"
                + " | objets au sol : " + ground), false);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ClearLagManager.reload();
        ctx.getSource().sendSuccess(() -> Messages.success("Configuration clearlag.json rechargee."), true);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        int removed = ClearLagManager.clearNow(server);
        ctx.getSource().sendSuccess(() -> Messages.success(removed
                + " objet(s) au sol supprime(s) immediatement."), true);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }
}
