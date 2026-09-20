package com.utopia.client.arcade;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Les Envahisseurs. Cinq rangees de huit descendent en zigzag, un vaisseau les fauche par en bas.
 *
 * <p>Toute la tension du jeu tient dans une seule regle : le bloc n'avance pas en continu, il fait
 * un pas a intervalle fixe, et cet intervalle est proportionnel au nombre de survivants. Quarante
 * envahisseurs rampent, les deux derniers foncent - vider une rangee rend donc la fin de vague plus
 * dure, et un joueur qui traine finit toujours par etre rattrape.
 *
 * <p>Seul l'envahisseur du bas de chaque colonne tire : les autres arroseraient leurs camarades, et
 * surtout leurs tirs tomberaient de trop haut pour etre esquives. Les quatre boucliers sont une
 * grille de blocs pleins que les tirs des deux camps rongent case par case ; ils s'usent aussi vite
 * qu'on s'abrite derriere, et le bloc finit par les devorer en descendant.
 *
 * <p>Le terrain vit en coordonnees flottantes de 0 a 100 sur 80 et n'est converti en pixels qu'au
 * dessin : cale sur la grille de l'ecran, un tir changerait de portee selon la taille de la fenetre.
 */
public final class EnvahisseursGame implements ArcadeGame {

    private static final double W = 100.0;
    private static final double H = 80.0;
    /** Marge laissee aux bords : le bloc rebondit dessus, il ne doit pas lecher le cadre. */
    private static final double MARGE = 1.5;

    private static final int RANGS = 5;
    private static final int COLONNES = 8;
    private static final int TOTAL = RANGS * COLONNES;
    private static final double INV_L = 5.0;
    private static final double INV_H = 3.6;
    private static final double ESPACE_X = 9.0;
    private static final double ESPACE_Y = 6.4;
    private static final double PAS_X = 1.6;
    private static final double DESCENTE = 3.2;
    /** Intervalle entre deux pas du bloc, du plus lent (40 vivants) au plus rapide (1 vivant). */
    private static final double PAS_LENT = 0.62;
    private static final double PAS_RAPIDE = 0.05;

    private static final double VAISSEAU_L = 7.0;
    private static final double VAISSEAU_H = 3.4;
    private static final double VAISSEAU_Y = 73.0;
    private static final double VAISSEAU_V = 50.0;
    /** Un appui clavier pousse la cible de ce petit bond ; la repetition du clavier fait le reste. */
    private static final double BOND_CLAVIER = 4.5;
    private static final double CADENCE = 0.32;

    private static final double TIR_L = 0.9;
    private static final double TIR_HAUT = 2.6;
    private static final double TIR_JOUEUR_V = 78.0;
    private static final double TIR_ENNEMI_V = 34.0;
    private static final int MAX_TIRS_ENNEMIS = 5;

    private static final int NB_BOUCLIERS = 4;
    private static final int B_COLONNES = 8;
    private static final int B_RANGS = 4;
    private static final double B_L = 9.0;
    private static final double B_H = 4.0;
    private static final double B_Y = 61.0;
    private static final double CELL_L = B_L / B_COLONNES;
    private static final double CELL_H = B_H / B_RANGS;

    /** Rangees du haut = plus de points : elles sont les plus difficiles a degager proprement. */
    private static final int[] POINTS = {50, 40, 30, 20, 10};
    private static final int[] COULEURS = {
            0xFFE86BD2, 0xFF7FB8F0, 0xFF7FE08A, 0xFFE8D06B, 0xFFE8785B};

    /** Deux images alternees a chaque pas du bloc : les pattes bougent, le bloc parait vivant. */
    private static final String[][] MOTIF = {
            {"..XXXX..", ".XXXXXX.", "XX.XX.XX", "XXXXXXXX", ".X.XX.X."},
            {"..XXXX..", ".XXXXXX.", "XX.XX.XX", "XXXXXXXX", "X.X..X.X"}};
    private static final String[] MOTIF_VAISSEAU = {"...X...", "..XXX..", "XXXXXXX", "XXXXXXX"};

