package com.utopia.data;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.utopia.parcel.Parcel;
import com.utopia.room.Room;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Donnees persistantes des chambres d'auberge (toutes dimensions). Stocke dans l'overworld. */
public final class RoomData extends SavedData {
    private static final String ID = "utopia_rooms";

    public static final SavedData.Factory<RoomData> FACTORY =
            new SavedData.Factory<>(RoomData::new, RoomData::load, null);

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Set<UUID> aubergistes = new HashSet<>();

    public RoomData() {
    }

    // -------- Aubergistes (joueurs autorises a ouvrir /auberge, designes par un op) --------

    public boolean isAubergiste(UUID id) {
        return aubergistes.contains(id);
    }

    public Collection<UUID> aubergistes() {
        return aubergistes;
    }

    /** Ajoute/retire le statut d'aubergiste ; renvoie le nouvel etat (true = aubergiste). */
    public boolean toggleAubergiste(UUID id) {
        boolean now;
        if (aubergistes.contains(id)) {
            aubergistes.remove(id);
            now = false;
        } else {
            aubergistes.add(id);
            now = true;
        }
        setDirty();
        return now;
    }

    public static RoomData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    public Room get(String id) {
        return rooms.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String id) {
        return rooms.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public void put(Room room) {
        rooms.put(room.id().toLowerCase(Locale.ROOT), room);
        setDirty();
    }

    public void remove(String id) {
        if (rooms.remove(id.toLowerCase(Locale.ROOT)) != null) {
            setDirty();
        }
    }

    /** Chambre contenant cette position, ou nul. */
    public Room roomAt(ResourceLocation dim, int x, int y, int z) {
        for (Room r : rooms.values()) {
            if (r.contains(dim, x, y, z)) {
                return r;
            }
        }
        return null;
    }

    // -------- Serialisation --------

    public static RoomData load(CompoundTag tag, HolderLookup.Provider registries) {
        RoomData data = new RoomData();
        ListTag list = tag.getList("rooms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag rt = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(rt.getString("dim"));
            if (dim == null) {
                continue;
            }
            Parcel.Box box = new Parcel.Box(rt.getInt("x1"), rt.getInt("y1"), rt.getInt("z1"),
                    rt.getInt("x2"), rt.getInt("y2"), rt.getInt("z2"));
            Room room = new Room(rt.getString("id"), rt.getString("name"), dim, box);
            room.setPricePerDay(rt.getLong("pricePerDay"));
            room.setDays(rt.getInt("days"));
            room.setFrozen(rt.getBoolean("frozen"));
            if (rt.contains("occupant")) {
                try {
                    room.setOccupant(UUID.fromString(rt.getString("occupant")), rt.getString("occupantName"));
                } catch (IllegalArgumentException ignored) {
                    // occupant corrompu
                }
            }
            data.rooms.put(room.id().toLowerCase(Locale.ROOT), room);
        }
        ListTag aub = tag.getList("aubergistes", Tag.TAG_STRING);
        for (int i = 0; i < aub.size(); i++) {
            try {
                data.aubergistes.add(UUID.fromString(aub.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Room r : rooms.values()) {
            CompoundTag rt = new CompoundTag();
            rt.putString("id", r.id());
            rt.putString("name", r.name());
            rt.putString("dim", r.dimension().toString());
            Parcel.Box b = r.box();
            rt.putInt("x1", b.minX());
            rt.putInt("y1", b.minY());
            rt.putInt("z1", b.minZ());
            rt.putInt("x2", b.maxX());
            rt.putInt("y2", b.maxY());
            rt.putInt("z2", b.maxZ());
            rt.putLong("pricePerDay", r.pricePerDay());
            rt.putInt("days", r.days());
            rt.putBoolean("frozen", r.frozen());
            if (r.occupant() != null) {
                rt.putString("occupant", r.occupant().toString());
                rt.putString("occupantName", r.occupantName() == null ? "" : r.occupantName());
            }
            list.add(rt);
        }
        tag.put("rooms", list);
        ListTag aub = new ListTag();
        for (UUID id : aubergistes) {
            aub.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("aubergistes", aub);
        return tag;
    }
}
