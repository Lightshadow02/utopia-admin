package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Simon. La machine allonge sa sequence de couleurs d'un cran a chaque manche, le joueur la rejoue
 * de memoire, et la premiere faute termine la partie.
 *
 * <p>Trois details font la jouabilite. Les deux tours sont separes : pendant la demonstration le
 * plateau est assombri, marque "Observe", et les clics sont avales - sans cela on perd en cliquant
 * pendant que la machine parle. Chaque couleur s'eteint completement avant la suivante, sinon
 * "vert vert" serait impossible a distinguer de "vert". Enfin la cadence accelere avec la longueur
 * mais s'arrete sur un plancher : au-dela, ce n'est plus de la memoire, c'est du reflexe.
 *
 * <p>Une manche vaut le carre de sa longueur : tenir douze couleurs doit rapporter bien plus que
 * douze manches d'une seule couleur, sinon abandonner tot serait la meilleure strategie.
 */
public final class SimonGame implements ArcadeGame {

    private static final double W = 100.0;
    private static final double H = 100.0;
    /** Le plateau laisse une bande en haut pour le titre de phase et la jauge de reponse. */
    private static final double PLATEAU_X = 9.0;
    private static final double PLATEAU_Y = 17.0;
    private static final double PLATEAU_T = 82.0;
    private static final double FENTE = 3.0;
    private static final double MOYEU = 22.0;

    /** Secondes laissees au joueur pour chaque appui : evite une partie qui ne finit jamais. */
    private static final double TEMPS_REPONSE = 5.0;
    private static final double DUREE_ERREUR = 1.2;

    private static final int[] SOMBRE = {0xFF1B5E2A, 0xFF7E1C20, 0xFF7E6C14, 0xFF17447E};
    private static final int[] CLAIR = {0xFF6BF08A, 0xFFFF6363, 0xFFFFE860, 0xFF66B8FF};
    /** Rappel d'appui dessine sur chaque quartier : la disposition des fleches n'est pas devinable. */
    private static final String[] TOUCHES = {"1 ^", "2 >", "3 <", "4 v"};

    private enum Phase { PRET, PAUSE, DEMO, JOUEUR, ERREUR, FINI }

    private final Random random = new Random();
    private final List<Integer> sequence = new ArrayList<>();

    private Phase phase = Phase.PRET;
    /** Position lue dans la sequence : par la machine en DEMO, par le joueur en JOUEUR. */
    private int index;
    private double minuteur;
    private double reponse;
    private double eclatJoueur;
    /** Quartier actuellement illumine, -1 si le plateau est au repos. */
    private int allume = -1;
    private boolean allumeEnCours;
    private long score;

    /** Duree d'allumage d'une couleur, avec plancher pour rester memorisable. */
    private double tempsAllume() {
        return Math.max(0.20, 0.62 - (sequence.size() - 1) * 0.025);
    }

    /** Temps mort entre deux couleurs : c'est lui qui rend "vert vert" lisible. */
    private double tempsEteint() {
        return Math.max(0.10, tempsAllume() * 0.45);
    }

    private void mancheSuivante() {
        sequence.add(random.nextInt(4));
        index = 0;
        allume = -1;
        allumeEnCours = false;
        minuteur = 0.0;
        phase = Phase.DEMO;
    }

    private void demarrer() {
        if (phase != Phase.PRET) {
            return;
        }
        phase = Phase.PAUSE;
        minuteur = 0.7;
    }

    @Override
    public void update(double dt) {
        if (phase == Phase.FINI) {
            return;
        }
        // Une image longue ne doit pas faire defiler la demonstration d'un coup : on borne le pas.
        double pas = Math.max(0.0, Math.min(dt, 0.25));

        if (eclatJoueur > 0) {
            eclatJoueur -= pas;
            // L'eclat de confirmation s'eteint aussi pendant la pause de fin de manche, mais
            // jamais pendant ERREUR : c'est la que la bonne couleur doit rester visible.
            if (eclatJoueur <= 0 && phase != Phase.ERREUR) {
                allume = -1;
            }
        }

        switch (phase) {
            case PAUSE -> {
                minuteur -= pas;
                if (minuteur <= 0) {
                    mancheSuivante();
                }
            }
            case DEMO -> avancerDemo(pas);
            case JOUEUR -> {
                reponse -= pas;
                if (reponse <= 0) {
                    rater();
                }
            }
            case ERREUR -> {
                minuteur -= pas;
                if (minuteur <= 0) {
                    phase = Phase.FINI;
                }
            }
            default -> { }
        }
    }

