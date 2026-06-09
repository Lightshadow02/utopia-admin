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
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Color;
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
 * Ecran owo-ui generique : panneau sombre custom (fond + bordure), en-tete accentue, boutons
 * "flat + bordure" avec survol, et defilement quand le contenu est trop grand. Tout dessine en
 * code (aucune texture). Les vitres grises de remplissage sont masquees.
 */
public class UtopiaOwoMenuScreen extends BaseOwoScreen<FlowLayout> {

    private static final int COLS = 9;

    private static final Surface PANEL = OwoStyle.PANEL;
    private static final Surface HEADER = OwoStyle.HEADER;
    private static final Surface BTN = OwoStyle.BTN;
    private static final Surface BTN_HOVER = OwoStyle.BTN_HOVER;
    private static final Surface INFO = OwoStyle.INFO;
    private static final Color SCROLLBAR = OwoStyle.SCROLLBAR;

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
        panel.surface(PANEL);
        panel.padding(Insets.of(8));
        panel.gap(7);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        // En-tete accentue.
        FlowLayout header = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        header.surface(HEADER);
        header.padding(Insets.of(6));
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.child(Components.label(data.title()).shadow(true));
        panel.child(header);

        // Corps.
        FlowLayout body = Containers.verticalFlow(Sizing.content(), Sizing.content());
        body.gap(4);
        body.horizontalAlignment(HorizontalAlignment.CENTER);

        Set<Integer> clickable = new HashSet<>(data.clickable());
        List<ItemStack> items = data.items();
        int rows = Math.max(1, Math.min(6, data.rows()));
        int usedRows = data.grid() ? buildGrid(body, items, clickable, rows) : buildRows(body, items, clickable, rows);

        // Defilement si le contenu depasse la hauteur dispo.
        int estHeight = usedRows * 24 + 6;
        int maxHeight = Math.max(80, this.height - 130);
        if (estHeight > maxHeight) {
            ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.content(), Sizing.fixed(maxHeight), body);
            scroll.scrollbar(ScrollContainer.Scrollbar.flat(SCROLLBAR));
            scroll.scrollbarThiccness(4);
            scroll.padding(Insets.right(7)); // place pour la scrollbar a droite
            panel.child(scroll);
        } else {
            panel.child(body);
        }

        root.child(panel);
    }

    /** Mode lignes centrees ; renvoie le nombre de lignes affichees. */
    private int buildRows(FlowLayout body, List<ItemStack> items, Set<Integer> clickable, int rows) {
        Font font = Minecraft.getInstance().font;
        int maxText = 0;
        for (int i = 0; i < items.size(); i++) {
            if (clickable.contains(i) && !isHidden(items.get(i))) {
                maxText = Math.max(maxText, font.width(items.get(i).getHoverName()));
            }
        }
        int buttonWidth = maxText + 16 + 6 + 14;

        int used = 0;
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
            body.child(rowFlow);
            used++;
        }
        return used;
    }

    /** Mode grille (respecte ligne/colonne) ; renvoie le nombre de lignes. */
    private int buildGrid(FlowLayout body, List<ItemStack> items, Set<Integer> clickable, int rows) {
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
        body.child(grid);
        return rows;
    }

    private static boolean isHidden(ItemStack stack) {
        return stack.isEmpty() || stack.is(Items.GRAY_STAINED_GLASS_PANE);
    }

    /** Bouton "flat + bordure" avec survol, ou carte d'info (lore en ligne). */
    private FlowLayout chip(ItemStack stack, int slot, boolean isClickable, int buttonWidth) {
        ItemComponent icon = Components.item(stack);
        icon.setTooltipFromStack(true);

        if (isClickable) {
            FlowLayout chip = Containers.horizontalFlow(
                    buttonWidth > 0 ? Sizing.fixed(buttonWidth) : Sizing.content(), Sizing.content());
            chip.padding(Insets.of(5));
            chip.gap(6);
            chip.verticalAlignment(VerticalAlignment.CENTER);
            chip.horizontalAlignment(HorizontalAlignment.LEFT);
            chip.surface(BTN);
            chip.cursorStyle(CursorStyle.POINTER);
            chip.mouseEnter().subscribe(() -> chip.surface(BTN_HOVER));
            chip.mouseLeave().subscribe(() -> chip.surface(BTN));
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
        chip.padding(Insets.of(5));
        chip.gap(6);
        chip.verticalAlignment(VerticalAlignment.CENTER);
        chip.surface(INFO);

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
