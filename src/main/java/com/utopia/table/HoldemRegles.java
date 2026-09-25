package com.utopia.table;

/**
 * Les regles de paiement du Casino Hold'em, seules et sans rien autour.
 *
 * <p>Elles sont tenues a part de la table et de son ecran pour une raison simple : c'est le seul
 * endroit du jeu ou une erreur se compte en Utopieces, et il doit pouvoir se relire - et se
 * verifier - sans rien connaitre du reste du mod.
 */
public final class HoldemRegles {

    private HoldemRegles() {
    }

    /** Ce que paie l'ante selon la main du joueur, en plus de l'ante rendue. */
    public static int rapportAnte(Cartes.Combinaison c) {
        return switch (c) {
            case QUINTE_FLUSH_ROYALE -> 100;
            case QUINTE_FLUSH -> 20;
            case CARRE -> 10;
            case FULL -> 3;
            case COULEUR -> 2;
            default -> 1;
        };
    }

    /**
     * Le croupier n'entre en jeu qu'a partir d'une paire de quatre. C'est cette regle qui donne au
     * jeu son interet : sans elle, il suffirait de se coucher des qu'on n'a rien.
     */
    public static boolean qualifie(Cartes.Eval eval) {
        if (eval.combinaison().ordinal() > Cartes.Combinaison.PAIRE.ordinal()) {
            return true;
        }
        if (eval.combinaison() != Cartes.Combinaison.PAIRE) {
            return false;
        }
        // La force d'une paire s'ecrit en base quinze, rang double en tete : on remonte jusqu'a lui.
        long rangDouble = eval.force();
        while (rangDouble >= 15) {
            rangDouble /= 15;
        }
        return rangDouble >= 4;
    }

    /**
     * Ce que rendent l'ante et le suivi, mise comprise.
     *
     * @return {@code [retour de l'ante, retour du suivi]} ; zero signifie perdu
     */
    public static long[] gains(Cartes.Eval joueur, Cartes.Eval croupier, long ante, long suivi) {
        int rapport = rapportAnte(joueur.combinaison());
        if (!qualifie(croupier)) {
            // Croupier hors jeu : l'ante paie sa table, le suivi n'a servi a rien et revient.
            return new long[] {ante * (1L + rapport), suivi};
        }
        int comparaison = joueur.compareTo(croupier);
        if (comparaison > 0) {
            return new long[] {ante * (1L + rapport), suivi * 2};
        }
        if (comparaison == 0) {
            return new long[] {ante, suivi};
        }
        return new long[] {0, 0};
    }

    /** La phrase qui explique le resultat, dans les memes termes que le calcul. */
    public static String resume(Cartes.Eval joueur, Cartes.Eval croupier) {
        if (!qualifie(croupier)) {
            return "Croupier non qualifie : l'ante paie " + rapportAnte(joueur.combinaison())
                    + " contre 1";
        }
        int comparaison = joueur.compareTo(croupier);
        if (comparaison > 0) {
            return "Gagne avec " + joueur.combinaison().label;
        }
        return comparaison == 0 ? "Egalite : tout est rendu"
                : "Perdu contre " + croupier.combinaison().label;
    }
}
