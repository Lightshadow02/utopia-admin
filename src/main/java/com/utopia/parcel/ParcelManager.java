package com.utopia.parcel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.Config;
import com.utopia.data.ParcelData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/** Trace de parcelle (outil), visualisation, classification des blocs et verifications de permission. */
public final class ParcelManager {

    /** Points traces par joueur (sommets du polygone en cours). */
    private static final Map<UUID, List<BlockPos>> TRACES = new ConcurrentHashMap<>();

    /** Particule rouge (lignes entre points). */
    private static final DustParticleOptions LINE = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.15F), 1.0F);
    /** Particule rouge foncee (sommets cliques). */
    private static final DustParticleOptions VERTEX = new DustParticleOptions(new Vector3f(0.45F, 0.0F, 0.0F), 1.7F);

    /** Blocs consideres comme "machines" (interaction = flag MACHINES). */
    private static final Set<Block> MACHINES = Set.of(
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE,
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.ENCHANTING_TABLE,
            Blocks.BREWING_STAND, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.LOOM,
            Blocks.CARTOGRAPHY_TABLE, Blocks.STONECUTTER, Blocks.BEACON, Blocks.NOTE_BLOCK,
            Blocks.REPEATER, Blocks.COMPARATOR, Blocks.DAYLIGHT_DETECTOR, Blocks.JUKEBOX, Blocks.BELL);

    private ParcelManager() {
    }

    // -------- Outil --------

    public static Item wandItem() {
        ResourceLocation id = ResourceLocation.tryParse(Config.PARCEL_WAND_ITEM.get());
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id);
        }
        return Items.GOLDEN_HOE;
    }

    public static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.is(wandItem());
    }

    // -------- Trace (sommets du polygone) --------

    public static List<BlockPos> trace(UUID playerId) {
        return TRACES.computeIfAbsent(playerId, k -> new ArrayList<>());
    }

    public static int addPoint(ServerPlayer player, BlockPos pos) {
        List<BlockPos> pts = trace(player.getUUID());
        pts.add(pos.immutable());
        return pts.size();
    }

    /** Retire le dernier point ; renvoie le point retire ou nul. */
    public static BlockPos undoPoint(UUID playerId) {
        List<BlockPos> pts = TRACES.get(playerId);
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        return pts.remove(pts.size() - 1);
    }

    public static void clearTrace(UUID playerId) {
        TRACES.remove(playerId);
    }

    public static int traceSize(UUID playerId) {
        List<BlockPos> pts = TRACES.get(playerId);
        return pts == null ? 0 : pts.size();
    }

    /** Construit un polygone (pleine hauteur de la dimension) a partir du trace (>= 3 points), ou nul. */
    public static Parcel.Poly buildPoly(ServerPlayer player) {
        List<BlockPos> pts = TRACES.get(player.getUUID());
        if (pts == null || pts.size() < 3) {
            return null;
        }
        int[] xs = new int[pts.size()];
        int[] zs = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i).getX();
            zs[i] = pts.get(i).getZ();
        }
        ServerLevel level = player.serverLevel();
        return new Parcel.Poly(xs, zs, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    /** Affiche, pour chaque joueur traceur, les sommets (rouge fonce) et les aretes (rouge). */
    public static void renderTraces(MinecraftServer server) {
        if (TRACES.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, List<BlockPos>> e : TRACES.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            List<BlockPos> pts = e.getValue();
            if (player == null || pts.isEmpty()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            for (BlockPos v : pts) {
                level.sendParticles(player, VERTEX, true, v.getX() + 0.5, v.getY() + 1.2, v.getZ() + 0.5,
                        3, 0.04, 0.04, 0.04, 0.0);
            }
            int n = pts.size();
            for (int i = 0; i < n - 1; i++) {
                drawEdge(level, player, pts.get(i), pts.get(i + 1));
            }
            if (n >= 3) {
                drawEdge(level, player, pts.get(n - 1), pts.get(0)); // arete de fermeture
            }
        }
    }

    private static void drawEdge(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double ay = a.getY() + 1.1;
        double az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5;
        double by = b.getY() + 1.1;
        double bz = b.getZ() + 0.5;
        double dist = Math.sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az));
        int steps = Math.max(1, (int) (dist / 0.4));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            level.sendParticles(player, LINE, true,
                    ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // -------- Permissions --------

    public static boolean isOp(ServerPlayer player) {
        return player.server.getPlayerList().isOp(player.getGameProfile());
    }

    public static boolean canBypass(ServerPlayer player) {
        return Config.PARCEL_OP_BYPASS.get() && isOp(player);
    }

    /** Permission requise pour interagir (clic droit) avec ce bloc, ou nul si non protege. */
    public static Parcel.Flag requiredInteractFlag(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES) || block instanceof LeverBlock) {
            return Parcel.Flag.DOORS;
        }
        if (MACHINES.contains(block)) {
            return Parcel.Flag.MACHINES;
        }
        if (level.getBlockEntity(pos) instanceof Container) {
            return Parcel.Flag.CONTAINERS;
        }
        return null;
    }

    /**
     * Le joueur peut-il effectuer l'action {@code flag} a cette position ?
     * Vrai hors parcelle (zone libre) ou si bypass op ; sinon selon proprietaire/membre.
     */
    public static boolean isActionAllowed(ServerPlayer player, ServerLevel level, BlockPos pos, Parcel.Flag flag) {
        if (canBypass(player)) {
            return true;
        }
        Parcel parcel = ParcelData.get(player.server).parcelAt(level.dimension().location(),
                pos.getX(), pos.getY(), pos.getZ());
        if (parcel == null) {
            return true; // zone libre
        }
        return parcel.allows(player.getUUID(), flag);
    }
}
