package com.utopia.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Position persistante de l'hologramme du classement des soldes (BalTop).
 * Un seul hologramme global ; stocke dans l'overworld ({@code data/utopia_baltop_holo.dat}).
 */
public final class BalTopData extends SavedData {
    private static final String ID = "utopia_baltop_holo";

    public static final SavedData.Factory<BalTopData> FACTORY =
            new SavedData.Factory<>(BalTopData::new, BalTopData::load, null);

    private boolean enabled;
    private ResourceLocation dim;
    private double x;
    private double y;
    private double z;

    public BalTopData() {
    }

    public static BalTopData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean enabled() {
        return enabled;
    }

    public ResourceLocation dim() {
        return dim;
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

    /** Place (ou replace) l'hologramme a cette position et l'active. */
    public void place(ResourceLocation dim, double x, double y, double z) {
        this.enabled = true;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        setDirty();
    }

    public void move(double dx, double dy, double dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        setDirty();
    }

    public void disable() {
        this.enabled = false;
        setDirty();
    }

    public static BalTopData load(CompoundTag tag, HolderLookup.Provider registries) {
        BalTopData d = new BalTopData();
        d.enabled = tag.getBoolean("enabled");
        d.dim = ResourceLocation.tryParse(tag.getString("dim"));
        d.x = tag.getDouble("x");
        d.y = tag.getDouble("y");
        d.z = tag.getDouble("z");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("enabled", enabled);
        if (dim != null) {
            tag.putString("dim", dim.toString());
        }
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        return tag;
    }
}
