package com.utopia.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes des recompenses quotidiennes : pour chaque joueur, le DERNIER JOUR reclame
 * (en jour epoch, voir {@link java.time.LocalDate#toEpochDay()}) et la serie en cours.
 * Stocke dans l'overworld ({@code data/utopia_daily.dat}).
 */
public final class DailyData extends SavedData {
    private static final String ID = "utopia_daily";

    public static final SavedData.Factory<DailyData> FACTORY =
            new SavedData.Factory<>(DailyData::new, DailyData::load, null);

    /** Au-dela de cet age (en jours), on oublie un jour reclame (borne la taille de l'historique). */
    private static final long HISTORY_WINDOW_DAYS = 70;

    /** Etat d'un joueur. */
    public static final class Entry {
        /** Jour epoch de la derniere reclamation (0 = jamais / reinitialise). */
        public long lastClaimDay;
        public int streak;
        /** Jours epoch effectivement reclames (historique borne), pour l'affichage du calendrier. */
        public final Set<Long> claimedDays = new HashSet<>();

        Entry(long lastClaimDay, int streak) {
            this.lastClaimDay = lastClaimDay;
            this.streak = streak;
        }
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    public DailyData() {
    }

    public static DailyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Renvoie l'etat du joueur (peut etre nul s'il n'a jamais reclame). */
    public Entry getEntry(UUID playerId) {
        return entries.get(playerId);
    }

    /** Met a jour (ou cree) l'etat d'un joueur et marque les donnees comme modifiees. */
    public void setEntry(UUID playerId, long lastClaimDay, int streak) {
        Entry e = entries.computeIfAbsent(playerId, k -> new Entry(lastClaimDay, streak));
        e.lastClaimDay = lastClaimDay;
        e.streak = streak;
        setDirty();
    }

    /** Enregistre un jour comme reclame (pour l'affichage du calendrier) et purge l'historique ancien. */
    public void markClaimed(UUID playerId, long epochDay) {
        Entry e = entries.computeIfAbsent(playerId, k -> new Entry(epochDay, 0));
        e.claimedDays.add(epochDay);
        e.claimedDays.removeIf(d -> d < epochDay - HISTORY_WINDOW_DAYS);
        setDirty();
    }

    /** Ce jour epoch a-t-il ete reclame par ce joueur (dans la fenetre d'historique) ? */
    public boolean hasClaimed(UUID playerId, long epochDay) {
        Entry e = entries.get(playerId);
        return e != null && e.claimedDays.contains(epochDay);
    }

    /** Reinitialisation complete d'un joueur (reclamation, serie et historique). */
    public void resetEntry(UUID playerId) {
        Entry e = entries.get(playerId);
        if (e != null) {
            e.lastClaimDay = 0L;
            e.streak = 0;
            e.claimedDays.clear();
            setDirty();
        }
    }

    public static DailyData load(CompoundTag tag, HolderLookup.Provider registries) {
        DailyData data = new DailyData();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            try {
                UUID id = UUID.fromString(entryTag.getString("uuid"));
                Entry entry = new Entry(entryTag.getLong("lastClaimDay"), entryTag.getInt("streak"));
                for (long d : entryTag.getLongArray("claimed")) {
                    entry.claimedDays.add(d);
                }
                data.entries.put(id, entry);
            } catch (IllegalArgumentException ignored) {
                // entree corrompue : on l'ignore
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("uuid", e.getKey().toString());
            entryTag.putLong("lastClaimDay", e.getValue().lastClaimDay);
            entryTag.putInt("streak", e.getValue().streak);
            entryTag.putLongArray("claimed", e.getValue().claimedDays.stream().mapToLong(Long::longValue).toArray());
            list.add(entryTag);
        }
        tag.put("players", list);
        return tag;
    }
}
