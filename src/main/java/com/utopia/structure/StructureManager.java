package com.utopia.structure;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import com.utopia.data.StructureData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Moteur des structures a etats : selection de la zone (apercu en particules rouges), capture d'un
 * etat sous forme de schematique vanilla, pose d'un etat, et bascule automatique jour / nuit.
 *
 * <p>La capture/pose s'appuie sur {@link StructureTemplate} (le moteur du bloc de structure) : la
 * palette et les block entities sont gerees par le jeu.
 */
public final class StructureManager {

    /** Volume maximal d'une zone (securite : capture et pose sont synchrones). */
    public static final long MAX_VOLUME = 200_000L;

    /** Joueurs en train de definir une zone : coin 1 (clic gauche) / coin 2 (clic droit). */
    private static final Map<UUID, BlockPos[]> CORNERS = new ConcurrentHashMap<>();

    private static final DustParticleOptions LINE = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.15F), 1.0F);
    private static final DustParticleOptions VERTEX = new DustParticleOptions(new Vector3f(1.0F, 0.5F, 0.0F), 1.7F);

    private StructureManager() {
    }

    // ------------------------------------------------------------------ Selection de zone

    public static void startSelect(UUID id) {
        CORNERS.put(id, new BlockPos[2]);
    }

    public static boolean isSelecting(UUID id) {
        return CORNERS.containsKey(id);
    }

    public static void clearSelect(UUID id) {
        CORNERS.remove(id);
    }

    /** Definit le coin 1 (clic gauche) ou le coin 2 (clic droit). */
    public static void setCorner(UUID id, BlockPos pos, boolean first) {
        BlockPos[] c = CORNERS.computeIfAbsent(id, k -> new BlockPos[2]);
        c[first ? 0 : 1] = pos.immutable();
    }

    public static BlockPos[] corners(UUID id) {
        return CORNERS.get(id);
    }

    /** Vrai si les deux coins sont poses. */
    public static boolean hasBox(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        return c != null && c[0] != null && c[1] != null;
    }

    /** Coin minimal de la selection, ou null. */
    public static BlockPos min(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        if (c == null || c[0] == null || c[1] == null) {
            return null;
        }
        return new BlockPos(Math.min(c[0].getX(), c[1].getX()),
                Math.min(c[0].getY(), c[1].getY()),
                Math.min(c[0].getZ(), c[1].getZ()));
    }

    /** Dimensions de la selection (inclusives), ou null. */
    public static Vec3i size(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        if (c == null || c[0] == null || c[1] == null) {
            return null;
        }
        return new Vec3i(Math.abs(c[0].getX() - c[1].getX()) + 1,
                Math.abs(c[0].getY() - c[1].getY()) + 1,
                Math.abs(c[0].getZ() - c[1].getZ()) + 1);
    }

    // ------------------------------------------------------------------ Capture / pose

    /** Capture l'etat actuel du monde dans le slot demande (1 ou 2). */
    public static boolean capture(MinecraftServer server, StructureData.Struct struct, int slot) {
        ServerLevel level = resolveLevel(server, struct.dim);
        if (level == null || struct.volume() > MAX_VOLUME) {
            return false;
        }
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, struct.min, struct.size, true, null);
        struct.setState(slot, template.save(new CompoundTag()));
        StructureData.get(server).setDirty();
        return true;
    }

    /** Pose l'etat demande dans le monde. Renvoie false si l'etat n'existe pas / dimension absente. */
    public static boolean apply(MinecraftServer server, StructureData.Struct struct, int slot) {
        CompoundTag tag = struct.state(slot);
        if (tag == null) {
            return false;
        }
        ServerLevel level = resolveLevel(server, struct.dim);
        if (level == null) {
            return false;
        }
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), tag);
        template.placeInWorld(level, struct.min, struct.min, new StructurePlaceSettings(),
                level.getRandom(), Block.UPDATE_ALL);
        struct.current = slot == 2 ? 2 : 1;
        StructureData.get(server).setDirty();
        return true;
    }

    // ------------------------------------------------------------------ Bascule automatique

    /**
     * A appeler periodiquement : pour chaque structure en mode auto, pose l'etat correspondant a
     * l'heure du monde (nuit -> etat 2, jour -> etat 1). Ne fait rien tant que l'etat est deja bon.
     */
    public static void tickAuto(MinecraftServer server) {
        StructureData data = StructureData.get(server);
        for (StructureData.Struct struct : data.all()) {
            if (!struct.auto || !struct.hasState(1) || !struct.hasState(2)) {
                continue;
            }
            ServerLevel level = resolveLevel(server, struct.dim);
            if (level == null || !level.isLoaded(struct.min)) {
                continue;
            }
            int wanted = isNight(level) ? 2 : 1;
            if (struct.current != wanted) {
                apply(server, struct, wanted);
            }
        }
    }

    /** Nuit minecraftienne (approximativement de 13000 a 23000). */
    public static boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    // ------------------------------------------------------------------ Apercu (particules rouges)

    /** Affiche la boite de selection en cours (12 aretes rouges) pour chaque joueur qui selectionne. */
    public static void renderSelections(MinecraftServer server) {
        if (CORNERS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, BlockPos[]> e : CORNERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                continue;
            }
            BlockPos[] c = e.getValue();
            ServerLevel level = player.serverLevel();
            if (c[0] != null && c[1] != null) {
                drawBox(level, player, c[0], c[1]);
            } else if (c[0] != null) {
                vertex(level, player, c[0]);
            } else if (c[1] != null) {
                vertex(level, player, c[1]);
            }
        }
    }

    /** Affiche la zone d'une structure existante (apercu ponctuel). */
    public static void showZone(ServerPlayer player, StructureData.Struct struct) {
        BlockPos max = struct.min.offset(struct.size.getX() - 1, struct.size.getY() - 1, struct.size.getZ() - 1);
        drawBox(player.serverLevel(), player, struct.min, max);
    }

    private static void drawBox(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b) {
        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;
        line(level, player, x0, y0, z0, x0, y1, z0);
        line(level, player, x1, y0, z0, x1, y1, z0);
        line(level, player, x1, y0, z1, x1, y1, z1);
        line(level, player, x0, y0, z1, x0, y1, z1);
        line(level, player, x0, y0, z0, x1, y0, z0);
        line(level, player, x1, y0, z0, x1, y0, z1);
        line(level, player, x1, y0, z1, x0, y0, z1);
        line(level, player, x0, y0, z1, x0, y0, z0);
        line(level, player, x0, y1, z0, x1, y1, z0);
        line(level, player, x1, y1, z0, x1, y1, z1);
        line(level, player, x1, y1, z1, x0, y1, z1);
        line(level, player, x0, y1, z1, x0, y1, z0);
        vertex(level, player, a);
        vertex(level, player, b);
    }

    /** Trace une arete en semant des particules (pas adaptatif, borne pour les grandes zones). */
    private static void line(ServerLevel level, ServerPlayer player,
                             double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.min(64, Math.max(2, len));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            level.sendParticles(player, LINE, true,
                    x0 + dx * t, y0 + dy * t, z0 + dz * t, 1, 0, 0, 0, 0);
        }
    }

    private static void vertex(ServerLevel level, ServerPlayer player, BlockPos pos) {
        level.sendParticles(player, VERTEX, true,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
