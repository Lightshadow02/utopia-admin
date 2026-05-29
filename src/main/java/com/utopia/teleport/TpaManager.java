package com.utopia.teleport;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.Config;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gere les demandes de teleportation entre joueurs ({@code /tpa} et {@code /tpahere}),
 * leur expiration et le cooldown d'envoi.
 */
public final class TpaManager {

    /** Type de demande. */
    public enum Type {
        /** L'emetteur veut se teleporter vers la cible. */
        TPA,
        /** L'emetteur veut que la cible se teleporte vers lui. */
        TPA_HERE
    }

    /** Une demande en attente. */
    public static final class Request {
        public final UUID sender;
        public final UUID target;
        public final Type type;
        public final int createdTick;

        Request(UUID sender, UUID target, Type type, int createdTick) {
            this.sender = sender;
            this.target = target;
            this.type = type;
            this.createdTick = createdTick;
        }
    }

    /** demandes en attente : cible -> (emetteur -> demande). */
    private static final Map<UUID, Map<UUID, Request>> INCOMING = new ConcurrentHashMap<>();
    /** dernier tick d'envoi par emetteur, pour le cooldown. */
    private static final Map<UUID, Integer> LAST_SENT = new ConcurrentHashMap<>();

    private TpaManager() {
    }

    /** Resultat d'une tentative d'envoi de demande. */
    public enum SendResult {
        OK,
        SELF,
        COOLDOWN,
        ALREADY_PENDING
    }

    /**
     * Cree une demande de {@code sender} vers {@code target}. Renvoie le resultat afin que la
     * commande puisse afficher le bon message (et la duree de cooldown restante le cas echeant).
     */
    public static SendResult send(ServerPlayer sender, ServerPlayer target, Type type) {
        if (sender.getUUID().equals(target.getUUID())) {
            return SendResult.SELF;
        }
        int now = sender.server.getTickCount();
        int cooldownTicks = Config.TPA_COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) {
            Integer last = LAST_SENT.get(sender.getUUID());
            if (last != null && now - last < cooldownTicks) {
                return SendResult.COOLDOWN;
            }
        }

