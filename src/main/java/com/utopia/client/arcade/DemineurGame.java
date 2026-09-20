package com.utopia.client.arcade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Demineur. Quatorze colonnes sur onze rangees, vingt-deux mines : on ouvre les cases sures en
 * lisant les chiffres, et on marque d'un drapeau celles que l'on croit piegees.
 *
 * <p>Trois choix font la jouabilite. Les mines ne sont posees qu'APRES le premier clic, en
 * excluant la case cliquee et ses huit voisines : le premier coup ne peut donc pas tuer, et comme
 * la case de depart se retrouve sans mine autour, elle ouvre toujours une grande zone d'un seul
 * clic. L'ouverture en cascade propage l'ouverture a toutes les cases vides adjacentes, sans quoi
 * il faudrait cliquer cent cinquante-quatre fois. Enfin le chronometre ne part qu'au premier clic :
 * la partie est payante, le joueur a le droit de regarder la grille avant de s'engager.
 *
 * <p>Le drapeau ne rapporte rien : payer le drapeau reviendrait a en poser partout pour ramasser
 * des points sans jamais prendre le moindre risque.
 */
public final class DemineurGame implements ArcadeGame {

    private static final int COLS = 14;
    private static final int ROWS = 11;
    private static final int MINES = 22;
    private static final int SURES = COLS * ROWS - MINES;

    /** Terrain logique : une case vaut 1, plus un bandeau d'information au-dessus de la grille. */
    private static final double BANDEAU = 1.4;
    private static final double W = COLS;
    private static final double H = ROWS + BANDEAU;

    private static final int POINTS_CASE = 25;
    /** Prime de victoire, completee par une prime de rapidite qui ne retire jamais de points. */
    private static final long PRIME_VICTOIRE = 1500L;
    private static final double SECONDES_PRIMEES = 300.0;

    /** Couleurs d'origine des chiffres : bleu, vert, rouge, bleu fonce, bordeaux, cyan, noir, gris. */
    private static final int[] ENCRE_CHIFFRE = {
            0xFF000000, 0xFF1B4FD8, 0xFF1E7B1E, 0xFFD02020, 0xFF0B1A6E,
            0xFF7B1717, 0xFF107F7F, 0xFF101010, 0xFF5A5A5A};

    private static final int FOND_CADRE = 0xFF5A616E;
    private static final int FOND_BANDEAU = 0xFF2A3040;
    private static final int CASE_FERMEE = 0xFFA9AEB8;
    private static final int CASE_OUVERTE = 0xFFD6D9DE;
    private static final int CASE_FATALE = 0xFFC24040;

    private final Random alea = new Random();
    private final boolean[][] mine = new boolean[ROWS][COLS];
    private final boolean[][] ouverte = new boolean[ROWS][COLS];
    private final boolean[][] drapeau = new boolean[ROWS][COLS];
    private final int[][] voisines = new int[ROWS][COLS];

    /** Curseur partage par la souris et le clavier : il montre toujours la case qui sera jouee. */
    private int curseurC = COLS / 2;
    private int curseurR = ROWS / 2;

    private boolean minesPosees;
    private boolean perdu;
    private boolean gagne;
    private int ouvertes;
    private int drapeaux;
    private long score;
    private double temps;
    /** Case qui a fait sauter la partie, surlignee en rouge a la fin. */
    private int fataleC = -1;
    private int fataleR = -1;

    @Override
    public void update(double dt) {
        // Le chronometre ne tourne pas tant que la grille n'est pas amorcee, ni une fois la partie
        // finie : sinon le temps de lecture couterait la prime de rapidite.
        if (perdu || gagne || !minesPosees) {
            return;
        }
        temps += dt;
    }

    private static boolean dansLaGrille(int c, int r) {
        return c >= 0 && c < COLS && r >= 0 && r < ROWS;
    }

