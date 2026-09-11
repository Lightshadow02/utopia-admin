package com.utopia.client.arcade;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Casse-briques. La raquette suit la souris, la balle rebondit, le mur descend d'un cran a chaque
 * niveau nettoye.
 *
 * <p>Le terrain est en coordonnees flottantes de 0 a 100 et n'est converti en pixels qu'au dessin :
 * une balle calee sur la grille de l'ecran rebondirait par paliers visibles, et le jeu serait
 * different selon la taille de la fenetre.
 */
public final class BreakoutGame implements ArcadeGame {

    private static final double W = 100.0;
    private static final double H = 75.0;
    private static final int BRICK_COLS = 10;
    private static final int BRICK_ROWS = 5;
    private static final double BRICK_W = W / BRICK_COLS;
    private static final double BRICK_H = 3.2;
    private static final double BRICK_TOP = 8.0;
    private static final double PADDLE_W = 16.0;
    private static final double PADDLE_H = 2.0;
    private static final double PADDLE_Y = H - 5.0;
    private static final double BALL_R = 1.1;

    private static final int[] ROW_COLORS = {
            0xFFD8434B, 0xFFE08A2E, 0xFFE0D02E, 0xFF5FC45F, 0xFF3B9FD6};

    private final boolean[][] bricks = new boolean[BRICK_ROWS][BRICK_COLS];

    private double paddleX = W / 2;
    private double ballX = W / 2;
    private double ballY = PADDLE_Y - 6;
    private double ballVX;
    private double ballVY;
    private double speed = 42.0;
    private int lives = 3;
    private int level = 1;
    private long score;
    private boolean dead;
    private boolean launched;

    public BreakoutGame() {
        fillWall();
    }

    private void fillWall() {
        for (int r = 0; r < BRICK_ROWS; r++) {
            for (int c = 0; c < BRICK_COLS; c++) {
                bricks[r][c] = true;
            }
        }
    }

    private boolean wallCleared() {
        for (boolean[] row : bricks) {
            for (boolean b : row) {
                if (b) {
                    return false;
                }
            }
        }
        return true;
    }

    private void resetBall() {
        launched = false;
        ballX = paddleX;
        ballY = PADDLE_Y - BALL_R - 0.6;
        ballVX = 0;
        ballVY = 0;
    }

    private void launch() {
        if (launched || dead) {
            return;
        }
        launched = true;
        // Depart toujours vers le haut, legerement de biais : un tir vertical pur ferait un
        // aller-retour sans fin entre la raquette et le plafond.
        double angle = Math.toRadians(-60 - Math.random() * 60);
        ballVX = Math.cos(angle) * speed;
        ballVY = Math.sin(angle) * speed;
    }

    @Override
    public void update(double dt) {
        if (dead) {
            return;
        }
        if (!launched) {
            ballX = paddleX;
            ballY = PADDLE_Y - BALL_R - 0.6;
            return;
        }
        // Pas fixes : une balle deplacee d'un bloc a 5 images par seconde traverserait une brique
        // sans jamais la toucher.
        double remaining = Math.min(dt, 0.25);
        while (remaining > 0) {
            double step = Math.min(remaining, 1.0 / 240.0);
            remaining -= step;
            advance(step);
            if (dead || !launched) {
                return;
            }
        }
    }

    private void advance(double dt) {
        ballX += ballVX * dt;
        ballY += ballVY * dt;

        if (ballX - BALL_R < 0) {
            ballX = BALL_R;
            ballVX = Math.abs(ballVX);
        } else if (ballX + BALL_R > W) {
            ballX = W - BALL_R;
            ballVX = -Math.abs(ballVX);
        }
        if (ballY - BALL_R < 0) {
            ballY = BALL_R;
            ballVY = Math.abs(ballVY);
        }

        if (ballY - BALL_R > H) {
            lives--;
            if (lives <= 0) {
                dead = true;
            } else {
                resetBall();
            }
            return;
        }

        // Raquette : l'angle de renvoi depend du point d'impact, c'est ce qui rend le jeu pilotable.
        if (ballVY > 0 && ballY + BALL_R >= PADDLE_Y && ballY - BALL_R <= PADDLE_Y + PADDLE_H
                && ballX >= paddleX - PADDLE_W / 2 - BALL_R
                && ballX <= paddleX + PADDLE_W / 2 + BALL_R) {
            double offset = (ballX - paddleX) / (PADDLE_W / 2);
            offset = Math.max(-1, Math.min(1, offset));
            double angle = Math.toRadians(-90 + offset * 60);
            speed = Math.min(speed * 1.01, 95.0);
            ballVX = Math.cos(angle) * speed;
            ballVY = Math.sin(angle) * speed;
            ballY = PADDLE_Y - BALL_R;
            return;
        }

        hitBricks();
    }

