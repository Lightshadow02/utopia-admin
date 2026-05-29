package com.utopia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.daily.DailyManager;
import com.utopia.daily.DailyMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/** Commande des recompenses quotidiennes : /daily (menu), /daily claim, /daily status, /daily admin, /daily reset. */
public final class DailyCommand {

    private DailyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("daily")
                .executes(DailyCommand::openMenu)
                .then(Commands.literal("claim")
                        .executes(DailyCommand::claim))
                .then(Commands.literal("status")
                        .executes(DailyCommand::status))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .executes(DailyCommand::openAdmin))
                .then(Commands.literal("reset")
                        .requires(src -> src.hasPermission(2))
                        .executes(DailyCommand::resetSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(DailyCommand::resetOther))));
    }

    private static int openMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        DailyMenus.openPlayerMenu(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int openAdmin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        DailyMenus.openAdminMenu(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int claim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean claimed = DailyManager.claim(player);
        return claimed ? com.mojang.brigadier.Command.SINGLE_SUCCESS : 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DailyManager.showStatus(player);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int resetSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        reset(player);
        ctx.getSource().sendSuccess(() -> Messages.success("Votre recompense quotidienne a ete reinitialisee."), false);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int resetOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        reset(target);
        ctx.getSource().sendSuccess(() -> Messages.success("Recompense quotidienne reinitialisee pour "
                + target.getGameProfile().getName() + "."), true);
        target.sendSystemMessage(Messages.info("Votre recompense quotidienne a ete reinitialisee par un administrateur."));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static void reset(ServerPlayer player) {
        // Reinitialisation complete (reclamation, serie et historique).
        DailyManager.reset(player.server, player.getUUID());
    }
}
