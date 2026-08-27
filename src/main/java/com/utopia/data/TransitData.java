package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
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
 * Donnees persistantes des Capitaines Transit : les capitaines places dans le monde, les quatre
 * destinations du continent de ressources, et le point de retour commun vers Utopia.
 *
 * <p>Les destinations et le point de retour sont stockes <b>globalement</b>, pas dans les capitaines :
 * deplacer ou reconfigurer un PNJ ne perd donc jamais les points d'arrivee, et modifier le point de
 * retour profite immediatement aux quatre capitaines du continent.
 */
public final class TransitData extends SavedData {

    private static final String ID = "utopia_transit";

    public static final SavedData.Factory<TransitData> FACTORY =
            new SavedData.Factory<>(TransitData::new, TransitData::load, null);

    /** Sens de service d'un capitaine. */
    public enum Mode {
        ALLER("Aller"),
        RETOUR("Retour");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Mode other() {
            return this == ALLER ? RETOUR : ALLER;
        }
    }

    /** Les quatre caps desservis depuis Utopia. */
    public enum Direction {
        NORD("Nord", "^"),
        EST("Est", ">"),
        SUD("Sud", "v"),
        OUEST("Ouest", "<");

        private final String label;
        private final String arrow;

        Direction(String label, String arrow) {
            this.label = label;
            this.arrow = arrow;
        }

        public String label() {
            return label;
        }

        public String arrow() {
            return arrow;
        }
    }

    /** Un point d'arrivee : monde, coordonnees et orientation a l'arrivee. */
    public static final class Point {
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public boolean enabled = true;

        public boolean isSet() {
            return !dim.isEmpty();
        }
    }

    /** Un Capitaine Transit place dans le monde. */
    public static final class Captain {
        public final String id;
        public String name = "Capitaine Transit";
        public Mode mode = Mode.ALLER;
        public String skinValue = "";
        public String skinSignature = "";
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public float restYaw;
        public boolean enabled = true;

        public Captain(String id) {
            this.id = id;
        }

        public boolean isPlaced() {
            return !dim.isEmpty();
        }
    }

    private final Map<String, Captain> captains = new LinkedHashMap<>();
    private final Map<Direction, Point> destinations = new LinkedHashMap<>();
    private final Point returnPoint = new Point();

    public TransitData() {
        for (Direction d : Direction.values()) {
            destinations.put(d, new Point());
        }
    }

    public static TransitData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String key(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    // -------- Capitaines --------

    public Collection<Captain> captains() {
        return captains.values();
    }

    public Captain captain(String id) {
        return captains.get(key(id));
    }

    public boolean exists(String id) {
        return captains.containsKey(key(id));
    }

    public Captain create(String name) {
        String base = key(name);
        String id = base;
        int n = 2;
        while (captains.containsKey(id)) { // un meme nom peut servir plusieurs fois (aller + retours)
            id = base + "_" + n++;
        }
        Captain captain = new Captain(id);
        captain.name = name.trim();
        captains.put(id, captain);
        setDirty();
        return captain;
    }

    public boolean remove(String id) {
        if (captains.remove(key(id)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // -------- Destinations et retour --------

    public Point destination(Direction direction) {
        return destinations.computeIfAbsent(direction, d -> new Point());
    }

    public Point returnPoint() {
        return returnPoint;
    }

    /** Une destination est utilisable si elle est configuree et activee. */
    public boolean isUsable(Direction direction) {
        Point p = destination(direction);
        return p.isSet() && p.enabled;
    }

    public boolean isReturnUsable() {
        return returnPoint.isSet() && returnPoint.enabled;
    }

    // -------- Serialisation --------

    private static void writePoint(CompoundTag tag, String prefix, Point p) {
        tag.putString(prefix + "Dim", p.dim);
        tag.putDouble(prefix + "X", p.x);
        tag.putDouble(prefix + "Y", p.y);
        tag.putDouble(prefix + "Z", p.z);
        tag.putFloat(prefix + "Yaw", p.yaw);
        tag.putFloat(prefix + "Pitch", p.pitch);
        tag.putBoolean(prefix + "On", p.enabled);
    }

    private static void readPoint(CompoundTag tag, String prefix, Point p) {
        p.dim = tag.getString(prefix + "Dim");
        p.x = tag.getDouble(prefix + "X");
        p.y = tag.getDouble(prefix + "Y");
        p.z = tag.getDouble(prefix + "Z");
        p.yaw = tag.getFloat(prefix + "Yaw");
        p.pitch = tag.getFloat(prefix + "Pitch");
        p.enabled = !tag.contains(prefix + "On") || tag.getBoolean(prefix + "On");
    }

    public static TransitData load(CompoundTag tag, HolderLookup.Provider registries) {
        TransitData data = new TransitData();
        ListTag list = tag.getList("captains", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            String id = c.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Captain captain = new Captain(id);
            captain.name = c.getString("name");
            try {
                captain.mode = Mode.valueOf(c.getString("mode"));
            } catch (IllegalArgumentException ignored) {
                captain.mode = Mode.ALLER;
            }
            captain.skinValue = c.getString("skin");
            captain.skinSignature = c.getString("skinSig");
            captain.dim = c.getString("dim");
            captain.x = c.getDouble("x");
            captain.y = c.getDouble("y");
            captain.z = c.getDouble("z");
            captain.restYaw = c.getFloat("restYaw");
            captain.enabled = !c.contains("enabled") || c.getBoolean("enabled");
            data.captains.put(id, captain);
        }
        for (Direction d : Direction.values()) {
            readPoint(tag, "dest" + d.name(), data.destination(d));
        }
        readPoint(tag, "back", data.returnPoint);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Captain captain : captains.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", captain.id);
            c.putString("name", captain.name == null ? "" : captain.name);
            c.putString("mode", captain.mode.name());
            c.putString("skin", captain.skinValue == null ? "" : captain.skinValue);
            c.putString("skinSig", captain.skinSignature == null ? "" : captain.skinSignature);
            c.putString("dim", captain.dim == null ? "" : captain.dim);
            c.putDouble("x", captain.x);
            c.putDouble("y", captain.y);
            c.putDouble("z", captain.z);
            c.putFloat("restYaw", captain.restYaw);
            c.putBoolean("enabled", captain.enabled);
            list.add(c);
        }
        tag.put("captains", list);
        for (Direction d : Direction.values()) {
            writePoint(tag, "dest" + d.name(), destination(d));
        }
        writePoint(tag, "back", returnPoint);
        return tag;
    }

    /** Noms des capitaines, pour les menus. */
    public List<String> ids() {
        return new ArrayList<>(captains.keySet());
    }
}
