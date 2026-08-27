package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes des metiers : la liste des metiers, les joueurs qui les exercent, le suivi des
 * salaires deja verses, les notifications en attente et un historique de moderation.
 *
 * <p>Les joueurs sont identifies par leur UUID : un changement de pseudo ne fait donc jamais perdre
 * un metier. Le dernier pseudo connu est memorise uniquement pour l'affichage.
 */
public final class JobData extends SavedData {

    private static final String ID = "utopia_jobs";
    /** Taille maximale de l'historique conserve (les entrees les plus anciennes sont oubliees). */
    private static final int HISTORY_MAX = 300;

    public static final SavedData.Factory<JobData> FACTORY =
            new SavedData.Factory<>(JobData::new, JobData::load, null);

    /** Un metier : un nom affiche et un salaire quotidien fixe, activable / desactivable. */
    public static final class Job {
        public final String id;      // cle interne (nom en minuscules au moment de la creation)
        public String name;          // nom affiche, modifiable
        public long salary;          // salaire quotidien en Utopieces
        public boolean enabled = true;

        public Job(String id, String name, long salary) {
            this.id = id;
            this.name = name;
            this.salary = salary;
        }
    }

    /** Une ligne d'historique : horodatage reel + texte deja formate. */
    public record HistoryEntry(long millis, String text) {
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> assignments = new HashMap<>();
    private final Map<UUID, Long> lastPaidDay = new HashMap<>();
    private final Map<UUID, List<String>> pending = new HashMap<>();
    private final Map<UUID, String> knownNames = new HashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Set<UUID> bankers = new HashSet<>();

    public JobData() {
    }

    public static JobData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // -------- Metiers --------

    public Collection<Job> jobs() {
        return jobs.values();
    }

    public Job job(String id) {
        return jobs.get(key(id));
    }

    public boolean jobExists(String name) {
        return jobs.containsKey(key(name));
    }

    public Job createJob(String name, long salary) {
        Job job = new Job(key(name), name.trim(), Math.max(0, salary));
        jobs.put(job.id, job);
        setDirty();
        return job;
    }

    public boolean removeJob(String id) {
        if (jobs.remove(key(id)) == null) {
            return false;
        }
        // Les joueurs qui l'exercaient ne gardent pas un metier fantome.
        for (Set<String> held : assignments.values()) {
            held.remove(key(id));
        }
        assignments.entrySet().removeIf(e -> e.getValue().isEmpty());
        setDirty();
        return true;
    }

    // -------- Affectations --------

    /** Metiers exerces par ce joueur (ids). */
    public Set<String> jobsOf(UUID player) {
        return assignments.getOrDefault(player, Set.of());
    }

    public boolean hasJob(UUID player, String id) {
        return jobsOf(player).contains(key(id));
    }

    public void assign(UUID player, String id) {
        assignments.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(key(id));
        setDirty();
    }

    public void unassign(UUID player, String id) {
        Set<String> held = assignments.get(player);
        if (held != null && held.remove(key(id))) {
            if (held.isEmpty()) {
                assignments.remove(player);
            }
            setDirty();
        }
    }

    /** Tous les joueurs exercant au moins un metier. */
    public Set<UUID> employees() {
        return new LinkedHashSet<>(assignments.keySet());
    }

