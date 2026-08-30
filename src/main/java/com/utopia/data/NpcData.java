package com.utopia.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * PNJ decoratifs poses par l'administration : une statue vivante a l'effigie d'un joueur.
 *
 * <p>Le skin est <b>copie</b> dans la sauvegarde, pas emprunte au joueur : le PNJ garde son visage
 * meme si l'interesse ne revient jamais, ou change de skin par la suite.
 */
public final class NpcData extends SavedData {

    private static final String ID = "utopia_npcs";

    public static final SavedData.Factory<NpcData> FACTORY =
            new SavedData.Factory<>(NpcData::new, NpcData::load, null);

    /** Un PNJ decoratif. */
    public static final class Npc {
        public final String id;
        public String name;
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public float restYaw;
        public boolean enabled = true;
        public boolean showName = true;
        /** Propriete "textures" du profil copie, et sa signature. Vide = Steve. */
        public String skinValue = "";
        public String skinSignature = "";
        /** Pseudo dont le visage a ete copie, pour s'en souvenir a l'ecran. */
        public String skinFrom = "";

        public Npc(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean isPlaced() {
            return !dim.isEmpty();
        }
    }

    private final Map<String, Npc> npcs = new LinkedHashMap<>();

    public NpcData() {
    }

    public static NpcData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static String key(String name) {
        String out = java.text.Normalizer.normalize(name == null ? "" : name,
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return out.isEmpty() ? "npc" : out;
    }

    public Npc get(String id) {
        return id == null ? null : npcs.get(id);
    }

    /** Cree un PNJ ; l'identifiant derive du nom, suffixe si besoin pour rester unique. */
    public Npc create(String name) {
        String base = key(name);
        String id = base;
        int n = 2;
        while (npcs.containsKey(id)) {
            id = base + "_" + n++;
        }
        Npc npc = new Npc(id, name.trim());
        npcs.put(id, npc);
        setDirty();
        return npc;
    }

    public boolean remove(String id) {
        if (npcs.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public List<Npc> all() {
        List<Npc> out = new ArrayList<>(npcs.values());
        out.sort(Comparator.comparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // -------- Serialisation --------

    public static NpcData load(CompoundTag tag, HolderLookup.Provider registries) {
        NpcData data = new NpcData();
        ListTag list = tag.getList("npcs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag n = list.getCompound(i);
            String id = n.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Npc npc = new Npc(id, n.getString("name"));
            npc.dim = n.getString("dim");
            npc.x = n.getDouble("x");
            npc.y = n.getDouble("y");
            npc.z = n.getDouble("z");
            npc.restYaw = n.getFloat("yaw");
            npc.enabled = !n.contains("enabled") || n.getBoolean("enabled");
            npc.showName = !n.contains("showName") || n.getBoolean("showName");
            npc.skinValue = n.getString("skin");
            npc.skinSignature = n.getString("skinSig");
            npc.skinFrom = n.getString("skinFrom");
            data.npcs.put(id, npc);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Npc npc : npcs.values()) {
            CompoundTag n = new CompoundTag();
            n.putString("id", npc.id);
            n.putString("name", npc.name);
            n.putString("dim", npc.dim);
            n.putDouble("x", npc.x);
            n.putDouble("y", npc.y);
            n.putDouble("z", npc.z);
            n.putFloat("yaw", npc.restYaw);
            n.putBoolean("enabled", npc.enabled);
            n.putBoolean("showName", npc.showName);
            n.putString("skin", npc.skinValue);
            n.putString("skinSig", npc.skinSignature);
            n.putString("skinFrom", npc.skinFrom);
            list.add(n);
        }
        tag.put("npcs", list);
        return tag;
    }
}
