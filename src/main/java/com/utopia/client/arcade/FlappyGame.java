package com.utopia.client.arcade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Un jeu a une touche : l'oiseau tombe, chaque pression le relance, et il faut passer entre les
 * tuyaux.
 *
 * <p>La premiere pression lance la partie. Sans ce depart differe, l'oiseau tomberait pendant que
 * le joueur lit l'ecran, et la partie payee serait perdue avant d'avoir commence.
 */
public final class FlappyGame implements ArcadeGame {

    private static final double W = 100.0;
    private static final double H = 75.0;
    private static final double GRAVITY = 190.0;
    private static final double FLAP = -62.0;
    private static final double SPEED = 34.0;
    private static final double PIPE_W = 11.0;
    private static final double SPACING = 42.0;
    private static final double BIRD_X = 26.0;
    private static final double BIRD_R = 2.4;

    /** Un tuyau : l'ouverture va de {@code gapY} a {@code gapY + gapH}. */
    private static final class Pipe {
        double x;
        double gapY;
        double gapH;
        boolean passed;
    }

    private final Random random = new Random();
    private final Deque<Pipe> pipes = new ArrayDeque<>();

    private double birdY = H / 2;
    private double birdV;
    private long score;
    private boolean dead;
    private boolean started;

    public FlappyGame() {
        double x = W + 10;
        for (int i = 0; i < 4; i++) {
            pipes.addLast(creer(x + i * SPACING));
        }
    }

    private Pipe creer(double x) {
        Pipe p = new Pipe();
        p.x = x;
        // L'ouverture se resserre avec le score, mais jamais au point de devenir infranchissable.
        p.gapH = Math.max(15.0, 24.0 - score * 0.25);
        p.gapY = 8 + random.nextDouble() * (H - 16 - p.gapH);
        return p;
    }

    @Override
    public void update(double dt) {
        if (dead || !started) {
            return;
        }
        dt = Math.min(dt, 0.05);
        birdV += GRAVITY * dt;
        birdY += birdV * dt;

        if (birdY - BIRD_R < 0) {
            birdY = BIRD_R;
            birdV = 0;
        }
        if (birdY + BIRD_R > H) {
            dead = true;
            return;
        }

        for (Pipe p : pipes) {
            p.x -= SPEED * dt;
            if (!p.passed && p.x + PIPE_W < BIRD_X) {
                p.passed = true;
                score++;
            }
            // Collision : le tuyau est un rectangle troue, on teste le chevauchement horizontal
            // puis la sortie de l'ouverture.
            if (BIRD_X + BIRD_R > p.x && BIRD_X - BIRD_R < p.x + PIPE_W
                    && (birdY - BIRD_R < p.gapY || birdY + BIRD_R > p.gapY + p.gapH)) {
                dead = true;
                return;
            }
        }
        while (!pipes.isEmpty() && pipes.peekFirst().x + PIPE_W < -2) {
            pipes.pollFirst();
            pipes.addLast(creer(pipes.peekLast().x + SPACING));
        }
    }

    private void battre() {
        if (dead) {
            return;
        }
        started = true;
        birdV = FLAP;
    }

    @Override
    public boolean keyPressed(int keyCode) {
        if (keyCode == 32 || keyCode == 257 || keyCode == 265 || keyCode == 87 || keyCode == 90) {
            battre();
            return true;
        }
        return false;
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        battre();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double scale = Math.min(width / W, height / H);
        int fw = (int) (W * scale);
        int fh = (int) (H * scale);
        int ox = x + (width - fw) / 2;
        int oy = y + (height - fh) / 2;

        g.fillGradient(ox, oy, ox + fw, oy + fh, 0xFF1A3A6E, 0xFF3E7AB0);

        for (Pipe p : pipes) {
            int px = ox + (int) (p.x * scale);
            int pw = (int) (PIPE_W * scale);
            int haut = oy + (int) (p.gapY * scale);
            int bas = oy + (int) ((p.gapY + p.gapH) * scale);
            g.fill(px, oy, px + pw, haut, 0xFF3E9E4E);
            g.fill(px, bas, px + pw, oy + fh, 0xFF3E9E4E);
            // Colerette : sans elle, les tuyaux sont des barres plates sans relief.
            g.fill(px - 2, haut - (int) (2 * scale), px + pw + 2, haut, 0xFF54BE64);
            g.fill(px - 2, bas, px + pw + 2, bas + (int) (2 * scale), 0xFF54BE64);
        }

        int br = Math.max(2, (int) (BIRD_R * scale));
        int bx = ox + (int) (BIRD_X * scale);
        int by = oy + (int) (birdY * scale);
        g.fill(bx - br, by - br, bx + br, by + br, 0xFFF5D63A);
        g.fill(bx + br / 2, by - br / 3, bx + br + 1, by + br / 3, 0xFFE08A2E);

        if (!started && !dead) {
            g.fill(ox, oy + fh / 2 - (int) (6 * scale), ox + fw, oy + fh / 2 + (int) (6 * scale),
                    0x66000000);
        }
        g.renderOutline(ox - 1, oy - 1, fw + 2, fh + 2, 0xFF24507E);
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
        return started ? "Espace ou clic pour battre des ailes" : "Espace ou clic pour commencer";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
