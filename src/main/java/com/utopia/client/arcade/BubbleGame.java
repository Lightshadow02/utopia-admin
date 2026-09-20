package com.utopia.client.arcade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Bubble Shooter. On vise a la souris, on tire une bulle, et trois bulles de meme couleur qui se
 * touchent explosent.
 *
 * <p>La grille est <b>hexagonale</b> : une rangee sur deux est decalee d'une demi-bulle. C'est ce
 * decalage qui donne six voisins a chaque bulle au lieu de quatre, et sans lui les grappes se
 * cassent en lignes droites au lieu de s'agglomerer.
 *
 * <p>Les bulles que la grappe detachee laisse sans attache tombent a leur tour : c'est la ou se
 * jouent les gros coups, et le calcul se fait par un parcours depuis le plafond.
 */
public final class BubbleGame implements ArcadeGame {

    private static final int COLS = 12;
    private static final int ROWS = 14;
    /** Rangees remplies au depart. */
    private static final int START_ROWS = 5;
    /** Rangee a partir de laquelle la partie est perdue. */
    private static final int DEAD_ROW = ROWS - 2;

    private static final int[] COLORS = {
            0xFFE0524F, 0xFF4FA8E0, 0xFF5FC45F, 0xFFE0C94F, 0xFFB05FD0, 0xFFE08A3E};

    /** Terrain en unites de bulle : une bulle fait 2 de diametre, une rangee 1.74 de haut. */
    private static final double R = 1.0;
    private static final double ROW_H = 1.74;
    private static final double W = COLS * 2 * R + R;
    private static final double H = ROWS * ROW_H + 2 * R;

    private final Random random = new Random();
    /** -1 = case vide, sinon l'indice de couleur. */
    private final int[][] grid = new int[ROWS][COLS];

    private int current;
    private int next;
    private double aimAngle = -Math.PI / 2;
    private double shotX;
    private double shotY;
    private double shotVX;
    private double shotVY;
    private boolean flying;
    private long score;
    private boolean dead;
    /** Tirs avant que le plafond ne descende d'une rangee : la pression monte toute seule. */
    private int shotsBeforeDrop = 8;
    private int shotsFired;

