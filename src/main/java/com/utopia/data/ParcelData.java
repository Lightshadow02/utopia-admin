package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.parcel.Parcel;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes des parcelles (toutes dimensions confondues).
 * Stocke dans l'overworld ({@code data/utopia_parcels.dat}).
 */
public final class ParcelData extends SavedData {
    private static final String ID = "utopia_parcels";

    public static final SavedData.Factory<ParcelData> FACTORY =
            new SavedData.Factory<>(ParcelData::new, ParcelData::load, null);

    private final Map<String, Parcel> parcels = new LinkedHashMap<>();

    public ParcelData() {
    }

    public static ParcelData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Collection<Parcel> all() {
        return parcels.values();
    }

    public Parcel get(String id) {
        return parcels.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean exists(String id) {
        return parcels.containsKey(id.toLowerCase(java.util.Locale.ROOT));
    }

    public void put(Parcel parcel) {
        parcels.put(parcel.id().toLowerCase(java.util.Locale.ROOT), parcel);
        setDirty();
    }

    public void remove(String id) {
        if (parcels.remove(id.toLowerCase(java.util.Locale.ROOT)) != null) {
            setDirty();
        }
    }

    /** Parcelle contenant cette position, ou nul. */
    public Parcel parcelAt(ResourceLocation dim, int x, int y, int z) {
        for (Parcel p : parcels.values()) {
            if (p.contains(dim, x, y, z)) {
                return p;
            }
        }
        return null;
    }

    /** Parcelles dont une boite chevauche la boite donnee (pour avertir d'un chevauchement). */
    public List<Parcel> overlapping(ResourceLocation dim, Parcel.Box box, String ignoreId) {
        List<Parcel> result = new ArrayList<>();
        for (Parcel p : parcels.values()) {
            if (!p.dimension().equals(dim) || p.id().equalsIgnoreCase(ignoreId)) {
                continue;
            }
            boolean hit = false;
            for (Parcel.Box b : p.boxes()) {
                if (b.intersects(box)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                for (Parcel.Poly poly : p.polys()) {
                    if (poly.bounds().intersects(box)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                result.add(p);
            }
        }
        return result;
    }

    public List<Parcel> ownedBy(UUID owner) {
        List<Parcel> result = new ArrayList<>();
        for (Parcel p : parcels.values()) {
            if (p.isOwner(owner)) {
                result.add(p);
            }
        }
        return result;
    }

    public List<Parcel> forSale() {
        List<Parcel> result = new ArrayList<>();
        for (Parcel p : parcels.values()) {
            if (p.forSale()) {
                result.add(p);
            }
        }
        return result;
    }

    // -------- Serialisation --------

    public static ParcelData load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelData data = new ParcelData();
        ListTag list = tag.getList("parcels", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag pt = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(pt.getString("dim"));
            if (dim == null) {
                continue;
            }
            Parcel parcel = new Parcel(pt.getString("id"), pt.getString("name"), dim);
            parcel.setPrice(pt.getLong("price"));
            parcel.setForSale(pt.getBoolean("forSale"));
            if (pt.contains("owner")) {
                try {
                    parcel.setOwner(UUID.fromString(pt.getString("owner")), pt.getString("ownerName"));
                } catch (IllegalArgumentException ignored) {
                    // owner corrompu
                }
            }
            ListTag boxes = pt.getList("boxes", Tag.TAG_COMPOUND);
            for (int b = 0; b < boxes.size(); b++) {
                CompoundTag bt = boxes.getCompound(b);
                parcel.addBox(new Parcel.Box(bt.getInt("x1"), bt.getInt("y1"), bt.getInt("z1"),
                        bt.getInt("x2"), bt.getInt("y2"), bt.getInt("z2")));
            }
            ListTag polys = pt.getList("polys", Tag.TAG_COMPOUND);
            for (int b = 0; b < polys.size(); b++) {
                CompoundTag pp = polys.getCompound(b);
                int[] xs = pp.getIntArray("xs");
                int[] zs = pp.getIntArray("zs");
                if (xs.length >= 3 && xs.length == zs.length) {
                    parcel.addPoly(new Parcel.Poly(xs, zs, pp.getInt("minY"), pp.getInt("maxY")));
                }
            }
            ListTag members = pt.getList("members", Tag.TAG_COMPOUND);
            for (int m = 0; m < members.size(); m++) {
                CompoundTag mt = members.getCompound(m);
                try {
                    parcel.setMember(UUID.fromString(mt.getString("uuid")), Parcel.Flag.fromMask(mt.getInt("flags")));
                } catch (IllegalArgumentException ignored) {
                    // membre corrompu
                }
            }
            data.parcels.put(parcel.id().toLowerCase(java.util.Locale.ROOT), parcel);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Parcel p : parcels.values()) {
            CompoundTag pt = new CompoundTag();
            pt.putString("id", p.id());
            pt.putString("name", p.name());
            pt.putString("dim", p.dimension().toString());
            pt.putLong("price", p.price());
            pt.putBoolean("forSale", p.forSale());
            if (p.owner() != null) {
                pt.putString("owner", p.owner().toString());
                pt.putString("ownerName", p.ownerName() == null ? "" : p.ownerName());
            }
            ListTag boxes = new ListTag();
            for (Parcel.Box b : p.boxes()) {
                CompoundTag bt = new CompoundTag();
                bt.putInt("x1", b.minX());
                bt.putInt("y1", b.minY());
                bt.putInt("z1", b.minZ());
                bt.putInt("x2", b.maxX());
                bt.putInt("y2", b.maxY());
                bt.putInt("z2", b.maxZ());
                boxes.add(bt);
            }
            pt.put("boxes", boxes);
            ListTag polys = new ListTag();
            for (Parcel.Poly poly : p.polys()) {
                CompoundTag pp = new CompoundTag();
                pp.putIntArray("xs", poly.xs());
                pp.putIntArray("zs", poly.zs());
                pp.putInt("minY", poly.minY());
                pp.putInt("maxY", poly.maxY());
                polys.add(pp);
            }
            pt.put("polys", polys);
            ListTag members = new ListTag();
            for (Map.Entry<UUID, EnumSet<Parcel.Flag>> e : p.members().entrySet()) {
                CompoundTag mt = new CompoundTag();
                mt.putString("uuid", e.getKey().toString());
                mt.putInt("flags", Parcel.Flag.mask(e.getValue()));
                members.add(mt);
            }
            pt.put("members", members);
            list.add(pt);
        }
        tag.put("parcels", list);
        return tag;
    }
}
