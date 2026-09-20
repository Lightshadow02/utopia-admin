package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 2048. On pousse la grille dans une direction, les tuiles egales fusionnent, et une nouvelle
 * apparait a chaque coup qui a bouge quelque chose.
 *
 * <p>Deux regles font tout l'equilibre du jeu et sont faciles a rater : une tuile <b>ne fusionne
 * qu'une fois par coup</b> (sinon une ligne de quatre 2 donnerait un 8 au lieu de deux 4), et une
 * nouvelle tuile n'apparait que si le coup a reellement deplace quelque chose - sinon taper contre
 * un mur remplirait la grille gratuitement.
 */
public final class Game2048 implements ArcadeGame {

    private static final int N = 4;

    /** Couleurs par puissance de deux, du 2 au 2048 et au-dela. */
    private static final int[] TILE_COLORS = {
            0xFFCDC1B4, 0xFFEEE4DA, 0xFFEDE0C8, 0xFFF2B179, 0xFFF59563,
            0xFFF67C5F, 0xFFF65E3B, 0xFFEDCF72, 0xFFEDCC61, 0xFFEDC850,
            0xFFEDC53F, 0xFFEDC22E, 0xFF3C3A32};

    private final int[][] grid = new int[N][N];
    private final Random random = new Random();

    private long score;
    private boolean dead;

    public Game2048() {
        ajouterTuile();
        ajouterTuile();
    }

    private void ajouterTuile() {
        List<int[]> libres = new ArrayList<>();
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (grid[r][c] == 0) {
                    libres.add(new int[] {r, c});
                }
            }
        }
        if (libres.isEmpty()) {
            return;
        }
        int[] p = libres.get(random.nextInt(libres.size()));
        // Un 4 une fois sur dix : c'est ce qui empeche la grille de rester confortable.
        grid[p[0]][p[1]] = random.nextInt(10) == 0 ? 4 : 2;
    }

    @Override
    public void update(double dt) {
        // Rien a animer : 2048 n'avance que sur les touches.
    }

    @Override
    public boolean keyPressed(int keyCode) {
        int dx = 0;
        int dy = 0;
        switch (keyCode) {
            case 265, 87, 90 -> dy = -1;
            case 264, 83 -> dy = 1;
            case 263, 65, 81 -> dx = -1;
            case 262, 68 -> dx = 1;
            default -> {
                return false;
            }
        }
        if (dead) {
            return true;
        }
        if (pousser(dx, dy)) {
            ajouterTuile();
            dead = bloque();
        }
        return true;
    }

    /** Pousse toute la grille ; renvoie vrai si quelque chose a bouge. */
    private boolean pousser(int dx, int dy) {
        boolean bouge = false;
        // On parcourt a rebours du sens de poussee : la tuile la plus avancee se range en premier,
        // sans quoi les fusions se feraient dans le mauvais ordre.
        int debutR = dy > 0 ? N - 1 : 0;
        int pasR = dy > 0 ? -1 : 1;
        int debutC = dx > 0 ? N - 1 : 0;
        int pasC = dx > 0 ? -1 : 1;

        boolean[][] fusionnee = new boolean[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                int r = debutR + pasR * i;
                int c = debutC + pasC * j;
                if (grid[r][c] == 0) {
                    continue;
                }
                int nr = r;
                int nc = c;
                while (true) {
                    int sr = nr + dy;
                    int sc = nc + dx;
                    if (sr < 0 || sr >= N || sc < 0 || sc >= N) {
                        break;
                    }
                    if (grid[sr][sc] == 0) {
                        grid[sr][sc] = grid[nr][nc];
                        grid[nr][nc] = 0;
                        nr = sr;
                        nc = sc;
                        bouge = true;
                        continue;
                    }
                    if (grid[sr][sc] == grid[nr][nc] && !fusionnee[sr][sc] && !fusionnee[nr][nc]) {
                        grid[sr][sc] *= 2;
                        grid[nr][nc] = 0;
                        fusionnee[sr][sc] = true;
                        score += grid[sr][sc];
                        bouge = true;
                    }
                    break;
                }
            }
        }
        return bouge;
    }

    /** Bloque : plus une case libre, et aucune paire adjacente a fusionner. */
    private boolean bloque() {
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (grid[r][c] == 0) {
                    return false;
                }
                if (c + 1 < N && grid[r][c] == grid[r][c + 1]) {
                    return false;
                }
                if (r + 1 < N && grid[r][c] == grid[r + 1][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int couleur(int valeur) {
        if (valeur <= 0) {
            return TILE_COLORS[0];
        }
        int rang = Math.min(TILE_COLORS.length - 1, 31 - Integer.numberOfLeadingZeros(valeur));
        return TILE_COLORS[rang];
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        int cote = Math.min(width, height);
        int cell = cote / N;
        int fw = cell * N;
        int ox = x + (width - fw) / 2;
        int oy = y + (height - fw) / 2;

        g.fillGradient(ox, oy, ox + fw, oy + fw, 0xFFA59A8E, 0xFF8D8377);

        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int px = ox + c * cell;
                int py = oy + r * cell;
                int v = grid[r][c];
                g.fill(px + 2, py + 2, px + cell - 2, py + cell - 2, couleur(v));
                if (v == 0) {
                    continue;
                }
                String texte = String.valueOf(v);
                // Le texte fonce sur les petites tuiles claires, clair au-dela : sans ce
                // basculement, les chiffres disparaissent dans le fond.
                int encre = v <= 4 ? 0xFF6B6157 : 0xFFF8F5F0;
                g.drawString(font, texte,
                        px + (cell - font.width(texte)) / 2,
                        py + (cell - font.lineHeight) / 2, encre, false);
            }
        }
        g.renderOutline(ox - 1, oy - 1, fw + 2, fw + 2, 0xFF8F7A66);
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
        return "Fleches ou ZQSD pour pousser la grille";
    }

    @Override
    public double aspect() {
        return 1.0;
    }
}
