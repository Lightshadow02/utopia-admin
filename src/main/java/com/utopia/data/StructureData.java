package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Structures a etats : une zone du monde dont le serveur memorise deux "schematiques" (etat 1 et
 * etat 2) qu'il peut reposer a la demande, manuellement ou automatiquement (jour / nuit).
 *
 * <p>Les schematiques sont des {@code StructureTemplate} vanilla serialises (palette + block
 * entities), donc compacts et fideles.
 */
public final class StructureData extends SavedData {

    private static final String ID = "utopia_structures";

    public static final SavedData.Factory<StructureData> FACTORY =
            new SavedData.Factory<>(StructureData::new, StructureData::load, null);

    /** Une structure : sa zone, ses deux etats et son mode de bascule. */
    public static final class Struct {
        public final String name;
        public String dim;
        public BlockPos min;   // coin minimal de la zone
        public Vec3i size;     // dimensions (x, y, z)
        public CompoundTag stateA;  // schematique de l'etat 1 (null = pas encore capture)
        public CompoundTag stateB;  // schematique de l'etat 2
        public boolean auto;        // true = bascule automatique jour/nuit
        public int current = 1;     // etat actuellement pose (1 ou 2)

        public Struct(String name) {
            this.name = name;
        }

        public boolean hasState(int slot) {
            return (slot == 2 ? stateB : stateA) != null;
        }

        public CompoundTag state(int slot) {
            return slot == 2 ? stateB : stateA;
        }

        public void setState(int slot, CompoundTag tag) {
            if (slot == 2) {
                stateB = tag;
            } else {
                stateA = tag;
            }
        }

        /** Volume de la zone en blocs. */
        public long volume() {
            return size == null ? 0L : (long) size.getX() * size.getY() * size.getZ();
        }
    }

    private final Map<String, Struct> structures = new LinkedHashMap<>();

    public StructureData() {
    }

    public static StructureData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Collection<Struct> all() {
        return structures.values();
    }

    public Struct get(String name) {
        return structures.get(key(name));
    }

    public boolean exists(String name) {
        return structures.containsKey(key(name));
    }

    public void put(Struct struct) {
        structures.put(key(struct.name), struct);
        setDirty();
    }

    public boolean remove(String name) {
        if (structures.remove(key(name)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // -------- Serialisation --------

    public static StructureData load(CompoundTag tag, HolderLookup.Provider registries) {
        StructureData data = new StructureData();
        ListTag list = tag.getList("structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            String name = s.getString("name");
            if (name.isEmpty()) {
                continue;
            }
            Struct st = new Struct(name);
            st.dim = s.getString("dim");
            st.min = new BlockPos(s.getInt("x"), s.getInt("y"), s.getInt("z"));
            st.size = new Vec3i(s.getInt("sx"), s.getInt("sy"), s.getInt("sz"));
            if (s.contains("stateA", Tag.TAG_COMPOUND)) {
                st.stateA = s.getCompound("stateA");
            }
            if (s.contains("stateB", Tag.TAG_COMPOUND)) {
                st.stateB = s.getCompound("stateB");
            }
            st.auto = s.getBoolean("auto");
            st.current = s.getInt("current") == 2 ? 2 : 1;
            data.structures.put(key(name), st);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Struct st : structures.values()) {
            CompoundTag s = new CompoundTag();
            s.putString("name", st.name);
            s.putString("dim", st.dim == null ? "" : st.dim);
            s.putInt("x", st.min.getX());
            s.putInt("y", st.min.getY());
            s.putInt("z", st.min.getZ());
            s.putInt("sx", st.size.getX());
            s.putInt("sy", st.size.getY());
            s.putInt("sz", st.size.getZ());
            if (st.stateA != null) {
                s.put("stateA", st.stateA);
            }
            if (st.stateB != null) {
                s.put("stateB", st.stateB);
            }
            s.putBoolean("auto", st.auto);
            s.putInt("current", st.current);
            list.add(s);
        }
        tag.put("structures", list);
        return tag;
    }

    /** Liste des noms, pour les menus. */
    public List<String> names() {
        return new ArrayList<>(structures.keySet());
    }
}
