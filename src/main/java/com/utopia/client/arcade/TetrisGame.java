package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Tetris. Les sept pieces tombent, les lignes completes disparaissent, la chute accelere par
 * paliers.
 *
 * <p>Les pieces sortent d'un <b>sac</b> melange plutot que d'un tirage au hasard : sept pieces,
 * chacune une fois, puis on remelange. C'est la regle des jeux modernes, et elle evite la serie de
 * huit S d'affilee qui rend un tirage purement aleatoire injouable.
 */
public final class TetrisGame implements ArcadeGame {

    private static final int COLS = 10;
    private static final int ROWS = 20;

    /** Les sept pieces, chacune dans ses quatre rotations, en coordonnees de cellule. */
    private static final int[][][][] SHAPES = {
            // I
            {{{0, 1}, {1, 1}, {2, 1}, {3, 1}}, {{2, 0}, {2, 1}, {2, 2}, {2, 3}},
             {{0, 2}, {1, 2}, {2, 2}, {3, 2}}, {{1, 0}, {1, 1}, {1, 2}, {1, 3}}},
            // J
            {{{0, 0}, {0, 1}, {1, 1}, {2, 1}}, {{1, 0}, {2, 0}, {1, 1}, {1, 2}},
             {{0, 1}, {1, 1}, {2, 1}, {2, 2}}, {{1, 0}, {1, 1}, {0, 2}, {1, 2}}},
            // L
            {{{2, 0}, {0, 1}, {1, 1}, {2, 1}}, {{1, 0}, {1, 1}, {1, 2}, {2, 2}},
             {{0, 1}, {1, 1}, {2, 1}, {0, 2}}, {{0, 0}, {1, 0}, {1, 1}, {1, 2}}},
            // O
            {{{1, 0}, {2, 0}, {1, 1}, {2, 1}}, {{1, 0}, {2, 0}, {1, 1}, {2, 1}},
             {{1, 0}, {2, 0}, {1, 1}, {2, 1}}, {{1, 0}, {2, 0}, {1, 1}, {2, 1}}},
            // S
            {{{1, 0}, {2, 0}, {0, 1}, {1, 1}}, {{1, 0}, {1, 1}, {2, 1}, {2, 2}},
             {{1, 1}, {2, 1}, {0, 2}, {1, 2}}, {{0, 0}, {0, 1}, {1, 1}, {1, 2}}},
            // T
            {{{1, 0}, {0, 1}, {1, 1}, {2, 1}}, {{1, 0}, {1, 1}, {2, 1}, {1, 2}},
             {{0, 1}, {1, 1}, {2, 1}, {1, 2}}, {{1, 0}, {0, 1}, {1, 1}, {1, 2}}},
            // Z
            {{{0, 0}, {1, 0}, {1, 1}, {2, 1}}, {{2, 0}, {1, 1}, {2, 1}, {1, 2}},
             {{0, 1}, {1, 1}, {1, 2}, {2, 2}}, {{1, 0}, {0, 1}, {1, 1}, {0, 2}}},
    };

    private static final int[] COLORS = {
            0xFF34C6D3, 0xFF3B6FD6, 0xFFE08A2E, 0xFFE0D02E,
            0xFF5FC45F, 0xFFA85FD0, 0xFFD8434B};

    private final Random random = new Random();
    private final int[][] grid = new int[ROWS][COLS];
    private final List<Integer> bag = new ArrayList<>();

    private int piece;
    private int rotation;
    private int px;
    private int py;
    private int next;
    private long score;
    private int lines;
    private boolean dead;
    private double fallTimer;
    /** Delai avant que le joueur n'ait a jouer : le temps de lire le terrain. */
    private double startDelay = 0.8;

    public TetrisGame() {
        next = draw();
        spawn();
    }

    private int draw() {
        if (bag.isEmpty()) {
            for (int i = 0; i < SHAPES.length; i++) {
                bag.add(i);
            }
            Collections.shuffle(bag, random);
        }
        return bag.remove(bag.size() - 1);
    }

    private void spawn() {
        piece = next;
        next = draw();
        rotation = 0;
        px = COLS / 2 - 2;
        py = 0;
        if (collides(px, py, rotation)) {
            dead = true;
        }
    }

    private int level() {
        return 1 + lines / 10;
    }

    private double fallInterval() {
        return Math.max(0.08, 0.80 - (level() - 1) * 0.07);
    }

    private boolean collides(int x, int y, int rot) {
        for (int[] c : SHAPES[piece][rot]) {
            int cx = x + c[0];
            int cy = y + c[1];
            if (cx < 0 || cx >= COLS || cy >= ROWS) {
                return true;
            }
            if (cy >= 0 && grid[cy][cx] != 0) {
                return true;
            }
        }
        return false;
    }

