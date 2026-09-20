package com.utopia.client.arcade;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Puissance 4 contre l'ordinateur : sept colonnes, six rangees, le premier qui aligne quatre
 * jetons prend la manche. Le joueur est jaune, la machine rouge.
 *
 * <p>Tout l'interet du jeu tient a la force de l'adversaire. Il explore l'arbre des coups en
 * minimax avec elagage alpha-beta sur cinq demi-coups, mais deux garde-fous encadrent ce calcul :
 * la victoire immediate et le blocage d'un alignement adverse sont joues sans discussion, et en
 * dehors de ces cas il lui arrive de prendre le deuxieme meilleur coup. Une machine parfaite au
 * Puissance 4 ne perd jamais, et une borne d'arcade invincible ne se rejoue pas.
 *
 * <p>La chute du jeton est animee et bloque les commandes tant qu'elle dure : c'est ce qui donne
 * du poids au geste, et cela evite qu'un double clic ne joue deux colonnes d'un coup.
 *
 * <p>La partie enchaine les manches et cumule les points jusqu'a la premiere defaite : une bonne
 * lecture du plateau se paie en duree, un seul quatre encaisse ferme la borne.
 */
public final class Puissance4Game implements ArcadeGame {

    private static final int COLS = 7;
    private static final int ROWS = 6;
    private static final int VIDE = 0;
    private static final int HUMAIN = 1;
    private static final int IA = 2;

    /** Terrain logique : bande du jeton en attente, grille, puis ligne de texte sous le plateau. */
    private static final double BANDE = 1.0;
    private static final double TEXTE = 0.7;
    private static final double W = COLS;
    private static final double H = BANDE + ROWS + TEXTE;

    /** Reglee pour une chute pleine hauteur en une demi-seconde : lisible sans etre lente. */
    private static final double GRAVITE = 52.0;

    private static final int PROFONDEUR = 5;
    private static final int VICTOIRE = 1_000_000;
    private static final int INFINI = Integer.MAX_VALUE / 4;
    /** Colonnes visitees du centre vers les bords : l'elagage alpha-beta coupe bien plus tot. */
    private static final int[] ORDRE = {3, 2, 4, 1, 5, 0, 6};
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private static final long GAIN_MANCHE = 1000L;
    private static final long BONUS_RAPIDITE = 60L;
    private static final long GAIN_NULLE = 250L;
    /** La defaite rapporte peu mais rapporte : le score affiche ne doit jamais reculer. */
    private static final long GAIN_DEFAITE = 50L;

    private static final int COULEUR_HUMAIN = 0xFFF2C63C;
    private static final int COULEUR_IA = 0xFFD8434B;
    private static final int COULEUR_PLATEAU = 0xFF1B3FA8;
    private static final int COULEUR_TROU = 0xFF0A1226;

    private enum Etat { ATTENTE, CHUTE, REFLEXION, ECLAT, FIN }

    private final Random alea = new Random();
    /** Ligne 0 en haut ; la gravite remplit depuis la ligne ROWS-1. */
    private final int[][] cellules = new int[ROWS][COLS];
    private final int[] hauteurs = new int[COLS];

    private Etat etat = Etat.ATTENTE;
    private int selection = 3;
    private int manche = 1;
    private int coupsJoueur;
    private long score;
    private boolean fini;
    /** Alterne a chaque manche pour que personne ne garde l'avantage du premier coup. */
    private boolean iaCommence;

    private int chuteCol;
    private int chuteJoueur;
    private double chuteY;
    private double chuteVY;
    private double chuteCible;

    private double attente;
    private double eclat;
    private double dureeEclat;
    private int vainqueurManche = VIDE;
    private int[] cellulesGagnantes;

    // ------------------------------------------------------------------ plateau

    private boolean jouable(int col) {
        return col >= 0 && col < COLS && hauteurs[col] < ROWS;
    }

    private int poser(int col, int joueur) {
        int ligne = ROWS - 1 - hauteurs[col];
        cellules[ligne][col] = joueur;
        hauteurs[col]++;
        return ligne;
    }

    private void retirer(int col) {
        hauteurs[col]--;
        cellules[ROWS - 1 - hauteurs[col]][col] = VIDE;
    }

    private boolean plateauPlein() {
        for (int col = 0; col < COLS; col++) {
            if (hauteurs[col] < ROWS) {
                return false;
            }
        }
        return true;
    }

    private int jetonsPoses() {
        int total = 0;
        for (int col = 0; col < COLS; col++) {
            total += hauteurs[col];
        }
        return total;
    }

