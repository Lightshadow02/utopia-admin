package com.utopia.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Stockage persistant de deux inventaires par joueur (Inventaire 1 / Inventaire 2) et de celui
 * actuellement actif. Permet de basculer d'un inventaire a l'autre (ex. sauvegarder son inventaire
 * de survie avant de passer en creatif sans le perdre).
 */
public final class InventoryData extends SavedData {

    private static final String ID = "utopia_inventories";

    public static final SavedData.Factory<InventoryData> FACTORY =
            new SavedData.Factory<>(InventoryData::new, InventoryData::load, null);

    /** Les deux inventaires NBT d'un joueur + l'index actif. */
    private static final class Slots {
        int active = 1;
        ListTag inv1;
        ListTag inv2;
    }

    private final Map<UUID, Slots> players = new HashMap<>();
    private final java.util.Set<UUID> staff = new java.util.HashSet<>(); // joueurs autorises a /staff

    public InventoryData() {
    }

    public static InventoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // -------- Liste staff (acces a /staff) --------

    public boolean isStaff(UUID id) {
        return staff.contains(id);
    }

    public java.util.Collection<UUID> staff() {
        return staff;
    }

    /** Ajoute (true) ou retire (false) un joueur de la liste staff. Renvoie l'etat final. */
    public boolean setStaff(UUID id, boolean member) {
        if (member) {
            staff.add(id);
        } else {
            staff.remove(id);
        }
        setDirty();
        return member;
    }

    private Slots slots(UUID id) {
        return players.computeIfAbsent(id, k -> new Slots());
    }

    public int getActive(UUID id) {
        return slots(id).active;
    }

    public void setActive(UUID id, int slot) {
        slots(id).active = (slot == 2 ? 2 : 1);
        setDirty();
    }

    /** Contenu NBT du slot demande (1 ou 2), ou null s'il n'a jamais ete sauvegarde. */
    public ListTag getSlot(UUID id, int slot) {
        Slots s = slots(id);
        return slot == 2 ? s.inv2 : s.inv1;
    }

    public void setSlot(UUID id, int slot, ListTag tag) {
        Slots s = slots(id);
        if (slot == 2) {
            s.inv2 = tag;
        } else {
            s.inv1 = tag;
        }
        setDirty();
    }

    // -------- Serialisation --------

    public static InventoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        InventoryData data = new InventoryData();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(p.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Slots s = new Slots();
            s.active = p.getInt("active") == 2 ? 2 : 1;
            if (p.contains("inv1", Tag.TAG_LIST)) {
                s.inv1 = p.getList("inv1", Tag.TAG_COMPOUND);
            }
            if (p.contains("inv2", Tag.TAG_LIST)) {
                s.inv2 = p.getList("inv2", Tag.TAG_COMPOUND);
            }
            data.players.put(id, s);
        }
        ListTag staffList = tag.getList("staff", Tag.TAG_STRING);
        for (int i = 0; i < staffList.size(); i++) {
            try {
                data.staff.add(UUID.fromString(staffList.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Slots> e : players.entrySet()) {
            Slots s = e.getValue();
            CompoundTag p = new CompoundTag();
            p.putString("uuid", e.getKey().toString());
            p.putInt("active", s.active);
            if (s.inv1 != null) {
                p.put("inv1", s.inv1);
            }
            if (s.inv2 != null) {
                p.put("inv2", s.inv2);
            }
            list.add(p);
        }
        tag.put("players", list);
        ListTag staffList = new ListTag();
        for (UUID id : staff) {
            staffList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("staff", staffList);
        return tag;
    }
}
