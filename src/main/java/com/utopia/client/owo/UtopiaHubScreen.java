package com.utopia.client.owo;

import java.util.List;

import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenHubPayload;

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
 * Ecran d'accueil "riche" (hub) facon application : en-tete accentue, carte de statistiques, puis
 * une grille de gros boutons (icone + libelle + sous-libelle) et un pied de page (Rafraichir / Fermer).
 * Tout est dessine en code via {@link OwoStyle} (aucune texture). Les actions repassent par le canal
 * de menu existant : un clic envoie un {@link MenuClickPayload} avec l'{@code id} du bouton.
 */
public class UtopiaHubScreen extends BaseOwoScreen<FlowLayout> {

    private static final int BUTTON_WIDTH = 158;
    private static final int COLS = 2;

    private final OpenHubPayload data;
    private boolean closeSent = false;

    public UtopiaHubScreen(OpenHubPayload data) {
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
        panel.gap(7);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        // En-tete accentue (titre centre).
        FlowLayout header = Containers.horizontalFlow(Sizing.fixed(BUTTON_WIDTH * COLS + 5), Sizing.content());
        header.surface(OwoStyle.HEADER);
        header.padding(Insets.of(6));
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.child(Components.label(data.title()).shadow(true));
        panel.child(header);

        // Carte de statistiques (solde, parcelles, rang...).
        if (!data.stats().isEmpty()) {
            FlowLayout stats = Containers.verticalFlow(Sizing.fixed(BUTTON_WIDTH * COLS + 5), Sizing.content());
            stats.surface(OwoStyle.INFO);
            stats.padding(Insets.of(7));
            stats.gap(3);
            stats.horizontalAlignment(HorizontalAlignment.CENTER);
            for (Component line : data.stats()) {
                stats.child(Components.label(line).shadow(true));
            }
            panel.child(stats);
        }

        // Grille de gros boutons (2 colonnes).
        FlowLayout grid = Containers.verticalFlow(Sizing.content(), Sizing.content());
        grid.gap(5);
        grid.horizontalAlignment(HorizontalAlignment.CENTER);
        List<OpenHubPayload.Button> buttons = data.buttons();
        for (int i = 0; i < buttons.size(); i += COLS) {
            FlowLayout row = Containers.horizontalFlow(Sizing.content(), Sizing.content());
            row.gap(5);
            for (int c = 0; c < COLS && i + c < buttons.size(); c++) {
                row.child(bigButton(buttons.get(i + c)));
            }
            grid.child(row);
        }

        int rows = (buttons.size() + COLS - 1) / COLS;
        int estHeight = rows * 42 + 6;
        int maxHeight = Math.max(84, this.height - 170);
        if (estHeight > maxHeight) {
            ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.content(), Sizing.fixed(maxHeight), grid);
            scroll.scrollbar(ScrollContainer.Scrollbar.flat(OwoStyle.SCROLLBAR));
            scroll.scrollbarThiccness(4);
            scroll.padding(Insets.right(7));
            panel.child(scroll);
        } else {
            panel.child(grid);
        }

        // Pied de page : Rafraichir / Fermer.
        FlowLayout footer = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        footer.gap(6);
        footer.horizontalAlignment(HorizontalAlignment.CENTER);
        footer.child(smallButton(Component.literal("Rafraichir").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                () -> click(data.refreshId())));
        if (data.backId() >= 0) {
            footer.child(smallButton(Component.literal("Retour").withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                    () -> click(data.backId())));
        }
        footer.child(smallButton(Component.literal("Fermer").withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                () -> Minecraft.getInstance().setScreen(null)));
        panel.child(footer);

        root.child(panel);
    }

    /** Gros bouton : icone + (libelle au-dessus, sous-libelle en dessous), largeur fixe, survol. */
    private FlowLayout bigButton(OpenHubPayload.Button b) {
        FlowLayout button = Containers.horizontalFlow(Sizing.fixed(BUTTON_WIDTH), Sizing.fixed(34));
        button.padding(Insets.of(5));
        button.gap(7);
        button.verticalAlignment(VerticalAlignment.CENTER);
        button.horizontalAlignment(HorizontalAlignment.LEFT);
        button.surface(OwoStyle.BTN);
        button.cursorStyle(CursorStyle.POINTER);
        button.mouseEnter().subscribe(() -> button.surface(OwoStyle.BTN_HOVER));
        button.mouseLeave().subscribe(() -> button.surface(OwoStyle.BTN));
        button.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
            click(b.id());
            return true;
        });

        // Icone : affichee seulement si non vide (les menus "texte seul" passent un stack vide).
        if (!b.icon().isEmpty()) {
            ItemComponent icon = Components.item(b.icon());
            icon.setTooltipFromStack(false);
            button.child(icon);
        }

        button.child(Components.label(b.label()).shadow(true));

        // La description n'est plus affichee en ligne : elle devient une bulle d'info au survol.
        if (!b.sublabel().getString().isEmpty()) {
            button.tooltip(b.sublabel());
        }
        return button;
    }

    /** Petit bouton de pied de page (libelle centre, survol). */
    private FlowLayout smallButton(Component label, Runnable action) {
        FlowLayout button = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        button.padding(Insets.of(6));
        button.surface(OwoStyle.BTN);
        button.cursorStyle(CursorStyle.POINTER);
        button.horizontalAlignment(HorizontalAlignment.CENTER);
        button.verticalAlignment(VerticalAlignment.CENTER);
        button.child(Components.label(label));
        button.mouseEnter().subscribe(() -> button.surface(OwoStyle.BTN_HOVER));
        button.mouseLeave().subscribe(() -> button.surface(OwoStyle.BTN));
        button.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
            action.run();
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        });
        return button;
    }

    private void click(int id) {
        PacketDistributor.sendToServer(MenuC2SPayload.of(new MenuClickPayload(data.sessionId(), id, 0)));
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
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
