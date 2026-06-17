package com.utopia.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Hologrammes (armor stands invisibles) au-dessus des stands de marche : proprietaire, et pour chaque
 * offre l'objet + quantite + prix + temps restant. Synchronise periodiquement depuis le tick serveur.
 */
public final class MarketHolograms {

    private static final String HOLO_STALL = "utopiaMarketStall";
    private static final String HOLO_LINE = "utopiaMarketLine";
    private static final double LINE_GAP = 0.28;

    private MarketHolograms() {
    }

    public static void sync(MinecraftServer server) {
        MarketData data = MarketData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dim = level.dimension().location();
            String dimStr = dim.toString();

            Map<String, List<ArmorStand>> existing = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand as && as.getPersistentData().contains(HOLO_STALL)) {
                    existing.computeIfAbsent(as.getPersistentData().getString(HOLO_STALL), k -> new ArrayList<>()).add(as);
                }
            }

            Set<String> wanted = new HashSet<>();
            for (MarketData.Stall stall : data.stalls()) {
                if (!stall.dim.equals(dimStr)) {
                    continue;
                }
                BlockPos pos = stall.pos();
                if (!level.isLoaded(pos)) {
                    continue;
                }
                String key = stall.dim + ";" + pos.getX() + ";" + pos.getY() + ";" + pos.getZ();
                List<Component> lines = linesFor(stall);
                wanted.add(key);
                double x = pos.getX() + 0.5;
                double z = pos.getZ() + 0.5;
                double topY = pos.getY() + 1.3 + (lines.size() - 1) * LINE_GAP;

                List<ArmorStand> stands = existing.get(key);
                if (stands == null || stands.size() != lines.size()) {
                    if (stands != null) {
                        stands.forEach(Entity::discard);
                    }
                    spawn(level, key, x, topY, z, lines);
                } else {
                    stands.sort(Comparator.comparingInt(s -> s.getPersistentData().getInt(HOLO_LINE)));
                    for (int i = 0; i < lines.size(); i++) {
                        ArmorStand s = stands.get(i);
                        s.teleportTo(x, topY - i * LINE_GAP, z);
                        s.setCustomName(lines.get(i));
                        s.setCustomNameVisible(true);
                    }
                }
            }

            for (Map.Entry<String, List<ArmorStand>> entry : existing.entrySet()) {
                if (!wanted.contains(entry.getKey())) {
                    entry.getValue().forEach(Entity::discard);
                }
            }
        }
    }

    private static List<Component> linesFor(MarketData.Stall stall) {
        List<Component> lines = new ArrayList<>();
        if (stall.owner == null) {
            lines.add(Component.literal("Stand libre").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)));
            lines.add(Component.literal("Clic droit pour vendre").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
            return lines;
        }
        lines.add(Component.literal("Stand de " + stall.ownerName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        long now = System.currentTimeMillis();
        if (stall.offers.isEmpty()) {
            lines.add(Component.literal("(aucune offre)").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
        }
        for (MarketData.Offer o : stall.offers) {
            String time = Messages.formatDuration(Math.max(0, o.expiryMillis - now) / 1000);
            lines.add(Component.literal(o.stack.getCount() + "x ").withStyle(ChatFormatting.WHITE)
                    .append(o.stack.getHoverName().copy().withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)))
                    .append(Component.literal("  -  " + EconomyManager.format(o.price))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)))
                    .append(Component.literal("  -  " + time)
                            .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false))));
        }
        return lines;
    }

    private static void spawn(ServerLevel level, String key, double x, double topY, double z, List<Component> lines) {
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
            stand.getPersistentData().putString(HOLO_STALL, key);
            stand.getPersistentData().putInt(HOLO_LINE, i);
            stand.setPos(x, y, z);
            level.addFreshEntity(stand);
        }
    }
}
