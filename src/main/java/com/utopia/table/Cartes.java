package com.utopia.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Le jeu de cartes des tables : un sabot, et de quoi juger une main.
 *
 * <p>Les enseignes sont ecrites en echappements Unicode plutot qu'en caracteres : le fichier reste
 * lisible partout et l'encodage ne peut pas se perdre en chemin.
 */
public final class Cartes {

    public enum Couleur {
        PIQUE("♠", true), COEUR("♥", false), CARREAU("♦", false), TREFLE("♣", true);

        public final String signe;
        /** Vrai pour les enseignes noires : la couleur d'affichage en depend. */
        public final boolean noire;

        Couleur(String signe, boolean noire) {
            this.signe = signe;
            this.noire = noire;
        }
    }

    /** Une carte. Le rang va de 2 a 14, l'as valant 14 : c'est l'ordre du poker. */
    public record Carte(int rang, Couleur couleur) {

        public String libelle() {
            return nomRang(rang) + couleur.signe;
        }
    }

    public static String nomRang(int rang) {
        return switch (rang) {
            case 11 -> "V";
            case 12 -> "D";
            case 13 -> "R";
            case 14 -> "A";
            default -> String.valueOf(rang);
        };
    }

    /**
     * Le sabot. Il contient plusieurs jeux comme dans un vrai casino et se remelange quand il
     * descend sous le quart, ce qui retire tout interet a compter les cartes.
     *
     * <p>Le remelange n'arrive qu'<b>entre deux mains</b>, jamais pendant. Remelanger en plein
     * milieu d'une donne remettrait dans le sabot des cartes deja sur la table : le croupier
     * pourrait servir un second as de pique a qui en a deja un, et un tapis de Hold'em afficherait
     * une figure impossible.
     */
    public static final class Sabot {
        private final int jeux;
        private final List<Carte> cartes = new ArrayList<>();
        private final Random alea = new Random();

        public Sabot(int jeux) {
            this.jeux = Math.max(1, jeux);
            remelanger();
        }

        public void remelanger() {
            cartes.clear();
            for (int j = 0; j < jeux; j++) {
                for (Couleur c : Couleur.values()) {
                    for (int r = 2; r <= 14; r++) {
                        cartes.add(new Carte(r, c));
                    }
                }
            }
            Collections.shuffle(cartes, alea);
        }

        public int restantes() {
            return cartes.size();
        }

        /**
         * A appeler avant de servir une main, et la seulement. Renvoie vrai si le sabot a ete
         * refait, ce qui merite d'etre annonce a la table.
         */
        public boolean remelangerSiEntame() {
            if (cartes.size() > jeux * 52 / 4) {
                return false;
            }
            remelanger();
            return true;
        }

        public Carte tirer() {
            if (cartes.isEmpty()) {
                remelanger(); // ceinture : remelangerSiEntame doit deja l'avoir evite
            }
            return cartes.remove(cartes.size() - 1);
        }

        public List<Carte> tirer(int combien) {
            List<Carte> main = new ArrayList<>(combien);
            for (int i = 0; i < combien; i++) {
                main.add(tirer());
            }
            return main;
        }
    }

    // ------------------------------------------------------------------ Blackjack

    /**
     * Le total d'une main au blackjack. Les as valent onze tant que la main tient, un ensuite :
     * on compte tout a onze puis on redescend autant d'as que necessaire.
     */
    public static int total(List<Carte> main) {
        int somme = 0;
        int as = 0;
        for (Carte c : main) {
            if (c.rang() == 14) {
                as++;
                somme += 11;
            } else {
                somme += Math.min(10, c.rang());
            }
        }
        while (somme > 21 && as > 0) {
            somme -= 10;
            as--;
        }
        return somme;
    }

    /** Vrai si la main compte un as valant encore onze : le croupier s'arrete aussi sur un 17 mou. */
    public static boolean molle(List<Carte> main) {
        int somme = 0;
        int as = 0;
        for (Carte c : main) {
            if (c.rang() == 14) {
                as++;
                somme += 11;
            } else {
                somme += Math.min(10, c.rang());
            }
        }
        int abaisses = 0;
        while (somme > 21 && abaisses < as) {
            somme -= 10;
            abaisses++;
        }
        return abaisses < as;
    }

    public static boolean blackjack(List<Carte> main) {
        return main.size() == 2 && total(main) == 21;
    }

    // ------------------------------------------------------------------ Poker

    /** Les combinaisons, de la plus faible a la plus forte. */
    public enum Combinaison {
        CARTE_HAUTE("Carte haute"), PAIRE("Paire"), DEUX_PAIRES("Deux paires"), BRELAN("Brelan"),
        QUINTE("Quinte"), COULEUR("Couleur"), FULL("Full"), CARRE("Carre"),
        QUINTE_FLUSH("Quinte flush"), QUINTE_FLUSH_ROYALE("Quinte flush royale");

        public final String label;

