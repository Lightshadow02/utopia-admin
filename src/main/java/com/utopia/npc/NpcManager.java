package com.utopia.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.utopia.data.NpcData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Pose et entretien des statues decoratives.
 *
 * <p>Le skin n'est pas une reference vers un joueur mais une <b>copie</b> de sa propriete
 * "textures" : la statue garde son visage quand le joueur se deconnecte, quitte le serveur, ou
 * change de skin. C'est ce qui la rend utilisable comme decor durable.
 */
public final class NpcManager {

    private static final double SPAWN_EPSILON = 0.05;

    private NpcManager() {
    }

    /** Recree les statues manquantes, deplace celles qui ont bouge, retire celles qu'on a supprimees. */
    public static void sync(MinecraftServer server) {
        NpcData data = NpcData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, com.utopia.entity.DecorNpc> present = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.DecorNpc npc) {
                    if (present.putIfAbsent(npc.ownerKey(), npc) != null) {
                        npc.discard(); // doublon : une seule statue par fiche
                    }
                }
            }
            for (NpcData.Npc entry : data.all()) {
                com.utopia.entity.DecorNpc npc = present.remove(entry.id);
                ServerLevel target = entry.isPlaced() ? resolveLevel(server, entry.dim) : null;
                boolean wanted = target == level && entry.enabled;
                if (!wanted) {
                    if (npc != null) {
                        npc.discard();
                    }
                    continue;
                }
                if (!level.isLoaded(BlockPos.containing(entry.x, entry.y, entry.z))) {
                    continue;
                }
                if (npc == null || npc.isRemoved()) {
                    npc = new com.utopia.entity.DecorNpc(
                            com.utopia.entity.UtopiaEntities.DECOR_NPC.get(), level);
                    npc.setOwnerKey(entry.id);
                    npc.moveTo(entry.x, entry.y, entry.z, entry.restYaw, 0.0f);
                    npc.setRestYaw(entry.restYaw);
                    npc.applyLook(entry.name, entry.skinValue, entry.skinSignature, entry.showName);
                    level.addFreshEntity(npc);
                } else {
                    npc.setRestYaw(entry.restYaw);
                    npc.applyLook(entry.name, entry.skinValue, entry.skinSignature, entry.showName);
                    if (npc.distanceToSqr(entry.x, entry.y, entry.z) > SPAWN_EPSILON) {
                        npc.moveTo(entry.x, entry.y, entry.z, entry.restYaw, 0.0f);
                    }
                }
            }
            present.values().forEach(Entity::discard);
        }
    }

    /** Efface la statue d'une fiche supprimee. */
    public static void removeEntity(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.DecorNpc npc && id.equals(npc.ownerKey())) {
                    npc.discard();
                }
            }
        }
    }

    /** Enregistre la position et l'orientation du joueur comme celles de la statue. */
    public static void place(ServerPlayer admin, NpcData.Npc entry) {
        entry.dim = admin.level().dimension().location().toString();
        entry.x = admin.getX();
        entry.y = admin.getY();
        entry.z = admin.getZ();
        entry.restYaw = admin.getYRot();
        NpcData.get(admin.server).setDirty();
    }

    /** Copie le visage d'un joueur connecte. */
    public static boolean copyFrom(ServerPlayer source, NpcData.Npc entry) {
        return apply(entry, source.getGameProfile(), source.getGameProfile().getName());
    }

    private static boolean apply(NpcData.Npc entry, GameProfile profile, String name) {
        for (Property prop : profile.getProperties().get("textures")) {
            entry.skinValue = prop.value();
            entry.skinSignature = prop.signature() == null ? "" : prop.signature();
            entry.skinFrom = name;
            return true;
        }
        return false; // serveur en mode hors ligne : aucun skin a copier
    }

    /**
     * Va chercher le visage d'un joueur qui n'est pas la, par son pseudo. La resolution du pseudo
     * puis l'appel au service de session sont bloquants : ils tournent hors du fil principal, et le
     * resultat revient dessus pour que le reste du mod n'ait jamais a s'en soucier.
     *
     * @param whenDone recoit le pseudo trouve, ou null si le joueur est introuvable ou sans skin
     */
    public static void fetchSkin(MinecraftServer server, String pseudo, NpcData.Npc entry,
                                 Consumer<String> whenDone) {
        String name = pseudo == null ? "" : pseudo.trim();
        if (name.isEmpty()) {
            whenDone.accept(null);
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            boolean ok = copyFrom(online, entry);
            NpcData.get(server).setDirty();
            whenDone.accept(ok ? online.getGameProfile().getName() : null);
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                Optional<GameProfile> cached = server.getProfileCache() == null
                        ? Optional.empty() : server.getProfileCache().get(name);
                if (cached.isEmpty()) {
                    return null;
                }
                var result = server.getSessionService().fetchProfile(cached.get().getId(), true);
                return result == null ? null : result.profile();
            } catch (Exception e) {
                com.utopia.UtopiaMod.LOGGER.warn("[Utopia] Skin de \"{}\" introuvable : {}", name,
                        e.toString());
                return null;
            }
        }).thenAcceptAsync(profile -> {
            if (profile == null) {
                whenDone.accept(null);
                return;
            }
            boolean ok = apply(entry, profile, profile.getName());
            NpcData.get(server).setDirty();
            if (ok) {
                sync(server);
            }
            whenDone.accept(ok ? profile.getName() : null);
        }, server);
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
