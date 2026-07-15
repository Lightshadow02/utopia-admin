package com.utopia.client.owo;

import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenPanelPayload;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ecran "panneau de reglages" : une liste de lignes (libelle a gauche, valeur au centre, bouton
 * optionnel a droite), une rangee de boutons d'action en pied de page, et un retour. Aligne en
 * colonnes facon tableau. Dessine en code via {@link OwoStyle}.
 */
public class UtopiaPanelScreen extends BaseOwoScreen<FlowLayout> {

    private static final int LABEL_W = 128;
    private static final int VALUE_W = 124;
    private static final int BTN_W = 78;
    private static final int ROW_W = LABEL_W + VALUE_W + BTN_W + 12;

    private final OpenPanelPayload data;
    private boolean closeSent = false;

    public UtopiaPanelScreen(OpenPanelPayload data) {
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
        panel.surface(OwoStyle.PANEL);
        panel.padding(Insets.of(8));
        panel.gap(6);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        // En-tete : fleches de navigation optionnelles de part et d'autre du titre.
        FlowLayout header = Containers.horizontalFlow(Sizing.fixed(ROW_W), Sizing.content());
        header.surface(OwoStyle.HEADER);
        header.padding(Insets.of(4));
        header.gap(6);
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.verticalAlignment(VerticalAlignment.CENTER);
        if (data.prevId() >= 0) {
            header.child(textButton(Component.literal("< Prec.").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.prevId())));
        }
        header.child(Components.label(data.title()).shadow(true));
        if (data.nextId() >= 0) {
            header.child(textButton(Component.literal("Suiv. >").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.nextId())));
        }
        panel.child(header);

        // Lignes (label | valeur | bouton).
        FlowLayout rows = Containers.verticalFlow(Sizing.content(), Sizing.content());
        rows.gap(3);
        for (OpenPanelPayload.Row r : data.rows()) {
            rows.child(row(r));
        }

        int estHeight = data.rows().size() * 26 + 4;
        int maxHeight = Math.max(80, this.height - 150);
        if (estHeight > maxHeight) {
            ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.content(), Sizing.fixed(maxHeight), rows);
            scroll.scrollbar(ScrollContainer.Scrollbar.flat(OwoStyle.SCROLLBAR));
            scroll.scrollbarThiccness(4);
            scroll.padding(Insets.right(6));
            panel.child(scroll);
        } else {
            panel.child(rows);
        }

        // Pied de page : actions, sur leur propre rangee SAUF si inlineFooter (alors tout sur la nav).
        if (!data.inlineFooter() && !data.footer().isEmpty()) {
            FlowLayout footer = Containers.horizontalFlow(Sizing.fixed(ROW_W), Sizing.content());
            footer.gap(5);
            footer.horizontalAlignment(HorizontalAlignment.CENTER);
            for (OpenPanelPayload.Action a : data.footer()) {
                footer.child(textButton(a.label(), () -> click(a.id())));
            }
            panel.child(footer);
        }

        // Rangee du bas : Retour, [actions si inlineFooter], Rafraichir, Fermer.
        FlowLayout nav = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        nav.gap(5);
        nav.horizontalAlignment(HorizontalAlignment.CENTER);
        if (data.backId() >= 0) {
            nav.child(textButton(Component.literal("< Retour").withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                    () -> click(data.backId())));
        }
        if (data.inlineFooter()) {
            for (OpenPanelPayload.Action a : data.footer()) {
                nav.child(textButton(a.label(), () -> click(a.id())));
            }
        }
        if (data.refreshId() >= 0) {
            nav.child(textButton(Component.literal("Rafraichir").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.refreshId())));
        }
        nav.child(textButton(Component.literal("Fermer").withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                () -> Minecraft.getInstance().setScreen(null)));
        panel.child(nav);

        root.child(panel);
    }

    /** Une ligne du panneau : libelle (gauche) + valeur (centre) + bouton optionnel (droite). */
    private FlowLayout row(OpenPanelPayload.Row r) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fixed(ROW_W), Sizing.content());
        row.surface(OwoStyle.INFO);
        row.padding(Insets.of(4, 4, 6, 6));
        row.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout labelCell = Containers.horizontalFlow(Sizing.fixed(LABEL_W), Sizing.content());
        // maxWidth : les noms d'items longs passent a la ligne au lieu de deborder sur la valeur.
        labelCell.child(Components.label(r.label()).maxWidth(LABEL_W));
        row.child(labelCell);

        FlowLayout valueCell = Containers.horizontalFlow(Sizing.fixed(VALUE_W), Sizing.content());
        valueCell.child(Components.label(r.value()).shadow(true).maxWidth(VALUE_W));
        row.child(valueCell);

        FlowLayout btnCell = Containers.horizontalFlow(Sizing.fixed(BTN_W), Sizing.content());
        btnCell.horizontalAlignment(HorizontalAlignment.RIGHT);
        btnCell.verticalAlignment(VerticalAlignment.CENTER);
        if (r.buttonId() >= 0) {
            // Le bouton est aligne a droite dans une cellule fixe : un libelle trop long deborderait
            // VERS LA GAUCHE (il apparaissait coupe). On borne donc le texte, qui passe a la ligne.
            btnCell.child(textButton(r.buttonLabel(), () -> click(r.buttonId()), BTN_W - 12));
        }
        row.child(btnCell);
        return row;
    }

    /** Petit bouton "flat + bordure" avec survol (libelle non borne : pied de page, navigation...). */
    private FlowLayout textButton(Component label, Runnable action) {
        return textButton(label, action, -1);
    }

    /** Petit bouton "flat + bordure" avec survol ; {@code maxLabelWidth} &gt; 0 fait passer le texte a la ligne. */
    private FlowLayout textButton(Component label, Runnable action, int maxLabelWidth) {
        FlowLayout b = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        b.padding(Insets.of(4, 4, 6, 6));
        b.surface(OwoStyle.BTN);
        b.cursorStyle(CursorStyle.POINTER);
        b.horizontalAlignment(HorizontalAlignment.CENTER);
        b.verticalAlignment(VerticalAlignment.CENTER);
        io.wispforest.owo.ui.component.LabelComponent text = Components.label(label);
        if (maxLabelWidth > 0) {
            text.maxWidth(maxLabelWidth);
        }
        b.child(text);
        b.mouseEnter().subscribe(() -> b.surface(OwoStyle.BTN_HOVER));
        b.mouseLeave().subscribe(() -> b.surface(OwoStyle.BTN));
        b.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
            action.run();
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        });
        return b;
    }

    private void click(int id) {
        PacketDistributor.sendToServer(MenuC2SPayload.of(new MenuClickPayload(data.sessionId(), id, 0)));
    }

    @Override
    public void removed() {
        super.removed();
        if (!closeSent) {
            closeSent = true;
            PacketDistributor.sendToServer(MenuC2SPayload.of(new MenuClickPayload(data.sessionId(), -1, 0)));
        }
    }
}
