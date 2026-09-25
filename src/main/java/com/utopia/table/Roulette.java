package com.utopia.table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Le tapis de la roulette europeenne : trente-sept cases, un seul zero, et tous les paris du
 * tapis. Les combinaisons sont engendrees plutot qu'ecrites une a une - un tapis recopie a la main
 * finit toujours par oublier un carre ou payer une transversale au mauvais rapport.
 */
public final class Roulette {

    /** Les numeros rouges du tapis europeen. Le reste est noir, sauf le zero. */
    private static final Set<Integer> ROUGES = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18,
            19, 21, 23, 25, 27, 30, 32, 34, 36);

    /** Une famille de paris, telle qu'elle se choisit a l'ecran. */
    public enum Famille {
        PLEIN("Plein", 35, "Un seul numero"),
        CHEVAL("Cheval", 17, "Deux numeros voisins"),
        TRANSVERSALE("Transversale", 11, "Les trois numeros d'une ligne"),
        CARRE("Carre", 8, "Quatre numeros en bloc"),
        SIXAIN("Sixain", 5, "Deux lignes de trois"),
        DOUZAINE("Douzaine", 2, "Douze numeros d'affilee"),
        COLONNE("Colonne", 2, "Une colonne du tapis"),
        SIMPLE("Chances simples", 1, "Rouge, noir, pair, impair, manque, passe");

        public final String label;
        /** Ce que rapporte une mise gagnante, en plus de la mise rendue. */
        public final int rapport;
        public final String detail;

        Famille(String label, int rapport, String detail) {
            this.label = label;
            this.rapport = rapport;
            this.detail = detail;
        }
    }

    /** Un pari possible : son nom, les numeros qu'il couvre, ce qu'il rapporte. */
    public record Choix(String libelle, Set<Integer> numeros, int rapport) {
    }

    private Roulette() {
    }

    public static boolean rouge(int numero) {
        return ROUGES.contains(numero);
    }

    /** "17 rouge", "0 vert". */
    public static String decrire(int numero) {
        if (numero == 0) {
            return "0 vert";
        }
        return numero + (rouge(numero) ? " rouge" : " noir");
    }

    /** Tous les paris d'une famille, dans l'ordre du tapis. */
    public static List<Choix> choix(Famille famille) {
        List<Choix> out = new ArrayList<>();
        switch (famille) {
            case PLEIN -> {
                for (int n = 0; n <= 36; n++) {
                    out.add(new Choix(decrire(n), Set.of(n), famille.rapport));
                }
            }
            case CHEVAL -> {
                // Le zero est a cheval avec les trois premiers numeros, comme sur un vrai tapis.
                for (int n = 1; n <= 3; n++) {
                    out.add(paire(0, n, famille.rapport));
                }
                for (int n = 1; n <= 36; n++) {
                    if (n % 3 != 0) {
                        out.add(paire(n, n + 1, famille.rapport)); // voisins de ligne
                    }
                    if (n <= 33) {
                        out.add(paire(n, n + 3, famille.rapport)); // voisins de colonne
                    }
                }
            }
            case TRANSVERSALE -> {
                for (int n = 1; n <= 34; n += 3) {
                    out.add(new Choix(n + "-" + (n + 1) + "-" + (n + 2),
                            new LinkedHashSet<>(List.of(n, n + 1, n + 2)), famille.rapport));
                }
            }
            case CARRE -> {
                for (int n = 1; n <= 32; n++) {
                    if (n % 3 == 0) {
                        continue; // un carre ne deborde pas sur la ligne suivante
                    }
                    out.add(new Choix(n + "-" + (n + 1) + "-" + (n + 3) + "-" + (n + 4),
                            new LinkedHashSet<>(List.of(n, n + 1, n + 3, n + 4)), famille.rapport));
                }
            }
            case SIXAIN -> {
                for (int n = 1; n <= 31; n += 3) {
                    Set<Integer> s = new LinkedHashSet<>();
                    for (int k = n; k < n + 6; k++) {
                        s.add(k);
                    }
                    out.add(new Choix(n + " a " + (n + 5), s, famille.rapport));
                }
            }
            case DOUZAINE -> {
                for (int d = 0; d < 3; d++) {
                    Set<Integer> s = new LinkedHashSet<>();
                    for (int k = d * 12 + 1; k <= d * 12 + 12; k++) {
                        s.add(k);
                    }
                    out.add(new Choix((d * 12 + 1) + " a " + (d * 12 + 12), s, famille.rapport));
                }
            }
            case COLONNE -> {
                for (int c = 1; c <= 3; c++) {
                    Set<Integer> s = new LinkedHashSet<>();
                    for (int k = c; k <= 36; k += 3) {
                        s.add(k);
                    }
                    out.add(new Choix("Colonne " + c + " (finit par " + (c == 3 ? "3, 6, 9..." : c + ", "
                            + (c + 3) + ", " + (c + 6) + "...") + ")", s, famille.rapport));
                }
            }
            case SIMPLE -> {
                out.add(pairs("Rouge", famille.rapport, n -> rouge(n)));
                out.add(pairs("Noir", famille.rapport, n -> !rouge(n)));
                out.add(pairs("Pair", famille.rapport, n -> n % 2 == 0));
                out.add(pairs("Impair", famille.rapport, n -> n % 2 == 1));
                out.add(pairs("Manque (1 a 18)", famille.rapport, n -> n <= 18));
                out.add(pairs("Passe (19 a 36)", famille.rapport, n -> n >= 19));
            }
        }
        return out;
    }

    private static Choix paire(int a, int b, int rapport) {
        return new Choix(a + "-" + b, new LinkedHashSet<>(List.of(a, b)), rapport);
    }

    /**
     * Une chance simple. Le zero n'en fait jamais partie : c'est lui qui donne son avantage a la
     * maison, et l'oublier reviendrait a jouer sans.
     */
    private static Choix pairs(String libelle, int rapport, java.util.function.IntPredicate regle) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int n = 1; n <= 36; n++) {
            if (regle.test(n)) {
                s.add(n);
            }
        }
        return new Choix(libelle, s, rapport);
    }
}
