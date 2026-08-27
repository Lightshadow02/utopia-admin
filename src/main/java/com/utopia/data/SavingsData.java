package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * Donnees persistantes des livrets d'epargne : le bareme des taux (fixe par les administrateurs), le
 * livret de chaque joueur et son suivi jour par jour.
 *
 * <p>Le bareme est volontairement separe des livrets : le banquier lit le taux mais ne le touche
 * jamais, il ne fait qu'encaisser et rendre des pieces au comptoir.
 */
public final class SavingsData extends SavedData {

    private static final String ID = "utopia_savings";
    /** Taille maximale du journal des operations conserve. */
    private static final int JOURNAL_MAX = 500;
    /** Nombre de journees de suivi conservees par livret. */
    public static final int HISTORY_DAYS = 60;

    public static final SavedData.Factory<SavingsData> FACTORY =
            new SavedData.Factory<>(SavingsData::new, SavingsData::load, null);

    /**
     * Un palier du bareme : des que le livret atteint {@code threshold} Utopieces, {@code bonus} vient
     * s'ajouter au taux de base. Les paliers se cumulent : plus le livret est garni, plus il rapporte.
     */
    public static final class Tier {
        public long threshold;
        public double bonus;

        public Tier(long threshold, double bonus) {
            this.threshold = Math.max(0, threshold);
            this.bonus = bonus;
        }
    }

    /**
     * Le releve d'une journee pour un livret : ce que la nuit a rapporte, les mouvements passes au
     * comptoir dans la journee, et le solde de cloture.
     */
    public static final class DayEntry {
        public final long day;          // epoch day (heure de Paris)
        public long opening;            // solde au reveil, avant les interets
        public long interest;           // verse a minuit
        public double rate;             // taux applique cette nuit-la
        public long deposits;           // depose au comptoir dans la journee
        public long withdrawals;        // retire au comptoir dans la journee
        public long closing;            // solde en fin de journee

        public DayEntry(long day, long opening) {
            this.day = day;
            this.opening = opening;
            this.closing = opening;
        }
    }

    /** Le livret d'un joueur. */
    public static final class Account {
        public final UUID owner;
        public long balance;
        /** Derniere nuit deja creditee (epoch day) : garantit un seul versement par nuit. */
        public long lastInterestDay;
        public long openedDay;
        public long totalInterest;
        public long totalDeposits;
        public long totalWithdrawals;
        /** Interets verses pendant que le joueur etait absent, pas encore annonces. */
        public long pendingInterest;
        public int pendingNights;
        public final List<DayEntry> history = new ArrayList<>();

        public Account(UUID owner) {
            this.owner = owner;
        }

        /** Releve de la journee demandee, cree si besoin (le suivi est borne dans le temps). */
        public DayEntry entry(long day) {
            for (DayEntry e : history) {
                if (e.day == day) {
                    return e;
                }
            }
            DayEntry e = new DayEntry(day, balance);
            history.add(e);
            history.sort(Comparator.comparingLong(x -> x.day));
            while (history.size() > HISTORY_DAYS) {
                history.remove(0);
            }
            return e;
        }

        /** Dernier releve connu (le plus recent), ou null si le livret vient d'etre ouvert. */
        public DayEntry lastEntry() {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
    }

    /** Une ligne du journal : horodatage reel + texte deja formate. */
    public record LogEntry(long millis, String text) {
    }

    // -------- Bareme (administrateurs uniquement) --------

    private double baseRate = 0.25;                  // % par nuit
    private final List<Tier> tiers = new ArrayList<>(List.of(
            new Tier(1_000L, 0.25),
            new Tier(10_000L, 0.25),
            new Tier(100_000L, 0.25)));
    private long ceiling = 0;                        // plafond par livret, 0 = illimite
    private boolean enabled = true;

    // -------- Livrets --------

    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<UUID, String> knownNames = new LinkedHashMap<>();
    private final List<LogEntry> journal = new ArrayList<>();
    /** Derniere nuit traitee et total verse cette nuit-la (pour l'en-tete du registre). */
    private long lastRunDay;
    private long lastRunTotal;

    public SavingsData() {
    }

