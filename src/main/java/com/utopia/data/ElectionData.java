package com.utopia.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes du systeme d'elections : l'election courante (candidats, votes, statut, duree)
 * et la position de l'hologramme des resultats (capturee depuis la position d'un admin).
 */
public final class ElectionData extends SavedData {

    private static final String ID = "utopia_elections";

    public static final SavedData.Factory<ElectionData> FACTORY =
            new SavedData.Factory<>(ElectionData::new, ElectionData::load, null);

    public enum Status { SETUP, OPEN, CLOSED, CANCELLED }

    /** Une election : nom, duree, statut, candidats et votes (un vote par joueur, modifiable tant qu'OPEN). */
    public static final class Election {
        public String name;
        public int durationMinutes;
        public Status status = Status.SETUP;
        public long startMillis;
        public long endMillis;
        public final List<String> candidates = new ArrayList<>();
        public final Map<UUID, String> votes = new LinkedHashMap<>();

        public Election(String name, int durationMinutes) {
            this.name = name;
            this.durationMinutes = durationMinutes;
        }
    }

    private Election current;
    // Position de l'hologramme des resultats.
    private boolean holoConfigured;
    private String holoDim = "minecraft:overworld";
    private double holoX;
    private double holoY = 64.0;
    private double holoZ;

    public ElectionData() {
    }

    public static ElectionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Election current() {
        return current;
    }

    public void setCurrent(Election election) {
        this.current = election;
        setDirty();
    }

    // -------- Hologramme --------

    public boolean holoConfigured() {
        return holoConfigured;
    }

    public String holoDim() {
        return holoDim;
    }

    public double holoX() {
        return holoX;
    }

    public double holoY() {
        return holoY;
    }

    public double holoZ() {
        return holoZ;
    }

    public void setHologram(String dim, double x, double y, double z) {
        this.holoConfigured = true;
        this.holoDim = dim;
        this.holoX = x;
        this.holoY = y;
        this.holoZ = z;
        setDirty();
    }

    // -------- Serialisation --------

    public static ElectionData load(CompoundTag tag, HolderLookup.Provider registries) {
        ElectionData data = new ElectionData();
        data.holoConfigured = tag.getBoolean("holoConfigured");
        if (tag.contains("holoDim")) {
            data.holoDim = tag.getString("holoDim");
        }
        data.holoX = tag.getDouble("holoX");
        data.holoY = tag.contains("holoY") ? tag.getDouble("holoY") : 64.0;
        data.holoZ = tag.getDouble("holoZ");

        if (tag.contains("election", Tag.TAG_COMPOUND)) {
            CompoundTag e = tag.getCompound("election");
            Election el = new Election(e.getString("name"), e.getInt("duration"));
            try {
                el.status = Status.valueOf(e.getString("status"));
            } catch (IllegalArgumentException ignored) {
                el.status = Status.SETUP;
            }
            el.startMillis = e.getLong("start");
            el.endMillis = e.getLong("end");
            ListTag cands = e.getList("candidates", Tag.TAG_STRING);
            for (int i = 0; i < cands.size(); i++) {
                el.candidates.add(cands.getString(i));
            }
            ListTag votes = e.getList("votes", Tag.TAG_COMPOUND);
            for (int i = 0; i < votes.size(); i++) {
                CompoundTag v = votes.getCompound(i);
                try {
                    el.votes.put(UUID.fromString(v.getString("voter")), v.getString("candidate"));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu
                }
            }
            data.current = el;
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("holoConfigured", holoConfigured);
        tag.putString("holoDim", holoDim);
        tag.putDouble("holoX", holoX);
        tag.putDouble("holoY", holoY);
        tag.putDouble("holoZ", holoZ);

        if (current != null) {
            CompoundTag e = new CompoundTag();
            e.putString("name", current.name == null ? "" : current.name);
            e.putInt("duration", current.durationMinutes);
            e.putString("status", current.status.name());
            e.putLong("start", current.startMillis);
            e.putLong("end", current.endMillis);
            ListTag cands = new ListTag();
            for (String c : current.candidates) {
                cands.add(StringTag.valueOf(c));
            }
            e.put("candidates", cands);
            ListTag votes = new ListTag();
            for (Map.Entry<UUID, String> entry : current.votes.entrySet()) {
                CompoundTag v = new CompoundTag();
                v.putString("voter", entry.getKey().toString());
                v.putString("candidate", entry.getValue());
                votes.add(v);
            }
            e.put("votes", votes);
            tag.put("election", e);
        }
        return tag;
    }
}
