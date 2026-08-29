package com.utopia.client.owo;

import java.util.List;

import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenTablePayload;

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
 * Ecran <b>tableau</b> : un en-tete de colonnes, puis une ligne par enregistrement. Chaque colonne a
 * sa largeur et son alignement, decides par le serveur, de sorte que les chiffres se lisent cales a
 * droite les uns sous les autres.
 *
 * <p>Il n'y a pas de bouton par ligne : la ligne entiere se clique, ce qui laisse toute la largeur
 * aux donnees et evite la colonne d'actions qui repete le meme mot vingt fois.
 */
public class UtopiaTableScreen extends BaseOwoScreen<FlowLayout> implements GuiScaleLock.Scaled {

    /** Espace entre deux cellules. */
    private static final int CELL_GAP = 6;

    private final OpenTablePayload data;
    private final int rowWidth;
    private boolean closeSent = false;

    public UtopiaTableScreen(OpenTablePayload data) {
        super(data.title());
        this.data = data;
        int total = 0;
        for (int w : data.widths()) {
            total += w + CELL_GAP;
        }
        this.rowWidth = Math.max(180, total + 6);
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

        // En-tete : titre, encadre des fleches de pagination quand il y a plusieurs pages.
        FlowLayout header = Containers.horizontalFlow(Sizing.fixed(rowWidth), Sizing.content());
        header.surface(OwoStyle.HEADER);
        header.padding(Insets.of(4));
        header.gap(6);
        header.horizontalAlignment(HorizontalAlignment.CENTER);
        header.verticalAlignment(VerticalAlignment.CENTER);
        if (data.prevId() >= 0) {
            header.child(textButton(Component.literal("< Prec.")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.prevId())));
        }
        header.child(Components.label(data.title()).shadow(true));
        if (data.nextId() >= 0) {
            header.child(textButton(Component.literal("Suiv. >")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    () -> click(data.nextId())));
        }
        panel.child(header);

        if (!data.stats().isEmpty()) {
            FlowLayout stats = Containers.verticalFlow(Sizing.fixed(rowWidth), Sizing.content());
            stats.surface(OwoStyle.INFO);
            stats.padding(Insets.of(4, 4, 6, 6));
            stats.gap(2);
            for (Component line : data.stats()) {
                // Bornee : sans maxWidth une ligne un peu longue sort du panneau par la droite au
                // lieu de passer a la ligne, et l'information disparait de l'ecran.
                stats.child(Components.label(line).shadow(true).maxWidth(rowWidth - 12));
            }
            panel.child(stats);
        }

        // Criteres de recherche : au-dessus du tableau, avec leur etat ecrit en clair et leur bouton.
        for (OpenTablePayload.Control c : data.controls()) {
            panel.child(control(c));
        }

        // Ligne d'en-tete des colonnes : elle ne defile pas, elle reste au-dessus du tableau.
        if (!data.headers().isEmpty()) {
            panel.child(cells(data.headers(), OwoStyle.HEADER, false, -1,
                    net.minecraft.world.item.ItemStack.EMPTY));
        }

        FlowLayout rows = Containers.verticalFlow(Sizing.content(), Sizing.content());
        rows.gap(2);
        for (OpenTablePayload.Row r : data.rows()) {
            rows.child(cells(r.cells(), OwoStyle.INFO, r.actionId() >= 0, r.actionId(), r.icon()));
        }

        // La place laissee au tableau depend de ce qu'il y a autour : titre, lignes de statistiques,
        // criteres de recherche, en-tete de colonnes et barre du bas. Une reserve constante suffisait
        // tant que les ecrans se ressemblaient ; avec quatre lignes de filtres, elle ne suffit plus
        // et le panneau depasse de l'ecran par le haut comme par le bas.
        int chrome = 96 + data.stats().size() * 12 + data.controls().size() * 26
                + (data.headers().isEmpty() ? 0 : 20);
        int estHeight = data.rows().size() * 20 + 4;
        int maxHeight = Math.max(60, this.height - chrome);
        if (estHeight > maxHeight) {
            ScrollContainer<FlowLayout> scroll =
                    Containers.verticalScroll(Sizing.content(), Sizing.fixed(maxHeight), rows);
            scroll.scrollbar(ScrollContainer.Scrollbar.flat(OwoStyle.SCROLLBAR));
            scroll.scrollbarThiccness(4);
            scroll.padding(Insets.right(6));
            panel.child(scroll);
        } else {
            panel.child(rows);
        }