    private void lock() {
        for (int[] c : SHAPES[piece][rotation]) {
            int cx = px + c[0];
            int cy = py + c[1];
            if (cy >= 0 && cy < ROWS && cx >= 0 && cx < COLS) {
                grid[cy][cx] = piece + 1;
            }
        }
        clearLines();
        spawn();
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < COLS; c++) {
                if (grid[r][c] == 0) {
                    full = false;
                    break;
                }
            }
            if (!full) {
                continue;
            }
            cleared++;
            for (int rr = r; rr > 0; rr--) {
                System.arraycopy(grid[rr - 1], 0, grid[rr], 0, COLS);
            }
            java.util.Arrays.fill(grid[0], 0);
            r++; // la ligne descendue doit etre reexaminee
        }
        if (cleared == 0) {
            return;
        }
        lines += cleared;
        // Bareme classique : quatre lignes d'un coup valent bien plus que quatre fois une ligne.
        int base = switch (cleared) {
            case 1 -> 100;
            case 2 -> 300;
            case 3 -> 500;
            default -> 800;
        };
        score += (long) base * level();
    }

    @Override
    public void update(double dt) {
        if (dead) {
            return;
        }
        if (startDelay > 0) {
            startDelay -= dt;
            return;
        }
        fallTimer += dt;
        int steps = 0;
        while (fallTimer >= fallInterval() && steps < 4 && !dead) {
            fallTimer -= fallInterval();
            steps++;
            if (collides(px, py + 1, rotation)) {
                lock();
            } else {
                py++;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        if (dead) {
            return false;
        }
        switch (keyCode) {
            case 263, 65, 81 -> { // gauche
                if (!collides(px - 1, py, rotation)) {
                    px--;
                }
            }
            case 262, 68 -> { // droite
                if (!collides(px + 1, py, rotation)) {
                    px++;
                }
            }
            case 264, 83 -> { // bas : descente douce, un point par case gagnee
                if (!collides(px, py + 1, rotation)) {
                    py++;
                    score++;
                    fallTimer = 0;
                }
            }
            case 265, 87, 90 -> rotate();
            case 32 -> hardDrop();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void rotate() {
        int r = (rotation + 1) % 4;
        // Rattrapage au mur : une piece collee au bord doit pouvoir tourner en se decalant d'une
        // case, sinon la rotation est refusee sans que le joueur comprenne pourquoi.
        for (int shift : new int[] {0, -1, 1, -2, 2}) {
            if (!collides(px + shift, py, r)) {
                px += shift;
                rotation = r;
                return;
            }
        }
    }

    private void hardDrop() {
        int dropped = 0;
        while (!collides(px, py + 1, rotation)) {
            py++;
            dropped++;
        }
        score += dropped * 2L;
        lock();
        fallTimer = 0;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        int cell = Math.max(1, Math.min(width / (COLS + 6), height / ROWS));
        int fieldW = cell * COLS;
        int fieldH = cell * ROWS;
        int panelW = cell * 5;
        int total = fieldW + panelW + cell;
        int ox = x + (width - total) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fillGradient(ox, oy, ox + fieldW, oy + fieldH, 0xFF2E2666, 0xFF150F33);
        for (int r = 1; r < ROWS; r++) {
            g.fill(ox, oy + r * cell, ox + fieldW, oy + r * cell + 1, 0xFF453A96);
        }
        for (int c = 1; c < COLS; c++) {
            g.fill(ox + c * cell, oy, ox + c * cell + 1, oy + fieldH, 0xFF453A96);
        }

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (grid[r][c] != 0) {
                    drawCell(g, ox + c * cell, oy + r * cell, cell, COLORS[grid[r][c] - 1]);
                }
            }
        }

        // Ombre de chute : indispensable pour viser une colonne au fond du terrain.
        if (!dead) {
            int ghost = py;
            while (!collides(px, ghost + 1, rotation)) {
                ghost++;
            }
            for (int[] c : SHAPES[piece][rotation]) {
                int cy = ghost + c[1];
                if (cy >= 0) {
                    g.renderOutline(ox + (px + c[0]) * cell, oy + cy * cell, cell, cell, 0x55FFFFFF);
                }
            }
            for (int[] c : SHAPES[piece][rotation]) {
                int cy = py + c[1];
                if (cy >= 0) {
                    drawCell(g, ox + (px + c[0]) * cell, oy + cy * cell, cell, COLORS[piece]);
                }
            }
        }
        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF6A5FC0);

        int panelX = ox + fieldW + cell;
        g.fillGradient(panelX, oy, panelX + panelW, oy + cell * 6, 0xFF2E2666, 0xFF150F33);
        g.renderOutline(panelX, oy, panelW, cell * 6, 0xFF6A5FC0);
        for (int[] c : SHAPES[next][0]) {
            drawCell(g, panelX + cell / 2 + c[0] * cell, oy + cell + c[1] * cell, cell, COLORS[next]);
        }
    }

    private static void drawCell(GuiGraphics g, int x, int y, int cell, int color) {
        g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, color);
        // Un lisere clair en haut a gauche donne du relief sans texture.
        g.fill(x + 1, y + 1, x + cell - 1, y + 2, 0x44FFFFFF);
        g.fill(x + 1, y + 1, x + 2, y + cell - 1, 0x33FFFFFF);
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
        return "Fleches deplacer - Haut tourner - Espace lacher";
    }

    @Override
    public double aspect() {
        return (double) (COLS + 6) / ROWS;
    }
}
