package com.utopia.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.utopia.data.HologramData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

/** Pose et entretien des hologrammes libres : une ligne de texte = un support invisible. */
public final class HologramManager {

    private static final String TAG = "utopiaFreeHolo";
    private static final double LINE_GAP = 0.28;

    /** Palette proposee dans les menus : des couleurs lisibles sur un ciel comme sur un mur. */
    public static final List<ChatFormatting> PALETTE = List.of(
            ChatFormatting.WHITE, ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.GREEN,
            ChatFormatting.AQUA, ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE, ChatFormatting.RED,
            ChatFormatting.GRAY, ChatFormatting.DARK_GRAY);

    private HologramManager() {
    }

    public static ChatFormatting color(String name) {
        ChatFormatting found = ChatFormatting.getByName(name);
        return found == null ? ChatFormatting.WHITE : found;
    }

    /** Couleur suivante de la palette, pour un bouton qui les fait defiler. */
    public static String nextColor(String current) {
        ChatFormatting c = color(current);
        int index = PALETTE.indexOf(c);
        return PALETTE.get((index + 1 + PALETTE.size()) % PALETTE.size()).getName();
    }

    public static Component render(HologramData.Line line) {
        ChatFormatting c = color(line.color);
        return Component.literal(line.text)
                .withStyle(s -> s.withColor(c).withBold(line.bold).withItalic(false));
    }

    /** Recree ce qui manque, met a jour ce qui a change, retire ce qui n'a plus lieu d'etre. */
    public static void sync(MinecraftServer server) {
        HologramData data = HologramData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, List<ArmorStand>> stands = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand stand) {
                    String key = stand.getPersistentData().getString(TAG);
                    if (!key.isEmpty()) {
                        stands.computeIfAbsent(key, k -> new ArrayList<>()).add(stand);
                    }
                }
            }
            for (HologramData.Hologram holo : data.all()) {
                List<ArmorStand> existing = stands.remove(holo.id);
                ServerLevel target = holo.isPlaced() ? resolveLevel(server, holo.dim) : null;
                boolean wanted = target == level && holo.enabled && !holo.lines.isEmpty();
                if (!wanted) {
                    if (existing != null) {
                        existing.forEach(Entity::discard);
                    }
                    continue;
                }
                if (!level.isLoaded(BlockPos.containing(holo.x, holo.y, holo.z))) {
                    continue;
                }
                apply(level, holo, existing);
            }
            stands.values().forEach(list -> list.forEach(Entity::discard));
        }
    }

    private static void apply(ServerLevel level, HologramData.Hologram holo, List<ArmorStand> existing) {
        List<HologramData.Line> lines = holo.lines;
        double topY = holo.y + (lines.size() - 1) * LINE_GAP;
        if (existing != null && existing.size() == lines.size()) {
            existing.sort(Comparator.comparingInt(s -> s.getPersistentData().getInt("line")));
            for (int i = 0; i < lines.size(); i++) {
                ArmorStand stand = existing.get(i);
                stand.setCustomName(render(lines.get(i)));
                stand.setCustomNameVisible(true);
                stand.teleportTo(holo.x, topY - i * LINE_GAP, holo.z);
            }
            return;
        }
        if (existing != null) {
            existing.forEach(Entity::discard);
        }
        for (int i = 0; i < lines.size(); i++) {
            spawn(level, holo.id, i, holo.x, topY - i * LINE_GAP, holo.z, render(lines.get(i)));
        }
    }

    private static void spawn(ServerLevel level, String key, int index,
                              double x, double y, double z, Component text) {
        ArmorStand stand = new ArmorStand(level, x, y, z);
        CompoundTag tag = stand.saveWithoutId(new CompoundTag());
        tag.putBoolean("Marker", true);
        stand.load(tag);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setNoBasePlate(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.getPersistentData().putString(TAG, key);
        stand.getPersistentData().putInt("line", index);
        stand.setPos(x, y, z);
        level.addFreshEntity(stand);
    }

    /** Efface les supports d'un hologramme supprime. */
    public static void removeEntities(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand stand
                        && id.equals(stand.getPersistentData().getString(TAG))) {
                    stand.discard();
                }
            }
        }
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
