package com.utopia.teleport;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.Config;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Gere les teleportations avec delai (warmup) configurable. Pendant le delai, la teleportation
 * peut etre annulee si le joueur bouge ou subit des degats (selon la configuration).
 *
 * <p>Le decompte est traite a chaque tick serveur via {@link #tick(MinecraftServer)}.
 */
public final class TeleportManager {

    /** Une teleportation en attente d'execution. */
    private static final class Pending {
        final ServerLevel targetLevel;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final Component successMessage;
        final BlockPos startBlock;
        final float startHealth;
        int ticksRemaining;
        int lastAnnouncedSecond;

        Pending(ServerLevel targetLevel, double x, double y, double z, float yaw, float pitch,
                Component successMessage, BlockPos startBlock, float startHealth, int ticksRemaining) {
            this.targetLevel = targetLevel;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.successMessage = successMessage;
            this.startBlock = startBlock;
            this.startHealth = startHealth;
            this.ticksRemaining = ticksRemaining;
            this.lastAnnouncedSecond = -1;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private TeleportManager() {
    }

    /**
     * Programme une teleportation pour {@code player}. Applique le delai configure ; si le delai
     * est nul, la teleportation est immediate. Toute teleportation deja en attente pour ce joueur
     * est remplacee.
     */
    public static void schedule(ServerPlayer player, ServerLevel targetLevel,
                                double x, double y, double z, float yaw, float pitch,
                                Component successMessage) {
        int warmupSeconds = Config.TELEPORT_WARMUP_SECONDS.get();
        if (warmupSeconds <= 0) {
            execute(player, targetLevel, x, y, z, yaw, pitch, successMessage);
            return;
        }

        Pending pending = new Pending(targetLevel, x, y, z, yaw, pitch, successMessage,
                player.blockPosition(), player.getHealth(), warmupSeconds * 20);
        PENDING.put(player.getUUID(), pending);
        player.sendSystemMessage(Messages.info("Teleportation dans " + warmupSeconds + "s. Ne bougez pas...")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** Annule une teleportation en attente pour ce joueur (sans message). Renvoie vrai si quelque chose a ete annule. */
    public static boolean cancel(UUID playerId) {
        return PENDING.remove(playerId) != null;
    }

    /** Traite les decomptes a chaque tick serveur. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        boolean cancelOnMove = Config.TELEPORT_CANCEL_ON_MOVE.get();
        boolean cancelOnDamage = Config.TELEPORT_CANCEL_ON_DAMAGE.get();

        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending pending = entry.getValue();

            // Le joueur s'est deconnecte : on abandonne la teleportation.
            if (player == null) {
                it.remove();
                continue;
            }

            if (cancelOnMove && !player.blockPosition().equals(pending.startBlock)) {
                it.remove();
                player.sendSystemMessage(Messages.error("Teleportation annulee : vous avez bouge."));
                continue;
            }

            if (cancelOnDamage && player.getHealth() < pending.startHealth) {
                it.remove();
                player.sendSystemMessage(Messages.error("Teleportation annulee : vous avez subi des degats."));
                continue;
            }

            pending.ticksRemaining--;
            if (pending.ticksRemaining <= 0) {
                it.remove();
                execute(player, pending.targetLevel, pending.x, pending.y, pending.z,
                        pending.yaw, pending.pitch, pending.successMessage);
            } else if (Config.TELEPORT_EFFECTS.get()) {
                // Animation de "chargement" pendant le decompte (anneau tournant autour du joueur).
                playWarmupEffect(player);
            }
        }
    }

    /** Anneau de particules tournant qui monte autour du joueur, pendant le delai de teleportation. */
    private static void playWarmupEffect(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double phase = (player.tickCount % 30) / 30.0 * (Math.PI * 2.0);
        double radius = 0.75;
        double height = 0.2 + ((player.tickCount % 20) / 20.0) * 1.6; // monte de bas en haut
        for (int i = 0; i < 3; i++) {
            double angle = phase + i * (Math.PI * 2.0 / 3.0);
            double px = player.getX() + Math.cos(angle) * radius;
            double pz = player.getZ() + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.WITCH, px, player.getY() + height, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
        // Quelques etincelles au centre pour densifier l'effet.
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 0.1, player.getZ(),
                2, 0.2, 0.05, 0.2, 0.0);
    }

    private static void execute(ServerPlayer player, ServerLevel targetLevel,
                                double x, double y, double z, float yaw, float pitch,
                                Component successMessage) {
        boolean effects = Config.TELEPORT_EFFECTS.get();
        if (effects) {
            playEffect(player.serverLevel(), player.getX(), player.getY(), player.getZ());
        }
        player.teleportTo(targetLevel, x, y, z, yaw, pitch);
        player.fallDistance = 0.0F;
        if (effects) {
            playEffect(targetLevel, x, y, z);
        }
        if (successMessage != null) {
            player.sendSystemMessage(successMessage);
        }
    }

    /** Petite animation de teleportation (tourbillon de portail + lueur) avec un son, autour du point. */
    private static void playEffect(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.PORTAL, x, y + 1.0, z, 45, 0.4, 0.9, 0.4, 0.7);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1.0, z, 20, 0.3, 0.6, 0.3, 0.05);
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.2, z, 14, 0.35, 0.1, 0.35, 0.02);
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.2F);
    }
}
