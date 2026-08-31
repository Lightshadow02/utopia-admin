package com.utopia.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Reseau des balises de voyage : chaque balise posee dans le monde, et ce que chaque joueur a
 * decouvert.
 *
 * <p>Une balise n'est pas un point de teleportation ouvert a tous : il faut l'avoir touchee une fois
 * pour pouvoir y revenir. C'est ce qui fait du reseau une recompense d'exploration plutot qu'une
 * carte offerte.
 */
public final class WaystoneData extends SavedData {

    private static final String ID = "utopia_waystones";

    public static final SavedData.Factory<WaystoneData> FACTORY =
            new SavedData.Factory<>(WaystoneData::new, WaystoneData::load, null);

    /** Une balise posee. */
    public static final class Waystone {
        public final String id;          // "dimension@x,y,z" : la position EST l'identite
        public String name;
        public final String dim;
        public final int x;
        public final int y;
        public final int z;
        public UUID owner;               // null = posee par l'administration
        public String ownerName = "";
        /** Une balise publique est connue de tous sans avoir a la trouver. */
        public boolean global;

        public Waystone(String id, String name, String dim, int x, int y, int z) {
            this.id = id;
            this.name = name;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private final Map<String, Waystone> stones = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> discovered = new LinkedHashMap<>();

    public WaystoneData() {
    }

    public static WaystoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** L'identite d'une balise est sa position : deux balises ne peuvent pas se superposer. */
    public static String key(String dim, BlockPos pos) {
        return dim + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public Waystone get(String id) {
        return id == null ? null : stones.get(id);
    }

    public Waystone at(String dim, BlockPos pos) {
        return stones.get(key(dim, pos));
    }

    public Waystone put(Waystone stone) {
        stones.put(stone.id, stone);
        setDirty();
        return stone;
    }

    public boolean remove(String id) {
        if (stones.remove(id) == null) {
            return false;
        }
        // Personne ne garde le souvenir d'une balise qui n'existe plus.
        for (Set<String> known : discovered.values()) {
            known.remove(id);
        }
        setDirty();
        return true;
    }

    public List<Waystone> all() {
        List<Waystone> out = new ArrayList<>(stones.values());
        out.sort(Comparator.comparing(w -> w.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Balises accessibles a ce joueur : les publiques, plus celles qu'il a trouvees. */
    public List<Waystone> availableTo(UUID player) {
        Set<String> known = discovered.getOrDefault(player, Set.of());
        List<Waystone> out = new ArrayList<>();
        for (Waystone w : all()) {
            if (w.global || known.contains(w.id)) {
                out.add(w);
            }
        }
        return out;
    }

    public boolean knows(UUID player, String id) {
        Waystone stone = stones.get(id);
        return stone != null && (stone.global || discovered.getOrDefault(player, Set.of()).contains(id));
    }

    /** Marque une balise comme trouvee ; renvoie true si c'est une decouverte. */
    public boolean discover(UUID player, String id) {
        if (!stones.containsKey(id)) {
            return false;
        }
        boolean added = discovered.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(id);
        if (added) {
            setDirty();
        }
        return added;
    }

    public int discoveredCount(UUID player) {
        return discovered.getOrDefault(player, Set.of()).size();
    }

    // -------- Serialisation --------

    public static WaystoneData load(CompoundTag tag, HolderLookup.Provider registries) {
        WaystoneData data = new WaystoneData();
        ListTag list = tag.getList("stones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag w = list.getCompound(i);
            String id = w.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Waystone stone = new Waystone(id, w.getString("name"), w.getString("dim"),
                    w.getInt("x"), w.getInt("y"), w.getInt("z"));
            if (w.contains("owner")) {
                try {
                    stone.owner = UUID.fromString(w.getString("owner"));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : la balise devient administrative
                }
            }
            stone.ownerName = w.getString("ownerName");
            stone.global = w.getBoolean("global");
            data.stones.put(id, stone);
        }
        ListTag players = tag.getList("discovered", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag p = players.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(p.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag known = p.getList("ids", Tag.TAG_STRING);
            Set<String> set = new LinkedHashSet<>();
            for (int k = 0; k < known.size(); k++) {
                set.add(known.getString(k));
            }
            if (!set.isEmpty()) {
                data.discovered.put(id, set);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Waystone stone : stones.values()) {
            CompoundTag w = new CompoundTag();
            w.putString("id", stone.id);
            w.putString("name", stone.name);
            w.putString("dim", stone.dim);
            w.putInt("x", stone.x);
            w.putInt("y", stone.y);
            w.putInt("z", stone.z);
            if (stone.owner != null) {
                w.putString("owner", stone.owner.toString());
            }
            w.putString("ownerName", stone.ownerName);
            w.putBoolean("global", stone.global);
            list.add(w);
        }
        tag.put("stones", list);

        ListTag players = new ListTag();
        for (Map.Entry<UUID, Set<String>> e : discovered.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.putString("uuid", e.getKey().toString());
            ListTag known = new ListTag();
            for (String id : e.getValue()) {
                known.add(StringTag.valueOf(id));
            }
            p.put("ids", known);
            players.add(p);
        }
        tag.put("discovered", players);
        return tag;
    }
}