    private final Random random = new Random();
    private final boolean[][] vivants = new boolean[RANGS][COLONNES];
    private final boolean[][][] boucliers = new boolean[NB_BOUCLIERS][B_RANGS][B_COLONNES];
    private final double[] boucliersX = new double[NB_BOUCLIERS];
    private final List<Tir> tirsEnnemis = new ArrayList<>();
    private final int[] colonnesArmees = new int[COLONNES];

    private double vaisseauX = W / 2;
    private double cibleX = W / 2;
    private double blocX;
    private double blocY;
    private int sens = 1;
    private int restants;
    private int vague = 1;
    private int vies = 3;
    private long score;
    private boolean fini;
    private int image;

    private double accumPas;
    private double delaiFeu = 1.0;
    private double cadenceRestante;
    /** Sursis avant que la vague ne se mette en marche : la partie est payante, on ne perd pas en lisant. */
    private double delaiDepart = 1.6;
    /** Sursis apres une vie perdue, le temps de se replacer sans se reprendre le tir suivant. */
    private double delaiReprise;

    private boolean tirActif;
    private double tirX;
    private double tirY;

    public EnvahisseursGame() {
        for (int b = 0; b < NB_BOUCLIERS; b++) {
            boucliersX[b] = b * (W / NB_BOUCLIERS) + (W / NB_BOUCLIERS - B_L) / 2;
        }
        monterBoucliers();
        monterVague();
    }

    private static final class Tir {
        private final double x;
        /** Bord d'attaque du tir : le bas pour un tir ennemi, le haut pour un tir du joueur. */
        private double y;

