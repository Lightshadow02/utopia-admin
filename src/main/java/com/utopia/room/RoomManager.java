package com.utopia.room;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.Config;
import com.utopia.parcel.Parcel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;

/** Outil de selection des chambres (2 coins, Y compris) + helpers (wand, op, teleport). */
public final class RoomManager {

    /** Coins selectionnes par joueur : [0] = coin 1 (clic gauche), [1] = coin 2 (clic droit). */
    private static final Map<UUID, BlockPos[]> CORNERS = new ConcurrentHashMap<>();

    private RoomManager() {
    }

    public static Item wandItem() {
        ResourceLocation id = ResourceLocation.tryParse(Config.ROOM_WAND_ITEM.get());
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id);
        }
        return Items.BLAZE_ROD;
    }

    public static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.is(wandItem());
    }

    public static boolean isOp(ServerPlayer player) {
        return player.server.getPlayerList().isOp(player.getGameProfile());
    }

    /** Definit le coin 1 (clic gauche) ou le coin 2 (clic droit). */
    public static void setCorner(ServerPlayer player, BlockPos pos, boolean first) {
        BlockPos[] c = CORNERS.computeIfAbsent(player.getUUID(), k -> new BlockPos[2]);
        c[first ? 0 : 1] = pos.immutable();
    }

    public static BlockPos[] corners(UUID playerId) {
        return CORNERS.get(playerId);
    }

    /** Construit la boite 3D a partir des 2 coins (Y compris), ou nul si incomplets. */
    public static Parcel.Box buildBox(ServerPlayer player) {
        BlockPos[] c = CORNERS.get(player.getUUID());
        if (c == null || c[0] == null || c[1] == null) {
            return null;
        }
        return Parcel.Box.of(c[0].getX(), c[0].getY(), c[0].getZ(), c[1].getX(), c[1].getY(), c[1].getZ());
    }

    public static void clearCorners(UUID playerId) {
        CORNERS.remove(playerId);
    }

    // ------------------------------------------------------------------ Apercu de selection (3D)

    /** Arete cyan, sommet bleu (boite filaire de la chambre en cours de selection). */
    private static final DustParticleOptions ROOM_LINE = new DustParticleOptions(new Vector3f(0.1F, 0.9F, 1.0F), 1.0F);
    private static final DustParticleOptions ROOM_VERTEX = new DustParticleOptions(new Vector3f(0.0F, 0.45F, 1.0F), 1.7F);

    /** Affiche, pour chaque selectionneur, la boite 3D (12 aretes) ou le(s) coin(s) en cours. */
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

    /** Boite filaire entre 2 coins (faces exterieures des blocs), avec les coins mis en avant. */
    private static void drawBox(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b) {
        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;
        // 4 aretes verticales
        line(level, player, x0, y0, z0, x0, y1, z0);
        line(level, player, x1, y0, z0, x1, y1, z0);
        line(level, player, x1, y0, z1, x1, y1, z1);
        line(level, player, x0, y0, z1, x0, y1, z1);
        // 4 aretes du bas
        line(level, player, x0, y0, z0, x1, y0, z0);
        line(level, player, x1, y0, z0, x1, y0, z1);
        line(level, player, x1, y0, z1, x0, y0, z1);
        line(level, player, x0, y0, z1, x0, y0, z0);
        // 4 aretes du haut
        line(level, player, x0, y1, z0, x1, y1, z0);
        line(level, player, x1, y1, z0, x1, y1, z1);
        line(level, player, x1, y1, z1, x0, y1, z1);
        line(level, player, x0, y1, z1, x0, y1, z0);
        vertex(level, player, a);
        vertex(level, player, b);
    }

    private static void line(ServerLevel level, ServerPlayer player, double x1, double y1, double z1, double x2, double y2, double z2) {
        double dist = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = Math.min(200, Math.max(1, (int) (dist / 0.4)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            level.sendParticles(player, ROOM_LINE, true,
                    x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void vertex(ServerLevel level, ServerPlayer player, BlockPos p) {
        level.sendParticles(player, ROOM_VERTEX, true, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                5, 0.1, 0.1, 0.1, 0.0);
    }
}