        Map<UUID, Request> forTarget = INCOMING.computeIfAbsent(target.getUUID(), k -> new HashMap<>());
        if (forTarget.containsKey(sender.getUUID())) {
            return SendResult.ALREADY_PENDING;
        }
        forTarget.put(sender.getUUID(), new Request(sender.getUUID(), target.getUUID(), type, now));
        LAST_SENT.put(sender.getUUID(), now);
        return SendResult.OK;
    }

    /** Renvoie le nombre de secondes de cooldown restantes pour cet emetteur (0 si aucun). */
    public static long cooldownRemainingSeconds(ServerPlayer sender) {
        int cooldownTicks = Config.TPA_COOLDOWN_SECONDS.get() * 20;
        Integer last = LAST_SENT.get(sender.getUUID());
        if (last == null || cooldownTicks <= 0) {
            return 0;
        }
        int elapsed = sender.server.getTickCount() - last;
        return Math.max(0, (cooldownTicks - elapsed + 19) / 20);
    }

    /**
     * Accepte une demande recue par {@code target}. Si {@code senderName} est nul, la demande la
     * plus recente est acceptee. Renvoie la demande acceptee, ou nul s'il n'y en a pas.
     */
    public static Request accept(ServerPlayer target, UUID senderId) {
        Request req = take(target.getUUID(), senderId);
        if (req == null) {
            return null;
        }
        MinecraftServer server = target.server;
        ServerPlayer sender = server.getPlayerList().getPlayer(req.sender);
        if (sender == null) {
            target.sendSystemMessage(Messages.error("Ce joueur n'est plus connecte."));
            return req;
        }

        if (req.type == Type.TPA) {
            // L'emetteur se teleporte vers la cible.
            sender.sendSystemMessage(Messages.success(target.getGameProfile().getName()
                    + " a accepte. Teleportation en cours..."));
            target.sendSystemMessage(Messages.info("Vous avez accepte la demande de "
                    + sender.getGameProfile().getName() + "."));
            TeleportManager.schedule(sender, target.serverLevel(),
                    target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot(),
                    Messages.success("Vous avez ete teleporte aupres de " + target.getGameProfile().getName() + "."));
        } else {
            // La cible se teleporte vers l'emetteur.
            sender.sendSystemMessage(Messages.success(target.getGameProfile().getName()
                    + " va etre teleporte vers vous."));
            target.sendSystemMessage(Messages.info("Vous avez accepte : teleportation vers "
                    + sender.getGameProfile().getName() + "..."));
            TeleportManager.schedule(target, sender.serverLevel(),
                    sender.getX(), sender.getY(), sender.getZ(), sender.getYRot(), sender.getXRot(),
                    Messages.success("Vous avez ete teleporte aupres de " + sender.getGameProfile().getName() + "."));
        }
        return req;
    }

    /** Refuse une demande recue par {@code target}. Renvoie la demande refusee, ou nul. */
    public static Request deny(ServerPlayer target, UUID senderId) {
        Request req = take(target.getUUID(), senderId);
        if (req == null) {
            return req;
        }
        ServerPlayer sender = target.server.getPlayerList().getPlayer(req.sender);
        if (sender != null) {
            sender.sendSystemMessage(Messages.warn(target.getGameProfile().getName()
                    + " a refuse votre demande de teleportation."));
        }
        return req;
    }

    /** Retire et renvoie une demande (la plus recente si {@code senderId} est nul). */
    private static Request take(UUID targetId, UUID senderId) {
        Map<UUID, Request> forTarget = INCOMING.get(targetId);
        if (forTarget == null || forTarget.isEmpty()) {
            return null;
        }
        Request chosen;
        if (senderId != null) {
            chosen = forTarget.remove(senderId);
        } else {
            chosen = null;
            for (Request r : forTarget.values()) {
                if (chosen == null || r.createdTick > chosen.createdTick) {
                    chosen = r;
                }
            }
            if (chosen != null) {
                forTarget.remove(chosen.sender);
            }
        }
        if (forTarget.isEmpty()) {
            INCOMING.remove(targetId);
        }
        return chosen;
    }

    /** Indique s'il existe au moins une demande en attente pour cette cible. */
    public static boolean hasIncoming(UUID targetId) {
        Map<UUID, Request> forTarget = INCOMING.get(targetId);
        return forTarget != null && !forTarget.isEmpty();
    }

    /** Supprime les demandes expirees a chaque tick serveur et previent les joueurs concernes. */
    public static void tick(MinecraftServer server) {
        if (INCOMING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        int timeoutTicks = Config.TPA_REQUEST_TIMEOUT_SECONDS.get() * 20;

        Iterator<Map.Entry<UUID, Map<UUID, Request>>> targetIt = INCOMING.entrySet().iterator();
        while (targetIt.hasNext()) {
            Map.Entry<UUID, Map<UUID, Request>> targetEntry = targetIt.next();
            Map<UUID, Request> forTarget = targetEntry.getValue();
            Iterator<Map.Entry<UUID, Request>> reqIt = forTarget.entrySet().iterator();
            while (reqIt.hasNext()) {
                Request req = reqIt.next().getValue();
                if (now - req.createdTick >= timeoutTicks) {
                    reqIt.remove();
                    notifyExpired(server, req);
                }
            }
            if (forTarget.isEmpty()) {
                targetIt.remove();
            }
        }
    }

    private static void notifyExpired(MinecraftServer server, Request req) {
        ServerPlayer sender = server.getPlayerList().getPlayer(req.sender);
        if (sender != null) {
            sender.sendSystemMessage(Messages.warn("Votre demande de teleportation a expire."));
        }
        ServerPlayer target = server.getPlayerList().getPlayer(req.target);
        if (target != null) {
            target.sendSystemMessage(Component.literal("[Utopia] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Une demande de teleportation a expire.").withStyle(ChatFormatting.GRAY)));
        }
    }
}