    private void avancerDemo(double pas) {
        minuteur -= pas;
        // Garde-fou : les durees sont minorees par un plancher, la boucle ne peut pas s'emballer,
        // mais un compteur coute moins cher qu'un ecran fige.
        int tours = 0;
        while (minuteur <= 0 && phase == Phase.DEMO && tours < 64) {
            tours++;
            if (allumeEnCours) {
                allumeEnCours = false;
                allume = -1;
                index++;
                if (index >= sequence.size()) {
                    index = 0;
                    reponse = TEMPS_REPONSE;
                    phase = Phase.JOUEUR;
                    return;
                }
                minuteur += tempsEteint();
            } else {
                allumeEnCours = true;
                allume = sequence.get(index);
                minuteur += tempsAllume();
            }
        }
    }

    private void rater() {
        // On montre la couleur attendue avant de conclure : sans cela le joueur ne sait pas ce
        // qu'il a mal retenu, et la partie est payante.
        allume = sequence.isEmpty() ? -1 : sequence.get(Math.min(index, sequence.size() - 1));
        eclatJoueur = 0;
        minuteur = DUREE_ERREUR;
        phase = Phase.ERREUR;
    }

    private void jouer(int quartier) {
        if (phase == Phase.PRET) {
            demarrer();
            return;
        }
        // Hors du tour du joueur, l'appui est purement ignore.
        if (phase != Phase.JOUEUR || quartier < 0 || quartier > 3 || index >= sequence.size()) {
            return;
        }
        allume = quartier;
        eclatJoueur = 0.22;
        if (sequence.get(index) != quartier) {
            rater();
            return;
        }
        index++;
        reponse = TEMPS_REPONSE;
        if (index >= sequence.size()) {
            int n = sequence.size();
            score += 10L * n * n;
            phase = Phase.PAUSE;
            minuteur = 0.9;
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        int quartier = switch (keyCode) {
            case 49, 321, 265, 87, 90 -> 0;   // 1, pave 1, haut, W, Z
            case 50, 322, 262, 68 -> 1;       // 2, pave 2, droite, D
            case 51, 323, 263, 65, 81 -> 2;   // 3, pave 3, gauche, A, Q
            case 52, 324, 264, 83 -> 3;       // 4, pave 4, bas, S
            case 32, 257 -> -1;               // espace, entree : uniquement pour lancer la partie
            default -> -2;
        };
        if (quartier == -2) {
            return false;
        }
        if (quartier < 0) {
            demarrer();
            return true;
        }
        jouer(quartier);
        return true;
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        if (phase == Phase.PRET) {
            demarrer();
            return;
        }
        double lx = fx * W;
        double ly = fy * H;
        // Le moyeu porte le numero de manche et couvre les coins interieurs des quatre quartiers :
        // un clic dessus ne designe aucune couleur, il ne doit pas en valider une au hasard.
        double cx = PLATEAU_X + PLATEAU_T / 2.0;
        double cy = PLATEAU_Y + PLATEAU_T / 2.0;
        double dmx = lx - cx;
        double dmy = ly - cy;
        if (dmx * dmx + dmy * dmy <= (MOYEU / 2.0) * (MOYEU / 2.0)) {
            return;
        }
        for (int q = 0; q < 4; q++) {
            double[] r = cadre(q);
            if (lx >= r[0] && lx <= r[2] && ly >= r[1] && ly <= r[3]) {
                jouer(q);
                return;
            }
        }
        // Un clic entre les quartiers ou hors du plateau ne compte pas comme une faute.
    }

    /** Coins logiques du quartier : 0 haut-gauche, 1 haut-droite, 2 bas-gauche, 3 bas-droite. */
    private static double[] cadre(int q) {
        double demi = (PLATEAU_T - FENTE) / 2.0;
        double x1 = (q == 0 || q == 2) ? PLATEAU_X : PLATEAU_X + demi + FENTE;
        double y1 = (q < 2) ? PLATEAU_Y : PLATEAU_Y + demi + FENTE;
        return new double[] {x1, y1, x1 + demi, y1 + demi};
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        double echelle = Math.min(width / W, height / H);
        int fieldW = (int) (W * echelle);
        int fieldH = (int) (H * echelle);
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fillGradient(ox, oy, ox + fieldW, oy + fieldH, 0xFF241C2E, 0xFF120C18);

        boolean tourJoueur = phase == Phase.JOUEUR;
        for (int q = 0; q < 4; q++) {
            double[] r = cadre(q);
            int x1 = ox + (int) (r[0] * echelle);
            int y1 = oy + (int) (r[1] * echelle);
            int x2 = ox + (int) (r[2] * echelle);
            int y2 = oy + (int) (r[3] * echelle);
            int couleur = allume == q ? CLAIR[q] : teinte(SOMBRE[q], tourJoueur ? 1.0 : 0.55);
            g.fill(x1, y1, x2, y2, couleur);
            g.renderOutline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), 0xFF05070C);
            g.drawCenteredString(font, TOUCHES[q], (x1 + x2) / 2, (y1 + y2) / 2 - 4,
                    allume == q ? 0xFF12181F : 0x77FFFFFF);
        }

