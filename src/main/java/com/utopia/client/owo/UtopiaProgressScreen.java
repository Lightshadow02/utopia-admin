package com.utopia.client.owo;

import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenProgressPayload;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.ItemComponent;
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
 * Ecran de progression : chaque objectif est dessine comme une <b>vraie barre continue</b> (une piste
 * sombre et une partie remplie proportionnelle), a la maniere d'une barre de lecture, et non comme une
 * suite de caracteres. La barre mise en avant (Utopieces) est plus haute et plus large que les autres.
 */
public class UtopiaProgressScreen extends BaseOwoScreen<FlowLayout> {

    private static final int PANEL_W = 320;
    private static final int BAR_W = 300;
    private static final int BAR_H_BIG = 16;
    private static final int BAR_H = 10;

    private final OpenProgressPayload data;
    private boolean closeSent = false;

    public UtopiaProgressScreen(OpenProgressPayload data) {
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

        // En-tete.
        FlowLayout header = Containers.horizontalFlow(Sizing.fixed(PANEL_W), Sizing.content());
        header.surface(OwoStyle.HEADER);
        header.padding(Insets.of(5));
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.child(Components.label(data.title()).shadow(true));
        panel.child(header);

        // Texte de presentation.
        if (!data.intro().isEmpty()) {
            FlowLayout intro = Containers.verticalFlow(Sizing.fixed(PANEL_W), Sizing.content());
            intro.surface(OwoStyle.INFO);
            intro.padding(Insets.of(6));
            intro.gap(2);
            for (Component line : data.intro()) {
                intro.child(Components.label(line).maxWidth(PANEL_W - 14));
            }
            panel.child(intro);
        }

        // Barres de progression.
        FlowLayout bars = Containers.verticalFlow(Sizing.content(), Sizing.content());
        bars.gap(5);
        bars.horizontalAlignment(HorizontalAlignment.CENTER);
        for (OpenProgressPayload.Bar bar : data.bars()) {
            bars.child(barRow(bar));
        }

        int estHeight = data.bars().size() * 44 + 8;
        int maxHeight = Math.max(90, this.height - 190);
        if (estHeight > maxHeight) {
            ScrollContainer<FlowLayout> scroll =
                    Containers.verticalScroll(Sizing.content(), Sizing.fixed(maxHeight), bars);
            scroll.scrollbar(ScrollContainer.Scrollbar.flat(OwoStyle.SCROLLBAR));
            scroll.scrollbarThiccness(4);
            scroll.padding(Insets.right(7));
            panel.child(scroll);
        } else {
            panel.child(bars);
        }

        // Pied de page.
        FlowLayout nav = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        nav.gap(5);
        nav.horizontalAlignment(HorizontalAlignment.CENTER);
        for (OpenProgressPayload.Action action : data.footer()) {
            nav.child(textButton(action.label(), () -> click(action.id())));
        }
        if (data.backId() >= 0) {
            nav.child(textButton(Component.literal("< Retour")
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)), () -> click(data.backId())));
        }
        if (data.refreshId() >= 0) {
            nav.child(textButton(Component.literal("Rafraichir")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.refreshId())));
        }
        nav.child(textButton(Component.literal("Fermer")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                () -> Minecraft.getInstance().setScreen(null)));
        panel.child(nav);

        root.child(panel);
    }

    /** Une ligne : icone + libelle + chiffres, puis la barre dessinee, et un bouton optionnel. */
    private FlowLayout barRow(OpenProgressPayload.Bar bar) {
        int height = bar.big() ? BAR_H_BIG : BAR_H;
        int width = bar.big() ? BAR_W : BAR_W - 20;

        FlowLayout row = Containers.verticalFlow(Sizing.fixed(width + 14), Sizing.content());
        row.surface(OwoStyle.INFO);
        row.padding(Insets.of(5));
        row.gap(3);
        row.horizontalAlignment(HorizontalAlignment.CENTER);

        // Ligne de titre : icone, nom, valeurs, bouton.
        FlowLayout head = Containers.horizontalFlow(Sizing.fixed(width), Sizing.content());
        head.gap(5);
        head.verticalAlignment(VerticalAlignment.CENTER);
        if (!bar.icon().isEmpty()) {
            ItemComponent icon = Components.item(bar.icon());
            icon.setTooltipFromStack(false);
            head.child(icon);
        }
        head.child(Components.label(bar.label()).shadow(true).maxWidth(width - 130));

        FlowLayout values = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        values.gap(4);
        values.verticalAlignment(VerticalAlignment.CENTER);
        values.child(Components.label(Component.literal(bar.current() + " / " + bar.required())
                .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false))));
        values.child(Components.label(Component.literal(percentText(bar))
                .withStyle(s -> s.withColor(bar.done() ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                        .withItalic(false))));
        if (bar.actionId() >= 0) {
            values.child(textButton(bar.buttonLabel(), () -> click(bar.actionId())));
        }
        head.child(values);
        row.child(head);

        // La barre : une piste sombre, et par-dessus la portion remplie.
        float ratio = bar.required() <= 0 ? 1f : Math.min(1f, (float) bar.current() / bar.required());
        int filled = Math.max(ratio > 0f ? 1 : 0, Math.round(width * ratio));

        FlowLayout track = Containers.horizontalFlow(Sizing.fixed(width), Sizing.fixed(height));
        track.surface(OwoStyle.BAR_TRACK);
        track.horizontalAlignment(HorizontalAlignment.LEFT);
        track.verticalAlignment(VerticalAlignment.CENTER);
        if (filled > 0) {
            FlowLayout fill = Containers.horizontalFlow(Sizing.fixed(filled), Sizing.fixed(height));
            // Degrade vertical : la barre a du relief au lieu d'etre plate.
            fill.surface(OwoStyle.bar(bar.done() ? 0xFF3FBF5F : bar.color()));
            track.child(fill);
        }
        row.child(track);
        return row;
    }

    private static String percentText(OpenProgressPayload.Bar bar) {
        if (bar.done()) {
            return "100 % OK";
        }
        float ratio = bar.required() <= 0 ? 0f : (float) bar.current() / bar.required();
        return String.format(java.util.Locale.FRANCE, "%.1f %%", ratio * 100f);
    }

    private FlowLayout textButton(Component label, Runnable action) {
        FlowLayout b = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        b.padding(Insets.of(3, 3, 5, 5));
        b.surface(OwoStyle.BTN);
        b.cursorStyle(CursorStyle.POINTER);
        b.horizontalAlignment(HorizontalAlignment.CENTER);
        b.verticalAlignment(VerticalAlignment.CENTER);
        b.child(Components.label(label));
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
