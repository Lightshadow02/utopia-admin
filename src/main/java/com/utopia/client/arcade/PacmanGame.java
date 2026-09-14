package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Pac-Man. Un labyrinthe, des pastilles, et quatre fantomes qui ne poursuivent pas de la meme
 * facon - c'est la seule chose qui rende le jeu interessant, alors elle est faite pour de vrai.
 *
 * <p>Chaque fantome vise une <b>case cible</b> et, a chaque intersection, prend la direction qui
 * l'en rapproche le plus, sans jamais faire demi-tour. Blinky vise Pac-Man ; Pinky vise quatre
 * cases devant lui, donc le coupe ; Inky vise le point symetrique de Blinky par rapport a deux
 * cases devant Pac-Man, ce qui le rend imprevisible ; Clyde poursuit de loin mais fuit des qu'il
 * approche. Tous alternent entre poursuite et dispersion vers leur coin : sans ces respirations,
 * une meute de quatre est imparable.
 */
public final class PacmanGame implements ArcadeGame {

    /** '#' mur, '.' pastille, 'o' super-pastille, ' ' vide, '-' porte de la maison. */
    private static final String[] MAZE = {
            "###################",
            "#........#........#",
            "#o##.###.#.###.##o#",
            "#.................#",
            "#.##.#.#####.#.##.#",
            "#....#.......#....#",
            "####.###   ###.####",
            "####.#       #.####",
            "####.# ##-## #.####",
            "    .  #   #  .    ",
            "####.# ##### #.####",
            "####.#       #.####",
            "####.# ##### #.####",
            "#........#........#",
            "#.##.###.#.###.##.#",
            "#o.#.....#.....#.o#",
            "##.#.#.#####.#.#.##",
            "#....#...#...#....#",
            "#.######.#.######.#",
            "#.................#",
            "###################",
    };

    private static final int COLS = 19;
    private static final int ROWS = 21;

    /** Vitesses en cases par seconde. */
    private static final double PAC_SPEED = 6.5;
    private static final double GHOST_SPEED = 5.6;
    private static final double GHOST_FRIGHT_SPEED = 3.4;
    /** Duree pendant laquelle les fantomes sont vulnerables, en secondes. */
    private static final double FRIGHT_TIME = 7.0;

    private static final int[] GHOST_COLORS = {0xFFE04040, 0xFFE8A0C8, 0xFF50D8E0, 0xFFE0A040};

    private final char[][] grid = new char[ROWS][COLS];
    private final Random random = new Random();

    private double pacX;
    private double pacY;
    private int pacDirX = -1;
    private int pacDirY;
    private int wantDirX = -1;
    private int wantDirY;

    private final Ghost[] ghosts = new Ghost[4];

    private long score;
    private int lives = 3;
    private int pellets;
    private int level = 1;
    private boolean dead;
    private double frightTimer;
    private int frightChain;
    private double modeTimer;
    private boolean chasing;
    private double startDelay = 1.6;
    private double deathPause;

    /** Un fantome : sa position, sa direction, sa maison, et l'etat qui decide de sa cible. */
    private static final class Ghost {
        double x;
        double y;
        int dirX;
        int dirY;
        final int homeX;
        final int homeY;
        final int scatterX;
        final int scatterY;
        final int index;
        boolean eaten;
        double releaseIn;

