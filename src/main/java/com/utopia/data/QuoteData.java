package com.utopia.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Donnees persistantes des devis entre joueurs : le devis lui-meme (objet, lignes chiffrees, etat) et
 * l'historique consultable par l'emetteur, par le destinataire et par l'administration.
 *
 * <p>Un devis n'est jamais supprime une fois envoye : il reste dans l'historique des deux parties,
 * c'est ce qui lui donne sa valeur de trace. Seul un brouillon peut disparaitre.
 */
public final class QuoteData extends SavedData {

    private static final String ID = "utopia_quotes";
    /** Nombre maximal de devis conserves ; au-dela, les plus anciens devis clos sont oublies. */
    private static final int MAX_QUOTES = 800;
    /** Nombre maximal de lignes sur un devis. */
    public static final int MAX_LINES = 30;
    /** Brouillons ouverts simultanement par joueur : sans plafond, ils chasseraient les archives. */
    public static final int MAX_DRAFTS = 10;
    /** Un brouillon jamais envoye est considere comme abandonne au bout de 30 jours. */
    private static final long DRAFT_TTL_MS = 30L * 86_400_000L;

    public static final SavedData.Factory<QuoteData> FACTORY =
            new SavedData.Factory<>(QuoteData::new, QuoteData::load, null);

    /** Cycle de vie d'un devis, du brouillon au reglement. */
    public enum Status {
        BROUILLON("Brouillon"),
        ENVOYE("Envoye"),
        ACCEPTE("Accepte"),
        REFUSE("Refuse"),
        EXPIRE("Expire"),
        SOLDE("Solde"),
        ANNULE("Annule");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Un devis clos ne bouge plus : il ne vit que comme trace dans l'historique. */
        public boolean closed() {
            return this == REFUSE || this == EXPIRE || this == SOLDE || this == ANNULE;
        }
    }

    /** Une ligne chiffree : une designation, une quantite et un prix unitaire. */
    public static final class Line {
        public String label;
        public int quantity;
        public long unitPrice;

        public Line(String label, int quantity, long unitPrice) {
            this.label = label;
            this.quantity = Math.max(1, quantity);
            this.unitPrice = Math.max(0, unitPrice);
        }

        public long total() {
            return (long) quantity * unitPrice;
        }
    }

    /** Un devis. */
    public static final class Quote {
        public final String id;
        public final UUID issuer;
        public UUID client;                 // null tant que le devis est un brouillon non adresse
        public String title = "Devis";
        public String note = "";
        public Status status = Status.BROUILLON;
        public final List<Line> lines = new ArrayList<>();
        public long createdAt;
        public long sentAt;
        public long decidedAt;
        public int validityDays;            // 0 = sans date limite
        public long paid;                   // deja regle par le client

        public Quote(String id, UUID issuer) {
            this.id = id;
            this.issuer = issuer;
        }

        public long total() {
            long total = 0;
            for (Line line : lines) {
                total += line.total();
            }
            return total;
        }

        public long remaining() {
            return Math.max(0, total() - paid);
        }

        /** Date limite de validite en millisecondes, ou 0 si le devis n'expire pas. */
        public long deadline() {
            return validityDays <= 0 || sentAt <= 0 ? 0 : sentAt + validityDays * 86_400_000L;
        }

        public boolean expired(long now) {
            long deadline = deadline();
            return deadline > 0 && now >= deadline;
        }

        /** Le destinataire peut-il encore se prononcer ? */
        public boolean awaitingAnswer() {
            return status == Status.ENVOYE;
        }
    }

    private final Map<String, Quote> quotes = new LinkedHashMap<>();
    private final Map<UUID, String> knownNames = new LinkedHashMap<>();
    private final Map<UUID, List<String>> pending = new LinkedHashMap<>();
    private int counter;

    // -------- Reglages (administration) --------

    private int taxPercent;                 // part prelevee par la mairie sur chaque reglement
    private int defaultValidityDays = 7;

    public QuoteData() {
    }

    public static QuoteData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // -------- Reglages --------

