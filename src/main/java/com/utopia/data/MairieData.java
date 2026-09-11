package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les reglements de la mairie : ce que le maire decide lui-meme depuis /maire, par opposition aux
 * reglages techniques du fichier de configuration, qui restent la main de l'administrateur.
 *
 * <p>Un seul fichier pour les quatre leviers (taxes nommees, impot sur les salaires, PVP, bareme de
 * points) : ce sont des decisions politiques, elles changent de mandat en mandat et se lisent
 * ensemble. Les compteurs vivants du classement, eux, vivent a part dans {@link LeaderboardData} :
 * ils sont reecrits en permanence, les reglements presque jamais.
 */
public final class MairieData extends SavedData {

    private static final String ID = "utopia_mairie";

    public static final SavedData.Factory<MairieData> FACTORY =
            new SavedData.Factory<>(MairieData::new, MairieData::load, null);

    /** Nombre de places recompensees au maximum : au-dela, un podium ne se lit plus. */
    public static final int MAX_PODIUM = 5;
    /** Plafond du cumul de toutes les taxes d'un meme flux, en pourcentage du montant. */
    public static final int MAX_TOTAL_TAX = 90;
    /**
     * Nombre maximal de taxes nommees. L'ecran qui les liste tient 49 lignes avant d'etre tronque
     * en silence ; on s'arrete a 40 pour garder de la marge si cet ecran gagne un bouton. La
     * quarante-et-unieme est refusee plutot que masquee.
     */
    public static final int MAX_TAXES = 40;

    /**
     * Les flux d'Utopieces qu'une taxe nommee peut atteindre. Les paris n'y figurent pas
     * volontairement : leur cagnotte est un circuit ferme a somme nulle, une ponction en cours de
     * route fausserait les cotes annoncees aux parieurs.
     */
    public enum Flow {
        DEVIS("Devis", "sur chaque reglement de devis"),
        MARCHE("Marche", "sur chaque vente d'un stand du marche"),
        SALAIRE("Salaires", "sur chaque salaire verse a 12h"),
        VIREMENT("Virements", "sur chaque /pay entre joueurs");

        public final String label;
        public final String detail;

        Flow(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }

        public static Flow byName(String name) {
            for (Flow f : values()) {
                if (f.name().equals(name)) {
                    return f;
                }
            }
            return null;
        }
    }

    /**
     * Une taxe nommee. Elle cumule une part proportionnelle et une somme fixe : la premiere suit la
     * taille de la transaction, la seconde permet les droits forfaitaires (un timbre, une vignette)
     * que le maire veut percevoir meme sur les petites sommes.
     */
    public static final class Tax {
        public final String id;
        public String name;
        public int percent;
        public long flat;
        public boolean enabled = true;
        public final EnumSet<Flow> flows = EnumSet.noneOf(Flow.class);
        /** Cumul percu depuis la creation : c'est le seul bilan dont dispose le maire. */
        public long collected;

        public Tax(String id, String name) {
            this.id = id;
            this.name = name;
        }

        /** Montant du a cette taxe pour une transaction donnee, avant plafonnement global. */
        public long on(long amount) {
            if (amount <= 0) {
                return 0;
            }
            return flat + (amount * percent / 100L);
        }

        public boolean appliesTo(Flow flow) {
            return enabled && flows.contains(flow);
        }

        /** Resume lisible : "5 % + 2" ou "5 %" ou "2 Utopieces". */
        public String rateLabel() {
            if (percent > 0 && flat > 0) {
                return percent + " % + " + flat;
            }
            if (percent > 0) {
                return percent + " %";
            }
            return flat + " par transaction";
        }
    }

    /** Un podium archive : le classement d'une journee, tel qu'il a ete solde. */
    public record PodiumEntry(java.util.UUID player, String name, long points, int kills, long reward) {
    }

    public record Podium(long day, List<PodiumEntry> entries) {
    }

    /** Combien de podiums on garde : au-dela, le palmares gonfle le fichier sans servir. */
    public static final int MAX_PALMARES = 30;

    // -------- Reglements --------

    /**
     * Tant que le maire n'a rien decide, on reconduit ce que faisait le serveur avant ce reglage :
     * la protection des entites d'une parcelle couvrait deja les joueurs qui s'y trouvaient, faute
     * d'etre ni des monstres ni des animaux. Une mise a jour ne doit pas changer les regles du jeu
     * dans le dos des joueurs, dans un sens comme dans l'autre.
     */
    private boolean pvpInParcels = !com.utopia.Config.PARCEL_PROTECT_ENTITIES.get();
    private int salaryTaxPercent;
    private final Map<String, Tax> taxes = new LinkedHashMap<>();

    // -------- Classement --------

