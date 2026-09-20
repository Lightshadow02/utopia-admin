package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Tape-taupes. Douze trous, des taupes qui montent puis redescendent, et quarante-cinq secondes
 * pour en assommer le plus possible a la souris.
 *
 * <p>Toute la jouabilite tient dans le COMBO : rater une taupe qui rentre, taper une bombe ou
 * cliquer dans le vide le remet a zero, et c'est lui qui multiplie les points jusqu'a cinq fois.
 * Sans cette regle, la strategie gagnante serait de marteler la souris au hasard sur la grille ;
 * avec elle, le joueur doit viser et se retenir, ce qui est exactement le geste du jeu.
 *
 * <p>La difficulte ne monte pas en donnant plus de points mais en laissant moins de temps : les
 * sorties raccourcissent et le nombre de taupes simultanees passe de une a quatre. Les bombes
 * n'arrivent qu'apres quelques secondes, le temps que le joueur comprenne ce qu'il regarde.
 */
public final class TaupesGame implements ArcadeGame {

    /** Terrain en unites logiques : converti en pixels au seul moment du dessin. */
    private static final double W = 120.0;
    private static final double H = 90.0;
    private static final int COLS = 4;
    private static final int ROWS = 3;
    private static final int TROUS = COLS * ROWS;
    /** Bandeau du haut reserve au chrono et au combo : aucune taupe ne doit y monter. */
    private static final double BANDEAU = 22.0;
    /** Haut de la zone de jeu : laisse de quoi sortir une taupe entiere sous le bandeau. */
    private static final double JEU_HAUT = 27.0;

    private static final double TROU_RX = 11.0;
    private static final double TROU_RY = 4.6;
    private static final double CORPS_RX = 8.0;
    private static final double CORPS_RY = 7.2;
    /** Course verticale du corps entre le fond du trou et la sortie complete. */
    private static final double MONTEE = CORPS_RY * 1.75;

    private static final double DUREE = 45.0;
    /** Repit avant le premier coup de pioche : la partie est payante, on ne la perd pas en lisant. */
    private static final double DEPART = 2.0;
    /** Retrait apres un coup reussi : la taupe plonge, on voit qu'elle est touchee. */
    private static final double RETRAIT = 0.14;
    private static final double VIE_FLOTTANT = 0.7;
    private static final int COMBO_MAX = 5;
    private static final long POINTS_TAUPE = 10L;
    private static final long POINTS_DOREE = 50L;
    private static final long MALUS_BOMBE = 25L;

    /** Segments d'un afficheur sept segments, bit 0 = haut, bit 6 = barre du milieu. */
    private static final int[] SEGMENTS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    private enum Type { TAUPE, DOREE, BOMBE }

    /** Une sortie en cours : le trou occupe, la nature du sortant et ou il en est de son cycle. */
    private static final class Sortie {
        private final int trou;
        private final Type type;
        private final double montee;
        private final double attente;
        private final double descente;
        private double t;
        private boolean touchee;
        private double emergenceFigee;
        private double retrait;

        private Sortie(int trou, Type type, double montee, double attente, double descente) {
            this.trou = trou;
            this.type = type;
            this.montee = montee;
            this.attente = attente;
            this.descente = descente;
        }

        private double duree() {
            return montee + attente + descente;
        }
    }

    /** Petit texte qui s'envole au-dessus d'un trou : le seul retour immediat sur un coup. */
    private static final class Flottant {
        private final String texte;
        private final double x;
        private final double y;
        private final int couleur;
        private double t;

        private Flottant(String texte, double x, double y, int couleur) {
            this.texte = texte;
            this.x = x;
            this.y = y;
            this.couleur = couleur;
        }
    }

    private final Random random = new Random();
    private final List<Sortie> sorties = new ArrayList<>();
    private final List<Flottant> flottants = new ArrayList<>();

    private double tempsRestant = DUREE;
    private double depart = DEPART;
    private double prochaine = 0.35;
    private double flashRouge;
    private long score;
    private int coups;
    private int meilleurCombo = 1;
    private boolean fini;

    private static double trouX(int trou) {
        return W * ((trou % COLS) + 0.5) / COLS;
    }

    private static double trouY(int trou) {
        return JEU_HAUT + (H - JEU_HAUT) * ((double) (trou / COLS) + 0.5) / ROWS;
    }

    /** Avancement de la partie, de 0 au depart a 1 a la derniere seconde. */
    private double progression() {
        double p = (DUREE - tempsRestant) / DUREE;
        return Math.max(0.0, Math.min(1.0, p));
    }

