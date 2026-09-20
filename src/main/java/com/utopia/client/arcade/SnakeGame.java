package com.utopia.client.arcade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Snake. Le serpent avance tout seul, grandit en mangeant, meurt contre un mur ou contre lui-meme.
 *
 * <p>Deux details font toute la jouabilite : le demi-tour instantane est refuse (sinon on se tue
 * d'une touche par megarde), et les changements de direction sont mis en file d'attente - a haute
 * vitesse, deux virages tapes dans le meme pas seraient sinon avales par le premier.
 */
public final class SnakeGame implements ArcadeGame {

    private static final int COLS = 24;
    private static final int ROWS = 18;
    /** Pas par seconde au depart ; le serpent accelere a mesure qu'il mange. */
    private static final double BASE_SPEED = 7.0;
    private static final double MAX_SPEED = 18.0;

    private final Random random = new Random();
    private final Deque<int[]> body = new ArrayDeque<>();
    private final Set<Integer> occupied = new HashSet<>();
    /** Virages en attente : au plus deux, de quoi enchainer un angle droit sans perdre de touche. */
    private final Deque<int[]> turns = new ArrayDeque<>();

    private int dirX = 1;
    private int dirY = 0;
    private int foodX;
    private int foodY;
    private long score;
    private boolean dead;
    private double accumulator;
    /** Compte a rebours avant le premier pas : le temps de poser les doigts sur les touches. */
    private double startDelay = 1.0;

    public SnakeGame() {
        int cx = COLS / 3;
        int cy = ROWS / 2;
        for (int i = 2; i >= 0; i--) {
            push(cx - i, cy);
        }
        placeFood();
    }

    private static int key(int x, int y) {
        return y * COLS + x;
    }

    private void push(int x, int y) {
        body.addLast(new int[] {x, y});
        occupied.add(key(x, y));
    }

    private void placeFood() {
        if (occupied.size() >= COLS * ROWS) {
            return; // terrain plein : la partie est de toute facon gagnee
        }
        do {
            foodX = random.nextInt(COLS);
            foodY = random.nextInt(ROWS);
        } while (occupied.contains(key(foodX, foodY)));
    }

    private double speed() {
        return Math.min(MAX_SPEED, BASE_SPEED + body.size() * 0.18);
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
        accumulator += dt * speed();
        // Borne le rattrapage : apres une pause (fenetre reduite, chargement), le serpent ne doit
        // pas traverser le terrain d'un coup.
        int steps = 0;
        while (accumulator >= 1.0 && steps < 4 && !dead) {
            accumulator -= 1.0;
            steps++;
            step();
        }
        if (accumulator > 1.0) {
            accumulator = 0;
        }
    }

    private void step() {
        int[] turn = turns.pollFirst();
        if (turn != null) {
            dirX = turn[0];
            dirY = turn[1];
        }
        int[] head = body.peekLast();
        int nx = head[0] + dirX;
        int ny = head[1] + dirY;
        if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) {
            dead = true;
            return;
        }
        boolean eats = nx == foodX && ny == foodY;
        // La queue libere sa case AVANT le test de collision : sans cela, avancer en suivant sa
        // propre queue serait une mort alors que la case se libere dans le meme pas.
        if (!eats) {
            int[] tail = body.pollFirst();
            occupied.remove(key(tail[0], tail[1]));
        }
        if (occupied.contains(key(nx, ny))) {
            dead = true;
            return;
        }
        push(nx, ny);
        if (eats) {
            score += 10;
            placeFood();
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        int[] d = switch (keyCode) {
            case 265, 87, 90 -> new int[] {0, -1};  // haut, W, Z
            case 264, 83 -> new int[] {0, 1};       // bas, S
            case 263, 65, 81 -> new int[] {-1, 0};  // gauche, A, Q
            case 262, 68 -> new int[] {1, 0};       // droite, D
            default -> null;
        };
        if (d == null) {
            return false;
        }
        int[] last = turns.peekLast();
        int curX = last != null ? last[0] : dirX;
        int curY = last != null ? last[1] : dirY;
        // Demi-tour refuse : il tuerait le joueur instantanement sur son propre cou.
        if (d[0] == -curX && d[1] == -curY) {
            return true;
        }
        if (d[0] == curX && d[1] == curY) {
            return true;
        }
        if (turns.size() < 2) {
            turns.addLast(d);
        }
        return true;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        int cell = Math.max(1, Math.min(width / COLS, height / ROWS));
        int fieldW = cell * COLS;
        int fieldH = cell * ROWS;
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fillGradient(ox, oy, ox + fieldW, oy + fieldH, 0xFF1A3A5C, 0xFF122C48);
        // Quadrillage discret : sans repere, on juge mal la distance au mur.
        for (int c = 1; c < COLS; c++) {
            g.fill(ox + c * cell, oy, ox + c * cell + 1, oy + fieldH, 0xFF2B5C8A);
        }
        for (int r = 1; r < ROWS; r++) {
            g.fill(ox, oy + r * cell, ox + fieldW, oy + r * cell + 1, 0xFF2B5C8A);
        }

        g.fill(ox + foodX * cell + 1, oy + foodY * cell + 1,
                ox + (foodX + 1) * cell - 1, oy + (foodY + 1) * cell - 1, 0xFFE04040);

        int i = 0;
        int n = body.size();
        for (int[] seg : body) {
            i++;
            boolean head = i == n;
            int color = head ? 0xFF9BE86B : shade(0xFF4CAF50, 1.0 - (double) (n - i) / Math.max(1, n) * 0.45);
            g.fill(ox + seg[0] * cell + 1, oy + seg[1] * cell + 1,
                    ox + (seg[0] + 1) * cell - 1, oy + (seg[1] + 1) * cell - 1, color);
        }

        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF5FA8C8);
    }

    private static int shade(int argb, double factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int gr = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
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
        return "Fleches ou ZQSD pour tourner";
    }

    @Override
    public double aspect() {
        return (double) COLS / ROWS;
    }
}
