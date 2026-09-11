package com.utopia.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Compteurs du classement en cours : les points gagnes depuis la derniere cloture, et rien d'autre.
 * Separe de {@link MairieData} parce que ce fichier est reecrit a chaque mob tue, tandis que les
 * reglements du maire ne bougent qu'a la main.
 */
public final class LeaderboardData extends SavedData {

    private static final String ID = "utopia_leaderboard";

    public static final SavedData.Factory<LeaderboardData> FACTORY =
            new SavedData.Factory<>(LeaderboardData::new, LeaderboardData::load, null);

    /** Une ligne du classement, deja triee par l'appelant. */
    public record Entry(UUID player, String name, long points, int kills) {
    }

    /**
     * Instant (seconde epoch) de la prochaine cloture. C'est une <b>echeance</b>, pas un numero de
     * jour : une echeance ne bouge pas quand le maire change l'heure de cloture, alors qu'un numero
     * de jour calcule a partir de cette heure changeait retroactivement de valeur et faisait solder
     * le concours sur-le-champ.
     */
    private long nextClose;
    private final Map<UUID, Long> points = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public LeaderboardData() {
    }

    public static LeaderboardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public long nextClose() {
        return nextClose;
    }

    /** Faux tant qu'aucune journee n'a ete ouverte : evite de solder un classement jamais commence. */
    public boolean initialized() {
        return nextClose != 0L;
    }

    /** Ouvre une nouvelle journee : les compteurs repartent de zero. */
    public void startPeriod(long closeAt) {
        this.nextClose = closeAt;
        points.clear();
        kills.clear();
        names.clear();
        setDirty();
    }

    /**
     * Deplace l'echeance sans toucher aux compteurs : le maire a change l'heure de cloture en cours
     * de journee, les prises deja faites restent acquises.
     */
    public void setNextClose(long closeAt) {
        this.nextClose = closeAt;
        setDirty();
    }

    /** Referme le concours : plus d'echeance, plus de points. */
    public void clear() {
        startPeriod(0L);
    }

    public void add(UUID player, String name, long gained) {
        if (gained <= 0) {
            return;
        }
        points.merge(player, gained, Long::sum);
        kills.merge(player, 1, Integer::sum);
        if (name != null && !name.isBlank()) {
            names.put(player, name);
        }
        setDirty();
    }

    public long pointsOf(UUID player) {
        return points.getOrDefault(player, 0L);
    }

    public int killsOf(UUID player) {
        return kills.getOrDefault(player, 0);
    }

    public int participants() {
        return points.size();
    }

    /**
     * Le classement du moment, du meilleur au moins bon. A points egaux, celui qui a tue le moins de
     * mobs passe devant : il a vise les prises qui rapportent plutot que le nombre.
     */
    public List<Entry> standings() {
        List<Entry> out = new ArrayList<>(points.size());
        for (Map.Entry<UUID, Long> e : points.entrySet()) {
            out.add(new Entry(e.getKey(), names.getOrDefault(e.getKey(), ""),
                    e.getValue(), kills.getOrDefault(e.getKey(), 0)));
        }
        out.sort(Comparator.comparingLong(Entry::points).reversed()
                .thenComparingInt(Entry::kills)
                .thenComparing(e -> e.player().toString()));
        return out;
    }

    /** Rang du joueur dans le classement courant (1 = premier), 0 s'il n'y figure pas. */
    public int rankOf(UUID player) {
        if (!points.containsKey(player)) {
            return 0;
        }
        List<Entry> all = standings();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(player)) {
                return i + 1;
            }
        }
        return 0;
    }

    // -------- Serialisation --------

    public static LeaderboardData load(CompoundTag tag, HolderLookup.Provider registries) {
        LeaderboardData data = new LeaderboardData();
        data.nextClose = tag.getLong("nextClose");
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                UUID id = UUID.fromString(e.getString("uuid"));
                data.points.put(id, e.getLong("points"));
                data.kills.put(id, e.getInt("kills"));
                String name = e.getString("name");
                if (!name.isEmpty()) {
                    data.names.put(id, name);
                }
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu : la ligne est perdue, le classement reste utilisable
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("nextClose", nextClose);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : points.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", e.getKey().toString());
            c.putLong("points", e.getValue());
            c.putInt("kills", kills.getOrDefault(e.getKey(), 0));
            c.putString("name", names.getOrDefault(e.getKey(), ""));
            list.add(c);
        }
        tag.put("entries", list);
        return tag;
    }
}