    private boolean leaderboardEnabled;
    /** Heure REELLE (fuseau de Paris) a laquelle la journee de classement est soldee. */
    private int leaderboardHour = 20;
    private int defaultKillPoints;
    /** Les mobs sortis d'un generateur comptent-ils ? Non par defaut : une ferme viderait le podium. */
    private boolean countSpawnerMobs;
    /** Identifiant de type d'entite -> points rapportes. */
    private final Map<String, Integer> killPoints = new LinkedHashMap<>();
    /** Recompenses par place, de la premiere a la derniere. */
    private final List<Long> podium = new ArrayList<>(List.of(500L, 250L, 100L));
    private final List<Podium> palmares = new ArrayList<>();

    public MairieData() {
    }

    public static MairieData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // -------- PVP --------

    public boolean pvpInParcels() {
        return pvpInParcels;
    }

    public void setPvpInParcels(boolean value) {
        this.pvpInParcels = value;
        setDirty();
    }

    // -------- Impot sur les salaires --------

    public int salaryTaxPercent() {
        return salaryTaxPercent;
    }

    public void setSalaryTaxPercent(int value) {
        this.salaryTaxPercent = Math.max(0, Math.min(MAX_TOTAL_TAX, value));
        setDirty();
    }

    // -------- Taxes nommees --------

    public Collection<Tax> taxes() {
        return taxes.values();
    }

    public Tax tax(String id) {
        return taxes.get(id);
    }

