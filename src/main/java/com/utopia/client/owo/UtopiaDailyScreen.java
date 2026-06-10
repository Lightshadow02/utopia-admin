package com.utopia.client.owo;

import java.util.ArrayList;
import java.util.List;

import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenDailyPayload;

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

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Calendrier de recompenses quotidiennes "riche" : en-tete accentue + serie (streak), grille
 * mensuelle (7 colonnes) avec une case coloree par etat (recupere / aujourd'hui / manque / a venir),
 * carte "prochaine recompense" et pied de page (mois precedent/suivant, reclamer, retour, fermer).
 * Entierement dessine en code via {@link OwoStyle}.
 */
public class UtopiaDailyScreen extends BaseOwoScreen<FlowLayout> {

    private static final int CELL = 44;
    private static final int COLS = 7;
    private static final int GRID_WIDTH = CELL * COLS;
    private static final String[] WEEKDAYS = {"Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"};

    private final OpenDailyPayload data;
    private boolean closeSent = false;

    /** Une icone qui defile entre plusieurs items de recompense. */
    private record Cycler(ItemComponent comp, List<ItemStack> stacks) {
    }

    private final List<Cycler> cyclers = new ArrayList<>();
    private int cycleTicks = 0;

    public UtopiaDailyScreen(OpenDailyPayload data) {
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
    public void tick() {
        super.tick();
        cycleTicks++;
        if (cycleTicks % 25 != 0 || cyclers.isEmpty()) {
            return; // change d'item ~ toutes les 1,25 s
        }
        int idx = cycleTicks / 25;
        for (Cycler c : cyclers) {
            if (c.stacks().size() > 1) {
                c.comp().stack(c.stacks().get(idx % c.stacks().size()));
            }
        }
    }

    @Override
    protected void build(FlowLayout root) {
        cyclers.clear();
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout panel = Containers.verticalFlow(Sizing.content(), Sizing.content());
        panel.surface(OwoStyle.PANEL);
        panel.padding(Insets.of(8));
        panel.gap(6);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        // En-tete : titre centre + ligne de serie a droite.
        FlowLayout header = Containers.horizontalFlow(Sizing.fixed(GRID_WIDTH), Sizing.content());
        header.surface(OwoStyle.HEADER);
        header.padding(Insets.of(6));
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.child(Components.label(data.title()).shadow(true));
        panel.child(header);

        FlowLayout streakBar = Containers.horizontalFlow(Sizing.fixed(GRID_WIDTH), Sizing.content());
        streakBar.horizontalAlignment(HorizontalAlignment.RIGHT);
        streakBar.child(Components.label(data.streak()));
        panel.child(streakBar);

        // Libelles des jours de la semaine.
        GridLayout weekdays = Containers.grid(Sizing.content(), Sizing.content(), 1, COLS);
        for (int c = 0; c < COLS; c++) {
            FlowLayout head = Containers.horizontalFlow(Sizing.fixed(CELL), Sizing.content());
            head.horizontalAlignment(HorizontalAlignment.CENTER);
            head.child(Components.label(Component.literal(WEEKDAYS[c])
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))));
            weekdays.child(head, 0, c);
        }
        panel.child(weekdays);

        // Grille du mois (6 semaines max).
        OpenDailyPayload.Day[] byDay = new OpenDailyPayload.Day[data.daysInMonth() + 1];
        for (OpenDailyPayload.Day d : data.days()) {
            if (d.day() >= 1 && d.day() <= data.daysInMonth()) {
                byDay[d.day()] = d;
            }
        }
        int rows = (data.firstWeekday() + data.daysInMonth() + COLS - 1) / COLS;
        rows = Math.max(1, Math.min(6, rows));

        GridLayout grid = Containers.grid(Sizing.content(), Sizing.content(), rows, COLS);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < COLS; c++) {
                int day = r * COLS + c - data.firstWeekday() + 1;
                if (day >= 1 && day <= data.daysInMonth() && byDay[day] != null) {
                    grid.child(dayCell(byDay[day]), r, c);
                } else {
                    FlowLayout empty = Containers.verticalFlow(Sizing.fixed(CELL), Sizing.fixed(CELL));
                    grid.child(empty, r, c);
                }
            }
        }
        panel.child(grid);

        // Carte "prochaine recompense" (icone qui defile entre les items s'il y en a plusieurs).
        if (!data.nextLore().isEmpty() || !data.nextIcons().isEmpty()) {
            FlowLayout next = Containers.horizontalFlow(Sizing.fixed(GRID_WIDTH), Sizing.content());
            next.surface(OwoStyle.INFO);
            next.padding(Insets.of(6));
            next.gap(7);
            next.verticalAlignment(VerticalAlignment.CENTER);
            if (!data.nextIcons().isEmpty()) {
                ItemComponent icon = Components.item(data.nextIcons().get(0));
                icon.setTooltipFromStack(false);
                icon.showOverlay(true);
                next.child(icon);
                if (data.nextIcons().size() > 1) {
                    cyclers.add(new Cycler(icon, data.nextIcons()));
                }
            }
            FlowLayout col = Containers.verticalFlow(Sizing.content(), Sizing.content());
            col.gap(1);
            for (Component line : data.nextLore()) {
                col.child(Components.label(line));
            }
            next.child(col);
            panel.child(next);
        }

        // Pied de page : navigation + reclamer + retour + fermer.
        FlowLayout footer = Containers.horizontalFlow(Sizing.fixed(GRID_WIDTH), Sizing.content());
        footer.gap(5);
        footer.horizontalAlignment(HorizontalAlignment.CENTER);
        footer.child(button(Component.literal("< Mois").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                () -> click(data.prevId())));
        if (data.canClaim()) {
            footer.child(button(Component.literal("Reclamer").withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)),
                    () -> click(data.claimId())));
        }
        footer.child(button(Component.literal("Menu").withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                () -> click(data.backId())));
        footer.child(button(Component.literal("Mois >").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                () -> click(data.nextId())));
        footer.child(button(Component.literal("Fermer").withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                () -> Minecraft.getInstance().setScreen(null)));
        panel.child(footer);

        root.child(panel);
    }

    /** Une case de jour : surface coloree selon l'etat, numero + icone de recompense. */
    private FlowLayout dayCell(OpenDailyPayload.Day d) {
        FlowLayout cell = Containers.verticalFlow(Sizing.fixed(CELL), Sizing.fixed(CELL));
        cell.surface(cellSurface(d.state()));
        cell.padding(Insets.of(2));
        cell.gap(1);
        cell.horizontalAlignment(HorizontalAlignment.CENTER);
        cell.verticalAlignment(VerticalAlignment.CENTER);

        cell.child(Components.label(Component.literal(Integer.toString(d.day()))
                .withStyle(s -> s.withColor(numberColor(d.state())).withItalic(false))));

        if (!d.rewards().isEmpty()) {
            ItemComponent icon = Components.item(d.rewards().get(0));
            icon.setTooltipFromStack(true);
            icon.showOverlay(true);
            cell.child(icon);
            if (d.rewards().size() > 1) {
                cyclers.add(new Cycler(icon, d.rewards()));
            }
        }

        if (d.actionId() >= 0) {
            cell.cursorStyle(CursorStyle.POINTER);
            cell.mouseEnter().subscribe(() -> cell.surface(CELL_HOVER));
            cell.mouseLeave().subscribe(() -> cell.surface(cellSurface(d.state())));
            cell.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
                click(d.actionId());
                return true;
            });
        }
        return cell;
    }

    // --- couleurs par etat ---

    private static final Surface CELL_HOVER =
            Surface.flat(0xFF39456E).and(Surface.outline(0xFFFFFFFF));

    private static Surface cellSurface(int state) {
        return switch (state) {
            case OpenDailyPayload.CLAIMED -> Surface.flat(0xFF18301E).and(Surface.outline(0xFF3FA34D));
            case OpenDailyPayload.CLAIMABLE -> Surface.flat(0xFF4A3A12).and(Surface.outline(0xFFE0A52A));
            case OpenDailyPayload.TODAY_DONE -> Surface.flat(0xFF18301E).and(Surface.outline(0xFF6FCF7F));
            case OpenDailyPayload.MISSED -> Surface.flat(0xFF341A1A).and(Surface.outline(0xFFB04242));
            case OpenDailyPayload.FUTURE -> Surface.flat(0xFF241A34).and(Surface.outline(0xFF8A5CC0));
            default -> Surface.flat(0xFF161922).and(Surface.outline(0xFF2C3354));
        };
    }

    private static int numberColor(int state) {
        return switch (state) {
            case OpenDailyPayload.CLAIMED, OpenDailyPayload.TODAY_DONE -> 0xFF7FD98C;
            case OpenDailyPayload.CLAIMABLE -> 0xFFFFD24A;
            case OpenDailyPayload.MISSED -> 0xFFD98080;
            case OpenDailyPayload.FUTURE -> 0xFFB793E0;
            default -> 0xFF8A93AD;
        };
    }

    /** Petit bouton de pied de page (libelle, survol). */
    private FlowLayout button(Component label, Runnable action) {
        FlowLayout b = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        b.padding(Insets.of(5));
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