        Ghost(int index, double x, double y, int scatterX, int scatterY, double releaseIn) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.homeX = (int) x;
            this.homeY = (int) y;
            this.scatterX = scatterX;
            this.scatterY = scatterY;
            this.releaseIn = releaseIn;
            this.dirX = -1;
        }
    }

    public PacmanGame() {
        chargerLabyrinthe();
        placerActeurs();
    }

    private void chargerLabyrinthe() {
        pellets = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = MAZE[r].charAt(c);
                grid[r][c] = ch;
                if (ch == '.' || ch == 'o') {
                    pellets++;
                }
            }
        }
    }

    private void placerActeurs() {
        pacX = 9.5;
        pacY = 19.5;
        pacDirX = -1;
        pacDirY = 0;
        wantDirX = -1;
        wantDirY = 0;
        ghosts[0] = new Ghost(0, 9.5, 8.5, COLS - 2, 0, 0.0);
        ghosts[1] = new Ghost(1, 8.5, 9.5, 1, 0, 2.0);
        ghosts[2] = new Ghost(2, 9.5, 9.5, COLS - 2, ROWS - 1, 5.0);
        ghosts[3] = new Ghost(3, 10.5, 9.5, 1, ROWS - 1, 8.0);
        frightTimer = 0;
        modeTimer = 0;
        chasing = false;
        startDelay = 1.6;
    }

    private boolean mur(int c, int r) {
        if (r < 0 || r >= ROWS) {
            return true;
        }
        c = ((c % COLS) + COLS) % COLS; // le tunnel relie les deux bords
        char ch = grid[r][c];
        return ch == '#' || ch == '-';
    }

    /** Le mur vu par un fantome : la porte de la maison le laisse passer. */
    private boolean murFantome(int c, int r) {
        if (r < 0 || r >= ROWS) {
            return true;
        }
        c = ((c % COLS) + COLS) % COLS;
        return grid[r][c] == '#';
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
        if (deathPause > 0) {
            deathPause -= dt;
            if (deathPause <= 0) {
                placerActeurs();
            }
            return;
        }
        dt = Math.min(dt, 0.05);

        if (frightTimer > 0) {
            frightTimer -= dt;
            if (frightTimer <= 0) {
                frightChain = 0;
            }
        } else {
            // Alternance dispersion / poursuite : 7 s de repit, 20 s de chasse.
            modeTimer += dt;
            double duree = chasing ? 20.0 : 7.0;
            if (modeTimer >= duree) {
                modeTimer = 0;
                chasing = !chasing;
                for (Ghost g : ghosts) {
                    g.dirX = -g.dirX;
                    g.dirY = -g.dirY;
                }
            }
        }

        bougerPacman(dt);
        for (Ghost g : ghosts) {
            bougerFantome(g, dt);
        }
        collisions();
    }

    private void bougerPacman(double dt) {
        // Le virage demande est pris des qu'il devient possible : sans cette memoire, il faudrait
        // appuyer au pixel pres et le jeu serait injouable.
        if (aligne(pacX) && aligne(pacY)) {
            int c = (int) pacX;
            int r = (int) pacY;
            if ((wantDirX != pacDirX || wantDirY != pacDirY)
                    && !mur(c + wantDirX, r + wantDirY)) {
                pacDirX = wantDirX;
                pacDirY = wantDirY;
            }
            if (mur(c + pacDirX, r + pacDirY)) {
                return; // nez au mur
            }
        }
        pacX += pacDirX * PAC_SPEED * dt;
        pacY += pacDirY * PAC_SPEED * dt;
        pacX = enrouler(pacX);
        manger();
    }

    /** Vrai quand la position est assez proche du centre d'une case pour y tourner. */
    private static boolean aligne(double v) {
        double f = v - Math.floor(v);
        return Math.abs(f - 0.5) < 0.18;
    }

    private static double enrouler(double x) {
        if (x < -0.5) {
            return COLS - 0.5;
        }
        if (x > COLS - 0.5) {
            return -0.5;
        }
        return x;
    }

    private void manger() {
        int c = (int) pacX;
        int r = (int) pacY;
        if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
            return;
        }
        char ch = grid[r][c];
        if (ch == '.') {
            grid[r][c] = ' ';
            score += 10;
            pellets--;
        } else if (ch == 'o') {
            grid[r][c] = ' ';
            score += 50;
            pellets--;
            frightTimer = Math.max(1.0, FRIGHT_TIME - (level - 1) * 0.8);
            frightChain = 0;
            for (Ghost g : ghosts) {
                if (!g.eaten) {
                    g.dirX = -g.dirX;
                    g.dirY = -g.dirY;
                }
            }
        }
        if (pellets <= 0) {
            level++;
            score += 500L * level;
            chargerLabyrinthe();
            placerActeurs();
        }
    }

    private void bougerFantome(Ghost g, double dt) {
        if (g.releaseIn > 0) {
            g.releaseIn -= dt;
            return;
        }
        double vitesse = g.eaten ? GHOST_SPEED * 1.8
                : (frightTimer > 0 ? GHOST_FRIGHT_SPEED : GHOST_SPEED + (level - 1) * 0.2);

        if (aligne(g.x) && aligne(g.y)) {
            int c = (int) g.x;
            int r = (int) g.y;
            int[] cible = cibleDe(g);
            List<int[]> choix = new ArrayList<>(4);
            int[][] dirs = {{0, -1}, {-1, 0}, {0, 1}, {1, 0}};
            for (int[] d : dirs) {
                // Demi-tour interdit : c'est ce qui empeche les fantomes de vibrer sur place.
                if (d[0] == -g.dirX && d[1] == -g.dirY) {
                    continue;
                }
                if (!murFantome(c + d[0], r + d[1])) {
                    choix.add(d);
                }
            }
            if (choix.isEmpty()) {
                g.dirX = -g.dirX;
                g.dirY = -g.dirY;
            } else if (frightTimer > 0 && !g.eaten) {
                // Apeure, il detale au hasard : c'est ce qui rend la chasse possible.
                int[] d = choix.get(random.nextInt(choix.size()));
                g.dirX = d[0];
                g.dirY = d[1];
            } else {
                int[] meilleur = choix.get(0);
                double meilleure = Double.MAX_VALUE;
                for (int[] d : choix) {
                    double dx = (c + d[0]) - cible[0];
                    double dy = (r + d[1]) - cible[1];
                    double dist = dx * dx + dy * dy;
                    if (dist < meilleure) {
                        meilleure = dist;
                        meilleur = d;
                    }
                }
                g.dirX = meilleur[0];
                g.dirY = meilleur[1];
            }
        }
        g.x += g.dirX * vitesse * dt;
        g.y += g.dirY * vitesse * dt;
        g.x = enrouler(g.x);

        if (g.eaten && Math.abs(g.x - g.homeX - 0.5) < 0.4 && Math.abs(g.y - g.homeY - 0.5) < 0.4) {
            g.eaten = false;
        }
    }

    /** La case visee, qui donne a chaque fantome son caractere. */
    private int[] cibleDe(Ghost g) {
        if (g.eaten) {
            return new int[] {g.homeX, g.homeY};
        }
        if (!chasing) {
            return new int[] {g.scatterX, g.scatterY};
        }
        int pc = (int) pacX;
        int pr = (int) pacY;
        return switch (g.index) {
            // Blinky colle au train de Pac-Man.
            case 0 -> new int[] {pc, pr};
            // Pinky vise quatre cases devant : elle coupe la route au lieu de suivre.
            case 1 -> new int[] {pc + pacDirX * 4, pr + pacDirY * 4};
            // Inky prend le symetrique de Blinky par rapport a deux cases devant Pac-Man : sa
            // trajectoire depend de celle d'un autre, d'ou son imprevisibilite.
            case 2 -> {
                int ax = pc + pacDirX * 2;
                int ay = pr + pacDirY * 2;
                yield new int[] {2 * ax - (int) ghosts[0].x, 2 * ay - (int) ghosts[0].y};
            }
            // Clyde poursuit de loin, mais file dans son coin des qu'il approche a huit cases.
            default -> {
                double dx = g.x - pacX;
                double dy = g.y - pacY;
                yield dx * dx + dy * dy > 64 ? new int[] {pc, pr}
                        : new int[] {g.scatterX, g.scatterY};
            }
        };
    }

    private void collisions() {
        for (Ghost g : ghosts) {
            if (g.eaten || g.releaseIn > 0) {
                continue;
            }
            double dx = g.x - pacX;
            double dy = g.y - pacY;
            if (dx * dx + dy * dy > 0.45 * 0.45) {
                continue;
            }
            if (frightTimer > 0) {
                g.eaten = true;
                frightChain++;
                // 200, 400, 800, 1600 : la prime double a chaque fantome de la meme super-pastille.
                score += 200L * (1L << Math.min(3, frightChain - 1));
            } else {
                lives--;
                deathPause = 1.2;
                if (lives <= 0) {
                    dead = true;
                }
                return;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 265, 87, 90 -> {
                wantDirX = 0;
                wantDirY = -1;
            }
            case 264, 83 -> {
                wantDirX = 0;
                wantDirY = 1;
            }
            case 263, 65, 81 -> {
                wantDirX = -1;
                wantDirY = 0;
            }
            case 262, 68 -> {
                wantDirX = 1;
                wantDirY = 0;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        int cell = Math.max(3, Math.min(width / COLS, height / (ROWS + 1)));
        int fieldW = cell * COLS;
        int fieldH = cell * ROWS;
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fill(ox, oy, ox + fieldW, oy + fieldH, 0xFF05050C);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int px = ox + c * cell;
                int py = oy + r * cell;
                char ch = grid[r][c];
                if (ch == '#') {
                    g.fill(px + 1, py + 1, px + cell - 1, py + cell - 1, 0xFF2530A8);
                } else if (ch == '-') {
                    g.fill(px + 1, py + cell / 2, px + cell - 1, py + cell / 2 + 1, 0xFFE8A0C8);
                } else if (ch == '.') {
                    int d = Math.max(1, cell / 6);
                    g.fill(px + cell / 2 - d, py + cell / 2 - d,
                            px + cell / 2 + d, py + cell / 2 + d, 0xFFF0D8A8);
                } else if (ch == 'o') {
                    int d = Math.max(2, cell / 3);
                    g.fill(px + cell / 2 - d, py + cell / 2 - d,
                            px + cell / 2 + d, py + cell / 2 + d, 0xFFFFE070);
                }
            }
        }

        int pr = Math.max(2, cell / 2 - 1);
        int pcx = ox + (int) (pacX * cell);
        int pcy = oy + (int) (pacY * cell);
        g.fill(pcx - pr, pcy - pr, pcx + pr, pcy + pr, 0xFFFFE23A);

        for (Ghost gh : ghosts) {
            int gx = ox + (int) (gh.x * cell);
            int gy = oy + (int) (gh.y * cell);
            int gr = Math.max(2, cell / 2 - 1);
            int couleur;
            if (gh.eaten) {
                couleur = 0xFF404868;
            } else if (frightTimer > 0) {
                // Il clignote sur la fin : le joueur doit sentir que le repit se termine.
                couleur = frightTimer < 2.0 && ((int) (frightTimer * 6) % 2 == 0)
                        ? 0xFFE8E8E8 : 0xFF3040C0;
            } else {
                couleur = GHOST_COLORS[gh.index];
            }
            g.fill(gx - gr, gy - gr, gx + gr, gy + gr, couleur);
            if (!gh.eaten) {
                int oeil = Math.max(1, cell / 8);
                g.fill(gx - gr / 2, gy - gr / 3, gx - gr / 2 + oeil, gy - gr / 3 + oeil, 0xFFFFFFFF);
                g.fill(gx + gr / 3, gy - gr / 3, gx + gr / 3 + oeil, gy - gr / 3 + oeil, 0xFFFFFFFF);
            }
        }

        for (int i = 0; i < lives; i++) {
            int lx = ox + 3 + i * (cell - 1);
            int ly = oy + fieldH - cell / 2;
            g.fill(lx, ly, lx + cell / 2, ly + cell / 2, 0xFFFFE23A);
        }

        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF2530A8);
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
        return "Fleches ou ZQSD - les super-pastilles rendent les fantomes comestibles";
    }

    @Override
    public double aspect() {
        return (double) COLS / (ROWS + 1);
    }
}