    public int taxPercent() {
        return taxPercent;
    }

    public void setTaxPercent(int value) {
        this.taxPercent = Math.max(0, Math.min(100, value));
        setDirty();
    }

    public int defaultValidityDays() {
        return defaultValidityDays;
    }

    public void setDefaultValidityDays(int value) {
        this.defaultValidityDays = Math.max(0, Math.min(365, value));
        setDirty();
    }

    // -------- Devis --------

    public Quote quote(String id) {
        return id == null ? null : quotes.get(id);
    }

    /** Ouvre un brouillon, ou {@code null} si le joueur en a deja trop en cours. */
    public Quote create(UUID issuer) {
        prune();
        if (draftCount(issuer) >= MAX_DRAFTS) {
            return null;
        }
        String id = String.format("D-%04d", ++counter);
        while (quotes.containsKey(id)) {
            id = String.format("D-%04d", ++counter);
        }
        Quote quote = new Quote(id, issuer);
        quote.createdAt = System.currentTimeMillis();
        quote.validityDays = defaultValidityDays;
        quotes.put(id, quote);
        setDirty();
        return quote;
    }

    /** Brouillons encore ouverts par ce joueur. */
    public int draftCount(UUID issuer) {
        int count = 0;
        for (Quote q : quotes.values()) {
            if (q.status == Status.BROUILLON && q.issuer.equals(issuer)) {
                count++;
            }
        }
        return count;
    }