    /** Les taxes qui frappent ce flux, dans l'ordre de creation. */
    public List<Tax> taxesFor(Flow flow) {
        List<Tax> out = new ArrayList<>();
        for (Tax t : taxes.values()) {
            if (t.appliesTo(flow)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Cree une taxe a partir du nom libre saisi par le maire. L'identifiant est derive du nom : les
     * accents et les espaces d'un libelle ne peuvent pas servir de cle de registre, mais le maire ne
     * doit pas avoir a s'en soucier.
     */
    public Tax createTax(String name) {
        String base = slug(name);
        if (base.isEmpty() || taxes.size() >= MAX_TAXES) {
            return null;
        }
        String id = base;
        int n = 2;
        while (taxes.containsKey(id)) {
            id = base + "_" + n++;
        }
        Tax tax = new Tax(id, name.trim());
        taxes.put(id, tax);
        setDirty();
        return tax;
    }

    public boolean removeTax(String id) {
        boolean removed = taxes.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Identifiant sur : lettres et chiffres sans accent, le reste devient un tiret bas. */
    private static String slug(String name) {
        if (name == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String cleaned = normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    // -------- Classement --------

    public boolean leaderboardEnabled() {
        return leaderboardEnabled;
    }

    public void setLeaderboardEnabled(boolean value) {
        this.leaderboardEnabled = value;
        setDirty();
    }

    public int leaderboardHour() {
        return leaderboardHour;
    }

    public void setLeaderboardHour(int hour) {
        this.leaderboardHour = Math.max(0, Math.min(23, hour));
        setDirty();
    }

    public int defaultKillPoints() {
        return defaultKillPoints;
    }

    public void setDefaultKillPoints(int value) {
        this.defaultKillPoints = Math.max(0, value);
        setDirty();
    }

    public boolean countSpawnerMobs() {
        return countSpawnerMobs;
    }

    public void setCountSpawnerMobs(boolean value) {
        this.countSpawnerMobs = value;
        setDirty();
    }

    /** Points rapportes par ce type d'entite ; le bareme par defaut s'applique s'il n'est pas cite. */
    public int pointsFor(String entityTypeId) {
        Integer explicit = killPoints.get(entityTypeId);
        return explicit != null ? Math.max(0, explicit) : defaultKillPoints;
    }

    public boolean hasPointsFor(String entityTypeId) {
        return killPoints.containsKey(entityTypeId);
    }

    public Map<String, Integer> killPoints() {
        return java.util.Collections.unmodifiableMap(killPoints);
    }

    public void setPointsFor(String entityTypeId, int points) {
        if (points <= 0) {
            killPoints.remove(entityTypeId);
        } else {
            killPoints.put(entityTypeId, points);
        }
        setDirty();
    }

    public void clearKillPoints() {
        killPoints.clear();
        setDirty();
    }

    public List<Long> podium() {
        return java.util.Collections.unmodifiableList(podium);
    }

    /** Recompense de la place demandee (1 = premier), 0 si cette place n'est pas dotee. */
    public long rewardForRank(int rank) {
        return rank >= 1 && rank <= podium.size() ? Math.max(0, podium.get(rank - 1)) : 0L;
    }

    public void setRewardForRank(int rank, long reward) {
        if (rank < 1 || rank > MAX_PODIUM) {
            return;
        }
        while (podium.size() < rank) {
            podium.add(0L);
        }
        podium.set(rank - 1, Math.max(0, reward));
        // Une place finale a zero n'est pas un podium : on la retire pour que l'ecran reste lisible.
        while (!podium.isEmpty() && podium.get(podium.size() - 1) <= 0) {
            podium.remove(podium.size() - 1);
        }
        setDirty();
    }

    public List<Podium> palmares() {
        return java.util.Collections.unmodifiableList(palmares);
    }

    public void archive(Podium entry) {
        palmares.add(0, entry);
        while (palmares.size() > MAX_PALMARES) {
            palmares.remove(palmares.size() - 1);
        }
        setDirty();
    }

    // -------- Serialisation --------

    public static MairieData load(CompoundTag tag, HolderLookup.Provider registries) {
        MairieData data = new MairieData();
        if (tag.contains("pvpInParcels")) {
            data.pvpInParcels = tag.getBoolean("pvpInParcels");
        }
        data.salaryTaxPercent = tag.getInt("salaryTax");
        data.leaderboardEnabled = tag.getBoolean("lbEnabled");
        data.leaderboardHour = tag.contains("lbHour") ? tag.getInt("lbHour") : 20;
        data.defaultKillPoints = tag.getInt("defaultPoints");
        data.countSpawnerMobs = tag.getBoolean("countSpawner");

        ListTag taxList = tag.getList("taxes", Tag.TAG_COMPOUND);
        for (int i = 0; i < taxList.size(); i++) {
            CompoundTag t = taxList.getCompound(i);
            String id = t.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Tax tax = new Tax(id, t.getString("name"));
            tax.percent = t.getInt("percent");
            tax.flat = t.getLong("flat");
            tax.enabled = !t.contains("enabled") || t.getBoolean("enabled");
            tax.collected = t.getLong("collected");
            ListTag flows = t.getList("flows", Tag.TAG_STRING);
            for (int j = 0; j < flows.size(); j++) {
                Flow flow = Flow.byName(flows.getString(j));
                if (flow != null) {
                    tax.flows.add(flow);
                }
            }
            data.taxes.put(id, tax);
        }

        CompoundTag points = tag.getCompound("killPoints");
        for (String key : points.getAllKeys()) {
            data.killPoints.put(key, points.getInt(key));
        }

        if (tag.contains("podium")) {
            data.podium.clear();
            ListTag rewards = tag.getList("podium", Tag.TAG_LONG);
            for (int i = 0; i < rewards.size() && i < MAX_PODIUM; i++) {
                data.podium.add(((net.minecraft.nbt.LongTag) rewards.get(i)).getAsLong());
            }
        }

        ListTag hist = tag.getList("palmares", Tag.TAG_COMPOUND);
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag p = hist.getCompound(i);
            List<PodiumEntry> entries = new ArrayList<>();
            ListTag rows = p.getList("entries", Tag.TAG_COMPOUND);
            for (int j = 0; j < rows.size(); j++) {
                CompoundTag e = rows.getCompound(j);
                try {
                    entries.add(new PodiumEntry(java.util.UUID.fromString(e.getString("uuid")),
                            e.getString("name"), e.getLong("points"), e.getInt("kills"), e.getLong("reward")));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : la ligne est perdue, le podium reste lisible
                }
            }
            data.palmares.add(new Podium(p.getLong("day"), entries));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("pvpInParcels", pvpInParcels);
        tag.putInt("salaryTax", salaryTaxPercent);
        tag.putBoolean("lbEnabled", leaderboardEnabled);
        tag.putInt("lbHour", leaderboardHour);
        tag.putInt("defaultPoints", defaultKillPoints);
        tag.putBoolean("countSpawner", countSpawnerMobs);

        ListTag taxList = new ListTag();
        for (Tax tax : taxes.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", tax.id);
            t.putString("name", tax.name == null ? tax.id : tax.name);
            t.putInt("percent", tax.percent);
            t.putLong("flat", tax.flat);
            t.putBoolean("enabled", tax.enabled);
            t.putLong("collected", tax.collected);
            ListTag flows = new ListTag();
            for (Flow flow : tax.flows) {
                flows.add(net.minecraft.nbt.StringTag.valueOf(flow.name()));
            }
            t.put("flows", flows);
            taxList.add(t);
        }
        tag.put("taxes", taxList);

        CompoundTag points = new CompoundTag();
        for (Map.Entry<String, Integer> e : killPoints.entrySet()) {
            points.putInt(e.getKey(), e.getValue());
        }
        tag.put("killPoints", points);

        ListTag rewards = new ListTag();
        for (long r : podium) {
            rewards.add(net.minecraft.nbt.LongTag.valueOf(r));
        }
        tag.put("podium", rewards);

        ListTag hist = new ListTag();
        for (Podium p : palmares) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", p.day());
            ListTag rows = new ListTag();
            for (PodiumEntry e : p.entries()) {
                CompoundTag r = new CompoundTag();
                r.putString("uuid", e.player().toString());
                r.putString("name", e.name() == null ? "" : e.name());
                r.putLong("points", e.points());
                r.putInt("kills", e.kills());
                r.putLong("reward", e.reward());
                rows.add(r);
            }
            c.put("entries", rows);
            hist.add(c);
        }
        tag.put("palmares", hist);
        return tag;
    }
}
