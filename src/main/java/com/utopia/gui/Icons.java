package com.utopia.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ItemLike;

/** Fabrique d'icones (ItemStacks d'affichage) pour les GUI : nom et description sans italique. */
public final class Icons {

    private Icons() {
    }

    /** Construit un libelle de couleur, italique desactivee (les noms d'items sont italiques par defaut). */
    public static Component label(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }

    /** Une ligne de description (lore). */
    public static Component lore(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }

    public static ItemStack icon(ItemLike item, int count, Component name, List<Component> loreLines) {
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (loreLines != null && !loreLines.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(loreLines)));
        }
        return stack;
    }

    public static ItemStack icon(ItemLike item, Component name, List<Component> loreLines) {
        return icon(item, 1, name, loreLines);
    }

    public static ItemStack icon(ItemLike item, Component name) {
        return icon(item, 1, name, List.of());
    }

    /** Panneau de remplissage (vitre grise) avec un nom vide. */
    public static ItemStack filler() {
        return icon(Items.GRAY_STAINED_GLASS_PANE, label(" ", ChatFormatting.GRAY));
    }

    /** Tete de joueur affichant le skin de la cible. */
    public static ItemStack playerHead(ServerPlayer player, Component name, List<Component> loreLines) {
        ItemStack stack = icon(Items.PLAYER_HEAD, 1, name, loreLines);
        stack.set(DataComponents.PROFILE, new ResolvableProfile(player.getGameProfile()));
        return stack;
    }
}
