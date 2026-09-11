package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * La salle d'arcade : les bornes posees dans le monde, leurs reglages, et le tableau des scores.
 *
 * <p>Les scores sont tenus <b>par jeu et non par borne</b> : toutes les bornes d'un meme jeu
 * alimentent le meme classement, sinon deplacer une borne effacerait des records et poser deux
 * bornes de Snake diviserait les joueurs en deux palmares sans rapport.
 */
public final class CasinoData extends SavedData {

    private static final String ID = "utopia_casino";

    public static final SavedData.Factory<CasinoData> FACTORY =
            new SavedData.Factory<>(CasinoData::new, CasinoData::load, null);

    /** Scores gardes par jeu : au-dela, le tableau ne se lit plus et le fichier gonfle. */
    public static final int MAX_SCORES = 25;

    /** Une borne posee dans le monde : un bloc, un jeu, un prix. */
    public static final class Machine {
        public final String dim;
        public final int x;
        public final int y;
        public final int z;
        public String gameId;
        /** Nom affiche sur la borne ; vide = le nom du jeu. */
        public String label = "";
        /** Utopieces exigees par partie. 0 = gratuit. */
        public long cost = 1;

        public Machine(String dim, int x, int y, int z, String gameId) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.gameId = gameId;
        }

        public String key() {
            return dim + "@" + x + "," + y + "," + z;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Une ligne du tableau des scores d'un jeu. */
    public record Score(UUID player, String name, long score, long millis) {
    }

    private final Map<String, Machine> machines = new LinkedHashMap<>();
    /** Identifiant de jeu -> meilleurs scores, du plus fort au plus faible. */
    private final Map<String, List<Score>> scores = new LinkedHashMap<>();
    /** Gerants : ils ouvrent /casino sans etre operateurs. */
    private final java.util.Set<UUID> managers = new java.util.LinkedHashSet<>();

    public CasinoData() {
    }

    public static CasinoData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // -------- Gerants --------

    public boolean isManager(UUID id) {
        return managers.contains(id);
    }

    public Collection<UUID> managers() {
        return java.util.Collections.unmodifiableCollection(managers);
    }

    /** Bascule le statut de gerant ; renvoie vrai s'il vient d'etre accorde. */
    public boolean toggleManager(UUID id) {
        boolean added;
        if (managers.contains(id)) {
            managers.remove(id);
            added = false;
        } else {
            managers.add(id);
            added = true;
        }
        setDirty();
        return added;
    }

    // -------- Bornes --------

    public Collection<Machine> machines() {
        return machines.values();
    }

    public Machine machineAt(ResourceLocation dim, BlockPos pos) {
        return machines.get(dim + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    public Machine machineByKey(String key) {
        return machines.get(key);
    }

    public Machine addMachine(ResourceLocation dim, BlockPos pos, String gameId) {
        Machine m = new Machine(dim.toString(), pos.getX(), pos.getY(), pos.getZ(), gameId);
        machines.put(m.key(), m);
        setDirty();
        return m;
    }

    public boolean removeMachine(String key) {
        boolean removed = machines.remove(key) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public int countFor(String gameId) {
        int n = 0;
        for (Machine m : machines.values()) {
            if (m.gameId.equals(gameId)) {
                n++;
            }
        }
        return n;
    }

    // -------- Scores --------

    public List<Score> scores(String gameId) {
        return java.util.Collections.unmodifiableList(scores.getOrDefault(gameId, List.of()));
    }

    public Score best(String gameId) {
        List<Score> list = scores.get(gameId);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * Inscrit un score. Un joueur n'occupe qu'une ligne par jeu : son meilleur. Un tableau ou le
     * meme nom tient les dix premieres places n'apprend rien a personne.
     *
     * <p>Renvoie le rang obtenu (1 = premier), ou 0 si le score n'entre pas au tableau.
     */
    public int submit(String gameId, UUID player, String name, long score, long millis) {
        if (score <= 0) {
            return 0;
        }
        List<Score> list = new ArrayList<>(scores.getOrDefault(gameId, List.of()));
        Score previous = null;
        for (Score s : list) {
            if (s.player().equals(player)) {
                previous = s;
                break;
            }
        }
        if (previous != null) {
            if (previous.score() >= score) {
                return 0; // il a deja fait mieux : on ne remplace pas
            }
            list.remove(previous);
        }
        list.add(new Score(player, name, score, millis));
        list.sort(Comparator.comparingLong(Score::score).reversed()
                .thenComparingLong(Score::millis));
        while (list.size() > MAX_SCORES) {
            list.remove(list.size() - 1);
        }
        scores.put(gameId, list);
        setDirty();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).player().equals(player)) {
                return i + 1;
            }
        }
        return 0;
    }

    public void clearScores(String gameId) {
        if (scores.remove(gameId) != null) {
            setDirty();
        }
    }

    // -------- Serialisation --------

    public static CasinoData load(CompoundTag tag, HolderLookup.Provider registries) {
        CasinoData data = new CasinoData();
        ListTag list = tag.getList("machines", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag m = list.getCompound(i);
            Machine machine = new Machine(m.getString("dim"), m.getInt("x"), m.getInt("y"),
                    m.getInt("z"), m.getString("game"));
            machine.label = m.getString("label");
            machine.cost = m.getLong("cost");
            data.machines.put(machine.key(), machine);
        }
        CompoundTag boards = tag.getCompound("scores");
        for (String gameId : boards.getAllKeys()) {
            List<Score> rows = new ArrayList<>();
            ListTag entries = boards.getList(gameId, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag e = entries.getCompound(i);
                try {
                    rows.add(new Score(UUID.fromString(e.getString("uuid")), e.getString("name"),
                            e.getLong("score"), e.getLong("at")));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : la ligne est perdue, le tableau reste lisible
                }
            }
            data.scores.put(gameId, rows);
        }
        ListTag mgr = tag.getList("managers", Tag.TAG_STRING);
        for (int i = 0; i < mgr.size(); i++) {
            try {
                data.managers.add(UUID.fromString(mgr.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Machine m : machines.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("dim", m.dim);
            c.putInt("x", m.x);
            c.putInt("y", m.y);
            c.putInt("z", m.z);
            c.putString("game", m.gameId);
            c.putString("label", m.label == null ? "" : m.label);
            c.putLong("cost", m.cost);
            list.add(c);
        }
        tag.put("machines", list);

        CompoundTag boards = new CompoundTag();
        for (Map.Entry<String, List<Score>> e : scores.entrySet()) {
            ListTag entries = new ListTag();
            for (Score s : e.getValue()) {
                CompoundTag c = new CompoundTag();
                c.putString("uuid", s.player().toString());
                c.putString("name", s.name() == null ? "" : s.name());
                c.putLong("score", s.score());
                c.putLong("at", s.millis());
                entries.add(c);
            }
            boards.put(e.getKey(), entries);
        }
        tag.put("scores", boards);

        ListTag mgr = new ListTag();
        for (UUID id : managers) {
            mgr.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("managers", mgr);
        return tag;
    }
}
