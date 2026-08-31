package com.utopia.waystone;

import com.utopia.data.WaystoneData;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Vie d'une balise : pose, decouverte, depart, disparition. */
public final class WaystoneManager {

    private WaystoneManager() {
    }

    /** Enregistre une balise fraichement posee et la fait decouvrir a son poseur. */
    public static void onPlaced(ServerLevel level, BlockPos pos, ServerPlayer player) {
        WaystoneData data = WaystoneData.get(level.getServer());
        String dim = level.dimension().location().toString();
        String id = WaystoneData.key(dim, pos);
        if (data.get(id) != null) {
            return;
        }
        WaystoneData.Waystone stone = new WaystoneData.Waystone(id,
                "Balise de " + player.getGameProfile().getName(), dim,
                pos.getX(), pos.getY(), pos.getZ());
        stone.owner = player.getUUID();
        stone.ownerName = player.getGameProfile().getName();
        data.put(stone);
        data.discover(player.getUUID(), id);
        player.sendSystemMessage(Messages.success("Balise posee. Nommez-la pour vous y retrouver."));
        WaystoneMenus.promptRename(player, id);
    }

    /** Clic droit : on decouvre la balise si c'est la premiere fois, puis on ouvre le reseau. */
    public static void onUsed(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.server;
        WaystoneData data = WaystoneData.get(server);
        String dim = player.level().dimension().location().toString();
        WaystoneData.Waystone stone = data.at(dim, pos);
        if (stone == null) {
            // Balise posee avant que le reseau existe, ou donnee perdue : on la rattrape en silence.
            stone = new WaystoneData.Waystone(WaystoneData.key(dim, pos), "Balise", dim,
                    pos.getX(), pos.getY(), pos.getZ());
            data.put(stone);
        }
        if (data.discover(player.getUUID(), stone.id)) {
            player.sendSystemMessage(Component.literal("[Balises] ")
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true))
                    .append(Component.literal("Vous avez decouvert \"" + stone.name
                                    + "\". Elle rejoint votre reseau.")
                            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(false))));
            ServerLevel level = player.serverLevel();
            level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.2,
                    pos.getZ() + 0.5, 30, 0.3, 0.5, 0.3, 0.02);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 1.2f);
        }
        WaystoneMenus.openStone(player, stone.id);
    }

    /** La balise cassee quitte le reseau : on ne laisse jamais viser un point qui n'existe plus. */
    public static void onBroken(ServerLevel level, BlockPos pos) {
        WaystoneData data = WaystoneData.get(level.getServer());
        WaystoneData.Waystone stone = data.at(level.dimension().location().toString(), pos);
        if (stone != null) {
            data.remove(stone.id);
        }
    }

    public enum TravelResult { OK, UNKNOWN, NOT_DISCOVERED, NO_WORLD, ALREADY_THERE }

    public static String reason(TravelResult result) {
        return switch (result) {
            case UNKNOWN -> "Cette balise n'existe plus.";
            case NOT_DISCOVERED -> "Vous n'avez pas encore trouve cette balise.";
            case NO_WORLD -> "Le monde de cette balise est introuvable.";
            case ALREADY_THERE -> "Vous y etes deja.";
            default -> "";
        };
    }

    /**
     * Depart vers une balise. On passe par le systeme de teleportation du mod : le joueur garde son
     * temps d'attente, son animation, et l'annulation s'il bouge — les memes regles que partout
     * ailleurs, plutot qu'un deplacement instantane qui ferait exception.
     */
    public static TravelResult travel(ServerPlayer player, String id) {
        WaystoneData data = WaystoneData.get(player.server);
        WaystoneData.Waystone stone = data.get(id);
        if (stone == null) {
            return TravelResult.UNKNOWN;
        }
        if (!data.knows(player.getUUID(), id)) {
            return TravelResult.NOT_DISCOVERED;
        }
        ServerLevel target = resolveLevel(player.server, stone.dim);
        if (target == null) {
            return TravelResult.NO_WORLD;
        }
        BlockPos pos = stone.pos();
        if (player.level() == target && player.blockPosition().closerThan(pos, 2.0)) {
            return TravelResult.ALREADY_THERE;
        }
        // On arrive devant la stele, pas dedans : le bloc occupe sa case.
        com.utopia.teleport.TeleportManager.schedule(player, target,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 1.5, 0.0f, 0.0f,
                Messages.success("Vous voila a \"" + stone.name + "\"."));
        return TravelResult.OK;
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    /** Nom court du monde, pour situer une balise sans afficher un identifiant technique. */
    public static String worldLabel(String dim) {
        if (dim == null) {
            return "?";
        }
        return switch (dim) {
            case "minecraft:overworld" -> "Surface";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        };
    }
}
