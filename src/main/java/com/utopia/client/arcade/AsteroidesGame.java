package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Asteroides. Un vaisseau derive dans un espace sans bord, casse les rochers en morceaux de plus en
 * plus petits, et n'a que trois vies pour tenir des vagues qui grossissent.
 *
 * <p>Tout le jeu tient dans l'<b>inertie</b> : la poussee s'ajoute a la vitesse au lieu de la
 * remplacer, si bien qu'on ne pilote pas le vaisseau, on le rattrape. Un frottement tres leger
 * (voir {@link #FROTTEMENT}) est le seul garde-fou : sans lui le vaisseau finit par traverser
 * l'ecran plus vite que le joueur ne peut reagir, avec lui trop fort le jeu devient une voiture.
 *
 * <p>L'espace est <b>enroule</b> : vaisseau, rochers et tirs qui sortent d'un bord rentrent par
 * l'autre. Les distances sont donc toujours mesurees en tenant compte du repli
 * ({@link #ecart}), sinon deux objets separes par un bord se croiseraient sans se toucher.
 *
 * <p>Le vaisseau ne reapparait qu'au centre degage et reste invulnerable et clignotant quelques
 * secondes : renaitre dans un rocher serait une mort en chaine que le joueur n'a pas commise, et la
 * partie est payante.
 */
public final class AsteroidesGame implements ArcadeGame {

    /** Terrain en unites logiques ; converti en pixels au seul moment du dessin. */
    private static final double W = 100.0;
    private static final double H = 75.0;

    /** Rayon de collision du vaisseau, un peu plus petit que sa pointe : on pardonne les frolements. */
    private static final double RAYON_VAISSEAU = 1.9;
    private static final double POUSSEE = 28.0;
    /** Duree d'une poussee declenchee par une frappe : couvre le trou avant la repetition clavier. */
    private static final double POUSSEE_TENUE = 0.55;
    /** Freinage par seconde, volontairement faible : c'est la derive qui fait le jeu. */
    private static final double FROTTEMENT = 0.42;
    private static final double VITESSE_MAX = 46.0;

    private static final double ROT_IMPULSION = 2.6;
    private static final double ROT_MAX = 4.4;
    /** Amortissement de la rotation : une tape tourne d'un cran, une touche tenue fait un tour. */
    private static final double ROT_AMORTI = 5.0;

    private static final double TIR_VITESSE = 62.0;
    /** Portee limitee : sans extinction, l'ecran se remplit et plus rien n'est lisible. */
    private static final double TIR_DUREE = 1.15;
    private static final int TIR_MAX = 5;
    private static final double TIR_DELAI = 0.2;

    /** Rayons par taille : 0 = petit, 1 = moyen, 2 = gros. */
    private static final double[] RAYONS = {2.4, 4.3, 7.2};
    /** Les petits vont plus vite et valent plus cher : ce sont eux qui tuent. */
    private static final double[] VITESSES = {14.0, 10.0, 6.5};
    private static final long[] POINTS = {100, 50, 20};

    private static final int[] TEINTES = {0xFF949AAA, 0xFF7C8190, 0xFF6A6F7E};

    private static final double INVULN_DEPART = 2.5;
    private static final double INVULN_RENAISSANCE = 3.0;
    /** Rayon a degager autour du centre avant de faire renaitre le vaisseau. */
    private static final double ZONE_SURE = 17.0;

    private final Random alea = new Random();
    private final List<Roche> roches = new ArrayList<>();
    private final List<Tir> tirs = new ArrayList<>();
    private final List<Eclat> eclats = new ArrayList<>();
    /** Fond etoile fige a la construction : un fond redessine au hasard chaque image scintille mal. */
    private final double[] etoiles = new double[90];

    private double vx;
    private double vy;
    private double px = W / 2;
    private double py = H / 2;
    private double angle = -Math.PI / 2;
    private double rotation;
    private double pousseeRestante;
    private double delaiTir;

    private int vies = 3;
    private int vague;
    private long score;
    private long prochaineVie = 3000;

    private boolean vivant = true;
    private double invulnerabilite = INVULN_DEPART;
    private double attenteRenaissance;
    private double attenteVague;
    /** Petit sursis avant le premier mouvement : le temps de poser les doigts sur les touches. */
    private double delaiDepart = 1.2;
    private double delaiFin = -1;
    private boolean fini;
    private double horloge;

    private int coupeX0;
    private int coupeY0;
    private int coupeX1;
    private int coupeY1;

    public AsteroidesGame() {
        for (int i = 0; i < etoiles.length; i += 3) {
            etoiles[i] = alea.nextDouble() * W;
            etoiles[i + 1] = alea.nextDouble() * H;
            etoiles[i + 2] = alea.nextDouble();
        }
        vagueSuivante();
    }

    private static final class Roche {
        double x;
        double y;
        double vx;
        double vy;
        double angle;
        double rotation;
        int taille;
    }

    private static final class Tir {
        double x;
        double y;
        double vx;
        double vy;
        double reste;
    }

    private static final class Eclat {
        double x;
        double y;
        double vx;
        double vy;
        double reste;
        double duree;
        int couleur;
    }

    // ------------------------------------------------------------------ espace enroule

    private static double enroule(double v, double taille) {
        double r = v % taille;
        return r < 0 ? r + taille : r;
    }

    /** Ecart le plus court entre deux abscisses (ou ordonnees) d'un espace qui se replie sur lui-meme. */
    private static double ecart(double a, double b, double taille) {
        double d = (a - b) % taille;
        if (d > taille / 2) {
            d -= taille;
        } else if (d < -taille / 2) {
            d += taille;
        }
        return d;
    }

    private static double distance2(double ax, double ay, double bx, double by) {
        double dx = ecart(ax, bx, W);
        double dy = ecart(ay, by, H);
        return dx * dx + dy * dy;
    }

    // ------------------------------------------------------------------ vagues et casse

    private void vagueSuivante() {
        vague++;
        // La vague grossit mais plafonne : au dela de neuf gros rochers l'ecran devient un mur.
        int nombre = Math.min(3 + vague, 9);
        double facteur = Math.min(1.0 + (vague - 1) * 0.07, 1.6);
        for (int i = 0; i < nombre; i++) {
            Roche r = new Roche();
            r.taille = 2;
            // On s'ecarte du VAISSEAU et non du centre du terrain : des la deuxieme vague il n'y
            // est plus, et un gros rocher pose exactement sur lui serait une vie volee sans qu'il
            // ait rien pu faire. Le repli suit la meme regle que le tirage.
            r.x = ((px + W / 2.0) % W + W) % W;
            r.y = ((py + H / 2.0) % H + H) % H;
            for (int essai = 0; essai < 40; essai++) {
                double cx = alea.nextDouble() * W;
                double cy = alea.nextDouble() * H;
                if (distance2(cx, cy, px, py) > 27 * 27) {
                    r.x = cx;
                    r.y = cy;
                    break;
                }
            }
            double dir = alea.nextDouble() * Math.PI * 2;
            double v = VITESSES[2] * facteur * (0.6 + alea.nextDouble() * 0.7);
            r.vx = Math.cos(dir) * v;
            r.vy = Math.sin(dir) * v;
            r.rotation = (alea.nextDouble() - 0.5) * 2.2;
            roches.add(r);
        }
    }

    private void casse(Roche mere, List<Roche> sortie) {
        eclatsRoche(mere);
        if (mere.taille == 0) {
            return;
        }
        int taille = mere.taille - 1;
        double base = Math.atan2(mere.vy, mere.vx);
        for (int i = 0; i < 2; i++) {
            Roche r = new Roche();
            r.taille = taille;
            r.x = mere.x;
            r.y = mere.y;
            // Les deux morceaux repartent en V autour de la trajectoire de la mere, plus vite
            // qu'elle : un gros casse doit disperser, pas laisser deux rochers colles.
            double ecartAngle = (i == 0 ? 1 : -1) * (0.6 + alea.nextDouble() * 0.6);
            double dir = base + ecartAngle;
            double v = VITESSES[taille] * (0.7 + alea.nextDouble() * 0.7);
            r.vx = Math.cos(dir) * v;
            r.vy = Math.sin(dir) * v;
            r.rotation = (alea.nextDouble() - 0.5) * 3.0;
            sortie.add(r);
        }
    }

    private void eclatsRoche(Roche r) {
        for (int i = 0; i < 5; i++) {
            double dir = alea.nextDouble() * Math.PI * 2;
            double v = 8 + alea.nextDouble() * 14;
            ajouteEclat(r.x, r.y, Math.cos(dir) * v, Math.sin(dir) * v, 0.45, 0xFFD8DEEA);
        }
    }

    private void ajouteEclat(double x, double y, double vx, double vy, double duree, int couleur) {
        if (eclats.size() >= 140) {
            return; // plafond de securite : aucune liste ne doit pouvoir enfler sans fin
        }
        Eclat e = new Eclat();
        e.x = x;
        e.y = y;
        e.vx = vx;
        e.vy = vy;
        e.reste = duree;
        e.duree = duree;
        e.couleur = couleur;
        eclats.add(e);
    }

    // ------------------------------------------------------------------ boucle

    @Override
    public void update(double dt) {
        if (fini) {
            return;
        }
        // Pas fixes : a 10 images par seconde, un rocher deplace d'un bloc passerait au travers du
        // vaisseau sans jamais le toucher.
        double reste = Math.min(dt, 0.25);
        while (reste > 0 && !fini) {
            double pas = Math.min(reste, 1.0 / 120.0);
            reste -= pas;
            avance(pas);
        }
    }

    private void avance(double dt) {
        horloge += dt;
        if (delaiDepart > 0) {
            delaiDepart -= dt;
            return;
        }

        if (delaiFin >= 0) {
            delaiFin -= dt;
            if (delaiFin <= 0) {
                fini = true;
            }
        }

        rotation -= rotation * Math.min(1.0, ROT_AMORTI * dt);
        angle += rotation * dt;

        if (vivant) {
            if (pousseeRestante > 0) {
                pousseeRestante -= dt;
                vx += Math.cos(angle) * POUSSEE * dt;
                vy += Math.sin(angle) * POUSSEE * dt;
            }
            vx -= vx * Math.min(1.0, FROTTEMENT * dt);
            vy -= vy * Math.min(1.0, FROTTEMENT * dt);
            double v = Math.sqrt(vx * vx + vy * vy);
            if (v > VITESSE_MAX) {
                vx = vx / v * VITESSE_MAX;
                vy = vy / v * VITESSE_MAX;
            }
            px = enroule(px + vx * dt, W);
            py = enroule(py + vy * dt, H);
        }

        delaiTir = Math.max(0, delaiTir - dt);
        deplaceTirs(dt);
        deplaceRoches(dt);
        deplaceEclats(dt);
        collisionsTirs();
        collisionVaisseau();
        gereVague(dt);
        gereRenaissance(dt);
    }

    private void deplaceTirs(double dt) {
        for (int i = tirs.size() - 1; i >= 0; i--) {
            Tir t = tirs.get(i);
            t.reste -= dt;
            if (t.reste <= 0) {
                tirs.remove(i);
                continue;
            }
            t.x = enroule(t.x + t.vx * dt, W);
            t.y = enroule(t.y + t.vy * dt, H);
        }
    }

    private void deplaceRoches(double dt) {
        for (Roche r : roches) {
            r.x = enroule(r.x + r.vx * dt, W);
            r.y = enroule(r.y + r.vy * dt, H);
            r.angle += r.rotation * dt;
        }
    }

    private void deplaceEclats(double dt) {
        for (int i = eclats.size() - 1; i >= 0; i--) {
            Eclat e = eclats.get(i);
            e.reste -= dt;
            if (e.reste <= 0) {
                eclats.remove(i);
                continue;
            }
            e.x = enroule(e.x + e.vx * dt, W);
            e.y = enroule(e.y + e.vy * dt, H);
        }
    }

    private void collisionsTirs() {
        List<Roche> nouvelles = null;
        for (int i = tirs.size() - 1; i >= 0; i--) {
            Tir t = tirs.get(i);
            for (int j = roches.size() - 1; j >= 0; j--) {
                Roche r = roches.get(j);
                double portee = RAYONS[r.taille] + 0.7;
                if (distance2(t.x, t.y, r.x, r.y) > portee * portee) {
                    continue;
                }
                tirs.remove(i);
                roches.remove(j);
                score += POINTS[r.taille];
                if (nouvelles == null) {
                    nouvelles = new ArrayList<>(2);
                }
                casse(r, nouvelles);
                break; // un tir ne casse qu'un rocher
            }
        }
        if (nouvelles != null) {
            roches.addAll(nouvelles);
        }
        // Le palier avance meme quand les vies sont au plafond, sinon perdre une vie en rendrait
        // aussitot une autre gratuitement.
        while (score >= prochaineVie) {
            prochaineVie += 3000;
            if (vies < 5) {
                vies++;
            }
        }
    }

    private void collisionVaisseau() {
        if (!vivant) {
            return;
        }
        boolean touche = false;
        for (Roche r : roches) {
            double portee = RAYONS[r.taille] + RAYON_VAISSEAU;
            if (distance2(px, py, r.x, r.y) <= portee * portee) {
                touche = true;
                break;
            }
        }
        if (!touche) {
            return;
        }
        if (invulnerabilite > 0) {
            // Sortir d'invulnerabilite colle a un rocher serait une mort immediate et incomprise :
            // on prolonge le sursis tant que le vaisseau est couvert.
            invulnerabilite = Math.max(invulnerabilite, 0.35);
            return;
        }
        perdVie();
    }

    private void perdVie() {
        vivant = false;
        vx = 0;
        vy = 0;
        rotation = 0;
        pousseeRestante = 0;
        for (int i = 0; i < 14; i++) {
            double dir = alea.nextDouble() * Math.PI * 2;
            double v = 6 + alea.nextDouble() * 20;
            ajouteEclat(px, py, Math.cos(dir) * v, Math.sin(dir) * v, 0.9, 0xFFFFC46B);
        }
        vies--;
        if (vies <= 0) {
            delaiFin = 0.9; // le temps de voir l'explosion avant l'ecran de fin
        } else {
            attenteRenaissance = 1.3;
        }
    }

    private void gereVague(double dt) {
        if (!roches.isEmpty()) {
            return;
        }
        if (attenteVague <= 0) {
            attenteVague = 1.4;
            score += 150L * vague; // prime de vague : le score doit monter meme sans casse facile
            return;
        }
        attenteVague -= dt;
        if (attenteVague <= 0) {
            attenteVague = 0;
            vagueSuivante();
        }
    }

    private void gereRenaissance(double dt) {
        if (invulnerabilite > 0) {
            invulnerabilite = Math.max(0, invulnerabilite - dt);
        }
        if (vivant || vies <= 0) {
            return;
        }
        attenteRenaissance -= dt;
        if (attenteRenaissance > 0) {
            return;
        }
        // On attend que le centre soit degage, mais pas indefiniment : passe quatre secondes on
        // renait quand meme, l'invulnerabilite couvre le risque.
        boolean degage = true;
        for (Roche r : roches) {
            double portee = ZONE_SURE + RAYONS[r.taille];
            if (distance2(W / 2, H / 2, r.x, r.y) <= portee * portee) {
                degage = false;
                break;
            }
        }
        if (!degage && attenteRenaissance > -4.0) {
            return;
        }
        vivant = true;
        px = W / 2;
        py = H / 2;
        vx = 0;
        vy = 0;
        angle = -Math.PI / 2;
        rotation = 0;
        attenteRenaissance = 0;
        invulnerabilite = INVULN_RENAISSANCE;
    }

    // ------------------------------------------------------------------ commandes

    @Override
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 263, 65, 81 -> tourne(-1);          // gauche, A, Q
            case 262, 68 -> tourne(1);               // droite, D
            case 265, 87, 90 -> pousse();            // haut, W, Z
            case 32, 257 -> tire();                  // espace, entree
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * La rotation est une vitesse amortie, pas un cap : sans evenement de relachement, c'est le seul
     * moyen qu'une tape tourne d'un cran et qu'une touche tenue fasse tourner sans a-coups.
     */
    private void tourne(int sens) {
        if (delaiDepart > 0) {
            delaiDepart = 0;
        }
        rotation = Math.max(-ROT_MAX, Math.min(ROT_MAX, rotation + sens * ROT_IMPULSION));
    }

    private void pousse() {
        if (delaiDepart > 0) {
            delaiDepart = 0;
        }
        if (vivant) {
            pousseeRestante = POUSSEE_TENUE;
        }
    }

    private void tire() {
        if (delaiDepart > 0) {
            delaiDepart = 0;
            return; // la premiere frappe reveille la partie, elle ne gaspille pas un tir
        }
        if (!vivant || delaiTir > 0 || tirs.size() >= TIR_MAX) {
            return;
        }
        delaiTir = TIR_DELAI;
        Tir t = new Tir();
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        t.x = enroule(px + cos * 3.6, W);
        t.y = enroule(py + sin * 3.6, H);
        // Vitesse propre et non relative au vaisseau : un tir lache en marche arriere doit quand
        // meme partir devant.
        t.vx = cos * TIR_VITESSE;
        t.vy = sin * TIR_VITESSE;
        t.reste = TIR_DUREE;
        tirs.add(t);
    }

    // ------------------------------------------------------------------ dessin

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double sc = Math.min(width / W, height / H);
        int cadreW = (int) (W * sc);
        int cadreH = (int) (H * sc);
        int ox = x + (width - cadreW) / 2;
        int oy = y + (height - cadreH) / 2;
        coupeX0 = ox;
        coupeY0 = oy;
        coupeX1 = ox + cadreW;
        coupeY1 = oy + cadreH;

        g.fillGradient(coupeX0, coupeY0, coupeX1, coupeY1, 0xFF1C2350, 0xFF090C22);
        for (int i = 0; i < etoiles.length; i += 3) {
            int sx = ox + (int) (etoiles[i] * sc);
            int sy = oy + (int) (etoiles[i + 1] * sc);
            int teinte = etoiles[i + 2] > 0.7 ? 0xFF8894AE : 0xFF5A6590;
            rect(g, sx, sy, sx + 1, sy + 1, teinte);
        }

        for (Roche r : roches) {
            double rayon = RAYONS[r.taille];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    double cx = r.x + dx * W;
                    double cy = r.y + dy * H;
                    if (cx + rayon * 1.3 < 0 || cx - rayon * 1.3 > W) {
                        continue;
                    }
                    if (cy + rayon * 1.3 < 0 || cy - rayon * 1.3 > H) {
                        continue;
                    }
                    roche(g, ox + cx * sc, oy + cy * sc, rayon * sc, r.angle, TEINTES[r.taille]);
                }
            }
        }

        for (Tir t : tirs) {
            int sx = ox + (int) (t.x * sc);
            int sy = oy + (int) (t.y * sc);
            int e = Math.max(1, (int) (0.5 * sc));
            rect(g, sx - e, sy - e, sx + e, sy + e, 0xFFFFE9A8);
        }

        for (Eclat e : eclats) {
            int sx = ox + (int) (e.x * sc);
            int sy = oy + (int) (e.y * sc);
            int taille = e.reste / e.duree > 0.5 ? Math.max(1, (int) (0.5 * sc)) : 1;
            rect(g, sx, sy, sx + taille, sy + taille, e.couleur);
        }

        if (vivant && (invulnerabilite <= 0 || ((int) (horloge * 9)) % 2 == 0)) {
            int couleur = invulnerabilite > 0 ? 0xFF7FB8FF : 0xFFE8F0FF;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    double cx = px + dx * W;
                    double cy = py + dy * H;
                    if (cx < -6 || cx > W + 6 || cy < -6 || cy > H + 6) {
                        continue;
                    }
                    vaisseau(g, ox + cx * sc, oy + cy * sc, sc, couleur);
                }
            }
        }

        interfaceJeu(g, ox, oy, cadreW, cadreH);
        g.renderOutline(coupeX0 - 1, coupeY0 - 1, cadreW + 2, cadreH + 2, 0xFF47528C);
    }

    private void interfaceJeu(GuiGraphics g, int ox, int oy, int cadreW, int cadreH) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        for (int i = 0; i < vies; i++) {
            int vx0 = ox + 6 + i * 10;
            int vy0 = oy + 6;
            triangle(g, new double[] {vx0 + 3, vx0, vx0 + 6},
                    new double[] {vy0, vy0 + 8, vy0 + 8}, 0xFFAFC6E8);
        }
        String texteVague = "VAGUE " + vague;
        g.drawString(font, texteVague, ox + cadreW - 6 - font.width(texteVague), oy + 6,
                0xFF7E8AA6, false);

        int centreX = ox + cadreW / 2;
        int centreY = oy + cadreH / 2;
        if (delaiDepart > 0) {
            g.drawCenteredString(font, "PRET ?", centreX, centreY - 26, 0xFFFFD966);
            g.drawCenteredString(font, "Une touche pour partir", centreX, centreY + 16, 0xFF8A93A3);
        } else if (!vivant && vies > 0) {
            g.drawCenteredString(font, "VAISSEAU DETRUIT", centreX, centreY - 4, 0xFFFF8A6B);
        } else if (attenteVague > 0) {
            g.drawCenteredString(font, "VAGUE " + (vague + 1), centreX, centreY - 4, 0xFF9BE86B);
        }
    }

    /**
     * Le vaisseau est un triangle plein, creuse a l'arriere par un second triangle de la couleur du
     * fond : dessine autrement il ressemblerait a un carre et on ne verrait plus ou il pointe.
     */
    private void vaisseau(GuiGraphics g, double cx, double cy, double sc, int couleur) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double[] lx = {3.9, -2.5, -2.5};
        double[] ly = {0.0, -2.4, 2.4};
        double[] xs = new double[3];
        double[] ys = new double[3];
        for (int i = 0; i < 3; i++) {
            xs[i] = cx + (lx[i] * cos - ly[i] * sin) * sc;
            ys[i] = cy + (lx[i] * sin + ly[i] * cos) * sc;
        }
        triangle(g, xs, ys, couleur);

        double[] cxl = {-2.5, -2.5, -0.7};
        double[] cyl = {-2.4, 2.4, 0.0};
        for (int i = 0; i < 3; i++) {
            xs[i] = cx + (cxl[i] * cos - cyl[i] * sin) * sc;
            ys[i] = cy + (cxl[i] * sin + cyl[i] * cos) * sc;
        }
        triangle(g, xs, ys, 0xFF131840);

        if (pousseeRestante > 0 && ((int) (horloge * 24)) % 2 == 0) {
            double longueur = 2.0 + (horloge * 37 % 1.0) * 1.6;
            double[] fx = {-2.7, -2.7, -2.7 - longueur};
            double[] fy = {-1.3, 1.3, 0.0};
            for (int i = 0; i < 3; i++) {
                xs[i] = cx + (fx[i] * cos - fy[i] * sin) * sc;
                ys[i] = cy + (fx[i] * sin + fy[i] * cos) * sc;
            }
            triangle(g, xs, ys, 0xFFFFA23A);
        }
    }

    /**
     * Rocher : un disque empile en bandes horizontales, dont le centre et la largeur ondulent selon
     * l'angle propre du rocher. C'est ce qui le fait tourner alors qu'on ne sait dessiner que des
     * rectangles.
     */
    private void roche(GuiGraphics g, double cx, double cy, double r, double ang, int couleur) {
        if (r < 1) {
            rect(g, (int) cx, (int) cy, (int) cx + 2, (int) cy + 2, couleur);
            return;
        }
        int haut = Math.max(coupeY0, (int) Math.floor(cy - r * 1.2));
        int bas = Math.min(coupeY1, (int) Math.ceil(cy + r * 1.2));
        int clair = eclaircit(couleur);
        for (int py0 = haut; py0 < bas; py0++) {
            double dy = (py0 + 0.5 - cy) / r;
            if (dy <= -1 || dy >= 1) {
                continue;
            }
            double demi = Math.sqrt(1 - dy * dy) * r * (1.0 + 0.15 * Math.sin(ang * 2 + dy * 4.5));
            double decal = 0.14 * r * Math.sin(ang * 3 + dy * 3.1);
            int x0 = (int) Math.round(cx + decal - demi);
            int x1 = (int) Math.round(cx + decal + demi);
            if (x1 <= x0) {
                x1 = x0 + 1;
            }
            rect(g, x0, py0, x1, py0 + 1, couleur);
            rect(g, x0, py0, x0 + 1, py0 + 1, clair);
            rect(g, x1 - 1, py0, x1, py0 + 1, clair);
        }
    }

    private static int eclaircit(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 60);
        int v = Math.min(255, ((argb >> 8) & 0xFF) + 60);
        int b = Math.min(255, (argb & 0xFF) + 60);
        return 0xFF000000 | (r << 16) | (v << 8) | b;
    }

    /** Remplissage d'un triangle par balayage de lignes, borne au cadre de jeu. */
    private void triangle(GuiGraphics g, double[] xs, double[] ys, int couleur) {
        double minY = Math.min(ys[0], Math.min(ys[1], ys[2]));
        double maxY = Math.max(ys[0], Math.max(ys[1], ys[2]));
        int y0 = Math.max(coupeY0, (int) Math.floor(minY));
        int y1 = Math.min(coupeY1, (int) Math.ceil(maxY));
        for (int py0 = y0; py0 < y1; py0++) {
            double ligne = py0 + 0.5;
            double gauche = Double.MAX_VALUE;
            double droite = -Double.MAX_VALUE;
            for (int e = 0; e < 3; e++) {
                int f = (e + 1) % 3;
                double ay = ys[e];
                double by = ys[f];
                // Le test strict d'un cote garantit ay != by : pas de division par zero possible.
                if (!((ligne >= ay && ligne < by) || (ligne >= by && ligne < ay))) {
                    continue;
                }
                double t = (ligne - ay) / (by - ay);
                double xi = xs[e] + (xs[f] - xs[e]) * t;
                gauche = Math.min(gauche, xi);
                droite = Math.max(droite, xi);
            }
            if (droite < gauche) {
                continue;
            }
            int x0 = (int) Math.round(gauche);
            int x1 = (int) Math.round(droite);
            if (x1 <= x0) {
                x1 = x0 + 1; // une pointe plus fine qu'un pixel doit quand meme se voir
            }
            rect(g, x0, py0, x1, py0 + 1, couleur);
        }
    }

    /** Tout passe par ici : un objet enroule deborde du cadre et salirait le score au-dessus. */
    private void rect(GuiGraphics g, int x0, int y0, int x1, int y1, int couleur) {
        int a = Math.max(coupeX0, x0);
        int b = Math.max(coupeY0, y0);
        int c = Math.min(coupeX1, x1);
        int d = Math.min(coupeY1, y1);
        if (c > a && d > b) {
            g.fill(a, b, c, d, couleur);
        }
    }

    // ------------------------------------------------------------------ etat

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
        return "Fleches pour tourner et pousser - Espace pour tirer";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
