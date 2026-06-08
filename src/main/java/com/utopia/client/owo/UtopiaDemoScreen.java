package com.utopia.client.owo;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * POC owo-ui : maquette du menu "Parcelle" entierement dessinee par owo-ui (panneaux,
 * alignements, boutons natifs). Sert a juger le rendu en jeu avant de convertir les vrais menus.
 * Ouvrable cote client via la commande {@code /utopiaui}. Donnees factices pour la demo.
 */
public class UtopiaDemoScreen extends BaseOwoScreen<FlowLayout> {

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        // Panneau principal centre.
        FlowLayout panel = Containers.verticalFlow(Sizing.fixed(230), Sizing.content());
        panel.surface(Surface.DARK_PANEL);
        panel.padding(Insets.of(14));
        panel.gap(8);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        panel.child(Components.label(Component.literal("§l§bParcelle #12")));
        panel.child(Components.label(Component.literal("§7Proprietaire : §fMairie")));
        panel.child(Components.label(Component.literal("§7Prix : §e1 250 Utopieces")));

        // Ligne d'icones de droits.
        FlowLayout flags = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        flags.gap(6);
        flags.child(Components.item(new ItemStack(Items.PLAYER_HEAD)));
        flags.child(Components.item(new ItemStack(Items.OAK_DOOR)));
        flags.child(Components.item(new ItemStack(Items.CHEST)));
        flags.child(Components.item(new ItemStack(Items.REDSTONE)));
        flags.child(Components.item(new ItemStack(Items.FLINT_AND_STEEL)));
        panel.child(flags);

        // Boutons d'action. ButtonComponent enveloppe un widget vanilla : owo lui ajoute
        // l'interface Component via mixin au runtime, d'ou le cast explicite au compile.
        FlowLayout buttons = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        buttons.gap(8);
        buttons.child((io.wispforest.owo.ui.core.Component) Components.button(Component.literal("Acheter"), b -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§a[owo demo] Bouton \"Acheter\" clique"), false);
            }
        }));
        buttons.child((io.wispforest.owo.ui.core.Component) Components.button(
                Component.literal("Fermer"), b -> this.onClose()));
        panel.child(buttons);

        root.child(panel);
    }
}
