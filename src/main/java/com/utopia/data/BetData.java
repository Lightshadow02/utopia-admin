package com.utopia.data;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Donnees persistantes des paris : le pari lui-meme, ses propositions, les mises engagees et le
 * registre administratif, conserve bien apres la disparition du Bookmaker.
 *
 * <p>Le systeme travaille en circuit ferme. Trois totaux sont tenus separement et doivent toujours
 * concorder : ce qui a ete <b>retire</b> aux joueurs ({@code collected}), ce qui est reparti entre
 * les propositions (la somme des cagnottes) et ce qui a ete <b>reverse</b> ({@code distributed}).
 * Aucune Utopiece n'est creee ni detruite ; toute divergence bloque le paiement.
 */
public final class BetData extends SavedData {

    private static final String ID = "utopia_bets";
    /** Nombre de paris conserves au registre ; au-dela, les plus anciens paris clos sont oublies. */
    private static final int MAX_BETS = 500;
    /** Nombre maximal de propositions sur un pari. */
    public static final int MAX_OPTIONS = 12;
    /** Duree sans la moindre mise au bout de laquelle un pari s'annule tout seul. */
    public static final long IDLE_MS = 24L * 3_600_000L;
    /** Duree laissee au createur pour designer le vainqueur une fois les mises fermees. */
    public static final long RESOLVE_MS = 24L * 3_600_000L;

    public static final SavedData.Factory<BetData> FACTORY =
            new SavedData.Factory<>(BetData::new, BetData::load, null);

    /** Etats d'un pari. */
    public enum State {
        OUVERT("Mises ouvertes"),
        FERME("Mises fermees"),
        RESOLU("Pari resolu"),
        ANNULE("Pari annule"),
        SUSPENDU("Suspendu - verification"),
        ERREUR("Erreur - verification necessaire"),
        ARCHIVE("Archive - ecart constate");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Un pari vivant immobilise des Utopieces : son createur ne peut pas en ouvrir un autre. */
        public boolean active() {
            return this == OUVERT || this == FERME || this == SUSPENDU || this == ERREUR;
        }

        public boolean closed() {
            return this == RESOLU || this == ANNULE || this == ARCHIVE;
        }
    }

    /** Une proposition : un libelle libre et la cagnotte qu'elle a attiree. */
    public static final class Option {
        public final String id;      // libelle normalise, stable une fois le pari publie
        public String label;
        public long pool;            // total mise sur cette proposition

