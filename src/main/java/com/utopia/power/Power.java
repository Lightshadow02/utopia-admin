package com.utopia.power;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Les capacites speciales que /dieux peut accorder. Une capacite se donne a une personne et non a
 * un objet : l'objet qui la porte peut etre perdu ou vole, le droit non.
 */
public enum Power {

    BATON_DE_MAGE("Baton de mage", Items.STICK, ChatFormatting.LIGHT_PURPLE,
            "Lance des sorts qui frappent les creatures hostiles alentour");

    public final String label;
    public final Item icone;
    public final ChatFormatting couleur;
    public final String detail;

    Power(String label, Item icone, ChatFormatting couleur, String detail) {
        this.label = label;
        this.icone = icone;
        this.couleur = couleur;
        this.detail = detail;
    }

    public static Power parNom(String nom) {
        for (Power p : values()) {
            if (p.name().equals(nom)) {
                return p;
            }
        }
        return null;
    }
}
