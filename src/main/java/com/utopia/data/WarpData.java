package com.utopia.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Warps administrateur persistes : points de teleportation nommes, definis et utilisables uniquement
 * par les operateurs. Stocke dans le niveau de l'overworld ({@code data/utopia_warps.dat}).
 */
public final class WarpData extends SavedData {

    private static final String ID = "utopia_warps";

    public static final SavedData.Factory<WarpData> FACTORY =
            new SavedData.Factory<>(WarpData::new, WarpData::load, null);

    /** Un point de teleportation : dimension + position + orientation. */
    public record Warp(String dim, double x, double y, double z, float yaw, float pitch) {

        public ServerLevel resolveLevel(MinecraftServer server) {
            ResourceLocation loc = ResourceLocation.tryParse(dim);
            return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        }
    }

    private final Map<String, Warp> warps = new LinkedHashMap<>();

    public WarpData() {
    }

    public static WarpData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String norm(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Warp get(String name) {
        return warps.get(norm(name));
    }

    public boolean exists(String name) {
        return warps.containsKey(norm(name));
    }

    public List<String> names() {
        return new ArrayList<>(warps.keySet());
    }

    public void set(String name, Warp warp) {
        warps.put(norm(name), warp);
        setDirty();
    }

    public boolean remove(String name) {
        if (warps.remove(norm(name)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // -------- Serialisation --------

    public static WarpData load(CompoundTag tag, HolderLookup.Provider registries) {
        WarpData data = new WarpData();
        ListTag list = tag.getList("warps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag w = list.getCompound(i);
            String name = w.getString("name");
            if (name.isEmpty()) {
                continue;
            }
            data.warps.put(norm(name), new Warp(w.getString("dim"),
                    w.getDouble("x"), w.getDouble("y"), w.getDouble("z"),
                    w.getFloat("yaw"), w.getFloat("pitch")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Warp> e : warps.entrySet()) {
            Warp warp = e.getValue();
            CompoundTag w = new CompoundTag();
            w.putString("name", e.getKey());
            w.putString("dim", warp.dim());
            w.putDouble("x", warp.x());
            w.putDouble("y", warp.y());
            w.putDouble("z", warp.z());
            w.putFloat("yaw", warp.yaw());
            w.putFloat("pitch", warp.pitch());
            list.add(w);
        }
        tag.put("warps", list);
        return tag;
    }
}