        Combinaison(String label) {
            this.label = label;
        }
    }

    /**
     * Une main jugee. {@code force} range deux mains de meme combinaison : elle encode les rangs
     * qui departagent, du plus decisif au moins, ce qui evite une comparaison au cas par cas.
     */
    public record Eval(Combinaison combinaison, long force) implements Comparable<Eval> {

        @Override
        public int compareTo(Eval autre) {
            int parCombinaison = Integer.compare(combinaison.ordinal(), autre.combinaison.ordinal());
            return parCombinaison != 0 ? parCombinaison : Long.compare(force, autre.force);
        }
    }

    /**
     * La meilleure main de cinq cartes parmi celles fournies. Les vingt et une combinaisons d'un
     * sept-cartes sont toutes essayees : c'est immediat a cette taille, et aucune regle de
     * raccourci ne peut alors se tromper sur un cas tordu.
     */
    public static Eval meilleure(List<Carte> cartes) {
        if (cartes.size() < 5) {
            return new Eval(Combinaison.CARTE_HAUTE, 0);
        }
        Eval meilleure = null;
        int n = cartes.size();
        int[] idx = new int[5];
        for (idx[0] = 0; idx[0] < n - 4; idx[0]++) {
            for (idx[1] = idx[0] + 1; idx[1] < n - 3; idx[1]++) {
                for (idx[2] = idx[1] + 1; idx[2] < n - 2; idx[2]++) {
                    for (idx[3] = idx[2] + 1; idx[3] < n - 1; idx[3]++) {
                        for (idx[4] = idx[3] + 1; idx[4] < n; idx[4]++) {
                            List<Carte> cinq = List.of(cartes.get(idx[0]), cartes.get(idx[1]),
                                    cartes.get(idx[2]), cartes.get(idx[3]), cartes.get(idx[4]));
                            Eval e = juger(cinq);
                            if (meilleure == null || e.compareTo(meilleure) > 0) {
                                meilleure = e;
                            }
                        }
                    }
                }
            }
        }
        return meilleure;
    }

    /** Juge exactement cinq cartes. */
    private static Eval juger(List<Carte> cinq) {
        int[] compte = new int[15];
        int[] enseignes = new int[4];
        for (Carte c : cinq) {
            compte[c.rang()]++;
            enseignes[c.couleur().ordinal()]++;
        }
        boolean couleur = false;
        for (int e : enseignes) {
            if (e == 5) {
                couleur = true;
            }
        }
        int hauteQuinte = hauteurQuinte(compte);

        if (couleur && hauteQuinte == 14) {
            return new Eval(Combinaison.QUINTE_FLUSH_ROYALE, 0);
        }
        if (couleur && hauteQuinte > 0) {
            return new Eval(Combinaison.QUINTE_FLUSH, hauteQuinte);
        }
        // Les rangs sont classes par nombre d'exemplaires d'abord, par hauteur ensuite : c'est
        // exactement l'ordre dans lequel le poker departage deux mains de meme combinaison.
        List<int[]> groupes = new ArrayList<>();
        for (int r = 14; r >= 2; r--) {
            if (compte[r] > 0) {
                groupes.add(new int[] {compte[r], r});
            }
        }
        groupes.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(b[1], a[1]));
        long force = 0;
        for (int[] g : groupes) {
            force = force * 15 + g[1];
        }

        int plusGrand = groupes.get(0)[0];
        int second = groupes.size() > 1 ? groupes.get(1)[0] : 0;
        if (plusGrand == 4) {
            return new Eval(Combinaison.CARRE, force);
        }
        if (plusGrand == 3 && second == 2) {
            return new Eval(Combinaison.FULL, force);
        }
        if (couleur) {
            return new Eval(Combinaison.COULEUR, force);
        }
        if (hauteQuinte > 0) {
            return new Eval(Combinaison.QUINTE, hauteQuinte);
        }
        if (plusGrand == 3) {
            return new Eval(Combinaison.BRELAN, force);
        }
        if (plusGrand == 2 && second == 2) {
            return new Eval(Combinaison.DEUX_PAIRES, force);
        }
        if (plusGrand == 2) {
            return new Eval(Combinaison.PAIRE, force);
        }
        return new Eval(Combinaison.CARTE_HAUTE, force);
    }

    /** Hauteur de la quinte, ou zero. L'as compte aussi pour un dans la quinte A-2-3-4-5. */
    private static int hauteurQuinte(int[] compte) {
        for (int haut = 14; haut >= 6; haut--) {
            boolean suite = true;
            for (int r = haut; r > haut - 5; r--) {
                if (compte[r] == 0) {
                    suite = false;
                    break;
                }
            }
            if (suite) {
                return haut;
            }
        }
        if (compte[14] > 0 && compte[2] > 0 && compte[3] > 0 && compte[4] > 0 && compte[5] > 0) {
            return 5; // la petite quinte, ou l'as se couche
        }
        return 0;
    }

    private Cartes() {
    }
}
