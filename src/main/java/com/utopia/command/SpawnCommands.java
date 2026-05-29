package com.utopia.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.Config;
import com.utopia.data.SpawnData;
import com.utopia.teleport.TeleportManager;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Commandes liees au spawn : /spawn (joueur) et /setspawn (admin, permission 2). */
public final class SpawnCommands {

    /** Dernier tick d'utilisation de /spawn par joueur (pour le cooldown). */
    private static final Map<UUID, Integer> LAST_SPAWN_USE = new ConcurrentHashMap<>();

    private SpawnCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spawn")
                .executes(SpawnCommands::spawn));

        dispatcher.register(Commands.literal("setspawn")
                .requires(src -> src.hasPermission(2))
                .executes(SpawnCommands::setSpawn));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.server;

        int cooldownTicks = Config.SPAWN_COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) {
            Integer last = LAST_SPAWN_USE.get(player.getUUID());
            int now = server.getTickCount();
            if (last != null && now - last < cooldownTicks) {
                long remaining = (cooldownTicks - (now - last) + 19) / 20;
                player.sendSystemMessage(Messages.warn("Patientez encore " + Messages.formatDuration(remaining)
                        + " avant de reutiliser /spawn."));
                return 0;
            }
        }

        SpawnData data = SpawnData.get(server);
        ServerLevel level;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;

        if (data.hasSpawn()) {
            level = data.resolveLevel(server);
            if (level == null) {
                // La dimension du spawn defini n'existe plus : on retombe sur le spawn du monde.
                player.sendSystemMessage(Messages.warn("La dimension du spawn defini est introuvable, utilisation du spawn du monde."));
                level = server.overworld();
                double[] w = SpawnData.worldSpawn(server);
                x = w[0];
                y = w[1];
                z = w[2];
                yaw = (float) w[3];
                pitch = (float) w[4];
            } else {
                x = data.x();
                y = data.y();
                z = data.z();
                yaw = data.yaw();
                pitch = data.pitch();
            }
        } else {
            level = server.overworld();
            double[] w = SpawnData.worldSpawn(server);
            x = w[0];
            y = w[1];
            z = w[2];
            yaw = (float) w[3];
            pitch = (float) w[4];
        }

        TeleportManager.schedule(player, level, x, y, z, yaw, pitch,
                Messages.success("Vous avez ete teleporte au spawn."));
        LAST_SPAWN_USE.put(player.getUUID(), server.getTickCount());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int setSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SpawnData data = SpawnData.get(player.server);
        data.setSpawn(
                player.serverLevel().dimension().location(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Messages.success("Spawn defini a votre position actuelle ("
                + String.format("%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ()) + ")."), true);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** Utilise par d'autres parties du mod si besoin d'un message generique. */
    public static Component infoNoSpawn() {
        return Messages.warn("Aucun spawn personnalise n'est defini.");
    }
}