    private void hitBricks() {
        if (ballY - BALL_R > BRICK_TOP + BRICK_ROWS * BRICK_H || ballY + BALL_R < BRICK_TOP) {
            return;
        }
        int row = (int) ((ballY - BRICK_TOP) / BRICK_H);
        int col = (int) (ballX / BRICK_W);
        if (row < 0 || row >= BRICK_ROWS || col < 0 || col >= BRICK_COLS || !bricks[row][col]) {
            return;
        }
        bricks[row][col] = false;
        score += (BRICK_ROWS - row) * 10L * level;

        // Le rebond suit le cote par lequel on est entre : comparer les depassements horizontal et
        // vertical evite qu'une brique touchee de cote ne renvoie la balle vers le bas.
        double bx = col * BRICK_W;
        double by = BRICK_TOP + row * BRICK_H;
        double overlapX = Math.min(ballX + BALL_R - bx, bx + BRICK_W - (ballX - BALL_R));
        double overlapY = Math.min(ballY + BALL_R - by, by + BRICK_H - (ballY - BALL_R));
        if (overlapX < overlapY) {
            ballVX = -ballVX;
        } else {
            ballVY = -ballVY;
        }

        if (wallCleared()) {
            level++;
            speed = Math.min(42.0 + level * 5.0, 95.0);
            fillWall();
            resetBall();
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 32, 257 -> launch();      // espace, entree
            case 263, 65, 81 -> paddleX = Math.max(PADDLE_W / 2, paddleX - 4);
            case 262, 68 -> paddleX = Math.min(W - PADDLE_W / 2, paddleX + 4);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mouseMoved(double fx, double fy) {
        paddleX = Math.max(PADDLE_W / 2, Math.min(W - PADDLE_W / 2, fx * W));
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        launch();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double scale = Math.min(width / W, height / H);
        int fieldW = (int) (W * scale);
        int fieldH = (int) (H * scale);
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fill(ox, oy, ox + fieldW, oy + fieldH, 0xFF07101A);

        for (int r = 0; r < BRICK_ROWS; r++) {
            for (int c = 0; c < BRICK_COLS; c++) {
                if (!bricks[r][c]) {
                    continue;
                }
                int bx = ox + (int) (c * BRICK_W * scale);
                int by = oy + (int) ((BRICK_TOP + r * BRICK_H) * scale);
                int bw = (int) (BRICK_W * scale) - 1;
                int bh = (int) (BRICK_H * scale) - 1;
                g.fill(bx, by, bx + bw, by + bh, ROW_COLORS[r]);
                g.fill(bx, by, bx + bw, by + 1, 0x55FFFFFF);
            }
        }

        int pw = (int) (PADDLE_W * scale);
        int ph = Math.max(2, (int) (PADDLE_H * scale));
        int pxp = ox + (int) ((paddleX - PADDLE_W / 2) * scale);
        int pyp = oy + (int) (PADDLE_Y * scale);
        g.fill(pxp, pyp, pxp + pw, pyp + ph, 0xFFDDE6F0);

        int br = Math.max(2, (int) (BALL_R * scale));
        int bxp = ox + (int) (ballX * scale);
        int byp = oy + (int) (ballY * scale);
        g.fill(bxp - br, byp - br, bxp + br, byp + br, 0xFFFFE9A8);

        // Vies restantes, en petits carres au pied du terrain.
        for (int i = 0; i < lives; i++) {
            int lx = ox + 3 + i * 8;
            int ly = oy + fieldH - 6;
            g.fill(lx, ly, lx + 5, ly + 3, 0xFFDDE6F0);
        }

        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF2A4360);
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
        return launched ? "Souris pour la raquette" : "Clic ou Espace pour lancer la balle";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