    public static SavingsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // -------- Bareme --------

    public double baseRate() {
        return baseRate;
    }

    public void setBaseRate(double rate) {
        this.baseRate = Math.max(0, Math.min(100, rate));
        setDirty();
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public void addTier(long threshold, double bonus) {
        tiers.add(new Tier(threshold, bonus));
        sortTiers();
        setDirty();
    }

    public void removeTier(int index) {
        if (index >= 0 && index < tiers.size()) {
            tiers.remove(index);
            setDirty();
        }
    }

    public void sortTiers() {
        tiers.sort(Comparator.comparingLong(t -> t.threshold));
    }

    public long ceiling() {
        return ceiling;
    }

    public void setCeiling(long value) {
        this.ceiling = Math.max(0, value);
        setDirty();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        setDirty();
    }

    /** Taux applique a un solde donne : le taux de base plus tous les paliers deja atteints. */
    public double rateFor(long balance) {
        double rate = baseRate;
        for (Tier t : tiers) {
            if (balance >= t.threshold) {
                rate += t.bonus;
            }
        }
        return rate;
    }

    /** Premier palier encore hors de portee pour ce solde, ou null si tout est atteint. */
    public Tier nextTier(long balance) {
        Tier best = null;
        for (Tier t : tiers) {
            if (balance < t.threshold && (best == null || t.threshold < best.threshold)) {
                best = t;
            }
        }
        return best;
    }

    // -------- Livrets --------

    public Account account(UUID player) {
        return accounts.get(player);
    }

    /** Ouvre le livret s'il n'existe pas encore ; la premiere nuit creditee sera la suivante. */
    public Account openAccount(UUID player, long day) {
        Account existing = accounts.get(player);
        if (existing != null) {
            return existing;
        }
        Account account = new Account(player);
        account.openedDay = day;
        account.lastInterestDay = day;
        accounts.put(player, account);
        setDirty();
        return account;
    }

    public boolean closeAccount(UUID player) {
        if (accounts.remove(player) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public Collection<Account> accounts() {
        return accounts.values();
    }

    /** Livrets tries du plus garni au moins garni (ordre du registre). */
    public List<Account> ranking() {
        List<Account> out = new ArrayList<>(accounts.values());
        out.sort(Comparator.comparingLong((Account a) -> a.balance).reversed());
        return out;
    }

    public long totalSaved() {
        long total = 0;
        for (Account a : accounts.values()) {
            total += a.balance;
        }
        return total;
    }

    public long lastRunDay() {
        return lastRunDay;
    }

    public long lastRunTotal() {
        return lastRunTotal;
    }

    public void setLastRun(long day, long total) {
        this.lastRunDay = day;
        this.lastRunTotal = total;
        setDirty();
    }

    // -------- Pseudos connus --------

    public String nameOf(UUID player) {
        return knownNames.getOrDefault(player, player.toString().substring(0, 8));
    }

    public void rememberName(UUID player, String name) {
        if (name != null && !name.isBlank() && !name.equals(knownNames.get(player))) {
            knownNames.put(player, name);
            setDirty();
        }
    }

    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    public Map<UUID, String> knownNames() {
        return knownNames;
    }

    // -------- Journal --------

    public List<LogEntry> journal() {
        return journal;
    }

    public void log(String text) {
        journal.add(new LogEntry(System.currentTimeMillis(), text));
        while (journal.size() > JOURNAL_MAX) {
            journal.remove(0);
        }
        setDirty();
    }

    // -------- Serialisation --------

    public static SavingsData load(CompoundTag tag, HolderLookup.Provider registries) {
        SavingsData data = new SavingsData();
        if (tag.contains("baseRate")) {
            data.baseRate = tag.getDouble("baseRate");
        }
        data.ceiling = tag.getLong("ceiling");
        data.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        data.lastRunDay = tag.getLong("lastRunDay");
        data.lastRunTotal = tag.getLong("lastRunTotal");

        if (tag.contains("tiers")) {
            data.tiers.clear();
            ListTag tiers = tag.getList("tiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < tiers.size(); i++) {
                CompoundTag t = tiers.getCompound(i);
                data.tiers.add(new Tier(t.getLong("at"), t.getDouble("bonus")));
            }
            data.sortTiers();
        }

        ListTag list = tag.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag a = list.getCompound(i);
            UUID owner;
            try {
                owner = UUID.fromString(a.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Account account = new Account(owner);
            account.balance = a.getLong("balance");
            account.lastInterestDay = a.getLong("lastInterest");
            account.openedDay = a.getLong("opened");
            account.totalInterest = a.getLong("totalInterest");
            account.totalDeposits = a.getLong("totalDeposits");
            account.totalWithdrawals = a.getLong("totalWithdrawals");
            account.pendingInterest = a.getLong("pendingInterest");
            account.pendingNights = a.getInt("pendingNights");
            ListTag days = a.getList("days", Tag.TAG_COMPOUND);
            for (int k = 0; k < days.size(); k++) {
                CompoundTag d = days.getCompound(k);
                DayEntry entry = new DayEntry(d.getLong("day"), d.getLong("opening"));
                entry.interest = d.getLong("interest");
                entry.rate = d.getDouble("rate");
                entry.deposits = d.getLong("deposits");
                entry.withdrawals = d.getLong("withdrawals");
                entry.closing = d.getLong("closing");
                account.history.add(entry);
            }
            account.history.sort(Comparator.comparingLong(x -> x.day));
            data.accounts.put(owner, account);
        }

        ListTag names = tag.getList("names", Tag.TAG_COMPOUND);
        for (int i = 0; i < names.size(); i++) {
            CompoundTag n = names.getCompound(i);
            try {
                data.knownNames.put(UUID.fromString(n.getString("uuid")), n.getString("name"));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }

        ListTag log = tag.getList("journal", Tag.TAG_COMPOUND);
        for (int i = 0; i < log.size(); i++) {
            CompoundTag l = log.getCompound(i);
            data.journal.add(new LogEntry(l.getLong("t"), l.getString("text")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("baseRate", baseRate);
        tag.putLong("ceiling", ceiling);
        tag.putBoolean("enabled", enabled);
        tag.putLong("lastRunDay", lastRunDay);
        tag.putLong("lastRunTotal", lastRunTotal);

        ListTag tierList = new ListTag();
        for (Tier t : tiers) {
            CompoundTag c = new CompoundTag();
            c.putLong("at", t.threshold);
            c.putDouble("bonus", t.bonus);
            tierList.add(c);
        }
        tag.put("tiers", tierList);

        ListTag list = new ListTag();
        for (Account account : accounts.values()) {
            CompoundTag a = new CompoundTag();
            a.putString("uuid", account.owner.toString());
            a.putLong("balance", account.balance);
            a.putLong("lastInterest", account.lastInterestDay);
            a.putLong("opened", account.openedDay);
            a.putLong("totalInterest", account.totalInterest);
            a.putLong("totalDeposits", account.totalDeposits);
            a.putLong("totalWithdrawals", account.totalWithdrawals);
            a.putLong("pendingInterest", account.pendingInterest);
            a.putInt("pendingNights", account.pendingNights);
            ListTag days = new ListTag();
            for (DayEntry e : account.history) {
                CompoundTag d = new CompoundTag();
                d.putLong("day", e.day);
                d.putLong("opening", e.opening);
                d.putLong("interest", e.interest);
                d.putDouble("rate", e.rate);
                d.putLong("deposits", e.deposits);
                d.putLong("withdrawals", e.withdrawals);
                d.putLong("closing", e.closing);
                days.add(d);
            }
            a.put("days", days);
            list.add(a);
        }
        tag.put("accounts", list);

        ListTag names = new ListTag();
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
            CompoundTag n = new CompoundTag();
            n.putString("uuid", e.getKey().toString());
            n.putString("name", e.getValue());
            names.add(n);
        }
        tag.put("names", names);

        ListTag log = new ListTag();
        for (LogEntry e : journal) {
            CompoundTag l = new CompoundTag();
            l.putLong("t", e.millis());
            l.putString("text", e.text());
            log.add(l);
        }
        tag.put("journal", log);
        return tag;
    }
}
