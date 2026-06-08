package com.utopia.room;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.Config;
import com.utopia.parcel.Parcel;
import com.utopia.util.Messages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    /** Teleporte au centre de la chambre. */
    public static void teleport(ServerPlayer player, Room room) {
        double[] c = room.center();
        if (c == null) {
            player.sendSystemMessage(Messages.error("Cette chambre n'a pas de zone."));
            return;
        }
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, room.dimension()));
        if (level == null) {
            level = player.serverLevel();
        }
        player.teleportTo(level, c[0], c[1], c[2], player.getYRot(), player.getXRot());
        player.sendSystemMessage(Messages.success("Teleporte a la chambre " + room.id() + "."));
    }
}
