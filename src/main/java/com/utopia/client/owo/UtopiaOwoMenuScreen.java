package com.utopia.client.owo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenMenuPayload;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.ItemComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.GridLayout;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ecran owo-ui generique en style "panneau" : titre en pilule, puis les icones du menu rendues
 * comme des boutons stylés (largeur uniforme, survol). Les icones non cliquables (infos) affichent
 * leur lore en ligne (ex: proprietaire). Les vitres grises de remplissage sont masquees.
 */
public class UtopiaOwoMenuScreen extends BaseOwoScreen<FlowLayout> {

    private static final int COLS = 9;

    private final OpenMenuPayload data;
    private boolean closeSent = false;

    public UtopiaOwoMenuScreen(OpenMenuPayload data) {
        super(data.title());
        this.data = data;
    }

    public int sessionId() {
        return data.sessionId();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout panel = Containers.verticalFlow(Sizing.content(), Sizing.content());
        panel.surface(Surface.DARK_PANEL);
        panel.padding(Insets.of(10));
        panel.gap(6);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        // Titre en "pilule".
        FlowLayout titleBar = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        titleBar.surface(Surface.PANEL_INSET);
        titleBar.padding(Insets.of(6));
        titleBar.horizontalAlignment(HorizontalAlignment.CENTER);
        titleBar.child(Components.label(data.title()).shadow(true));
        panel.child(titleBar);

        Set<Integer> clickable = new HashSet<>(data.clickable());
        List<ItemStack> items = data.items();
        int rows = Math.max(1, Math.min(6, data.rows()));

        if (data.grid()) {
            buildGrid(panel, items, clickable, rows);
        } else {
            buildRows(panel, items, clickable, rows);
        }

        root.child(panel);
    }

    /** Mode lignes centrees : chaque rangee non vide -> une ligne de boutons (largeur uniforme). */
    private void buildRows(FlowLayout panel, List<ItemStack> items, Set<Integer> clickable, int rows) {
        Font font = Minecraft.getInstance().font;
        int maxText = 0;
        for (int i = 0; i < items.size(); i++) {
            if (clickable.contains(i) && !isHidden(items.get(i))) {
                maxText = Math.max(maxText, font.width(items.get(i).getHoverName()));
            }
        }
        int buttonWidth = maxText + 16 + 6 + 12; // texte + icone + gap + paddings

        for (int r = 0; r < rows; r++) {
            List<ItemStack> rowItems = new ArrayList<>();
            List<Integer> rowSlots = new ArrayList<>();
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c;
                ItemStack stack = i < items.size() ? items.get(i) : ItemStack.EMPTY;
                if (isHidden(stack)) {
                    continue;
                }
                rowItems.add(stack);
                rowSlots.add(i);
            }
            if (rowItems.isEmpty()) {
                continue;
            }
            FlowLayout rowFlow = Containers.horizontalFlow(Sizing.content(), Sizing.content());
            rowFlow.gap(5);
            rowFlow.horizontalAlignment(HorizontalAlignment.CENTER);
            rowFlow.verticalAlignment(VerticalAlignment.CENTER);
            for (int k = 0; k < rowItems.size(); k++) {
                int slot = rowSlots.get(k);
                rowFlow.child(chip(rowItems.get(k), slot, clickable.contains(slot), buttonWidth));
            }
            panel.child(rowFlow);
        }
    }

    /** Mode grille : respecte les positions (ligne, colonne) ; les cases vides s'effacent. */
    private void buildGrid(FlowLayout panel, List<ItemStack> items, Set<Integer> clickable, int rows) {
        GridLayout grid = Containers.grid(Sizing.content(), Sizing.content(), rows, COLS);
        grid.horizontalAlignment(HorizontalAlignment.CENTER);
        grid.verticalAlignment(VerticalAlignment.CENTER);
        for (int i = 0; i < rows * COLS; i++) {
            ItemStack stack = i < items.size() ? items.get(i) : ItemStack.EMPTY;
            if (isHidden(stack)) {
                continue;
            }
            grid.child(chip(stack, i, clickable.contains(i), 0), i / COLS, i % COLS);
        }
        panel.child(grid);
    }

    private static boolean isHidden(ItemStack stack) {
        return stack.isEmpty() || stack.is(Items.GRAY_STAINED_GLASS_PANE);
    }

    /** Bouton cliquable (largeur uniforme) ou carte d'info (lore affiche en ligne). */
    private FlowLayout chip(ItemStack stack, int slot, boolean isClickable, int buttonWidth) {
        ItemComponent icon = Components.item(stack);
        icon.setTooltipFromStack(true);

        if (isClickable) {
            FlowLayout chip = Containers.horizontalFlow(
                    buttonWidth > 0 ? Sizing.fixed(buttonWidth) : Sizing.content(), Sizing.content());
            chip.padding(Insets.of(4));
            chip.gap(6);
            chip.verticalAlignment(VerticalAlignment.CENTER);
            chip.horizontalAlignment(HorizontalAlignment.LEFT);
            chip.surface(Surface.PANEL_INSET);
            chip.cursorStyle(CursorStyle.POINTER);
            chip.mouseEnter().subscribe(() -> chip.surface(Surface.PANEL));
            chip.mouseLeave().subscribe(() -> chip.surface(Surface.PANEL_INSET));
            chip.mouseDown().subscribe((mouseX, mouseY, button) -> {
                click(slot, button);
                return true;
            });
            chip.child(icon);
            chip.child(Components.label(stack.getHoverName()));
            return chip;
        }

        // Carte d'info : icone + (nom + lignes de lore empilees).
        FlowLayout chip = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.padding(Insets.of(4));
        chip.gap(6);
        chip.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout textCol = Containers.verticalFlow(Sizing.content(), Sizing.content());
        textCol.gap(2);
        textCol.child(Components.label(stack.getHoverName()));
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                textCol.child(Components.label(line));
            }
        }
        chip.child(icon);
        chip.child(textCol);
        return chip;
    }

    private void click(int slot, int button) {
        int mapped = button == 1 ? 1 : 0; // 1 = clic droit
        PacketDistributor.sendToServer(new MenuClickPayload(data.sessionId(), slot, mapped));
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public void removed() {
        super.removed();
        if (!closeSent) {
            closeSent = true;
            PacketDistributor.sendToServer(new MenuClickPayload(data.sessionId(), -1, 0));
        }
    }
}