    /**
     * Pose les mines en epargnant la case de depart et ses huit voisines. Il reste toujours au
     * moins cent quarante-cinq cases libres pour vingt-deux mines : la pose ne peut pas echouer.
     */
    private void poserMines(int c0, int r0) {
        List<Integer> libres = new ArrayList<>(COLS * ROWS);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (Math.abs(c - c0) <= 1 && Math.abs(r - r0) <= 1) {
                    continue;
                }
                libres.add(r * COLS + c);
            }
        }
        Collections.shuffle(libres, alea);
        int n = Math.min(MINES, libres.size());
        for (int i = 0; i < n; i++) {
            int idx = libres.get(i);
            mine[idx / COLS][idx % COLS] = true;
        }
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                voisines[r][c] = compterVoisines(c, r);
            }
        }
        minesPosees = true;
    }

    private int compterVoisines(int c, int r) {
        int n = 0;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dc == 0 && dr == 0) {
                    continue;
                }
                int nc = c + dc;
                int nr = r + dr;
                if (dansLaGrille(nc, nr) && mine[nr][nc]) {
                    n++;
                }
            }
        }
        return n;
    }

    private void reveler(int c, int r) {
        if (perdu || gagne || !dansLaGrille(c, r) || ouverte[r][c] || drapeau[r][c]) {
            return;
        }
        if (!minesPosees) {
            poserMines(c, r);
        }
        if (mine[r][c]) {
            perdu = true;
            fataleC = c;
            fataleR = r;
            // Toutes les mines apparaissent : le joueur doit pouvoir relire sa faute.
            for (int rr = 0; rr < ROWS; rr++) {
                for (int cc = 0; cc < COLS; cc++) {
                    if (mine[rr][cc]) {
                        ouverte[rr][cc] = true;
                    }
                }
            }
            return;
        }
        cascade(c, r);
        if (ouvertes >= SURES) {
            gagne = true;
            long prime = (long) Math.max(0.0, SECONDES_PRIMEES - temps) * 5L;
            score += PRIME_VICTOIRE + prime;
        }
    }

    /**
     * Ouverture par propagation. Une file plutot qu'une recursion : cent cinquante-quatre appels
     * empiles resteraient sans danger, mais la file evite toute question et ne peut pas boucler,
     * chaque case n'etant comptee qu'une fois a la depile.
     */
    private void cascade(int c0, int r0) {
        Deque<int[]> file = new ArrayDeque<>();
        file.add(new int[] {c0, r0});
        while (!file.isEmpty()) {
            int[] cur = file.poll();
            int c = cur[0];
            int r = cur[1];
            if (ouverte[r][c] || drapeau[r][c] || mine[r][c]) {
                continue;
            }
            ouverte[r][c] = true;
            ouvertes++;
            score += POINTS_CASE;
            if (voisines[r][c] != 0) {
                continue; // un chiffre borne la zone : on ne franchit jamais une indication
            }
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int nc = c + dc;
                    int nr = r + dr;
                    if (dansLaGrille(nc, nr) && !ouverte[nr][nc]) {
                        file.add(new int[] {nc, nr});
                    }
                }
            }
        }
    }

    private void basculerDrapeau(int c, int r) {
        if (perdu || gagne || !dansLaGrille(c, r) || ouverte[r][c]) {
            return;
        }
        if (drapeau[r][c]) {
            drapeau[r][c] = false;
            drapeaux--;
        } else {
            drapeau[r][c] = true;
            drapeaux++;
        }
    }

    @Override
    public void mouseMoved(double fx, double fy) {
        int c = (int) Math.floor(fx * W);
        int r = (int) Math.floor(fy * H - BANDEAU);
        if (dansLaGrille(c, r)) {
            curseurC = c;
            curseurR = r;
        }
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        int c = (int) Math.floor(fx * W);
        int r = (int) Math.floor(fy * H - BANDEAU);
        if (!dansLaGrille(c, r)) {
            return; // clic dans le bandeau ou hors du cadre : sans effet
        }
        curseurC = c;
        curseurR = r;
        if (button == 0) {
            reveler(c, r);
        } else if (button == 1) {
            basculerDrapeau(c, r);
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        // Doublure au clavier : la souris peut manquer sur une borne, la partie reste jouable.
        switch (keyCode) {
            case 265, 87, 90 -> curseurR = Math.max(0, curseurR - 1);
            case 264, 83 -> curseurR = Math.min(ROWS - 1, curseurR + 1);
            case 263, 65, 81 -> curseurC = Math.max(0, curseurC - 1);
            case 262, 68 -> curseurC = Math.min(COLS - 1, curseurC + 1);
            case 32 -> reveler(curseurC, curseurR);
            case 257 -> basculerDrapeau(curseurC, curseurR);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        // Deux echelles calees sur le cadre recu, et non une echelle unique avec recentrage : le
        // clic arrive en fraction du cadre (fx, fy), donc toute autre geometrie ferait diverger la
        // case dessinee de la case cliquee. Au demineur, une case d'ecart sur une mine coute la
        // partie - et elle est payante.
        double ex = width / W;
        double ey = height / H;
        int cadreW = width;
        int cadreH = height;
        int ox = x;
        int oy = y;
        int hautGrille = oy + (int) (BANDEAU * ey);

        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;

        g.fill(ox, oy, ox + cadreW, oy + cadreH, FOND_CADRE);
        g.fill(ox, oy, ox + cadreW, hautGrille, FOND_BANDEAU);

        // Un bandeau plus court que la police centrerait le texte au-dessus du cadre.
        int ligne = oy + Math.max(0, ((int) (BANDEAU * ey) - font.lineHeight) / 2);
        String restantes = "Mines " + (MINES - drapeaux);
        g.drawString(font, restantes, ox + 4, ligne, 0xFFE8B4B4, false);
        String horloge = horloge();
        g.drawString(font, horloge, ox + cadreW - 4 - font.width(horloge), ligne, 0xFFB4D0E8, false);
        String etat = perdu ? "Mine !" : gagne ? "Grille nettoyee" : "Cases " + ouvertes + "/" + SURES;
        g.drawCenteredString(font, etat, ox + cadreW / 2, ligne, 0xFFE0E4EA);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int x0 = ox + (int) (c * ex);
                int y0 = oy + (int) ((BANDEAU + r) * ey);
                int x1 = ox + (int) ((c + 1) * ex) - 1;
                int y1 = oy + (int) ((BANDEAU + r + 1) * ey) - 1;
                dessinerCase(g, font, c, r, x0, y0, x1, y1);
            }
        }

        int cx0 = ox + (int) (curseurC * ex);
        int cy0 = oy + (int) ((BANDEAU + curseurR) * ey);
        int cw = Math.max(2, (int) ((curseurC + 1) * ex) - (int) (curseurC * ex));
        int ch = Math.max(2, (int) ((BANDEAU + curseurR + 1) * ey) - (int) ((BANDEAU + curseurR) * ey));
        g.renderOutline(cx0, cy0, cw, ch, 0xFFFFD966);

        g.renderOutline(ox - 1, oy - 1, cadreW + 2, cadreH + 2, 0xFF4A5260);
    }

    private void dessinerCase(GuiGraphics g, net.minecraft.client.gui.Font font,
            int c, int r, int x0, int y0, int x1, int y1) {
        int larg = Math.max(1, x1 - x0);
        int haut = Math.max(1, y1 - y0);
        int cx = x0 + larg / 2;
        int cy = y0 + haut / 2;
        int cote = Math.min(larg, haut);

        if (!ouverte[r][c]) {
            g.fill(x0, y0, x1, y1, CASE_FERMEE);
            // Biseau clair en haut a gauche : c'est lui qui fait lire la case comme "a ouvrir".
            g.fill(x0, y0, x1, y0 + 1, 0xFFE2E6EC);
            g.fill(x0, y0, x0 + 1, y1, 0xFFE2E6EC);
            g.fill(x0, y1 - 1, x1, y1, 0xFF6D7480);
            g.fill(x1 - 1, y0, x1, y1, 0xFF6D7480);
            if (drapeau[r][c]) {
                dessinerDrapeau(g, cx, cy, cote);
            }
            return;
        }

        boolean fatale = c == fataleC && r == fataleR;
        g.fill(x0, y0, x1, y1, fatale ? CASE_FATALE : CASE_OUVERTE);
        if (mine[r][c]) {
            dessinerMine(g, cx, cy, cote);
            return;
        }
        int n = voisines[r][c];
        if (n <= 0) {
            return;
        }
        String texte = String.valueOf(n);
        g.drawString(font, texte, cx - font.width(texte) / 2, cy - font.lineHeight / 2,
                ENCRE_CHIFFRE[n], false);
    }

    /** Disque approche par bandes horizontales : GuiGraphics ne remplit que des rectangles. */
    private static void dessinerMine(GuiGraphics g, int cx, int cy, int cote) {
        int rayon = Math.max(1, cote / 4);
        for (int dy = -rayon; dy <= rayon; dy++) {
            int demi = (int) Math.sqrt(Math.max(0, rayon * rayon - dy * dy));
            g.fill(cx - demi, cy + dy, cx + demi + 1, cy + dy + 1, 0xFF141414);
        }
        if (rayon >= 3) {
            g.fill(cx - rayon / 2, cy - rayon / 2, cx - rayon / 2 + 2, cy - rayon / 2 + 2, 0xFFD8D8D8);
        }
    }

    private static void dessinerDrapeau(GuiGraphics g, int cx, int cy, int cote) {
        if (cote < 7) {
            // Trop petit pour un fanion lisible : un point rouge vaut mieux qu'une bouillie.
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFD02020);
            return;
        }
        int h = Math.max(2, cote / 3);
        g.fill(cx, cy - h, cx + 1, cy + h, 0xFF1A1A1A);
        g.fill(cx - h / 2, cy + h - 1, cx + h / 2 + 1, cy + h + 1, 0xFF1A1A1A);
        for (int i = 0; i < h; i++) {
            int large = h - i;
            g.fill(cx - large, cy - h + i, cx, cy - h + i + 1, 0xFFD02020);
        }
    }

    private String horloge() {
        int total = (int) Math.min(temps, 3599.0);
        int minutes = total / 60;
        int secondes = total % 60;
        return minutes + ":" + (secondes < 10 ? "0" : "") + secondes;
    }

    @Override
    public long score() {
        return score;
    }

    @Override
    public boolean over() {
        return perdu || gagne;
    }

    @Override
    public String hint() {
        return "Clic gauche ou Espace revele - clic droit ou Entree pose un drapeau";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
