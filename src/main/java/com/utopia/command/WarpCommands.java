package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.utopia.data.WarpData;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Warps administrateur (op niveau 2 uniquement) : /warp &lt;nom&gt;, /setwarp &lt;nom&gt;, /delwarp &lt;nom&gt;, /warps.
 * Teleportation instantanee vers des points nommes reserves aux operateurs.
 */
public final class WarpCommands {

    private WarpCommands() {
    }

    /** Suggestions : noms de warps existants. */
    private static final SuggestionProvider<CommandSourceStack> WARP_NAMES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(WarpData.get(ctx.getSource().getServer()).names(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("warp")
                .requires(s -> s.hasPermission(2))
                .executes(WarpCommands::list)
                .then(Commands.argument("nom", StringArgumentType.word())
                        .suggests(WARP_NAMES)
                        .executes(WarpCommands::warp)));

        dispatcher.register(Commands.literal("warps")
                .requires(s -> s.hasPermission(2))
                .executes(WarpCommands::list));

        dispatcher.register(Commands.literal("setwarp")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("nom", StringArgumentType.word())
                        .executes(WarpCommands::setWarp)));

        dispatcher.register(Commands.literal("delwarp")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("nom", StringArgumentType.word())
                        .suggests(WARP_NAMES)
                        .executes(WarpCommands::delWarp)));
    }

    private static int warp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "nom");
        WarpData.Warp warp = WarpData.get(player.server).get(name);
        if (warp == null) {
            player.sendSystemMessage(Messages.warn("Warp introuvable : " + name + ". Voir /warps."));
            return 0;
        }
        teleport(player, warp);
        player.sendSystemMessage(Messages.success("Teleporte au warp " + name + "."));
        return Command.SINGLE_SUCCESS;
    }

    /** Teleportation instantanee d'un joueur vers un warp (sans warmup, usage admin). */
    public static void teleport(ServerPlayer player, WarpData.Warp warp) {
        ServerLevel level = warp.resolveLevel(player.server);
        if (level == null) {
            player.sendSystemMessage(Messages.error("La dimension de ce warp est introuvable."));
            return;
        }
        player.teleportTo(level, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
    }

    private static int setWarp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "nom");
        WarpData data = WarpData.get(player.server);
        boolean overwrite = data.exists(name);
        data.set(name, new WarpData.Warp(
                player.serverLevel().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
        ctx.getSource().sendSuccess(() -> Messages.success((overwrite ? "Warp mis a jour : " : "Warp cree : ")
                + name + " (" + String.format("%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ()) + ")."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int delWarp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "nom");
        if (WarpData.get(player.server).remove(name)) {
            player.sendSystemMessage(Messages.success("Warp supprime : " + name + "."));
            return Command.SINGLE_SUCCESS;
        }
        player.sendSystemMessage(Messages.warn("Warp introuvable : " + name + "."));
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var names = WarpData.get(player.server).names();
        if (names.isEmpty()) {
            player.sendSystemMessage(Messages.info("Aucun warp. Cree-en un avec /setwarp <nom>."));
        } else {
            player.sendSystemMessage(Messages.info("Warps (" + names.size() + ") : " + String.join(", ", names) + "."));
        }
        return Command.SINGLE_SUCCESS;
    }
}