        public Option(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Une mise, telle qu'elle a ete enregistree. L'ordre d'arrivee sert a departager les arrondis. */
    public record Wager(long seq, UUID player, String playerName, String optionId, long amount, long millis) {
    }

    /** Une ligne du journal d'un pari. */
    public record LogEntry(long millis, String text) {
    }

    /** Un pari. */
    public static final class Bet {
        public final String id;
        public final UUID creator;
        public String creatorName;
        public String name = "";
        public String description = "";
        public final List<Option> options = new ArrayList<>();
        public State state = State.OUVERT;
        public State beforeSuspend;          // etat a retrouver si l'administration autorise la reprise

        public long createdAt;
        public int durationMinutes;
        public long closesAt;                // echeance des mises
        public long closedAt;
        public long resolvedAt;
        public long lastBetAt;

        public String winner = "";           // id de la proposition gagnante
        public String cancelReason = "";

        // Bookmaker
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public float restYaw;
        public double holoDy;                // reglage vertical de l'hologramme

        // Mises
        public final List<Wager> wagers = new ArrayList<>();
        public final Map<UUID, String> choice = new LinkedHashMap<>();   // proposition definitive
        public final Map<UUID, Long> staked = new LinkedHashMap<>();     // total mise par joueur
        public final Map<UUID, Long> payout = new LinkedHashMap<>();     // du a chaque joueur
        public final Set<UUID> paidOut = new LinkedHashSet<>();          // deja credite : jamais deux fois
        public long collected;               // total reellement retire aux joueurs
        public long distributed;             // total reellement reverse
        public boolean settled;              // le calcul de repartition a eu lieu

        public final List<LogEntry> journal = new ArrayList<>();

        public Bet(String id, UUID creator, String creatorName) {
            this.id = id;
            this.creator = creator;
            this.creatorName = creatorName;
        }

        public Option option(String optionId) {
            for (Option o : options) {
                if (o.id.equals(optionId)) {
                    return o;
                }
            }
            return null;
        }

        /** Cagnotte generale : toutes les Utopieces engagees, toutes propositions confondues. */
        public long pot() {
            long total = 0;
            for (Option o : options) {
                total += o.pool;
            }
            return total;
        }

        /** Nombre de joueurs ayant choisi cette proposition. */
        public int bettors(String optionId) {
            int count = 0;
            for (Map.Entry<UUID, String> e : choice.entrySet()) {
                if (e.getValue().equals(optionId)) {
                    count++;
                }
            }
            return count;
        }

        /** Cote d'une proposition : cagnotte generale divisee par la sienne. 0 si personne n'y a mise. */
        public double odds(String optionId) {
            Option o = option(optionId);
            if (o == null || o.pool <= 0) {
                return 0;
            }
            return (double) pot() / o.pool;
        }

        /** Cote qu'aurait la proposition si le joueur y ajoutait {@code extra} Utopieces. */
        public double oddsWith(String optionId, long extra) {
            Option o = option(optionId);
            if (o == null) {
                return 0;
            }
            long pool = o.pool + Math.max(0, extra);
            if (pool <= 0) {
                return 0;
            }
            return (double) (pot() + Math.max(0, extra)) / pool;
        }

        public long stakeOf(UUID player) {
            return staked.getOrDefault(player, 0L);
        }

        public boolean isPlaced() {
            return !dim.isEmpty();
        }

        public boolean acceptsWagers() {
            return state == State.OUVERT && System.currentTimeMillis() < closesAt;
        }

        public long remainingMs() {
            return Math.max(0, closesAt - System.currentTimeMillis());
        }

        /** Nombre de propositions ayant recu au moins une Utopiece. */
        public int fundedOptions() {
            int count = 0;
            for (Option o : options) {
                if (o.pool > 0) {
                    count++;
                }
            }
            return count;
        }

        /** Reste-t-il des Utopieces immobilisees ? Tant que oui, le Bookmaker ne peut pas disparaitre. */
        public boolean holdsMoney() {
            return collected > distributed;
        }

        public void log(String text) {
            journal.add(new LogEntry(System.currentTimeMillis(), text));
            while (journal.size() > 200) {
                journal.remove(0);
            }
        }
    }

    private final Map<String, Bet> bets = new LinkedHashMap<>();
    private final Map<UUID, List<String>> pending = new LinkedHashMap<>();
    private int counter;
    private long wagerSeq;

    public BetData() {
    }

    public static BetData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Normalise un libelle de proposition en identifiant stable. */
    public static String slug(String label) {
        String out = java.text.Normalizer.normalize(label == null ? "" : label,
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return out.isEmpty() ? "opt" : out;
    }

    // -------- Paris --------

    public Bet bet(String id) {
        return id == null ? null : bets.get(id);
    }

    public Bet create(UUID creator, String creatorName) {
        String id = String.format("B-%04d", ++counter);
        while (bets.containsKey(id)) {
            id = String.format("B-%04d", ++counter);
        }
        Bet bet = new Bet(id, creator, creatorName);
        bet.createdAt = System.currentTimeMillis();
        bets.put(id, bet);
        prune();
        setDirty();
        return bet;
    }

    public boolean remove(String id) {
        if (bets.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public long nextWagerSeq() {
        setDirty();
        return ++wagerSeq;
    }

    /** Tous les paris, du plus recent au plus ancien. */
    public List<Bet> all() {
        List<Bet> out = new ArrayList<>(bets.values());
        out.sort(Comparator.comparingLong((Bet b) -> b.createdAt).reversed());
        return out;
    }

    /** Paris encore vivants : ceux dont le Bookmaker doit exister dans le monde. */
    public List<Bet> live() {
        List<Bet> out = new ArrayList<>();
        for (Bet b : bets.values()) {
            if (b.state.active() || b.holdsMoney()) {
                out.add(b);
            }
        }
        return out;
    }

    /** Le pari en cours de ce joueur, ou null : chacun n'en tient qu'un a la fois. */
    public Bet activeOf(UUID creator) {
        for (Bet b : bets.values()) {
            if (b.creator.equals(creator) && b.state.active()) {
                return b;
            }
        }
        return null;
    }

    public List<Bet> createdBy(UUID player) {
        List<Bet> out = new ArrayList<>();
        for (Bet b : all()) {
            if (b.creator.equals(player)) {
                out.add(b);
            }
        }
        return out;
    }

    public List<Bet> participatedBy(UUID player) {
        List<Bet> out = new ArrayList<>();
        for (Bet b : all()) {
            if (b.staked.containsKey(player)) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * Borne le registre sans jamais perdre un pari vivant ni un pari qui immobilise encore des
     * Utopieces : seuls les plus anciens paris entierement soldes sont oublies.
     */
    private void prune() {
        if (bets.size() <= MAX_BETS) {
            return;
        }
        List<Bet> closed = new ArrayList<>();
        for (Bet b : bets.values()) {
            if (b.state.closed() && !b.holdsMoney()) {
                closed.add(b);
            }
        }
        closed.sort(Comparator.comparingLong(b -> b.createdAt));
        int excess = bets.size() - MAX_BETS;
        for (int i = 0; i < excess && i < closed.size(); i++) {
            bets.remove(closed.get(i).id);
        }
    }

    // -------- Notifications differees --------

    public void addPending(UUID player, String message) {
        pending.computeIfAbsent(player, k -> new ArrayList<>()).add(message);
        setDirty();
    }

    public List<String> takePending(UUID player) {
        List<String> out = pending.remove(player);
        if (out != null && !out.isEmpty()) {
            setDirty();
            return out;
        }
        return List.of();
    }

    // -------- Serialisation --------

    public static BetData load(CompoundTag tag, HolderLookup.Provider registries) {
        BetData data = new BetData();
        data.counter = tag.getInt("counter");
        data.wagerSeq = tag.getLong("wagerSeq");

        ListTag list = tag.getList("bets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            UUID creator;
            try {
                creator = UUID.fromString(b.getString("creator"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String id = b.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Bet bet = new Bet(id, creator, b.getString("creatorName"));
            bet.name = b.getString("name");
            bet.description = b.getString("description");
            boolean unreadableState = false;
            try {
                bet.state = State.valueOf(b.getString("state"));
            } catch (IllegalArgumentException e) {
                // Jamais d'etat clos par defaut : un pari qui detient encore des Utopieces doit
                // rester remboursable, et l'anomalie doit se voir au registre plutot que de solder
                // la cagnotte sans que personne ne l'apprenne.
                bet.state = State.ERREUR;
                unreadableState = true;
            }
            if (b.contains("beforeSuspend")) {
                try {
                    bet.beforeSuspend = State.valueOf(b.getString("beforeSuspend"));
                } catch (IllegalArgumentException ignored) {
                    bet.beforeSuspend = null;
                }
            }
            bet.createdAt = b.getLong("createdAt");
            bet.durationMinutes = b.getInt("duration");
            bet.closesAt = b.getLong("closesAt");
            bet.closedAt = b.getLong("closedAt");
            bet.resolvedAt = b.getLong("resolvedAt");
            bet.lastBetAt = b.getLong("lastBetAt");
            bet.winner = b.getString("winner");
            bet.cancelReason = b.getString("cancelReason");
            bet.dim = b.getString("dim");
            bet.x = b.getDouble("x");
            bet.y = b.getDouble("y");
            bet.z = b.getDouble("z");
            bet.restYaw = b.getFloat("yaw");
            bet.holoDy = b.getDouble("holoDy");
            bet.collected = b.getLong("collected");
            bet.distributed = b.getLong("distributed");
            bet.settled = b.getBoolean("settled");

            ListTag opts = b.getList("options", Tag.TAG_COMPOUND);
            for (int k = 0; k < opts.size(); k++) {
                CompoundTag o = opts.getCompound(k);
                Option option = new Option(o.getString("id"), o.getString("label"));
                option.pool = o.getLong("pool");
                bet.options.add(option);
            }
            ListTag ws = b.getList("wagers", Tag.TAG_COMPOUND);
            for (int k = 0; k < ws.size(); k++) {
                CompoundTag w = ws.getCompound(k);
                try {
                    bet.wagers.add(new Wager(w.getLong("seq"), UUID.fromString(w.getString("uuid")),
                            w.getString("name"), w.getString("option"), w.getLong("amount"),
                            w.getLong("t")));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : la mise est ignoree, la verification le signalera
                }
            }
            readMap(b, "choice", bet.choice);
            readLongMap(b, "staked", bet.staked);
            readLongMap(b, "payout", bet.payout);
            ListTag paid = b.getList("paidOut", Tag.TAG_STRING);
            for (int k = 0; k < paid.size(); k++) {
                try {
                    bet.paidOut.add(UUID.fromString(paid.getString(k)));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu
                }
            }
            ListTag log = b.getList("journal", Tag.TAG_COMPOUND);
            for (int k = 0; k < log.size(); k++) {
                CompoundTag l = log.getCompound(k);
                bet.journal.add(new LogEntry(l.getLong("t"), l.getString("text")));
            }
            if (unreadableState) {
                bet.log("BLOCAGE : etat illisible au chargement - pari place en verification");
            }
            data.bets.put(id, bet);
        }

        ListTag waiting = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < waiting.size(); i++) {
            CompoundTag w = waiting.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(w.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag msgs = w.getList("messages", Tag.TAG_STRING);
            List<String> out = new ArrayList<>();
            for (int k = 0; k < msgs.size(); k++) {
                out.add(msgs.getString(k));
            }
            if (!out.isEmpty()) {
                data.pending.put(id, out);
            }
        }
        return data;
    }

    private static void readMap(CompoundTag tag, String key, Map<UUID, String> target) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                target.put(UUID.fromString(e.getString("uuid")), e.getString("value"));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
    }

    private static void readLongMap(CompoundTag tag, String key, Map<UUID, Long> target) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                target.put(UUID.fromString(e.getString("uuid")), e.getLong("value"));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("counter", counter);
        tag.putLong("wagerSeq", wagerSeq);

        ListTag list = new ListTag();
        for (Bet bet : bets.values()) {
            CompoundTag b = new CompoundTag();
            b.putString("id", bet.id);
            b.putString("creator", bet.creator.toString());
            b.putString("creatorName", bet.creatorName == null ? "" : bet.creatorName);
            b.putString("name", bet.name);
            b.putString("description", bet.description);
            b.putString("state", bet.state.name());
            if (bet.beforeSuspend != null) {
                b.putString("beforeSuspend", bet.beforeSuspend.name());
            }
            b.putLong("createdAt", bet.createdAt);
            b.putInt("duration", bet.durationMinutes);
            b.putLong("closesAt", bet.closesAt);
            b.putLong("closedAt", bet.closedAt);
            b.putLong("resolvedAt", bet.resolvedAt);
            b.putLong("lastBetAt", bet.lastBetAt);
            b.putString("winner", bet.winner);
            b.putString("cancelReason", bet.cancelReason);
            b.putString("dim", bet.dim);
            b.putDouble("x", bet.x);
            b.putDouble("y", bet.y);
            b.putDouble("z", bet.z);
            b.putFloat("yaw", bet.restYaw);
            b.putDouble("holoDy", bet.holoDy);
            b.putLong("collected", bet.collected);
            b.putLong("distributed", bet.distributed);
            b.putBoolean("settled", bet.settled);

            ListTag opts = new ListTag();
            for (Option o : bet.options) {
                CompoundTag t = new CompoundTag();
                t.putString("id", o.id);
                t.putString("label", o.label);
                t.putLong("pool", o.pool);
                opts.add(t);
            }
            b.put("options", opts);

            ListTag ws = new ListTag();
            for (Wager w : bet.wagers) {
                CompoundTag t = new CompoundTag();
                t.putLong("seq", w.seq());
                t.putString("uuid", w.player().toString());
                t.putString("name", w.playerName());
                t.putString("option", w.optionId());
                t.putLong("amount", w.amount());
                t.putLong("t", w.millis());
                ws.add(t);
            }
            b.put("wagers", ws);

            b.put("choice", writeMap(bet.choice));
            b.put("staked", writeLongMap(bet.staked));
            b.put("payout", writeLongMap(bet.payout));
            ListTag paid = new ListTag();
            for (UUID id : bet.paidOut) {
                paid.add(StringTag.valueOf(id.toString()));
            }
            b.put("paidOut", paid);

            ListTag log = new ListTag();
            for (LogEntry e : bet.journal) {
                CompoundTag t = new CompoundTag();
                t.putLong("t", e.millis());
                t.putString("text", e.text());
                log.add(t);
            }
            b.put("journal", log);
            list.add(b);
        }
        tag.put("bets", list);

        ListTag waiting = new ListTag();
        for (Map.Entry<UUID, List<String>> e : pending.entrySet()) {
            CompoundTag w = new CompoundTag();
            w.putString("uuid", e.getKey().toString());
            ListTag msgs = new ListTag();
            for (String m : e.getValue()) {
                msgs.add(StringTag.valueOf(m));
            }
            w.put("messages", msgs);
            waiting.add(w);
        }
        tag.put("pending", waiting);
        return tag;
    }

    private static ListTag writeMap(Map<UUID, String> map) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> e : map.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("uuid", e.getKey().toString());
            t.putString("value", e.getValue());
            list.add(t);
        }
        return list;
    }

    private static ListTag writeLongMap(Map<UUID, Long> map) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : map.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("uuid", e.getKey().toString());
            t.putLong("value", e.getValue());
            list.add(t);
        }
        return list;
    }
}