        // Moyeu central : il porte le numero de manche, seule information dont le joueur a besoin
        // pendant qu'il compte.
        int cx = ox + (int) ((PLATEAU_X + PLATEAU_T / 2) * echelle);
        int cy = oy + (int) ((PLATEAU_Y + PLATEAU_T / 2) * echelle);
        int demiMoyeu = Math.max(8, (int) (MOYEU * echelle / 2));
        g.fill(cx - demiMoyeu, cy - demiMoyeu, cx + demiMoyeu, cy + demiMoyeu, 0xFF11161E);
        g.renderOutline(cx - demiMoyeu, cy - demiMoyeu, demiMoyeu * 2, demiMoyeu * 2, 0xFF6A5A78);
        long numero = Math.max(1, sequence.size());
        g.drawCenteredString(font, "Manche", cx, cy - 9, 0xFF7E8798);
        g.drawCenteredString(font, Long.toString(numero), cx, cy + 1, 0xFFE8EEF6);

        String titre = switch (phase) {
            case PRET -> "Clic ou Espace pour commencer";
            case PAUSE -> sequence.isEmpty() ? "Prepare-toi" : "Manche reussie";
            case DEMO -> "Observe";
            case JOUEUR -> "A toi";
            case ERREUR -> "Rate : voici la bonne couleur";
            case FINI -> "Termine";
        };
        int encre = switch (phase) {
            case JOUEUR -> 0xFF9BE86B;
            case ERREUR, FINI -> 0xFFE07070;
            default -> 0xFFD8DEE8;
        };
        g.drawCenteredString(font, titre, ox + fieldW / 2, oy + 4, encre);

        // Jauge de reponse : le joueur doit voir fondre son delai, pas le decouvrir en perdant.
        int jaugeY = oy + (int) ((PLATEAU_Y - 3.5) * echelle);
        int jaugeX1 = ox + (int) (PLATEAU_X * echelle);
        int jaugeX2 = ox + (int) ((PLATEAU_X + PLATEAU_T) * echelle);
        int jaugeH = Math.max(2, (int) (2.0 * echelle));
        if (jaugeY > oy && jaugeX2 > jaugeX1) {
            g.fill(jaugeX1, jaugeY, jaugeX2, jaugeY + jaugeH, 0xFF1A202B);
            if (tourJoueur) {
                double part = Math.max(0.0, Math.min(1.0, reponse / TEMPS_REPONSE));
                int fin = jaugeX1 + (int) ((jaugeX2 - jaugeX1) * part);
                g.fill(jaugeX1, jaugeY, fin, jaugeY + jaugeH, part > 0.3 ? 0xFF4CAF50 : 0xFFE0A030);
            }
        }

        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF6A5A78);
    }

    private static int teinte(int argb, double facteur) {
        int r = (int) (((argb >> 16) & 0xFF) * facteur);
        int v = (int) (((argb >> 8) & 0xFF) * facteur);
        int b = (int) ((argb & 0xFF) * facteur);
        return 0xFF000000 | (r << 16) | (v << 8) | b;
    }

    @Override
    public long score() {
        return score;
    }

    @Override
    public boolean over() {
        return phase == Phase.FINI;
    }

    @Override
    public String hint() {
        return "Retiens la sequence, puis rejoue-la";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