    private static boolean dedans(int ligne, int col) {
        return ligne >= 0 && ligne < ROWS && col >= 0 && col < COLS;
    }

    /** Vrai si le jeton pose en (ligne, col) ferme un alignement de quatre. */
    private boolean gagne(int ligne, int col) {
        return ligneGagnante(ligne, col) != null;
    }

    /**
     * Les quatre cases de l'alignement ferme par le jeton pose, ou null. On repart du bout de la
     * serie pour que le halo couvre bien quatre cases meme quand cinq jetons se suivent.
     */
    private int[] ligneGagnante(int ligne, int col) {
        if (!dedans(ligne, col)) {
            return null;
        }
        int joueur = cellules[ligne][col];
        if (joueur == VIDE) {
            return null;
        }
        for (int[] d : DIRS) {
            int dl = d[0];
            int dc = d[1];
            int l = ligne;
            int c = col;
            while (dedans(l - dl, c - dc) && cellules[l - dl][c - dc] == joueur) {
                l -= dl;
                c -= dc;
            }
            int longueur = 0;
            int ll = l;
            int cc = c;
            while (dedans(ll, cc) && cellules[ll][cc] == joueur) {
                longueur++;
                ll += dl;
                cc += dc;
            }
            if (longueur >= 4) {
                int[] cases = new int[4];
                for (int i = 0; i < 4; i++) {
                    cases[i] = (l + i * dl) * COLS + (c + i * dc);
                }
                return cases;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ intelligence

    /**
     * Note la position du point de vue de la machine. Seules comptent les fenetres de quatre cases
     * encore ouvertes : trois jetons dans une fenetre deja polluee par l'adversaire ne valent rien.
     * Les menaces adverses pesent un peu plus lourd que les siennes, faute de quoi elle attaque au
     * lieu de defendre et se fait battre d'un temps.
     */
    private int evaluer() {
        int note = 0;
        for (int l = 0; l < ROWS; l++) {
            for (int c = 0; c + 3 < COLS; c++) {
                note += fenetre(l, c, 0, 1);
            }
        }
        for (int l = 0; l + 3 < ROWS; l++) {
            for (int c = 0; c < COLS; c++) {
                note += fenetre(l, c, 1, 0);
            }
        }
        for (int l = 0; l + 3 < ROWS; l++) {
            for (int c = 0; c + 3 < COLS; c++) {
                note += fenetre(l, c, 1, 1);
            }
        }
        for (int l = 0; l + 3 < ROWS; l++) {
            for (int c = 3; c < COLS; c++) {
                note += fenetre(l, c, 1, -1);
            }
        }
        // Le centre traverse le plus de fenetres : une colonne centrale vaut plus qu'un bord.
        for (int l = 0; l < ROWS; l++) {
            note += poids(cellules[l][3]) * 7;
            note += (poids(cellules[l][2]) + poids(cellules[l][4])) * 3;
        }
        return note;
    }

    private static int poids(int cellule) {
        if (cellule == IA) {
            return 1;
        }
        return cellule == HUMAIN ? -1 : 0;
    }

    private int fenetre(int ligne, int col, int dl, int dc) {
        int ia = 0;
        int humain = 0;
        for (int i = 0; i < 4; i++) {
            int v = cellules[ligne + i * dl][col + i * dc];
            if (v == IA) {
                ia++;
            } else if (v == HUMAIN) {
                humain++;
            }
        }
        if (ia > 0 && humain > 0) {
            return 0;
        }
        if (ia == 4) {
            return VICTOIRE;
        }
        if (humain == 4) {
            return -VICTOIRE;
        }
        if (ia == 3) {
            return 55;
        }
        if (humain == 3) {
            return -62;
        }
        if (ia == 2) {
            return 12;
        }
        return humain == 2 ? -14 : 0;
    }

    /**
     * Minimax avec elagage alpha-beta. La prime decroissante sur les victoires pousse la machine a
     * gagner tout de suite plutot que dans trois coups, et a repousser sa defaite le plus loin
     * possible : sans elle, elle abandonne des qu'elle voit une perte forcee.
     */
    private int minimax(int profondeur, int alpha, int beta, boolean tourIa) {
        if (profondeur <= 0) {
            return evaluer();
        }
        int joueur = tourIa ? IA : HUMAIN;
        int meilleur = tourIa ? -INFINI : INFINI;
        boolean unCoupAuMoins = false;
        for (int col : ORDRE) {
            if (!jouable(col)) {
                continue;
            }
            unCoupAuMoins = true;
            int ligne = poser(col, joueur);
            int valeur;
            if (gagne(ligne, col)) {
                valeur = tourIa ? VICTOIRE + profondeur * 100 : -VICTOIRE - profondeur * 100;
            } else {
                valeur = minimax(profondeur - 1, alpha, beta, !tourIa);
            }
            retirer(col);
            if (tourIa) {
                meilleur = Math.max(meilleur, valeur);
                alpha = Math.max(alpha, meilleur);
            } else {
                meilleur = Math.min(meilleur, valeur);
                beta = Math.min(beta, meilleur);
            }
            if (alpha >= beta) {
                break;
            }
        }
        return unCoupAuMoins ? meilleur : 0;
    }

    /** Colonne choisie par la machine, ou -1 si le plateau est plein. */
    private int coupOrdinateur() {
        // Ouverture variee : sans cela toutes les manches commencent par la meme sequence.
        if (jetonsPoses() <= 1) {
            int[] debut = {3, 3, 3, 2, 4};
            int col = debut[alea.nextInt(debut.length)];
            if (jouable(col)) {
                return col;
            }
        }
        int gagnant = coupImmediat(IA);
        if (gagnant >= 0) {
            return gagnant;
        }
        // Blocage traite a part : c'est la seule bourde que le joueur ne pardonnerait pas.
        int blocage = coupImmediat(HUMAIN);
        if (blocage >= 0) {
            return blocage;
        }

        int meilleurCol = -1;
        int meilleur = -INFINI;
        int secondCol = -1;
        int second = -INFINI;
        for (int col : ORDRE) {
            if (!jouable(col)) {
                continue;
            }
            int ligne = poser(col, IA);
            // Fenetre complete a chaque racine : les valeurs doivent etre exactes pour departager
            // le meilleur coup du suivant.
            int valeur = gagne(ligne, col)
                    ? VICTOIRE + PROFONDEUR * 100
                    : minimax(PROFONDEUR - 1, -INFINI, INFINI, false);
            retirer(col);
            if (valeur > meilleur) {
                second = meilleur;
                secondCol = meilleurCol;
                meilleur = valeur;
                meilleurCol = col;
            } else if (valeur > second) {
                second = valeur;
                secondCol = col;
            }
        }
        // La faille volontaire : un coup presque aussi bon, de temps en temps. Elle ne touche ni
        // aux gains immediats ni aux positions deja perdues, donc la machine reste coherente.
        if (secondCol >= 0 && meilleur < VICTOIRE / 2 && second > -VICTOIRE / 2
                && meilleur - second <= 40 && alea.nextDouble() < 0.15) {
            return secondCol;
        }
        return meilleurCol;
    }

    /** Colonne ou {@code joueur} aligne quatre des maintenant, ou -1. */
    private int coupImmediat(int joueur) {
        for (int col : ORDRE) {
            if (!jouable(col)) {
                continue;
            }
            int ligne = poser(col, joueur);
            boolean quatre = gagne(ligne, col);
            retirer(col);
            if (quatre) {
                return col;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ deroulement

    @Override
    public void update(double dt) {
        if (fini) {
            return;
        }
        // Une image tres longue ne doit pas telescoper deux etapes de la manche.
        double pas = Math.min(Math.max(dt, 0.0), 0.25);
        switch (etat) {
            case REFLEXION -> reflechir(pas);
            case CHUTE -> tomber(pas);
            case ECLAT -> eclater(pas);
            default -> {
                // ATTENTE et FIN ne consomment pas de temps : le Puissance 4 attend le joueur.
            }
        }
    }

    private void reflechir(double dt) {
        attente -= dt;
        if (attente > 0) {
            return;
        }
        int col = coupOrdinateur();
        if (col < 0) {
            terminerManche(VIDE, null);
            return;
        }
        lancerJeton(col, IA);
    }

    private void lancerJeton(int col, int joueur) {
        chuteCol = col;
        chuteJoueur = joueur;
        chuteY = BANDE / 2.0;
        chuteVY = 0;
        chuteCible = BANDE + (ROWS - 1 - hauteurs[col]) + 0.5;
        etat = Etat.CHUTE;
    }

    private void tomber(double dt) {
        // Pas fixes : a dix images par seconde, un jeton integre d'un bloc depasserait le fond.
        double reste = dt;
        while (reste > 0 && etat == Etat.CHUTE) {
            double pas = Math.min(reste, 1.0 / 240.0);
            reste -= pas;
            chuteVY += GRAVITE * pas;
            chuteY += chuteVY * pas;
            if (chuteY >= chuteCible) {
                chuteY = chuteCible;
                atterrir();
            }
        }
    }

    private void atterrir() {
        int ligne = poser(chuteCol, chuteJoueur);
        if (chuteJoueur == HUMAIN) {
            coupsJoueur++;
        }
        int[] quatre = ligneGagnante(ligne, chuteCol);
        if (quatre != null) {
            terminerManche(chuteJoueur, quatre);
            return;
        }
        if (plateauPlein()) {
            terminerManche(VIDE, null);
            return;
        }
        if (chuteJoueur == HUMAIN) {
            etat = Etat.REFLEXION;
            // Courte pause avant la riposte : sans elle le jeton rouge semble tomber tout seul.
            attente = 0.45;
        } else {
            etat = Etat.ATTENTE;
        }
    }

    private void terminerManche(int vainqueur, int[] quatre) {
        vainqueurManche = vainqueur;
        cellulesGagnantes = quatre;
        eclat = 0;
        dureeEclat = quatre != null ? 1.8 : 1.1;
        etat = Etat.ECLAT;
    }

    private void eclater(double dt) {
        eclat += dt;
        if (eclat < dureeEclat) {
            return;
        }
        if (vainqueurManche == HUMAIN) {
            // Prime a la rapidite : vingt-et-un jetons est le maximum qu'un joueur puisse poser.
            long bonus = Math.max(0, 21 - coupsJoueur) * BONUS_RAPIDITE;
            score += GAIN_MANCHE + bonus;
            nouvelleManche();
        } else if (vainqueurManche == IA) {
            score += GAIN_DEFAITE;
            // Le halo se fige allume : l'ecran de fin doit montrer par ou la manche a ete perdue.
            eclat = 0;
            etat = Etat.FIN;
            fini = true;
        } else {
            score += GAIN_NULLE;
            nouvelleManche();
        }
    }

    private void nouvelleManche() {
        for (int l = 0; l < ROWS; l++) {
            for (int c = 0; c < COLS; c++) {
                cellules[l][c] = VIDE;
            }
        }
        for (int c = 0; c < COLS; c++) {
            hauteurs[c] = 0;
        }
        cellulesGagnantes = null;
        vainqueurManche = VIDE;
        coupsJoueur = 0;
        selection = 3;
        manche++;
        iaCommence = !iaCommence;
        if (iaCommence) {
            etat = Etat.REFLEXION;
            // Le joueur doit voir la grille vide avant que le premier jeton ne tombe.
            attente = 0.9;
        } else {
            etat = Etat.ATTENTE;
        }
    }

    // ------------------------------------------------------------------ commandes

    @Override
    public boolean keyPressed(int keyCode) {
        // Hors de son tour, le joueur ne pilote rien : un coup mis en reserve arriverait sur une
        // grille qui n'est plus celle qu'il regardait.
        if (etat != Etat.ATTENTE) {
            return false;
        }
        switch (keyCode) {
            case 263, 65, 81 -> deplacer(-1);       // gauche, A, Q
            case 262, 68 -> deplacer(1);            // droite, D
            case 32, 257, 265, 87, 90 -> jouerColonne(selection);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void deplacer(int sens) {
        // Les colonnes pleines sont sautees : s'arreter dessus obligerait a appuyer deux fois.
        for (int i = 0; i < COLS; i++) {
            selection = Math.floorMod(selection + sens, COLS);
            if (jouable(selection)) {
                return;
            }
        }
    }

    private void jouerColonne(int col) {
        if (etat != Etat.ATTENTE || !jouable(col)) {
            return;
        }
        lancerJeton(col, HUMAIN);
    }

    private static int colonneSous(double fx) {
        return Math.max(0, Math.min(COLS - 1, (int) (fx * COLS)));
    }

    @Override
    public void mouseMoved(double fx, double fy) {
        if (etat != Etat.ATTENTE || fx < 0 || fx > 1) {
            return;
        }
        selection = colonneSous(fx);
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        if (etat != Etat.ATTENTE || fx < 0 || fx > 1 || fy < 0 || fy > 1) {
            return;
        }
        selection = colonneSous(fx);
        jouerColonne(selection);
    }

    // ------------------------------------------------------------------ dessin

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double echelle = Math.min(width / W, height / H);
        if (echelle <= 0) {
            return;
        }
        int largeur = (int) (W * echelle);
        int hauteur = (int) (H * echelle);
        int ox = x + (width - largeur) / 2;
        int oy = y + (height - hauteur) / 2;

        g.fillGradient(ox, oy, ox + largeur, oy + hauteur, 0xFF2B2438, 0xFF20192B);

        int haut = oy + (int) (BANDE * echelle);
        int bas = oy + (int) ((BANDE + ROWS) * echelle);
        g.fillGradient(ox, haut, ox + largeur, bas, COULEUR_PLATEAU, 0xFF132E80);
        g.fill(ox, haut, ox + largeur, haut + 2, 0xFF4C73E8);

        if (etat == Etat.ATTENTE) {
            int cx0 = ox + (int) (selection * echelle);
            int cx1 = ox + (int) ((selection + 1) * echelle);
            g.fill(cx0, oy, cx1, bas, 0x22FFFFFF);
        }

        int rayon = Math.max(2, (int) (0.40 * echelle));
        // Halo battant a trois par seconde : assez lent pour etre vu, assez vif pour attirer l'oeil.
        boolean halo = cellulesGagnantes != null && ((int) (eclat / 0.16)) % 2 == 0;
        for (int l = 0; l < ROWS; l++) {
            for (int c = 0; c < COLS; c++) {
                int cx = ox + (int) ((c + 0.5) * echelle);
                int cy = oy + (int) ((BANDE + l + 0.5) * echelle);
                if (halo && contient(cellulesGagnantes, l * COLS + c)) {
                    jeton(g, cx, cy, rayon + Math.max(2, (int) (0.09 * echelle)), 0xFFFFFFFF);
                }
                jeton(g, cx, cy, rayon, couleur(cellules[l][c]));
            }
        }

        if (etat == Etat.CHUTE) {
            int cx = ox + (int) ((chuteCol + 0.5) * echelle);
            int cy = oy + (int) (chuteY * echelle);
            jeton(g, cx, cy, rayon, couleur(chuteJoueur));
        } else if (etat == Etat.ATTENTE) {
            int cx = ox + (int) ((selection + 0.5) * echelle);
            int cy = oy + (int) (BANDE / 2.0 * echelle);
            jeton(g, cx, cy, rayon, jouable(selection) ? COULEUR_HUMAIN : 0xFF7A6A88);
        }

        g.renderOutline(ox - 1, oy - 1, largeur + 2, hauteur + 2, 0xFF6A5A78);

        Font police = Minecraft.getInstance().font;
        if (police != null) {
            g.drawCenteredString(police, statut(), ox + largeur / 2, bas + 4, couleurStatut());
        }
    }

    private String statut() {
        return switch (etat) {
            case REFLEXION -> "Manche " + manche + " - l'ordinateur reflechit";
            case ECLAT -> switch (vainqueurManche) {
                case HUMAIN -> "Quatre a la suite - manche gagnee";
                case IA -> "Quatre pour l'ordinateur";
                default -> "Match nul - on recommence";
            };
            case FIN -> "Partie perdue";
            default -> "Manche " + manche + " - a vous de jouer";
        };
    }

    private int couleurStatut() {
        if (etat == Etat.ECLAT || etat == Etat.FIN) {
            return vainqueurManche == HUMAIN ? COULEUR_HUMAIN : 0xFFE08A8A;
        }
        return 0xFFB9C4D8;
    }

    private static boolean contient(int[] cases, int valeur) {
        for (int c : cases) {
            if (c == valeur) {
                return true;
            }
        }
        return false;
    }

    private static int couleur(int cellule) {
        return switch (cellule) {
            case HUMAIN -> COULEUR_HUMAIN;
            case IA -> COULEUR_IA;
            default -> COULEUR_TROU;
        };
    }

    /** Disque approche par bandes horizontales : GuiGraphics ne remplit que des rectangles. */
    private static void jeton(GuiGraphics g, int cx, int cy, int r, int couleur) {
        int rayon = Math.max(1, r);
        for (int dy = -rayon; dy <= rayon; dy++) {
            int demi = (int) Math.sqrt(Math.max(0, rayon * rayon - dy * dy));
            g.fill(cx - demi, cy + dy, cx + demi, cy + dy + 1, couleur);
        }
        if (rayon >= 4) {
            int e = rayon / 2;
            g.fill(cx - e, cy - e, cx - e + 2, cy - e + 2, 0x66FFFFFF);
        }
    }

    // ------------------------------------------------------------------ interface

    @Override
    public long score() {
        return score;
    }

    @Override
    public boolean over() {
        return fini;
    }

    @Override
    public String hint() {
        return "Souris ou fleches pour choisir la colonne";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
