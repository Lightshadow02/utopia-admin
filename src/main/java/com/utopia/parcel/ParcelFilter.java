package com.utopia.parcel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Criteres de recherche appliques aux listes de parcelles : boutique, parcelles d'un joueur et
 * inventaire d'administration partagent le meme jeu de filtres.
 *
 * <p>Chaque joueur garde ses criteres par ecran, le temps de sa session : on revient a la boutique
 * comme on l'avait laissee, sans que cela encombre la sauvegarde du monde.
 */
public final class ParcelFilter {

    /** Cles d'ecran : chacun garde ses propres criteres. */
    public static final String SHOP = "shop";
    public static final String MINE = "mine";
    public static final String ADMIN = "admin";

    /** Categorie recherchee. Bleu pour l'habitation, orange pour le commerce. */
    public enum Kind {
        TOUTES("Toutes"),
        HABITATION("Habitation"),
        COMMERCE("Commerce"),
        ADMIN("Administratives");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Sale {
        TOUTES("Peu importe"),
        EN_VENTE("En vente"),
        HORS_VENTE("Hors vente");

        private final String label;

        Sale(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Held {
        TOUS("Peu importe"),
        MAIRIE("Mairie"),
        JOUEURS("Joueurs");

        private final String label;

        Held(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Sort {
        ID("Identifiant"),
        PRIX_CROISSANT("Prix croissant"),
        PRIX_DECROISSANT("Prix decroissant"),
        SURFACE("Surface"),
        PROPRIETAIRE("Proprietaire");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public String search = "";
    public Kind kind = Kind.TOUTES;
    public Sale sale = Sale.TOUTES;
    public Held held = Held.TOUS;
    /** Prix maximum accepte ; 0 = sans limite. */
    public long maxPrice;
    public Sort sort = Sort.ID;

    private static final Map<UUID, Map<String, ParcelFilter>> STATE = new HashMap<>();

    /** Criteres du joueur pour cet ecran, crees a la premiere visite. */
    public static ParcelFilter of(ServerPlayer player, String context) {
        return STATE.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .computeIfAbsent(context, k -> new ParcelFilter());
    }

    /** Oublie les criteres d'un joueur (a sa deconnexion) : ils ne survivent pas a la session. */
    public static void forget(UUID player) {
        STATE.remove(player);
    }

    public void reset() {
        search = "";
        kind = Kind.TOUTES;
        sale = Sale.TOUTES;
        held = Held.TOUS;
        maxPrice = 0;
        sort = Sort.ID;
    }

    /** Un critere au moins est-il pose ? (le tri seul ne compte pas comme un filtre) */
    public boolean active() {
        return !search.isBlank() || kind != Kind.TOUTES || sale != Sale.TOUTES
                || held != Held.TOUS || maxPrice > 0;
    }

    // ------------------------------------------------------------------ Application

    public boolean matches(Parcel parcel) {
        switch (kind) {
            case ADMIN -> {
                if (!parcel.isAdmin()) {
                    return false;
                }
            }
            case HABITATION -> {
                if (parcel.isAdmin() || parcel.type() != Parcel.Type.HABITATION) {
                    return false;
                }
            }
            case COMMERCE -> {
                if (parcel.isAdmin() || parcel.type() != Parcel.Type.COMMERCE) {
                    return false;
                }
            }
            default -> {
                // aucune restriction de categorie
            }
        }
        if (sale == Sale.EN_VENTE && !parcel.forSale()) {
            return false;
        }
        if (sale == Sale.HORS_VENTE && parcel.forSale()) {
            return false;
        }
        if (held == Held.MAIRIE && parcel.isOwned()) {
            return false;
        }
        if (held == Held.JOUEURS && !parcel.isOwned()) {
            return false;
        }
        // Le prix ne veut dire quelque chose que sur une parcelle en vente : ailleurs c'est un
        // reliquat de la derniere annonce, jamais remis a zero et jamais affiche.
        if (maxPrice > 0 && parcel.forSale() && parcel.price() > maxPrice) {
            return false;
        }
        if (!search.isBlank()) {
            String needle = search.toLowerCase(Locale.ROOT);
            String owner = parcel.ownerName() == null ? "" : parcel.ownerName().toLowerCase(Locale.ROOT);
            String name = parcel.name() == null ? "" : parcel.name().toLowerCase(Locale.ROOT);
            if (!parcel.id().toLowerCase(Locale.ROOT).contains(needle)
                    && !owner.contains(needle) && !name.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    /** Filtre puis trie une liste de parcelles. */
    public List<Parcel> apply(Collection<Parcel> source) {
        List<Parcel> out = new ArrayList<>();
        for (Parcel parcel : source) {
            if (matches(parcel)) {
                out.add(parcel);
            }
        }
        out.sort(comparator());
        return out;
    }

    private Comparator<Parcel> comparator() {
        Comparator<Parcel> byId = Comparator.comparing(Parcel::id, String.CASE_INSENSITIVE_ORDER);
        return switch (sort) {
            case PRIX_CROISSANT -> saleFirst(Comparator.comparingLong(Parcel::price)).thenComparing(byId);
            case PRIX_DECROISSANT ->
                    saleFirst(Comparator.comparingLong(Parcel::price).reversed()).thenComparing(byId);
            case SURFACE -> Comparator.comparingLong(Parcel::approxFootprint).reversed().thenComparing(byId);
            case PROPRIETAIRE -> Comparator
                    .comparing((Parcel p) -> p.isOwned() ? p.ownerName() : "", String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byId);
            default -> byId;
        };
    }

    /**
     * Trier par prix, c'est trier des annonces : les parcelles hors vente n'en ont pas et se rangent
     * toutes apres, sans que leur ancien prix ne vienne fausser le classement.
     */
    private static Comparator<Parcel> saleFirst(Comparator<Parcel> byPrice) {
        return (a, b) -> {
            if (a.forSale() != b.forSale()) {
                return a.forSale() ? -1 : 1;
            }
            return a.forSale() ? byPrice.compare(a, b) : 0;
        };
    }

    // ------------------------------------------------------------------ Affichage

    /** Resume court des criteres, pour le sous-titre du bouton de recherche. */
    public String summary() {
        if (!active()) {
            return "aucun filtre - tri : " + sort.label().toLowerCase(Locale.ROOT);
        }
        List<String> parts = new ArrayList<>();
        if (!search.isBlank()) {
            parts.add("\"" + search + "\"");
        }
        if (kind != Kind.TOUTES) {
            parts.add(kind.label());
        }
        if (sale != Sale.TOUTES) {
            parts.add(sale.label().toLowerCase(Locale.ROOT));
        }
        if (held != Held.TOUS) {
            parts.add(held.label().toLowerCase(Locale.ROOT));
        }
        if (maxPrice > 0) {
            parts.add("max " + maxPrice);
        }
        return String.join(" - ", parts);
    }

    /** Valeur suivante d'un enum, pour les boutons qui font defiler les choix. */
    public static <E extends Enum<E>> E next(E value) {
        E[] values = value.getDeclaringClass().getEnumConstants();
        return values[(value.ordinal() + 1) % values.length];
    }

    private ParcelFilter() {
    }
}