        if (!data.footer().isEmpty()) {
            FlowLayout footer = Containers.horizontalFlow(Sizing.fixed(rowWidth), Sizing.content());
            footer.gap(5);
            footer.horizontalAlignment(HorizontalAlignment.CENTER);
            for (OpenTablePayload.Action a : data.footer()) {
                footer.child(textButton(a.label(), () -> click(a.id())));
            }
            panel.child(footer);
        }

        FlowLayout nav = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        nav.gap(5);
        nav.horizontalAlignment(HorizontalAlignment.CENTER);
        if (data.backId() >= 0) {
            nav.child(textButton(Component.literal("< Retour")
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                    () -> click(data.backId())));
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

    /**
     * Une rangee de cellules a largeur fixe. {@code clickable} rend la ligne entiere sensible au
     * survol et au clic : c'est ce qui remplace la colonne de boutons.
     */
    private FlowLayout cells(List<Component> values, Surface surface, boolean clickable, int actionId,
                            net.minecraft.world.item.ItemStack icon) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fixed(rowWidth), Sizing.content());
        row.surface(surface);
        row.padding(Insets.of(3, 3, 4, 4));
        row.gap(CELL_GAP);
        row.verticalAlignment(VerticalAlignment.CENTER);

        for (int i = 0; i < values.size(); i++) {
            int width = i < data.widths().size() ? data.widths().get(i) : 60;
            int align = i < data.aligns().size() ? data.aligns().get(i) : 0;
            FlowLayout cell = Containers.horizontalFlow(Sizing.fixed(width), Sizing.content());
            cell.horizontalAlignment(switch (align) {
                case 1 -> HorizontalAlignment.CENTER;
                case 2 -> HorizontalAlignment.RIGHT;
                default -> HorizontalAlignment.LEFT;
            });
            cell.verticalAlignment(VerticalAlignment.CENTER);
            if (i == 0 && !icon.isEmpty()) {
                // L'icone mange sa place dans la premiere colonne : le libelle se replierait sinon.
                io.wispforest.owo.ui.component.ItemComponent stack = Components.item(icon);
                stack.setTooltipFromStack(false);
                stack.margins(Insets.right(3));
                cell.child(stack);
                cell.child(Components.label(values.get(i)).shadow(true).maxWidth(Math.max(20, width - 19)));
            } else {
                cell.child(Components.label(values.get(i)).shadow(true).maxWidth(width));
            }
            row.child(cell);
        }

        if (clickable) {
            row.cursorStyle(CursorStyle.POINTER);
            row.mouseEnter().subscribe(() -> row.surface(OwoStyle.BTN_HOVER));
            row.mouseLeave().subscribe(() -> row.surface(surface));
            row.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
                click(actionId);
                Minecraft.getInstance().getSoundManager()
                        .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                return true;
            });
        }
        return row;
    }

    /** Une ligne de commande : libelle a gauche, etat au centre, bouton a droite. */
    private FlowLayout control(OpenTablePayload.Control c) {
        int labelW = 108;
        int btnW = 92;
        int valueW = Math.max(60, rowWidth - labelW - btnW - 3 * CELL_GAP);
        FlowLayout row = Containers.horizontalFlow(Sizing.fixed(rowWidth), Sizing.content());
        row.surface(OwoStyle.INFO);
        row.padding(Insets.of(3, 3, 4, 4));
        row.gap(CELL_GAP);
        row.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout labelCell = Containers.horizontalFlow(Sizing.fixed(labelW), Sizing.content());
        labelCell.child(Components.label(c.label()).maxWidth(labelW));
        row.child(labelCell);

        FlowLayout valueCell = Containers.horizontalFlow(Sizing.fixed(valueW), Sizing.content());
        valueCell.child(Components.label(c.value()).shadow(true).maxWidth(valueW));
        row.child(valueCell);

        FlowLayout btnCell = Containers.horizontalFlow(Sizing.fixed(btnW), Sizing.content());
        btnCell.horizontalAlignment(HorizontalAlignment.RIGHT);
        btnCell.verticalAlignment(VerticalAlignment.CENTER);
        if (c.actionId() >= 0) {
            btnCell.child(textButton(c.buttonLabel(), () -> click(c.actionId())));
        }
        row.child(btnCell);
        return row;
    }

    private FlowLayout textButton(Component label, Runnable action) {
        FlowLayout b = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        b.padding(Insets.of(4, 4, 6, 6));
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
