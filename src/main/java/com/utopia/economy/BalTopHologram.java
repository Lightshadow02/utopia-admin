package com.utopia.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.BalTopData;
import com.utopia.data.EconomyData;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Hologramme du classement des soldes (BalTop) : une pile d'armor stands invisibles affichant
 * le titre + le top 10 des plus riches, deplacable par les admins. Synchronise periodiquement.
 */
public final class BalTopHologram {

    private static final String HOLO_BALTOP = "utopiaHoloBaltop";
    private static final String HOLO_LINE = "utopiaHoloLine";
    private static final double LINE_GAP = 0.28;

    private BalTopHologram() {
    }

    /** Place l'hologramme au-dessus de l'admin. */
    public static void setHere(ServerPlayer player) {
        BalTopData.get(player.server).place(player.serverLevel().dimension().location(),
                player.getX(), player.getY() + 2.6, player.getZ());
        player.sendSystemMessage(Messages.success("Hologramme BalTop place ici."));
    }

    /** Retire l'hologramme (les armor stands seront supprimes a la prochaine sync). */
    public static void remove(MinecraftServer server) {
        BalTopData.get(server).disable();
    }

    /** Reconstruit/positionne/supprime les armor stands selon la position et le top 10 actuel. */
    public static void sync(MinecraftServer server) {
        BalTopData data = BalTopData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dim = level.dimension().location();

            List<ArmorStand> existing = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand as && as.getPersistentData().contains(HOLO_BALTOP)) {
                    existing.add(as);
                }
            }

            boolean here = data.enabled() && dim.equals(data.dim());
            if (!here) {
                existing.forEach(Entity::discard); // mauvaise dimension ou desactive
                continue;
            }
            if (!level.isLoaded(new BlockPos((int) Math.floor(data.x()), (int) Math.floor(data.y()), (int) Math.floor(data.z())))) {
                continue; // chunk non charge : on synchronisera plus tard
            }

            List<Component> lines = lines(server);
            if (existing.size() != lines.size()) {
                existing.forEach(Entity::discard);
                spawn(level, data.x(), data.y(), data.z(), lines);
            } else {
                existing.sort(Comparator.comparingInt(s -> s.getPersistentData().getInt(HOLO_LINE)));
                for (int i = 0; i < lines.size(); i++) {
                    ArmorStand s = existing.get(i);
                    s.teleportTo(data.x(), data.y() - i * LINE_GAP, data.z());
                    s.setCustomName(lines.get(i));
                    s.setCustomNameVisible(true);
                }
            }
        }
    }

    private static List<Component> lines(MinecraftServer server) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("★ TOP 10 RICHESSES ★").withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        List<Map.Entry<UUID, Long>> top = EconomyData.get(server).top(10);
        if (top.isEmpty()) {
            out.add(Component.literal("(aucun compte)").withStyle(ChatFormatting.GRAY));
            return out;
        }
        int rank = 1;
        for (Map.Entry<UUID, Long> e : top) {
            ChatFormatting color = switch (rank) {
                case 1 -> ChatFormatting.YELLOW;
                case 2 -> ChatFormatting.WHITE;
                case 3 -> ChatFormatting.GOLD;
                default -> ChatFormatting.GRAY;
            };
            out.add(Component.literal("#" + rank + " " + nameOf(server, e.getKey()) + " - " + EconomyManager.format(e.getValue()))
                    .withStyle(color));
            rank++;
        }
        return out;
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        if (id.equals(com.utopia.data.MarketData.MAIRIE_UUID)) {
            return com.utopia.data.MarketData.MAIRIE_NAME;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName)
                .orElse(id.toString().substring(0, 8));
    }

    private static void spawn(ServerLevel level, double x, double topY, double z, List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            double y = topY - i * LINE_GAP;
            ArmorStand stand = new ArmorStand(level, x, y, z);
            CompoundTag tag = stand.saveWithoutId(new CompoundTag());
            tag.putBoolean("Marker", true);
            stand.load(tag);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setNoBasePlate(true);
            stand.setCustomName(lines.get(i));
            stand.setCustomNameVisible(true);
            stand.getPersistentData().putString(HOLO_BALTOP, "1");
            stand.getPersistentData().putInt(HOLO_LINE, i);
            stand.setPos(x, y, z);
            level.addFreshEntity(stand);
        }
    }
}