    private int multiplicateur() {
        return Math.min(COMBO_MAX, 1 + coups / 3);
    }

    /**
     * Part de la taupe sortie du trou, de 0 (invisible) a 1 (entierement dehors). Une taupe touchee
     * repart de sa hauteur du moment : sinon elle remonterait d'un coup avant de plonger.
     */
    private static double emergence(Sortie s) {
        if (s.touchee) {
            return s.emergenceFigee * Math.max(0.0, 1.0 - s.retrait / RETRAIT);
        }
        if (s.t < s.montee) {
            return s.t / s.montee;
        }
        double reste = s.t - s.montee;
        if (reste < s.attente) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (reste - s.attente) / s.descente);
    }

    @Override
    public void update(double dt) {
        if (fini) {
            return;
        }
        // Pas fixes : sur une image longue, une taupe pourrait sinon sortir et rentrer entierement
        // entre deux dessins, donc etre ratee sans jamais avoir ete visible.
        double reste = Math.min(Math.max(dt, 0.0), 0.25);
        while (reste > 0 && !fini) {
            double pas = Math.min(reste, 1.0 / 120.0);
            reste -= pas;
            if (depart > 0) {
                depart -= pas;
                continue;
            }
            avancer(pas);
        }
    }

    private void avancer(double pas) {
        tempsRestant -= pas;
        if (tempsRestant <= 0) {
            tempsRestant = 0;
            fini = true;
        }
        flashRouge = Math.max(0.0, flashRouge - pas * 3.0);

        Iterator<Sortie> it = sorties.iterator();
        while (it.hasNext()) {
            Sortie s = it.next();
            s.t += pas;
            if (s.touchee) {
                s.retrait += pas;
                if (s.retrait >= RETRAIT) {
                    it.remove();
                }
            } else if (s.t >= s.duree()) {
                it.remove();
                // Une bombe rentree sans etre touchee est un bon reflexe, pas une faute.
                if (s.type != Type.BOMBE) {
                    if (coups > 0) {
                        flottant("RATE", trouX(s.trou), trouY(s.trou) - 8, 0xFFE06060);
                    }
                    coups = 0;
                }
            }
        }

        Iterator<Flottant> itf = flottants.iterator();
        while (itf.hasNext()) {
            Flottant f = itf.next();
            f.t += pas;
            if (f.t >= VIE_FLOTTANT) {
                itf.remove();
            }
        }

        if (fini) {
            return;
        }
        prochaine -= pas;
        if (prochaine <= 0) {
            faireSortir();
        }
    }

    private void faireSortir() {
        double p = progression();
        int simultanees = Math.min(4, 1 + (int) (p * 4.0));
        if (sorties.size() >= simultanees) {
            prochaine = 0.1; // la place se liberera d'elle-meme, on repasse bientot
            return;
        }
        boolean[] occupe = new boolean[TROUS];
        for (Sortie s : sorties) {
            occupe[s.trou] = true;
        }
        List<Integer> libres = new ArrayList<>();
        for (int i = 0; i < TROUS; i++) {
            if (!occupe[i]) {
                libres.add(i);
            }
        }
        if (libres.isEmpty()) {
            prochaine = 0.1;
            return;
        }

        double chanceBombe = p < 0.12 ? 0.0 : 0.10 + 0.14 * p;
        double chanceDoree = 0.07 + 0.05 * p;
        double tirage = random.nextDouble();
        Type type = tirage < chanceBombe
                ? Type.BOMBE
                : (tirage < chanceBombe + chanceDoree ? Type.DOREE : Type.TAUPE);

        double montee = 0.20 - 0.08 * p;
        double attente = 1.30 - 0.95 * p;
        double descente = 0.26 - 0.12 * p;
        if (type == Type.DOREE) {
            attente *= 0.55; // elle vaut cher, elle ne se laisse pas regarder
        }
        int trou = libres.get(random.nextInt(libres.size()));
        sorties.add(new Sortie(trou, type, montee, attente, descente));

        double intervalle = 1.20 - 0.80 * p;
        prochaine = intervalle * (0.75 + random.nextDouble() * 0.5);
    }

    private void flottant(String texte, double x, double y, int couleur) {
        // Borne la liste : un joueur tres rapide ne doit pas accumuler du texte a l'infini.
        if (flottants.size() >= 16) {
            flottants.remove(0);
        }
        flottants.add(new Flottant(texte, x, y, couleur));
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        if (fini || depart > 0) {
            return; // pendant le compte a rebours, un clic ne casse aucun combo
        }
        // L'ecran hote transmet aussi les clics tombes a cote du cadre : ils ne comptent pas.
        if (fx < 0 || fx > 1 || fy < 0 || fy > 1) {
            return;
        }
        double lx = fx * W;
        double ly = fy * H;
        if (ly < BANDEAU) {
            return; // le bandeau du chrono n'est pas une zone de jeu
        }

        Sortie cible = null;
        double meilleure = 0;
        for (Sortie s : sorties) {
            if (s.touchee) {
                continue;
            }
            double e = emergence(s);
            // Trop enfoncee : la toucher serait injuste dans un sens comme dans l'autre.
            if (e < 0.20) {
                continue;
            }
            double cx = trouX(s.trou);
            double cy = trouY(s.trou);
            double centre = cy + CORPS_RY - e * MONTEE;
            boolean dedans = lx >= cx - CORPS_RX - 1.5 && lx <= cx + CORPS_RX + 1.5
                    && ly >= centre - CORPS_RY - 2.5 && ly <= cy + TROU_RY * 0.5;
            if (dedans && e > meilleure) {
                meilleure = e;
                cible = s;
            }
        }

        if (cible == null) {
            // Clic dans le vide : sans cette sanction, le martelage battrait toujours la visee.
            if (coups > 0) {
                flottant("COMBO PERDU", lx, ly, 0xFFB0B8C4);
            }
            coups = 0;
            return;
        }
        frapper(cible, meilleure);
    }

    private void frapper(Sortie cible, double emergence) {
        cible.touchee = true;
        cible.emergenceFigee = emergence;
        cible.retrait = 0;
        double x = trouX(cible.trou);
        double y = trouY(cible.trou) - 10;

        if (cible.type == Type.BOMBE) {
            score = Math.max(0L, score - MALUS_BOMBE);
            coups = 0;
            flashRouge = 1.0;
            flottant("-" + MALUS_BOMBE, x, y, 0xFFFF6060);
            return;
        }
        coups++;
        int mult = multiplicateur();
        meilleurCombo = Math.max(meilleurCombo, mult);
        long points = (cible.type == Type.DOREE ? POINTS_DOREE : POINTS_TAUPE) * mult;
        score += points;
        flottant("+" + points, x, y, cible.type == Type.DOREE ? 0xFFFFE080 : 0xFF9BE86B);
    }

    @Override
    public boolean keyPressed(int keyCode) {
        return false; // ce jeu se joue entierement a la souris
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double scale = Math.min(width / W, height / H);
        if (scale <= 0) {
            return;
        }
        int fieldW = (int) (W * scale);
        int fieldH = (int) (H * scale);
        int ox = x + (width - fieldW) / 2;
        int oy = y + (height - fieldH) / 2;

        g.fillGradient(ox, oy, ox + fieldW, oy + fieldH, 0xFF2E5628, 0xFF234321);

        for (int i = 0; i < TROUS; i++) {
            dessinerTrou(g, ox, oy, scale, i);
        }
        for (Sortie s : sorties) {
            dessinerSortie(g, ox, oy, scale, s);
        }
        for (int i = 0; i < TROUS; i++) {
            dessinerRebord(g, ox, oy, scale, i);
        }

        dessinerFlottants(g, ox, oy, scale);
        if (flashRouge > 0) {
            int alpha = (int) (90 * Math.min(1.0, flashRouge));
            g.fill(ox, oy, ox + fieldW, oy + fieldH, (alpha << 24) | 0x00D02020);
        }
        dessinerBandeau(g, ox, oy, fieldW, fieldH, scale);
        if (depart > 0) {
            dessinerDepart(g, ox, oy, fieldW, fieldH);
        }
        g.renderOutline(ox - 1, oy - 1, fieldW + 2, fieldH + 2, 0xFF3E5A34);
    }

    private void dessinerTrou(GuiGraphics g, int ox, int oy, double scale, int trou) {
        int cx = ox + (int) (trouX(trou) * scale);
        int cy = oy + (int) (trouY(trou) * scale);
        int rx = Math.max(2, (int) (TROU_RX * scale));
        int ry = Math.max(1, (int) (TROU_RY * scale));
        ellipse(g, cx, cy + Math.max(1, ry / 3), rx + 1, ry, 0xFF2E4A26);
        ellipse(g, cx, cy, rx, ry, 0xFF120A06);
    }

    /** Moitie avant du trou, redessinee par-dessus : la taupe sort de derriere le rebord. */
    private void dessinerRebord(GuiGraphics g, int ox, int oy, double scale, int trou) {
        int cx = ox + (int) (trouX(trou) * scale);
        int cy = oy + (int) (trouY(trou) * scale);
        int rx = Math.max(2, (int) (TROU_RX * scale));
        int ry = Math.max(1, (int) (TROU_RY * scale));
        ellipseBasse(g, cx, cy, rx, ry, cy, 0xFF160C07);
        ellipseBasse(g, cx, cy + Math.max(1, ry / 2), rx, ry, cy + ry, 0xFF35521B);
    }

    private void dessinerSortie(GuiGraphics g, int ox, int oy, double scale, Sortie s) {
        double e = emergence(s);
        if (e <= 0.01) {
            return;
        }
        int cx = ox + (int) (trouX(s.trou) * scale);
        int cyTrou = oy + (int) (trouY(s.trou) * scale);
        double centre = trouY(s.trou) + CORPS_RY - e * MONTEE;
        int cyc = oy + (int) (centre * scale);
        if (s.type == Type.BOMBE) {
            dessinerBombe(g, cx, cyc, cyTrou, scale);
        } else {
            dessinerTaupe(g, cx, cyc, cyTrou, scale, s.type == Type.DOREE, s.touchee);
        }
    }

    private void dessinerTaupe(GuiGraphics g, int cx, int cyc, int limite, double scale,
            boolean doree, boolean touchee) {
        int rx = Math.max(2, (int) (CORPS_RX * scale));
        int ry = Math.max(2, (int) (CORPS_RY * scale));
        int corps = doree ? 0xFFE0B12C : 0xFF7A4E2C;
        int ventre = doree ? 0xFFFFE58A : 0xFFC08C5C;
        int oreille = doree ? 0xFFA97D14 : 0xFF553318;

        int oy1 = cyc - (int) (ry * 0.72);
        int rOreille = Math.max(1, rx / 4);
        ellipseHaute(g, cx - (int) (rx * 0.62), oy1, rOreille, rOreille, limite, oreille);
        ellipseHaute(g, cx + (int) (rx * 0.62), oy1, rOreille, rOreille, limite, oreille);

        ellipseHaute(g, cx, cyc, rx, ry, limite, corps);
        ellipseHaute(g, cx, cyc + ry / 3, (int) (rx * 0.62), (int) (ry * 0.62), limite, ventre);

        int yeux = cyc - (int) (ry * 0.28);
        int taille = Math.max(1, rx / 6);
        if (yeux + taille <= limite) {
            int ecart = (int) (rx * 0.34);
            g.fill(cx - ecart - taille, yeux, cx - ecart + taille, yeux + taille * 2, 0xFF120C08);
            g.fill(cx + ecart - taille, yeux, cx + ecart + taille, yeux + taille * 2, 0xFF120C08);
        }
        int nez = cyc + (int) (ry * 0.04);
        if (nez + taille <= limite) {
            g.fill(cx - taille, nez, cx + taille, nez + taille, 0xFFE08A96);
        }
        if (doree) {
            // Petite couronne : la taupe doree doit se reconnaitre au premier coup d'oeil.
            int cyCour = cyc - ry - Math.max(1, ry / 6);
            int l = Math.max(1, rx / 5);
            if (cyCour + l <= limite) {
                g.fill(cx - (int) (rx * 0.5), cyCour, cx + (int) (rx * 0.5), cyCour + l, 0xFFFFF0A0);
                g.fill(cx - (int) (rx * 0.5), cyCour - l, cx - (int) (rx * 0.5) + l, cyCour, 0xFFFFF0A0);
                g.fill(cx - l / 2, cyCour - l, cx + l - l / 2, cyCour, 0xFFFFF0A0);
                g.fill(cx + (int) (rx * 0.5) - l, cyCour - l, cx + (int) (rx * 0.5), cyCour, 0xFFFFF0A0);
            }
        }
        if (touchee) {
            int etoile = cyc - ry;
            if (etoile + 2 <= limite) {
                g.fill(cx - rx / 2, etoile, cx + rx / 2, etoile + 2, 0xCCFFFFFF);
            }
        }
    }

    private void dessinerBombe(GuiGraphics g, int cx, int cyc, int limite, double scale) {
        int r = Math.max(2, (int) (CORPS_RX * 0.78 * scale));
        // Mine a pointes, noire cerclee de rouge : rien a voir avec la silhouette d'une taupe.
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(-170 + i * 35);
            int sx = cx + (int) (Math.cos(a) * r * 1.15);
            int sy = cyc + (int) (Math.sin(a) * r * 1.15);
            int demi = Math.max(1, r / 4);
            if (sy + demi <= limite) {
                g.fill(sx - demi, sy - demi, sx + demi, sy + demi, 0xFF4A4A58);
            }
        }
        ellipseHaute(g, cx, cyc, r, r, limite, 0xFFD03434);
        ellipseHaute(g, cx, cyc, Math.max(1, r - Math.max(1, r / 5)), Math.max(1, r - Math.max(1, r / 5)),
                limite, 0xFF15151E);

        int barre = Math.max(1, r / 4);
        if (cyc + barre <= limite) {
            g.fill(cx - (int) (r * 0.55), cyc - barre / 2, cx + (int) (r * 0.55),
                    cyc - barre / 2 + barre, 0xFFE85050);
            g.fill(cx - barre / 2, cyc - (int) (r * 0.55), cx - barre / 2 + barre,
                    cyc + (int) (r * 0.55), 0xFFE85050);
        }
        int meche = cyc - r - Math.max(1, r / 3);
        if (meche + 2 <= limite) {
            g.fill(cx - 1, meche, cx + 1, cyc - r + 1, 0xFF8A7040);
            g.fill(cx - 2, meche - 3, cx + 2, meche, 0xFFFFD060);
        }
    }

    private void dessinerFlottants(GuiGraphics g, int ox, int oy, double scale) {
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        for (Flottant f : flottants) {
            double part = Math.max(0.0, 1.0 - f.t / VIE_FLOTTANT);
            int alpha = Math.max(24, (int) (255 * part));
            int couleur = (alpha << 24) | (f.couleur & 0x00FFFFFF);
            int tx = ox + (int) (f.x * scale);
            int ty = oy + (int) ((f.y - (1.0 - part) * 8.0) * scale);
            g.drawCenteredString(font, f.texte, tx, ty, couleur);
        }
    }

    private void dessinerBandeau(GuiGraphics g, int ox, int oy, int fieldW, int fieldH, double scale) {
        int bas = oy + (int) (BANDEAU * scale);
        g.fill(ox, oy, ox + fieldW, bas, 0xE6183020);
        g.fill(ox, bas, ox + fieldW, bas + 1, 0xFF3E5A34);

        int secondes = (int) Math.ceil(tempsRestant - 1e-9);
        secondes = Math.max(0, Math.min(99, secondes));
        boolean urgence = tempsRestant <= 10.0;
        // Clignotement des cinq dernieres secondes : le joueur doit le sentir sans lire le chiffre.
        boolean eteint = tempsRestant <= 5.0 && ((int) (tempsRestant * 4) % 2 == 0);
        int teinte = eteint ? 0xFF7A2020 : (urgence ? 0xFFFF5A5A : 0xFFF2EBC4);

        int hDig = Math.max(6, (int) (13.0 * scale));
        int wDig = Math.max(4, (int) (8.0 * scale));
        int ep = Math.max(1, (int) (2.4 * scale));
        int ecart = Math.max(2, (int) (3.0 * scale));
        int dx = ox + fieldW / 2 - (wDig * 2 + ecart) / 2;
        int dy = oy + Math.max(2, (int) (4.5 * scale));
        chiffre(g, dx, dy, wDig, hDig, ep, secondes / 10, teinte);
        chiffre(g, dx + wDig + ecart, dy, wDig, hDig, ep, secondes % 10, teinte);

        // Jauge de temps : la barre qui fond dit plus vite que le chiffre combien il reste.
        int jaugeY = bas - Math.max(2, (int) (2.5 * scale));
        int plein = (int) (fieldW * (tempsRestant / DUREE));
        g.fill(ox, jaugeY, ox + fieldW, jaugeY + Math.max(2, (int) (1.6 * scale)), 0xFF1C2A1A);
        g.fill(ox, jaugeY, ox + plein, jaugeY + Math.max(2, (int) (1.6 * scale)),
                urgence ? 0xFFE05050 : 0xFF6FD06F);

        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        g.drawString(font, "TEMPS", ox + 4, oy + 4, 0xFF8FA88A, false);
        int mult = multiplicateur();
        String combo = "COMBO x" + mult;
        int couleurCombo = switch (mult) {
            case 1 -> 0xFF8FA88A;
            case 2 -> 0xFFB8E08A;
            case 3 -> 0xFFE0D060;
            case 4 -> 0xFFE0A040;
            default -> 0xFFFF8040;
        };
        g.drawString(font, combo, ox + fieldW - font.width(combo) - 4, oy + 4, couleurCombo, false);
        String serie = coups > 0 ? "SERIE " + coups : "SERIE 0";
        g.drawString(font, serie, ox + fieldW - font.width(serie) - 4, oy + 14, 0xFF6E8A6A, false);
        String record = "MEILLEUR x" + meilleurCombo;
        g.drawString(font, record, ox + 4, oy + 14, 0xFF6E8A6A, false);
    }

    private void dessinerDepart(GuiGraphics g, int ox, int oy, int fieldW, int fieldH) {
        g.fill(ox, oy, ox + fieldW, oy + fieldH, 0x99000000);
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        int cx = ox + fieldW / 2;
        int cy = oy + fieldH / 2;
        g.drawCenteredString(font, "PRET ?", cx, cy - 14, 0xFFFFE080);
        g.drawCenteredString(font, "Clique les taupes, laisse passer les bombes", cx, cy, 0xFFD0D8C8);
        int compte = Math.max(1, (int) Math.ceil(depart - 1e-9));
        g.drawCenteredString(font, String.valueOf(compte), cx, cy + 16, 0xFF9BE86B);
    }

    /** Chiffre sept segments : le chrono doit rester lisible du fond de la piece. */
    private static void chiffre(GuiGraphics g, int x, int y, int w, int h, int ep, int valeur,
            int couleur) {
        int v = Math.max(0, Math.min(9, valeur));
        int m = SEGMENTS[v];
        int my = y + h / 2 - ep / 2;
        if ((m & 1) != 0) {
            g.fill(x, y, x + w, y + ep, couleur);
        }
        if ((m & 2) != 0) {
            g.fill(x + w - ep, y, x + w, my + ep, couleur);
        }
        if ((m & 4) != 0) {
            g.fill(x + w - ep, my, x + w, y + h, couleur);
        }
        if ((m & 8) != 0) {
            g.fill(x, y + h - ep, x + w, y + h, couleur);
        }
        if ((m & 16) != 0) {
            g.fill(x, my, x + ep, y + h, couleur);
        }
        if ((m & 32) != 0) {
            g.fill(x, y, x + ep, my + ep, couleur);
        }
        if ((m & 64) != 0) {
            g.fill(x, my, x + w, my + ep, couleur);
        }
    }

    /** Ellipse pleine, approchee par bandes horizontales : GuiGraphics ne fait que des rectangles. */
    private static void ellipse(GuiGraphics g, int cx, int cy, int rx, int ry, int couleur) {
        ellipseHaute(g, cx, cy, rx, ry, Integer.MAX_VALUE, couleur);
    }

    /** Ellipse tronquee sous {@code limite} : c'est ce qui fait sortir la taupe du trou. */
    private static void ellipseHaute(GuiGraphics g, int cx, int cy, int rx, int ry, int limite,
            int couleur) {
        if (rx < 1 || ry < 1) {
            return;
        }
        for (int dy = -ry; dy <= ry; dy++) {
            int ligne = cy + dy;
            if (ligne >= limite) {
                break;
            }
            double f = 1.0 - (double) (dy * dy) / ((double) ry * ry);
            if (f <= 0) {
                continue;
            }
            int demi = (int) (rx * Math.sqrt(f));
            if (demi < 1) {
                continue;
            }
            g.fill(cx - demi, ligne, cx + demi, ligne + 1, couleur);
        }
    }

    /** Moitie basse d'une ellipse, a partir de {@code haut} : sert au rebord avant du trou. */
    private static void ellipseBasse(GuiGraphics g, int cx, int cy, int rx, int ry, int haut,
            int couleur) {
        if (rx < 1 || ry < 1) {
            return;
        }
        for (int dy = -ry; dy <= ry; dy++) {
            int ligne = cy + dy;
            if (ligne < haut) {
                continue;
            }
            double f = 1.0 - (double) (dy * dy) / ((double) ry * ry);
            if (f <= 0) {
                continue;
            }
            int demi = (int) (rx * Math.sqrt(f));
            if (demi < 1) {
                continue;
            }
            g.fill(cx - demi, ligne, cx + demi, ligne + 1, couleur);
        }
    }

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
        return "Clique les taupes - evite les bombes";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
