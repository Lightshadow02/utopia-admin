package com.utopia.data;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes du marche public : emplacements de vente (stands) physiques, leurs offres,
 * et le "coffre de recuperation" virtuel de la mairie (objets expires en attente de restitution).
 *
 * <p>Le compte de la mairie ({@link #MAIRIE_UUID}) recoit la taxe (25 %) ; il apparait dans /baltop.
 */
public final class MarketData extends SavedData {

    private static final String ID = "utopia_market";

    /** Compte virtuel de la mairie (credite de la taxe de 25 %). */
    public static final UUID MAIRIE_UUID = UUID.nameUUIDFromBytes("utopia:mairie".getBytes(StandardCharsets.UTF_8));
    public static final String MAIRIE_NAME = "Mairie";

    public static final SavedData.Factory<MarketData> FACTORY =
            new SavedData.Factory<>(MarketData::new, MarketData::load, null);

    /** Une offre : une pile d'objets a vendre pour un prix total, avec une expiration (heure murale). */
    public static final class Offer {
        public ItemStack stack;
        public long price;
        public long expiryMillis;

        public Offer(ItemStack stack, long price, long expiryMillis) {
            this.stack = stack;
            this.price = price;
            this.expiryMillis = expiryMillis;
        }
    }

    /** Un emplacement de vente physique (position d'un bloc) ; peut etre libre ou possede par un joueur. */
    public static final class Stall {
        public final String dim;
        public final int x;
        public final int y;
        public final int z;
        public UUID owner;          // null = libre
        public String ownerName;
        public final List<Offer> offers = new ArrayList<>();

        public Stall(String dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean isFree() {
            return owner == null;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Un objet expire range dans la recuperation de la mairie (en attente de restitution). */
    public record RecoveryEntry(UUID owner, String ownerName, ItemStack stack, long expiryMillis) {
    }

    private final Map<String, Stall> stalls = new LinkedHashMap<>(); // cle "dim;x;y;z"
    private final List<RecoveryEntry> recovery = new ArrayList<>();
    private final Set<UUID> maires = new HashSet<>(); // joueurs autorises a /maire (designes par un op)

    public MarketData() {
    }

    // -------- Maire (joueur ayant acces au compte de la mairie via /maire) --------

    public boolean isMaire(UUID id) {
        return maires.contains(id);
    }

    public Collection<UUID> maires() {
        return maires;
    }

    /** Ajoute/retire le statut de maire ; renvoie le nouvel etat. */
    public boolean toggleMaire(UUID id) {
        boolean now;
        if (maires.contains(id)) {
            maires.remove(id);
            now = false;
        } else {
            maires.add(id);
            now = true;
        }
        setDirty();
        return now;
    }

    public static MarketData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String key(ResourceLocation dim, BlockPos pos) {
        return dim + ";" + pos.getX() + ";" + pos.getY() + ";" + pos.getZ();
    }

    // -------- Stands --------

    public Collection<Stall> stalls() {
        return stalls.values();
    }

    public Stall stallAt(ResourceLocation dim, BlockPos pos) {
        return stalls.get(key(dim, pos));
    }

    public boolean isStall(ResourceLocation dim, BlockPos pos) {
        return stalls.containsKey(key(dim, pos));
    }

    public void addStall(ResourceLocation dim, BlockPos pos) {
        String k = key(dim, pos);
        if (!stalls.containsKey(k)) {
            stalls.put(k, new Stall(dim.toString(), pos.getX(), pos.getY(), pos.getZ()));
            setDirty();
        }
    }

    public void removeStall(ResourceLocation dim, BlockPos pos) {
        if (stalls.remove(key(dim, pos)) != null) {
            setDirty();
        }
    }

    /** L'emplacement actuellement possede par ce joueur, ou null. */
    public Stall stallOf(UUID player) {
        for (Stall s : stalls.values()) {
            if (player.equals(s.owner)) {
                return s;
            }
        }
        return null;
    }

    public int freeStallCount() {
        int n = 0;
        for (Stall s : stalls.values()) {
            if (s.isFree()) {
                n++;
            }
        }
        return n;
    }

    // -------- Recuperation --------

    public List<RecoveryEntry> recovery() {
        return recovery;
    }

    public void addRecovery(RecoveryEntry entry) {
        recovery.add(entry);
        setDirty();
    }

    public void removeRecovery(RecoveryEntry entry) {
        if (recovery.remove(entry)) {
            setDirty();
        }
    }

    // -------- Serialisation --------

    public static MarketData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketData data = new MarketData();
        ListTag stallList = tag.getList("stalls", Tag.TAG_COMPOUND);
        for (int i = 0; i < stallList.size(); i++) {
            CompoundTag st = stallList.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(st.getString("dim"));
            if (dim == null) {
                continue;
            }
            BlockPos pos = new BlockPos(st.getInt("x"), st.getInt("y"), st.getInt("z"));
            Stall stall = new Stall(dim.toString(), pos.getX(), pos.getY(), pos.getZ());
            if (st.contains("owner")) {
                try {
                    stall.owner = UUID.fromString(st.getString("owner"));
                    stall.ownerName = st.getString("ownerName");
                } catch (IllegalArgumentException ignored) {
                    stall.owner = null;
                }
            }
            ListTag offers = st.getList("offers", Tag.TAG_COMPOUND);
            for (int j = 0; j < offers.size(); j++) {
                CompoundTag ot = offers.getCompound(j);
                ItemStack stack = ItemStack.parse(registries, ot.getCompound("stack")).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    stall.offers.add(new Offer(stack, ot.getLong("price"), ot.getLong("expiry")));
                }
            }
            data.stalls.put(key(dim, pos), stall);
        }
        ListTag rec = tag.getList("recovery", Tag.TAG_COMPOUND);
        for (int i = 0; i < rec.size(); i++) {
            CompoundTag rt = rec.getCompound(i);
            ItemStack stack = ItemStack.parse(registries, rt.getCompound("stack")).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                continue;
            }
            UUID owner;
            try {
                owner = UUID.fromString(rt.getString("owner"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            data.recovery.add(new RecoveryEntry(owner, rt.getString("ownerName"), stack, rt.getLong("expiry")));
        }
        ListTag maireList = tag.getList("maires", Tag.TAG_STRING);
        for (int i = 0; i < maireList.size(); i++) {
            try {
                data.maires.add(UUID.fromString(maireList.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag stallList = new ListTag();
        for (Stall s : stalls.values()) {
            CompoundTag st = new CompoundTag();
            st.putString("dim", s.dim);
            st.putInt("x", s.x);
            st.putInt("y", s.y);
            st.putInt("z", s.z);
            if (s.owner != null) {
                st.putString("owner", s.owner.toString());
                st.putString("ownerName", s.ownerName == null ? "" : s.ownerName);
            }
            ListTag offers = new ListTag();
            for (Offer o : s.offers) {
                CompoundTag ot = new CompoundTag();
                ot.put("stack", o.stack.save(registries));
                ot.putLong("price", o.price);
                ot.putLong("expiry", o.expiryMillis);
                offers.add(ot);
            }
            st.put("offers", offers);
            stallList.add(st);
        }
        tag.put("stalls", stallList);
        ListTag rec = new ListTag();
        for (RecoveryEntry e : recovery) {
            CompoundTag rt = new CompoundTag();
            rt.putString("owner", e.owner().toString());
            rt.putString("ownerName", e.ownerName() == null ? "" : e.ownerName());
            rt.put("stack", e.stack().save(registries));
            rt.putLong("expiry", e.expiryMillis());
            rec.add(rt);
        }
        tag.put("recovery", rec);
        ListTag maireList = new ListTag();
        for (UUID id : maires) {
            maireList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("maires", maireList);
        return tag;
    }
}
