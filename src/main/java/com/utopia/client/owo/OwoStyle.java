package com.utopia.client.owo;

import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Surface;

/** Palette/surfaces partagees des menus owo (panneau, en-tete, boutons), dessinees en code. */
public final class OwoStyle {

    private OwoStyle() {
    }

    /** Fond du panneau (sombre, legerement translucide) + bordure. */
    public static final Surface PANEL = Surface.flat(0xF0141622).and(Surface.outline(0xFF3B4268));
    /** En-tete accentue (bleu) + bordure. */
    public static final Surface HEADER = Surface.flat(0xFF26305C).and(Surface.outline(0xFF4A5AA0));
    /** Bouton au repos. */
    public static final Surface BTN = Surface.flat(0xFF1B2030).and(Surface.outline(0xFF3D466B));
    /** Bouton survole. */
    public static final Surface BTN_HOVER = Surface.flat(0xFF323C63).and(Surface.outline(0xFF5C6AB0));
    /** Carte d'information (non cliquable). */
    public static final Surface INFO = Surface.flat(0xFF11141F).and(Surface.outline(0xFF2C3354));
    /** Couleur de la barre de defilement. */
    public static final Color SCROLLBAR = Color.ofArgb(0xFF4A5AA0);
}