    /** Joueurs exercant ce metier. */
    public List<UUID> employeesOf(String id) {
        String k = key(id);
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Set<String>> e : assignments.entrySet()) {
            if (e.getValue().contains(k)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // -------- Suivi des paiements --------

    /** Dernier jour (epoch day, heure de Paris) ou ce joueur a ete paye ; 0 = jamais. */
    public long lastPaidDay(UUID player) {
        return lastPaidDay.getOrDefault(player, 0L);
    }

    public void setLastPaidDay(UUID player, long day) {
        lastPaidDay.put(player, day);
        setDirty();
    }

    // -------- Notifications en attente (joueur hors ligne) --------

    public List<String> pendingFor(UUID player) {
        return pending.getOrDefault(player, List.of());
    }

    public void addPending(UUID player, String message) {
        pending.computeIfAbsent(player, k -> new ArrayList<>()).add(message);
        setDirty();
    }

    /** Recupere et efface les notifications en attente : elles ne sont donc affichees qu'une fois. */
    public List<String> takePending(UUID player) {
        List<String> out = pending.remove(player);
        if (out != null && !out.isEmpty()) {
            setDirty();
            return out;
        }
        return List.of();
    }

    // -------- Pseudos connus (affichage des joueurs hors ligne) --------

    public String nameOf(UUID player) {
        return knownNames.getOrDefault(player, player.toString().substring(0, 8));
    }

    public void rememberName(UUID player, String name) {
        if (name != null && !name.isBlank() && !name.equals(knownNames.get(player))) {
            knownNames.put(player, name);
            setDirty();
        }
    }

    /** Cherche un joueur deja connu par son pseudo (insensible a la casse). */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    // -------- Historique --------

    public List<HistoryEntry> history() {
        return history;
    }

    public void log(String text) {
        history.add(new HistoryEntry(System.currentTimeMillis(), text));
        while (history.size() > HISTORY_MAX) {
            history.remove(0);
        }
        setDirty();
    }

    // -------- Banquiers (permission independante) --------

    public boolean isBanker(UUID id) {
        return bankers.contains(id);
    }

    public Collection<UUID> bankers() {
        return bankers;
    }

    /** Ajoute / retire le statut de banquier ; renvoie le nouvel etat. */
    public boolean toggleBanker(UUID id) {
        boolean now;
        if (bankers.contains(id)) {
            bankers.remove(id);
            now = false;
        } else {
            bankers.add(id);
            now = true;
        }
        setDirty();
        return now;
    }

    // -------- Serialisation --------

    public static JobData load(CompoundTag tag, HolderLookup.Provider registries) {
        JobData data = new JobData();
        ListTag jobList = tag.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobList.size(); i++) {
            CompoundTag j = jobList.getCompound(i);
            String id = j.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Job job = new Job(id, j.getString("name"), j.getLong("salary"));
            job.enabled = !j.contains("enabled") || j.getBoolean("enabled");
            data.jobs.put(id, job);
        }
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag p = players.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(p.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag held = p.getList("jobs", Tag.TAG_STRING);
            Set<String> set = new LinkedHashSet<>();
            for (int k = 0; k < held.size(); k++) {
                set.add(held.getString(k));
            }
            if (!set.isEmpty()) {
                data.assignments.put(id, set);
            }
            if (p.contains("lastPaid")) {
                data.lastPaidDay.put(id, p.getLong("lastPaid"));
            }
            if (p.contains("name")) {
                data.knownNames.put(id, p.getString("name"));
            }
            ListTag msgs = p.getList("pending", Tag.TAG_STRING);
            if (!msgs.isEmpty()) {
                List<String> list = new ArrayList<>();
                for (int k = 0; k < msgs.size(); k++) {
                    list.add(msgs.getString(k));
                }
                data.pending.put(id, list);
            }
        }
        ListTag hist = tag.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag h = hist.getCompound(i);
            data.history.add(new HistoryEntry(h.getLong("t"), h.getString("text")));
        }
        ListTag bank = tag.getList("bankers", Tag.TAG_STRING);
        for (int i = 0; i < bank.size(); i++) {
            try {
                data.bankers.add(UUID.fromString(bank.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag jobList = new ListTag();
        for (Job job : jobs.values()) {
            CompoundTag j = new CompoundTag();
            j.putString("id", job.id);
            j.putString("name", job.name);
            j.putLong("salary", job.salary);
            j.putBoolean("enabled", job.enabled);
            jobList.add(j);
        }
        tag.put("jobs", jobList);

        // Un seul enregistrement par joueur : metiers, dernier paiement, pseudo, notifications.
        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(assignments.keySet());
        all.addAll(lastPaidDay.keySet());
        all.addAll(knownNames.keySet());
        all.addAll(pending.keySet());
        ListTag players = new ListTag();
        for (UUID id : all) {
            CompoundTag p = new CompoundTag();
            p.putString("uuid", id.toString());
            ListTag held = new ListTag();
            for (String job : assignments.getOrDefault(id, Set.of())) {
                held.add(StringTag.valueOf(job));
            }
            p.put("jobs", held);
            if (lastPaidDay.containsKey(id)) {
                p.putLong("lastPaid", lastPaidDay.get(id));
            }
            if (knownNames.containsKey(id)) {
                p.putString("name", knownNames.get(id));
            }
            ListTag msgs = new ListTag();
            for (String m : pending.getOrDefault(id, List.of())) {
                msgs.add(StringTag.valueOf(m));
            }
            p.put("pending", msgs);
            players.add(p);
        }
        tag.put("players", players);

        ListTag hist = new ListTag();
        for (HistoryEntry e : history) {
            CompoundTag h = new CompoundTag();
            h.putLong("t", e.millis());
            h.putString("text", e.text());
            hist.add(h);
        }
        tag.put("history", hist);

        ListTag bank = new ListTag();
        for (UUID id : bankers) {
            bank.add(StringTag.valueOf(id.toString()));
        }
        tag.put("bankers", bank);
        return tag;
    }
}
