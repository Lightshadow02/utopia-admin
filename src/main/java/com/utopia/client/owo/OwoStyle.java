package com.utopia.client.owo;

import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Surface;

/** Palette/surfaces partagees des menus owo (panneau, en-tete, boutons), dessinees en code avec coins arrondis. */
public final class OwoStyle {

    private OwoStyle() {
    }

    /** Fond du panneau (sombre, legerement translucide) + bordure, coins arrondis. */
    public static final Surface PANEL = rounded(0xF0141622, 0xFF3B4268, 4);
    /** En-tete accentue (bleu) + bordure. */
    public static final Surface HEADER = rounded(0xFF26305C, 0xFF4A5AA0, 3);
    /** Bouton au repos. */
    public static final Surface BTN = rounded(0xFF1B2030, 0xFF3D466B, 3);
    /** Bouton survole. */
    public static final Surface BTN_HOVER = rounded(0xFF323C63, 0xFF5C6AB0, 3);
    /** Carte d'information (non cliquable). */
    public static final Surface INFO = rounded(0xFF11141F, 0xFF2C3354, 3);
    /** Couleur de la barre de defilement. */
    public static final Color SCROLLBAR = Color.ofArgb(0xFF4A5AA0);

    /** Surface a coins arrondis : un fond {@code fill} borde de {@code border}, rayon {@code radius} px. */
    public static Surface rounded(int fill, int border, int radius) {
        return (context, component) -> {
            int x = component.x();
            int y = component.y();
            int w = component.width();
            int h = component.height();
            roundedRect(context, x, y, w, h, radius, border);
            roundedRect(context, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
        };
    }

    /** Remplit un rectangle a coins arrondis (approximation par quart de cercle). */
    private static void roundedRect(OwoUIDrawContext ctx, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            ctx.fill(x, y, x + w, y + h, argb);
            return;
        }
        // Corps central + bandes laterales.
        ctx.fill(x + r, y, x + w - r, y + h, argb);
        ctx.fill(x, y + r, x + r, y + h - r, argb);
        ctx.fill(x + w - r, y + r, x + w, y + h - r, argb);
        // Coins (quart de cercle, ligne par ligne).
        for (int dy = 0; dy < r; dy++) {
            double dyc = r - dy - 0.5;
            double half = Math.sqrt(Math.max(0.0, (double) r * r - dyc * dyc));
            int cut = r - (int) Math.round(half);
            int top = y + dy;
            int bottom = y + h - 1 - dy;
            ctx.fill(x + cut, top, x + r, top + 1, argb);                 // haut-gauche
            ctx.fill(x + w - r, top, x + w - cut, top + 1, argb);         // haut-droit
            ctx.fill(x + cut, bottom, x + r, bottom + 1, argb);           // bas-gauche
            ctx.fill(x + w - r, bottom, x + w - cut, bottom + 1, argb);   // bas-droit
        }
    }
}