    public BubbleGame() {
        for (int[] row : grid) {
            java.util.Arrays.fill(row, -1);
        }
        for (int r = 0; r < START_ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                grid[r][c] = random.nextInt(COLORS.length);
            }
        }
        current = pickColor();
        next = pickColor();
        resetShot();
    }

    /** Une rangee decalee porte une bulle de moins : c'est ce qui fait tenir l'hexagone. */
    private static int colsIn(int row) {
        return row % 2 == 0 ? COLS : COLS - 1;
    }

    private static double rowOffset(int row) {
        return row % 2 == 0 ? R : 2 * R;
    }

    private static double centerX(int row, int col) {
        return rowOffset(row) + col * 2 * R;
    }

    private static double centerY(int row) {
        return R + row * ROW_H;
    }

    /**
     * Tire une couleur encore presente sur le terrain. Donner une couleur absente serait une balle
     * perdue d'avance, et c'est la premiere chose qu'un joueur ressent comme injuste.
     */
    private int pickColor() {
        List<Integer> presentes = new ArrayList<>();
        boolean[] vu = new boolean[COLORS.length];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                int v = grid[r][c];
                if (v >= 0 && !vu[v]) {
                    vu[v] = true;
                    presentes.add(v);
                }
            }
        }
        return presentes.isEmpty()
                ? random.nextInt(COLORS.length)
                : presentes.get(random.nextInt(presentes.size()));
    }

    private void resetShot() {
        flying = false;
        shotX = W / 2;
        shotY = H - R;
        shotVX = 0;
        shotVY = 0;
    }

    @Override
    public void update(double dt) {
        if (dead || !flying) {
            return;
        }
        // Pas fixes : une bulle deplacee d'un bloc traverserait la grille sans jamais s'y coller.
        double reste = Math.min(dt, 0.25);
        while (reste > 0 && flying && !dead) {
            double pas = Math.min(reste, 1.0 / 240.0);
            reste -= pas;
            avance(pas);
        }
    }

    private void avance(double dt) {
        shotX += shotVX * dt;
        shotY += shotVY * dt;

        if (shotX - R < 0) {
            shotX = R;
            shotVX = Math.abs(shotVX);
        } else if (shotX + R > W) {
            shotX = W - R;
            shotVX = -Math.abs(shotVX);
        }
        if (shotY - R <= 0) {
            coller();
            return;
        }
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                if (grid[r][c] < 0) {
                    continue;
                }
                double dx = shotX - centerX(r, c);
                double dy = shotY - centerY(r);
                if (dx * dx + dy * dy <= (2 * R) * (2 * R) * 0.9) {
                    coller();
                    return;
                }
            }
        }
    }

    /** Range la bulle en vol dans la case libre la plus proche, puis resout la grappe. */
    private void coller() {
        int meilleurR = 0;
        int meilleurC = 0;
        double meilleure = Double.MAX_VALUE;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                if (grid[r][c] >= 0) {
                    continue;
                }
                double dx = shotX - centerX(r, c);
                double dy = shotY - centerY(r);
                double d = dx * dx + dy * dy;
                if (d < meilleure) {
                    meilleure = d;
                    meilleurR = r;
                    meilleurC = c;
                }
            }
        }
        grid[meilleurR][meilleurC] = current;
        resoudre(meilleurR, meilleurC);

        current = next;
        next = pickColor();
        resetShot();

        shotsFired++;
        if (shotsFired % shotsBeforeDrop == 0) {
            descendre();
        }
        verifierFin();
    }

    private void resoudre(int r0, int c0) {
        int couleur = grid[r0][c0];
        Set<Long> grappe = new HashSet<>();
        Deque<int[]> file = new ArrayDeque<>();
        file.add(new int[] {r0, c0});
        grappe.add(cle(r0, c0));
        while (!file.isEmpty()) {
            int[] cur = file.poll();
            for (int[] v : voisins(cur[0], cur[1])) {
                if (grid[v[0]][v[1]] == couleur && grappe.add(cle(v[0], v[1]))) {
                    file.add(v);
                }
            }
        }
        if (grappe.size() < 3) {
            return;
        }
        for (long k : grappe) {
            grid[(int) (k >> 8)][(int) (k & 0xFF)] = -1;
        }
        score += grappe.size() * 10L;
        int detachees = fairTomberLesOrphelines();
        // Les bulles decrochees valent double : c'est la recompense du coup bien vise.
        score += detachees * 20L;
    }

    /** Tout ce qui n'est plus relie au plafond tombe. */
    private int fairTomberLesOrphelines() {
        Set<Long> tenues = new HashSet<>();
        Deque<int[]> file = new ArrayDeque<>();
        for (int c = 0; c < colsIn(0); c++) {
            if (grid[0][c] >= 0 && tenues.add(cle(0, c))) {
                file.add(new int[] {0, c});
            }
        }
        while (!file.isEmpty()) {
            int[] cur = file.poll();
            for (int[] v : voisins(cur[0], cur[1])) {
                if (grid[v[0]][v[1]] >= 0 && tenues.add(cle(v[0], v[1]))) {
                    file.add(v);
                }
            }
        }
        int tombees = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                if (grid[r][c] >= 0 && !tenues.contains(cle(r, c))) {
                    grid[r][c] = -1;
                    tombees++;
                }
            }
        }
        return tombees;
    }

    private static long cle(int r, int c) {
        return ((long) r << 8) | c;
    }

    /**
     * Les six voisins d'une case. Le decalage d'une rangee sur deux change les diagonales : c'est
     * tout le sel de la grille hexagonale, et l'erreur classique est de l'oublier.
     */
    private List<int[]> voisins(int r, int c) {
        List<int[]> out = new ArrayList<>(6);
        boolean paire = r % 2 == 0;
        int[][] deltas = paire
                ? new int[][] {{0, -1}, {0, 1}, {-1, -1}, {-1, 0}, {1, -1}, {1, 0}}
                : new int[][] {{0, -1}, {0, 1}, {-1, 0}, {-1, 1}, {1, 0}, {1, 1}};
        for (int[] d : deltas) {
            int nr = r + d[0];
            int nc = c + d[1];
            if (nr >= 0 && nr < ROWS && nc >= 0 && nc < colsIn(nr)) {
                out.add(new int[] {nr, nc});
            }
        }
        return out;
    }

    /** Le plafond descend d'une rangee : tout glisse vers le bas. */
    private void descendre() {
        for (int r = ROWS - 1; r > 0; r--) {
            int n = Math.min(colsIn(r), colsIn(r - 1));
            java.util.Arrays.fill(grid[r], -1);
            System.arraycopy(grid[r - 1], 0, grid[r], 0, n);
        }
        for (int c = 0; c < colsIn(0); c++) {
            grid[0][c] = random.nextInt(COLORS.length);
        }
    }

    private void verifierFin() {
        for (int c = 0; c < colsIn(DEAD_ROW); c++) {
            if (grid[DEAD_ROW][c] >= 0) {
                dead = true;
                return;
            }
        }
    }

    @Override
    public void mouseMoved(double fx, double fy) {
        double dx = fx * W - W / 2;
        double dy = fy * H - (H - R);
        if (dy > -0.2) {
            dy = -0.2; // on ne tire jamais vers le bas
        }
        aimAngle = Math.atan2(dy, dx);
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        tirer();
    }

    @Override
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 32, 257 -> tirer();
            case 263, 65, 81 -> aimAngle = Math.max(-Math.PI + 0.25, aimAngle - 0.08);
            case 262, 68 -> aimAngle = Math.min(-0.25, aimAngle + 0.08);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void tirer() {
        if (flying || dead) {
            return;
        }
        flying = true;
        double vitesse = 55.0;
        shotVX = Math.cos(aimAngle) * vitesse;
        shotVY = Math.sin(aimAngle) * vitesse;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double scale = Math.min(width / W, height / H);
        int fieldW = (int) (W * scale);
        int fieldH = (int) (H * scale);
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fillGradient(ox, oy, ox + fieldW, oy + fieldH, 0xFF283454, 0xFF1C2740);

        // Ligne de perdition : elle doit se voir bien avant d'etre atteinte.
        int deadY = oy + (int) (centerY(DEAD_ROW) * scale);
        g.fill(ox, deadY, ox + fieldW, deadY + 1, 0x66FF5555);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < colsIn(r); c++) {
                if (grid[r][c] < 0) {
                    continue;
                }
                bulle(g, ox + (int) (centerX(r, c) * scale), oy + (int) (centerY(r) * scale),
                        (int) (R * scale), COLORS[grid[r][c]]);
            }
        }

        // Ligne de visee en pointilles : sans repere, on tire au juge.
        if (!flying && !dead) {
            double px = W / 2;
            double py = H - R;
            for (int i = 1; i <= 14; i++) {
                px += Math.cos(aimAngle) * 1.6;
                py += Math.sin(aimAngle) * 1.6;
                if (py < R || px < 0 || px > W) {
                    break;
                }
                if (i % 2 == 0) {
                    continue;
                }
                int dx = ox + (int) (px * scale);
                int dy = oy + (int) (py * scale);
                g.fill(dx - 1, dy - 1, dx + 1, dy + 1, 0x99FFFFFF);
            }
        }

        int br = (int) (R * scale);
        bulle(g, ox + (int) (shotX * scale), oy + (int) (shotY * scale), br, COLORS[current]);
        // La bulle suivante, en attente sur le cote : on prepare son coup d'avance.
        bulle(g, ox + fieldW - br - 2, oy + fieldH - br - 2, Math.max(2, br - 1), COLORS[next]);

        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF4A5C90);
    }

    private static void bulle(GuiGraphics g, int cx, int cy, int r, int color) {
        if (r < 1) {
            r = 1;
        }
        // Disque approche par bandes : GuiGraphics ne sait remplir que des rectangles.
        for (int dy = -r; dy <= r; dy++) {
            int demi = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(cx - demi, cy + dy, cx + demi, cy + dy + 1, color);
        }
        if (r >= 3) {
            g.fill(cx - r / 2, cy - r / 2, cx - r / 2 + 2, cy - r / 2 + 2, 0x77FFFFFF);
        }
    }

    @Override
    public long score() {
        return score;
    }

    @Override
    public boolean over() {
        return dead;
    }

    @Override
    public String hint() {
        return flying ? "Souris pour viser" : "Souris pour viser - clic ou Espace pour tirer";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
