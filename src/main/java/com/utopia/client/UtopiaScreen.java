package com.utopia.client;

import com.utopia.gui.UtopiaMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Ecran client custom des menus Utopia : fond dessine en code (panneaux, bordure, barre de titre),
 * sans texture PNG. La disposition des slots reste celle d'un coffre vanilla (les icones s'alignent).
 */
public class UtopiaScreen extends AbstractContainerScreen<UtopiaMenu> {

    // Couleurs ARGB
    private static final int C_SHADOW = 0xF00A0A12;
    private static final int C_BORDER = 0xFF5B5BE6;
    private static final int C_PANEL = 0xFF20222E;
    private static final int C_TITLE_BAR = 0xFF2E2E8C;
    private static final int C_SLOT = 0xFF12131C;
    private static final int C_INV_PANEL = 0xFF2A2A38;

    public UtopiaScreen(UtopiaMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + menu.getRowCount() * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int rows = this.menu.getRowCount();
        int chestHeight = rows * 18 + 17;

        // Ombre + bordure.
        g.fill(x - 4, y - 4, x + this.imageWidth + 4, y + this.imageHeight + 4, C_SHADOW);
        g.fill(x - 1, y - 1, x + this.imageWidth + 1, y + this.imageHeight + 1, C_BORDER);
        // Panneau du haut (zone de la GUI).
        g.fill(x, y, x + this.imageWidth, y + chestHeight, C_PANEL);
        // Barre de titre.
        g.fill(x, y, x + this.imageWidth, y + 16, C_TITLE_BAR);
        // Panneau inventaire du joueur.
        g.fill(x, y + chestHeight, x + this.imageWidth, y + this.imageHeight, C_INV_PANEL);
        // Case sombre derriere chaque slot.
        for (Slot slot : this.menu.slots) {
            g.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, C_SLOT);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Titre en blanc (le fond est sombre) ; on n'affiche pas le label "Inventaire" par defaut.
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, false);
    }
}
