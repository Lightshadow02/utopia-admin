package com.utopia.client.arcade;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Un jeu de borne d'arcade. Tout se passe chez le client : le serveur a encaisse la partie et
 * n'entendra plus parler d'elle avant le score final.
 *
 * <p>Le temps est passe en secondes reelles et non en ticks : une balle de casse-briques a 20 pas
 * par seconde avance par saccades, et la vitesse d'un serpent ne doit pas dependre de la charge du
 * serveur.
 */
public interface ArcadeGame {

    /** Avance la partie de {@code dt} secondes. */
    void update(double dt);

    /** Dessine le terrain dans le cadre donne, bords compris. */
    void render(GuiGraphics graphics, int x, int y, int width, int height);

    /** Vrai si la touche a ete consommee par le jeu. */
    boolean keyPressed(int keyCode);

    /** Position de la souris, ramenee au cadre de jeu (utile aux jeux qui visent). */
    default void mouseMoved(double fx, double fy) {
    }

    /** Clic dans le cadre de jeu, en coordonnees relatives (0..1). */
    default void mouseClicked(double fx, double fy, int button) {
    }

    long score();

    boolean over();

    /** Rappel des touches, affiche sous le terrain. */
    String hint();

    /** Proportions du terrain (largeur / hauteur), pour le cadrer sans le deformer. */
    double aspect();
}