        private Tir(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private void monterVague() {
        for (int r = 0; r < RANGS; r++) {
            for (int c = 0; c < COLONNES; c++) {
                vivants[r][c] = true;
            }
        }
        restants = TOTAL;
        sens = 1;
        accumPas = 0;
        blocX = (W - ((COLONNES - 1) * ESPACE_X + INV_L)) / 2;
        // Chaque vague demarre plus bas, mais le plafond garantit que la premiere descente ne
        // touche jamais les boucliers d'entree de jeu.
        blocY = Math.min(6.0 + (vague - 1) * 2.2, 16.0);
    }

    private void monterBoucliers() {
        for (int b = 0; b < NB_BOUCLIERS; b++) {
            for (int r = 0; r < B_RANGS; r++) {
                for (int c = 0; c < B_COLONNES; c++) {
                    // Coins ronges et porche creuse dessous : la forme classique, qui laisse le
                    // joueur tirer de sous l'abri sans le percer lui-meme du premier coup.
                    boolean coin = r == 0 && (c == 0 || c == B_COLONNES - 1);
                    boolean porche = r == B_RANGS - 1 && (c == 3 || c == 4);
                    boucliers[b][r][c] = !coin && !porche;
                }
            }
        }
    }

    private double invX(int colonne) {
        return blocX + colonne * ESPACE_X;
    }

    private double invY(int rang) {
        return blocY + rang * ESPACE_Y;
    }

    private double intervallePas() {
        int n = Math.max(1, restants);
        double part = (n - 1) / (double) (TOTAL - 1);
        double base = PAS_RAPIDE + (PAS_LENT - PAS_RAPIDE) * part;
        // Le plancher de 0.02 s protege la boucle a pas fixes : un intervalle nul la ferait tourner
        // sans fin et figerait l'ecran du joueur.
        return Math.max(0.02, base * Math.max(0.5, Math.pow(0.87, vague - 1)));
    }

    private double vitesseTirEnnemi() {
        return Math.min(55.0, TIR_ENNEMI_V + (vague - 1) * 3.0);
    }

    @Override
    public void update(double dt) {
        if (fini) {
            return;
        }
        // Pas fixes : a 78 unites par seconde, un tir deplace d'un bloc sur une image longue
        // traverserait un envahisseur entier sans jamais le toucher.
        double reste = Math.min(dt, 0.25);
        while (reste > 0) {
            double pas = Math.min(reste, 1.0 / 240.0);
            reste -= pas;
            avancer(pas);
            if (fini) {
                return;
            }
        }
    }

    private void avancer(double pas) {
        deplacerVaisseau(pas);
        if (cadenceRestante > 0) {
            cadenceRestante -= pas;
        }
        deplacerTirJoueur(pas);
        if (fini) {
            return;
        }
        if (delaiDepart > 0) {
            delaiDepart -= pas;
            return;
        }
        if (delaiReprise > 0) {
            delaiReprise -= pas;
            return;
        }
        avancerBloc(pas);
        if (fini) {
            return;
        }
        armerTirEnnemi(pas);
        deplacerTirsEnnemis(pas);
    }

    private void deplacerVaisseau(double pas) {
        double ecart = cibleX - vaisseauX;
        double course = VAISSEAU_V * pas;
        if (Math.abs(ecart) <= course) {
            vaisseauX = cibleX;
        } else {
            vaisseauX += Math.signum(ecart) * course;
        }
        vaisseauX = borner(vaisseauX);
    }

    private static double borner(double x) {
        return Math.max(VAISSEAU_L / 2, Math.min(W - VAISSEAU_L / 2, x));
    }

    private void avancerBloc(double pas) {
        accumPas += pas;
        double intervalle = intervallePas();
        while (accumPas >= intervalle) {
            accumPas -= intervalle;
            pasBloc();
            if (fini) {
                return;
            }
            intervalle = intervallePas();
        }
    }

    private void pasBloc() {
        int colGauche = COLONNES;
        int colDroite = -1;
        for (int c = 0; c < COLONNES; c++) {
            for (int r = 0; r < RANGS; r++) {
                if (vivants[r][c]) {
                    colGauche = Math.min(colGauche, c);
                    colDroite = Math.max(colDroite, c);
                    break;
                }
            }
        }
        if (colDroite < 0) {
            return;
        }
        image = 1 - image;
        double suivant = blocX + sens * PAS_X;
        // Les bords se mesurent sur les colonnes encore vivantes : une aile decimee laisse le bloc
        // glisser plus loin, et la vague finissante balaie tout le terrain.
        double gauche = suivant + colGauche * ESPACE_X;
        double droite = suivant + colDroite * ESPACE_X + INV_L;
        if (gauche < MARGE || droite > W - MARGE) {
            sens = -sens;
            blocY += DESCENTE;
            devorerBoucliers();
            verifierAtterrissage();
        } else {
            blocX = suivant;
        }
    }

    private void verifierAtterrissage() {
        for (int r = RANGS - 1; r >= 0; r--) {
            double bas = invY(r) + INV_H;
            if (bas < VAISSEAU_Y) {
                return; // les rangs sont ordonnes : au-dessus, aucune ne peut avoir touche
            }
            for (int c = 0; c < COLONNES; c++) {
                if (vivants[r][c]) {
                    fini = true;
                    return;
                }
            }
        }
    }

    private void devorerBoucliers() {
        for (int r = 0; r < RANGS; r++) {
            double y0 = invY(r);
            double y1 = y0 + INV_H;
            if (y1 < B_Y || y0 > B_Y + B_H) {
                continue;
            }
            for (int c = 0; c < COLONNES; c++) {
                if (!vivants[r][c]) {
                    continue;
                }
                double x0 = invX(c);
                double x1 = x0 + INV_L;
                for (int b = 0; b < NB_BOUCLIERS; b++) {
                    double ox = boucliersX[b];
                    if (x1 < ox || x0 > ox + B_L) {
                        continue;
                    }
                    for (int br = 0; br < B_RANGS; br++) {
                        double cy = B_Y + br * CELL_H;
                        if (cy + CELL_H < y0 || cy > y1) {
                            continue;
                        }
                        for (int bc = 0; bc < B_COLONNES; bc++) {
                            double cx = ox + bc * CELL_L;
                            if (cx + CELL_L >= x0 && cx <= x1) {
                                boucliers[b][br][bc] = false;
                            }
                        }
                    }
                }
            }
        }
    }

    private void armerTirEnnemi(double pas) {
        delaiFeu -= pas;
        if (delaiFeu > 0) {
            return;
        }
        // Tir clairseme et irregulier : un metronome se contourne, une pluie aleatoire s'esquive.
        delaiFeu = (0.5 + random.nextDouble() * 1.2) * Math.max(0.4, 1.0 - (vague - 1) * 0.09);
        if (tirsEnnemis.size() >= MAX_TIRS_ENNEMIS) {
            return;
        }
        int n = 0;
        for (int c = 0; c < COLONNES; c++) {
            for (int r = RANGS - 1; r >= 0; r--) {
                if (vivants[r][c]) {
                    colonnesArmees[n++] = c;
                    break;
                }
            }
        }
        if (n == 0) {
            return;
        }
        int colonne = colonnesArmees[random.nextInt(n)];
        for (int r = RANGS - 1; r >= 0; r--) {
            if (vivants[r][colonne]) {
                tirsEnnemis.add(new Tir(invX(colonne) + INV_L / 2, invY(r) + INV_H));
                return;
            }
        }
    }

    private void deplacerTirsEnnemis(double pas) {
        double v = vitesseTirEnnemi();
        for (int i = tirsEnnemis.size() - 1; i >= 0; i--) {
            Tir t = tirsEnnemis.get(i);
            t.y += v * pas;
            if (t.y > H) {
                tirsEnnemis.remove(i);
                continue;
            }
            if (rongerBouclier(t.x, t.y)) {
                tirsEnnemis.remove(i);
                continue;
            }
            if (t.y >= VAISSEAU_Y && t.y - TIR_HAUT <= VAISSEAU_Y + VAISSEAU_H
                    && t.x >= vaisseauX - VAISSEAU_L / 2 && t.x <= vaisseauX + VAISSEAU_L / 2) {
                tirsEnnemis.remove(i);
                perdreVie();
                return; // perdreVie vide la liste : continuer a la parcourir n'aurait plus de sens
            }
        }
    }

    private void perdreVie() {
        vies--;
        if (vies <= 0) {
            vies = 0;
            fini = true;
            return;
        }
        tirsEnnemis.clear();
        tirActif = false;
        vaisseauX = W / 2;
        cibleX = W / 2;
        delaiReprise = 1.1;
    }

    private void deplacerTirJoueur(double pas) {
        if (!tirActif) {
            return;
        }
        tirY -= TIR_JOUEUR_V * pas;
        if (tirY <= 0) {
            tirActif = false;
            return;
        }
        if (rongerBouclier(tirX, tirY)) {
            tirActif = false;
            return;
        }
        if (tirY > invY(RANGS - 1) + INV_H || tirY < blocY) {
            return;
        }
        for (int r = RANGS - 1; r >= 0; r--) {
            double y0 = invY(r);
            if (tirY < y0 || tirY > y0 + INV_H) {
                continue;
            }
            for (int c = 0; c < COLONNES; c++) {
                if (!vivants[r][c]) {
                    continue;
                }
                double x0 = invX(c);
                if (tirX >= x0 && tirX <= x0 + INV_L) {
                    vivants[r][c] = false;
                    restants--;
                    tirActif = false;
                    score += (long) POINTS[r] * vague;
                    if (restants <= 0) {
                        vagueSuivante();
                    }
                    return;
                }
            }
        }
    }

    private void vagueSuivante() {
        vague++;
        score += 250L * vague;
        tirsEnnemis.clear();
        tirActif = false;
        delaiFeu = 1.0;
        delaiReprise = 0;
        delaiDepart = 1.6;
        monterVague();
        // Boucliers refaits a neuf : la vague arrive deja plus bas et plus vite, lui laisser en
        // plus des abris en ruine transformerait la vague 3 en execution.
        monterBoucliers();
    }

    private boolean rongerBouclier(double bx, double by) {
        if (by < B_Y || by >= B_Y + B_H) {
            return false;
        }
        for (int b = 0; b < NB_BOUCLIERS; b++) {
            double ox = boucliersX[b];
            if (bx < ox || bx >= ox + B_L) {
                continue;
            }
            int c = (int) ((bx - ox) / CELL_L);
            int r = (int) ((by - B_Y) / CELL_H);
            c = Math.max(0, Math.min(B_COLONNES - 1, c));
            r = Math.max(0, Math.min(B_RANGS - 1, r));
            if (!boucliers[b][r][c]) {
                return false; // la case est deja rongee : le tir file par le trou
            }
            boucliers[b][r][c] = false;
            // L'impact emiette le voisinage : sans cela un abri se percerait d'un couloir d'une
            // case de large, invisible a l'oeil mais totalement transparent aux tirs.
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr;
                    int cc = c + dc;
                    if (rr < 0 || rr >= B_RANGS || cc < 0 || cc >= B_COLONNES) {
                        continue;
                    }
                    if (random.nextInt(100) < 40) {
                        boucliers[b][rr][cc] = false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void tirer() {
        if (tirActif || cadenceRestante > 0 || fini) {
            return;
        }
        tirActif = true;
        cadenceRestante = CADENCE;
        tirX = vaisseauX;
        tirY = VAISSEAU_Y - 0.5;
    }

    @Override
    public boolean keyPressed(int keyCode) {
        // Un appui ne deplace pas le vaisseau, il pousse sa cible d'un bond ; le vaisseau la rejoint
        // a vitesse bornee. La repetition du clavier enchaine les bonds en glissade continue, et des
        // que le doigt se leve le vaisseau s'arrete en un dixieme de seconde au lieu de deriver.
        switch (keyCode) {
            // La cible est reancree sur le vaisseau a chaque pression : Minecraft repete la touche
            // maintenue bien plus vite que le vaisseau ne se deplace, et sans cet ancrage la cible
            // filait jusqu'au bord, ou le vaisseau glissait ensuite tout seul sans pouvoir s'arreter.
            case 263, 65, 81 -> cibleX = borner(vaisseauX - BOND_CLAVIER);
            case 262, 68 -> cibleX = borner(vaisseauX + BOND_CLAVIER);
            case 32, 257 -> tirer();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mouseMoved(double fx, double fy) {
        double x = borner(fx * W);
        vaisseauX = x;
        cibleX = x;
    }

    @Override
    public void mouseClicked(double fx, double fy, int button) {
        tirer();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int width, int height) {
        double echelle = Math.min(width / W, height / H);
        int cadreL = (int) (W * echelle);
        int cadreH = (int) (H * echelle);
        int ox = x + (width - cadreL) / 2;
        int oy = y + (height - cadreH) / 2;

        g.fillGradient(ox, oy, ox + cadreL, oy + cadreH, 0xFF241C50, 0xFF1A143B);
        // Ligne de sol : elle materialise la hauteur fatale, celle ou la vague gagne.
        int solY = oy + (int) ((VAISSEAU_Y + VAISSEAU_H + 1.0) * echelle);
        g.fill(ox, solY, ox + cadreL, solY + 1, 0xFF2E7F4E);

        dessinerBoucliers(g, ox, oy, echelle);
        dessinerEnvahisseurs(g, ox, oy, echelle);
        dessinerVaisseau(g, ox, oy, echelle);
        dessinerTirs(g, ox, oy, echelle);
        dessinerEtat(g, ox, oy, cadreL, cadreH, echelle);

        g.renderOutline(ox - 1, oy - 1, cadreL + 2, cadreH + 2, 0xFF4A6A94);
    }

    private void dessinerEnvahisseurs(GuiGraphics g, int ox, int oy, double echelle) {
        String[] motif = MOTIF[image];
        double pxL = INV_L * echelle / 8.0;
        double pxH = INV_H * echelle / motif.length;
        for (int r = 0; r < RANGS; r++) {
            double gy = invY(r);
            for (int c = 0; c < COLONNES; c++) {
                if (!vivants[r][c]) {
                    continue;
                }
                double gx = invX(c);
                double bx = ox + gx * echelle;
                double by = oy + gy * echelle;
                for (int mr = 0; mr < motif.length; mr++) {
                    String ligne = motif[mr];
                    for (int mc = 0; mc < ligne.length(); mc++) {
                        if (ligne.charAt(mc) != 'X') {
                            continue;
                        }
                        int x0 = (int) (bx + mc * pxL);
                        int y0 = (int) (by + mr * pxH);
                        int x1 = Math.max(x0 + 1, (int) (bx + (mc + 1) * pxL));
                        int y1 = Math.max(y0 + 1, (int) (by + (mr + 1) * pxH));
                        g.fill(x0, y0, x1, y1, COULEURS[r]);
                    }
                }
            }
        }
    }

    private void dessinerVaisseau(GuiGraphics g, int ox, int oy, double echelle) {
        // Clignotement pendant la reprise : le joueur voit d'un coup d'oeil qu'il est encore a l'abri.
        if (delaiReprise > 0 && ((int) (delaiReprise * 10)) % 2 == 0) {
            return;
        }
        double pxL = VAISSEAU_L * echelle / 7.0;
        double pxH = VAISSEAU_H * echelle / MOTIF_VAISSEAU.length;
        double bx = ox + (vaisseauX - VAISSEAU_L / 2) * echelle;
        double by = oy + VAISSEAU_Y * echelle;
        for (int mr = 0; mr < MOTIF_VAISSEAU.length; mr++) {
            String ligne = MOTIF_VAISSEAU[mr];
            for (int mc = 0; mc < ligne.length(); mc++) {
                if (ligne.charAt(mc) != 'X') {
                    continue;
                }
                int x0 = (int) (bx + mc * pxL);
                int y0 = (int) (by + mr * pxH);
                int x1 = Math.max(x0 + 1, (int) (bx + (mc + 1) * pxL));
                int y1 = Math.max(y0 + 1, (int) (by + (mr + 1) * pxH));
                g.fill(x0, y0, x1, y1, mr == 0 ? 0xFFFFE9A8 : 0xFF7FE0C0);
            }
        }
    }

    private void dessinerBoucliers(GuiGraphics g, int ox, int oy, double echelle) {
        for (int b = 0; b < NB_BOUCLIERS; b++) {
            double base = boucliersX[b];
            for (int r = 0; r < B_RANGS; r++) {
                for (int c = 0; c < B_COLONNES; c++) {
                    if (!boucliers[b][r][c]) {
                        continue;
                    }
                    double cx = base + c * CELL_L;
                    double cy = B_Y + r * CELL_H;
                    int x0 = (int) (ox + cx * echelle);
                    int y0 = (int) (oy + cy * echelle);
                    int x1 = Math.max(x0 + 1, (int) (ox + (cx + CELL_L) * echelle));
                    int y1 = Math.max(y0 + 1, (int) (oy + (cy + CELL_H) * echelle));
                    g.fill(x0, y0, x1, y1, 0xFF3FCB74);
                }
            }
        }
    }

    private void dessinerTirs(GuiGraphics g, int ox, int oy, double echelle) {
        int demi = Math.max(1, (int) (TIR_L * echelle / 2));
        if (tirActif) {
            int cx = (int) (ox + tirX * echelle);
            int y0 = (int) (oy + tirY * echelle);
            int y1 = Math.max(y0 + 2, (int) (oy + (tirY + TIR_HAUT) * echelle));
            g.fill(cx - demi, y0, cx + demi, y1, 0xFFFFF3B0);
        }
        for (Tir t : tirsEnnemis) {
            int cx = (int) (ox + t.x * echelle);
            int y1 = (int) (oy + t.y * echelle);
            int y0 = Math.min(y1 - 2, (int) (oy + (t.y - TIR_HAUT) * echelle));
            g.fill(cx - demi, y0, cx + demi, y1, 0xFFFF6B5B);
        }
    }

    private void dessinerEtat(GuiGraphics g, int ox, int oy, int cadreL, int cadreH, double echelle) {
        for (int i = 0; i < vies; i++) {
            int lx = ox + 4 + i * 12;
            int ly = oy + cadreH - 7;
            g.fill(lx + 3, ly, lx + 6, ly + 2, 0xFF7FE0C0);
            g.fill(lx, ly + 2, lx + 9, ly + 4, 0xFF7FE0C0);
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc == null ? null : mc.font;
        if (font == null) {
            return;
        }
        String vagueTexte = "VAGUE " + vague;
        g.drawString(font, vagueTexte, ox + cadreL - font.width(vagueTexte) - 4,
                oy + cadreH - 10, 0xFF8A93A3, false);
        if (delaiDepart > 0 && !fini) {
            int cx = ox + cadreL / 2;
            int cy = oy + (int) (VAISSEAU_Y * echelle) - 28;
            g.drawCenteredString(font, vagueTexte, cx, cy, 0xFFFFD966);
            g.drawCenteredString(font, "PRET", cx, cy + 12, 0xFF7FE0C0);
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
        return "Fleches pour bouger - Espace pour tirer";
    }

    @Override
    public double aspect() {
        return W / H;
    }
}