    /** Supprime un devis ; reserve aux brouillons et a l'administration. */
    public boolean remove(String id) {
        if (quotes.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Tous les devis, du plus recent au plus ancien. */
    public List<Quote> all() {
        List<Quote> out = new ArrayList<>(quotes.values());
        out.sort(Comparator.comparingLong((Quote q) -> Math.max(q.createdAt, q.sentAt)).reversed());
        return out;
    }

    /** Devis emis par ce joueur, du plus recent au plus ancien. */
    public List<Quote> issuedBy(UUID player) {
        List<Quote> out = new ArrayList<>();
        for (Quote q : all()) {
            if (q.issuer.equals(player)) {
                out.add(q);
            }
        }
        return out;
    }

    /** Devis recus par ce joueur (donc deja envoyes), du plus recent au plus ancien. */
    public List<Quote> receivedBy(UUID player) {
        List<Quote> out = new ArrayList<>();
        for (Quote q : all()) {
            // Un devis jamais envoye n'existe pas pour son destinataire, quel que soit son statut.
            if (player.equals(q.client) && q.sentAt > 0) {
                out.add(q);
            }
        }
        return out;
    }

    /** Devis recus en attente de reponse : ce qui merite une pastille dans le menu. */
    public int awaitingCount(UUID player) {
        int count = 0;
        for (Quote q : quotes.values()) {
            if (player.equals(q.client) && q.awaitingAnswer()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Fait le menage. On commence par les brouillons abandonnes : un devis jamais envoye n'est la
     * trace de rien, il ne doit surtout pas faire disparaitre une archive pour se loger. Ce n'est
     * qu'ensuite, si le magasin deborde encore, que les plus anciens devis clos sont oublies. Un
     * devis vivant (envoye ou accepte) n'est jamais sacrifie.
     *
     * @return le nombre de devis oublies
     */
    public int prune() {
        long now = System.currentTimeMillis();
        int before = quotes.size();
        quotes.values().removeIf(q -> q.status == Status.BROUILLON && q.sentAt <= 0
                && now - q.createdAt > DRAFT_TTL_MS);

        if (quotes.size() > MAX_QUOTES) {
            List<Quote> closed = new ArrayList<>();
            for (Quote q : quotes.values()) {
                if (q.status.closed()) {
                    closed.add(q);
                }
            }
            closed.sort(Comparator.comparingLong(q -> Math.max(q.createdAt, q.decidedAt)));
            int excess = quotes.size() - MAX_QUOTES;
            for (int i = 0; i < excess && i < closed.size(); i++) {
                quotes.remove(closed.get(i).id);
            }
        }
        int removed = before - quotes.size();
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    // -------- Pseudos connus --------

    public String nameOf(UUID player) {
        if (player == null) {
            return "-";
        }
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

    // -------- Notifications en attente (joueur hors ligne) --------

    public void addPending(UUID player, String message) {
        pending.computeIfAbsent(player, k -> new ArrayList<>()).add(message);
        setDirty();
    }

    /** Recupere et efface les notifications : elles ne sont donc affichees qu'une fois. */
    public List<String> takePending(UUID player) {
        List<String> out = pending.remove(player);
        if (out != null && !out.isEmpty()) {
            setDirty();
            return out;
        }
        return List.of();
    }

    // -------- Serialisation --------

    public static QuoteData load(CompoundTag tag, HolderLookup.Provider registries) {
        QuoteData data = new QuoteData();
        data.counter = tag.getInt("counter");
        data.taxPercent = tag.getInt("tax");
        if (tag.contains("validity")) {
            data.defaultValidityDays = tag.getInt("validity");
        }

        ListTag list = tag.getList("quotes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag q = list.getCompound(i);
            UUID issuer;
            try {
                issuer = UUID.fromString(q.getString("issuer"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String id = q.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Quote quote = new Quote(id, issuer);
            if (q.contains("client")) {
                try {
                    quote.client = UUID.fromString(q.getString("client"));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : le devis reste sans destinataire
                }
            }
            quote.title = q.getString("title");
            quote.note = q.getString("note");
            try {
                quote.status = Status.valueOf(q.getString("status"));
            } catch (IllegalArgumentException e) {
                quote.status = Status.BROUILLON;
            }
            quote.createdAt = q.getLong("created");
            quote.sentAt = q.getLong("sent");
            quote.decidedAt = q.getLong("decided");
            quote.validityDays = q.getInt("validity");
            quote.paid = q.getLong("paid");
            ListTag lines = q.getList("lines", Tag.TAG_COMPOUND);
            for (int k = 0; k < lines.size() && quote.lines.size() < MAX_LINES; k++) {
                CompoundTag l = lines.getCompound(k);
                quote.lines.add(new Line(l.getString("label"), l.getInt("qty"), l.getLong("price")));
            }
            data.quotes.put(id, quote);
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("counter", counter);
        tag.putInt("tax", taxPercent);
        tag.putInt("validity", defaultValidityDays);

        ListTag list = new ListTag();
        for (Quote quote : quotes.values()) {
            CompoundTag q = new CompoundTag();
            q.putString("id", quote.id);
            q.putString("issuer", quote.issuer.toString());
            if (quote.client != null) {
                q.putString("client", quote.client.toString());
            }
            q.putString("title", quote.title);
            q.putString("note", quote.note);
            q.putString("status", quote.status.name());
            q.putLong("created", quote.createdAt);
            q.putLong("sent", quote.sentAt);
            q.putLong("decided", quote.decidedAt);
            q.putInt("validity", quote.validityDays);
            q.putLong("paid", quote.paid);
            ListTag lines = new ListTag();
            for (Line line : quote.lines) {
                CompoundTag l = new CompoundTag();
                l.putString("label", line.label);
                l.putInt("qty", line.quantity);
                l.putLong("price", line.unitPrice);
                lines.add(l);
            }
            q.put("lines", lines);
            list.add(q);
        }
        tag.put("quotes", list);

        ListTag names = new ListTag();
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
            CompoundTag n = new CompoundTag();
            n.putString("uuid", e.getKey().toString());
            n.putString("name", e.getValue());
            names.add(n);
        }
        tag.put("names", names);

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

    /** Tous les joueurs apparaissant dans au moins un devis (pour les filtres d'administration). */
    public Set<UUID> participants() {
        Set<UUID> out = new LinkedHashSet<>();
        for (Quote q : quotes.values()) {
            out.add(q.issuer);
            if (q.client != null) {
                out.add(q.client);
            }
        }
        return out;
    }
}
