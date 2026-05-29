package com.utopia.data;

import com.utopia.UtopiaMod;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes du spawn du serveur defini via {@code /setspawn}.
 * Stocke dans le niveau de l'overworld ({@code data/utopia_spawn.dat}).
 */
public final class SpawnData extends SavedData {
    private static final String ID = "utopia_spawn";

    public static final SavedData.Factory<SpawnData> FACTORY =
            new SavedData.Factory<>(SpawnData::new, SpawnData::load, null);

    private boolean hasSpawn;
    private ResourceLocation dimension;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public SpawnData() {
    }

    public static SpawnData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean hasSpawn() {
        return hasSpawn;
    }

    /** Definit le spawn a la position d'un joueur (ou de toute entite vivante). */
    public void setSpawn(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
        this.hasSpawn = true;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        setDirty();
    }

    /** Resout le niveau du spawn. Renvoie nul si la dimension n'existe plus. */
    public ServerLevel resolveLevel(MinecraftServer server) {
        if (!hasSpawn || dimension == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public static SpawnData load(CompoundTag tag, HolderLookup.Provider registries) {
        SpawnData data = new SpawnData();
        if (tag.contains("dimension")) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
            if (dim != null) {
                data.hasSpawn = true;
                data.dimension = dim;
                data.x = tag.getDouble("x");
                data.y = tag.getDouble("y");
                data.z = tag.getDouble("z");
                data.yaw = tag.getFloat("yaw");
                data.pitch = tag.getFloat("pitch");
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (hasSpawn && dimension != null) {
            tag.putString("dimension", dimension.toString());
            tag.putDouble("x", x);
            tag.putDouble("y", y);
            tag.putDouble("z", z);
            tag.putFloat("yaw", yaw);
            tag.putFloat("pitch", pitch);
        }
        return tag;
    }

    /** Fallback : la position de spawn vanilla de l'overworld. */
    public static double[] worldSpawn(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        var pos = overworld.getSharedSpawnPos();
        float angle = overworld.getSharedSpawnAngle();
        // Centre du bloc en X/Z pour eviter de spawn dans un coin.
        return new double[] { pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, angle, 0.0F };
    }

    static {
        // Touche le logger pour confirmer le chargement de la classe en debug.
        UtopiaMod.LOGGER.debug("[Utopia] SpawnData charge.");
    }
}
