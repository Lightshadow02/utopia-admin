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
 * Hologrammes libres poses par l'administration : un titre, autant de lignes qu'on veut, chacune de
 * la couleur choisie, a l'endroit voulu.
 *
 * <p>Ils sont independants des hologrammes attaches a un systeme (parcelles, chantiers, paris) : ils
 * ne s'effacent jamais tout seuls et ne connaissent aucune regle metier.
 */
public final class HologramData extends SavedData {

    private static final String ID = "utopia_holograms";
    /** Nombre maximal de lignes sur un hologramme. */
    public static final int MAX_LINES = 15;

    public static final SavedData.Factory<HologramData> FACTORY =
            new SavedData.Factory<>(HologramData::new, HologramData::load, null);

    /** Une ligne : son texte et sa couleur. */
    public static final class Line {
        public String text;
        public String color;     // nom d'un ChatFormatting, ex "GOLD"
        public boolean bold;

        public Line(String text, String color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }
    }

    /** Un hologramme pose dans le monde. */
    public static final class Hologram {
        public final String id;
        public String name;
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public boolean enabled = true;
        public final List<Line> lines = new ArrayList<>();

        public Hologram(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean isPlaced() {
            return !dim.isEmpty();
        }
    }

    private final Map<String, Hologram> holograms = new LinkedHashMap<>();

    public HologramData() {
    }

    public static HologramData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static String key(String name) {
        String out = java.text.Normalizer.normalize(name == null ? "" : name,
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return out.isEmpty() ? "holo" : out;
    }

    public Hologram get(String id) {
        return id == null ? null : holograms.get(id);
    }

    public boolean exists(String name) {
        return holograms.containsKey(key(name));
    }

    /** Cree un hologramme ; l'identifiant derive du nom, suffixe si besoin pour rester unique. */
    public Hologram create(String name) {
        String base = key(name);
        String id = base;
        int n = 2;
        while (holograms.containsKey(id)) {
            id = base + "_" + n++;
        }
        Hologram holo = new Hologram(id, name.trim());
        holograms.put(id, holo);
        setDirty();
        return holo;
    }

    public boolean remove(String id) {
        if (holograms.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public List<Hologram> all() {
        List<Hologram> out = new ArrayList<>(holograms.values());
        out.sort(Comparator.comparing(h -> h.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // -------- Serialisation --------

    public static HologramData load(CompoundTag tag, HolderLookup.Provider registries) {
        HologramData data = new HologramData();
        ListTag list = tag.getList("holograms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag h = list.getCompound(i);
            String id = h.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Hologram holo = new Hologram(id, h.getString("name"));
            holo.dim = h.getString("dim");
            holo.x = h.getDouble("x");
            holo.y = h.getDouble("y");
            holo.z = h.getDouble("z");
            holo.enabled = !h.contains("enabled") || h.getBoolean("enabled");
            ListTag lines = h.getList("lines", Tag.TAG_COMPOUND);
            for (int k = 0; k < lines.size() && holo.lines.size() < MAX_LINES; k++) {
                CompoundTag l = lines.getCompound(k);
                holo.lines.add(new Line(l.getString("text"), l.getString("color"),
                        l.getBoolean("bold")));
            }
            data.holograms.put(id, holo);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Hologram holo : holograms.values()) {
            CompoundTag h = new CompoundTag();
            h.putString("id", holo.id);
            h.putString("name", holo.name);
            h.putString("dim", holo.dim);
            h.putDouble("x", holo.x);
            h.putDouble("y", holo.y);
            h.putDouble("z", holo.z);
            h.putBoolean("enabled", holo.enabled);
            ListTag lines = new ListTag();
            for (Line line : holo.lines) {
                CompoundTag l = new CompoundTag();
                l.putString("text", line.text);
                l.putString("color", line.color);
                l.putBoolean("bold", line.bold);
                lines.add(l);
            }
            h.put("lines", lines);
            list.add(h);
        }
        tag.put("holograms", list);
        return tag;
    }
}
