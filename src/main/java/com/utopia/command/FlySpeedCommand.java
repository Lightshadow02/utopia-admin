package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /flyspeed : regle la vitesse de vol du joueur via un multiplicateur (1 = normale, 10 = max).
 * Reservee aux operateurs.
 */
public final class FlySpeedCommand {

    /** Vitesse de vol par defaut de Minecraft. */
    private static final float DEFAULT_SPEED = 0.05f;

    private FlySpeedCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("flyspeed")
                .requires(s -> s.hasPermission(2))
                .executes(FlySpeedCommand::showCurrent)
                .then(Commands.literal("reset")
                        .executes(ctx -> apply(ctx, 1)))
                .then(Commands.argument("multiplicateur", IntegerArgumentType.integer(1, 10))
                        .executes(ctx -> apply(ctx, IntegerArgumentType.getInteger(ctx, "multiplicateur")))));
    }

    private static int showCurrent(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int mult = Math.max(1, Math.round(player.getAbilities().getFlyingSpeed() / DEFAULT_SPEED));
        player.sendSystemMessage(Messages.info("Vitesse de vol actuelle : x" + mult
                + ". Usage : /flyspeed <1-10> ou /flyspeed reset."));
        return Command.SINGLE_SUCCESS;
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, int mult) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.getAbilities().setFlyingSpeed(DEFAULT_SPEED * mult);
        player.onUpdateAbilities(); // synchronise les capacites avec le client
        player.sendSystemMessage(Messages.success("Vitesse de vol reglee : x" + mult
                + (mult == 1 ? " (normale)." : ".")));
        return Command.SINGLE_SUCCESS;
    }
}
