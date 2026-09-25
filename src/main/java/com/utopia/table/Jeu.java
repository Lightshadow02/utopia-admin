package com.utopia.table;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Les jeux qu'une table peut accueillir. */
public enum Jeu {

    BLACKJACK("Blackjack", Items.PAPER, ChatFormatting.GREEN, 7,
            "Battre le croupier sans depasser 21. Chacun son tour."),
    HOLDEM("Casino Hold'em", Items.BOOK, ChatFormatting.GOLD, 5,
            "Ta main contre celle du croupier. Chacun joue quand il veut."),
    ROULETTE("Roulette", Items.CLOCK, ChatFormatting.RED, 8,
            "Tirage toutes les minutes. Mise ce que tu veux, ou tu veux.");

    public final String label;
    public final Item icone;
    public final ChatFormatting couleur;
    /** Places au maximum : au-dela, une table devient illisible et le tour de chacun trop long. */
    public final int placesMax;
    public final String detail;

    Jeu(String label, Item icone, ChatFormatting couleur, int placesMax, String detail) {
        this.label = label;
        this.icone = icone;
        this.couleur = couleur;
        this.placesMax = placesMax;
        this.detail = detail;
    }

    public static Jeu parNom(String nom) {
        for (Jeu j : values()) {
            if (j.name().equals(nom)) {
                return j;
            }
        }
        return null;
    }
}
