package com.utopia.hologram;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.HologramData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Gestion des hologrammes libres depuis {@code /admin} : creation, texte ligne par ligne avec sa
 * couleur, deplacement au bloc pres, et suppression.
 */
public final class HologramMenus {

    private static final int PAGE_SIZE = 12;
    /** Pas de deplacement d'un reglage : assez fin pour ajuster, assez gros pour aller vite. */
    private static final double STEP = 0.25;

    private HologramMenus() {
    }

    // ==============================================================================================
    //  Liste
    // ==============================================================================================

    public static void open(ServerPlayer admin) {
        open(admin, 0);
    }

    public static void open(ServerPlayer admin, int page) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration."));
            return;
        }
        HologramData data = HologramData.get(admin.server);

        Component title = Icons.screenTitle("Hologrammes", ChatFormatting.GOLD);
        List<Component> stats = List.of(
                Component.literal(data.all().size() + " hologramme(s) pose(s)")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Texte libre, une couleur par ligne, place ou vous voulez.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                Icons.label("Nouvel hologramme", ChatFormatting.GREEN),
                Icons.lore("Pose a votre position, a completer ensuite", ChatFormatting.GRAY),
                HologramMenus::promptCreate));
        for (HologramData.Hologram holo : data.all()) {
            String id = holo.id;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(holo.enabled ? Items.GLOW_ITEM_FRAME : Items.GRAY_DYE),
                    Icons.label(holo.name, holo.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.lore(holo.lines.size() + " ligne(s)"
                                    + (holo.isPlaced()
                                            ? String.format(" - %.0f %.0f %.0f", holo.x, holo.y, holo.z)
                                            : " - non place")
                                    + (holo.enabled ? "" : " - masque"),
                            holo.isPlaced() ? ChatFormatting.GRAY : ChatFormatting.RED),
                    sp -> openHologram(sp, id)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                HologramMenus::open, com.utopia.menu.AdminMenu::open);
    }

    private static void promptCreate(ServerPlayer admin) {
        Menus.promptFreeText(admin, Icons.label("Nom de l'hologramme", ChatFormatting.GOLD),
                List.of(Icons.lore("Sert a le retrouver dans la liste", ChatFormatting.GRAY),
                        Icons.lore("Ex : Panneau du spawn", ChatFormatting.DARK_GRAY)),
                Icons.label("Creer ici", ChatFormatting.GREEN), "Panneau", 48,
                name -> {
                    if (name == null || name.isBlank()) {
                        admin.sendSystemMessage(Messages.warn("Nom vide."));
                        open(admin);
                        return;
                    }
                    HologramData data = HologramData.get(admin.server);
                    HologramData.Hologram holo = data.create(name);
                    placeHere(admin, holo);
                    holo.lines.add(new HologramData.Line(name.trim(),
                            ChatFormatting.GOLD.getName(), true));
                    data.setDirty();
                    HologramManager.sync(admin.server);
                    admin.sendSystemMessage(Messages.success("Hologramme \"" + holo.name
                            + "\" pose ici. Ajoutez-lui des lignes."));
                    openHologram(admin, holo.id);
                });
    }

    private static void placeHere(ServerPlayer admin, HologramData.Hologram holo) {
        holo.dim = admin.level().dimension().location().toString();
        holo.x = admin.getX();
        // Un hologramme se lit a hauteur d'yeux, pas sous les pieds.
        holo.y = admin.getY() + 2.0;
        holo.z = admin.getZ();
    }

    // ==============================================================================================
    //  Fiche
    // ==============================================================================================

    public static void openHologram(ServerPlayer admin, String id) {
        HologramData data = HologramData.get(admin.server);
        HologramData.Hologram holo = data.get(id);
        if (holo == null) {
            open(admin);
            return;
        }
        Component title = Icons.title(holo.name, ChatFormatting.GOLD);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom", ChatFormatting.GRAY),
                Icons.label(holo.name, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Nouveau nom", ChatFormatting.GOLD),
                        List.of(), Icons.label("Valider", ChatFormatting.GREEN), holo.name, 48,
                        text -> {
                            if (text != null && !text.isBlank()) {
                                holo.name = text.trim();
                                HologramData.get(sp.server).setDirty();
                            }
                            openHologram(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Lignes", ChatFormatting.GRAY),
                Icons.label(holo.lines.size() + " / " + HologramData.MAX_LINES, ChatFormatting.AQUA),
                Icons.label("Ecrire", ChatFormatting.YELLOW),
                sp -> openLines(sp, id, 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Position", ChatFormatting.GRAY),
                Icons.label(holo.isPlaced()
                        ? String.format("%.1f %.1f %.1f", holo.x, holo.y, holo.z) : "non place",
                        holo.isPlaced() ? ChatFormatting.WHITE : ChatFormatting.RED),
                Icons.label("Deplacer", ChatFormatting.YELLOW),
                sp -> openMove(sp, id)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Affichage", ChatFormatting.GRAY),
                Icons.label(holo.enabled ? "visible" : "masque",
                        holo.enabled ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                Icons.label(holo.enabled ? "Masquer" : "Afficher", ChatFormatting.YELLOW),
                sp -> {
                    holo.enabled = !holo.enabled;
                    HologramData.get(sp.server).setDirty();
                    HologramManager.sync(sp.server);
                    openHologram(sp, id);
                }));
        // Apercu : ce que les joueurs liront, dans l'ordre et avec les couleurs choisies.
        for (HologramData.Line line : holo.lines) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Apercu", ChatFormatting.DARK_GRAY),
                    HologramManager.render(line), null, null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            HologramData d = HologramData.get(sp.server);
                            d.remove(id);
                            HologramManager.removeEntities(sp.server, id);
                            sp.sendSystemMessage(Messages.success("Hologramme supprime."));
                            open(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openHologram(sp, id),
                HologramMenus::open);
    }

    // ==============================================================================================
    //  Lignes
    // ==============================================================================================

    public static void openLines(ServerPlayer admin, String id, int page) {
        HologramData data = HologramData.get(admin.server);
        HologramData.Hologram holo = data.get(id);
        if (holo == null) {
            open(admin);
            return;
        }
        Component title = Icons.title("Lignes - " + holo.name, ChatFormatting.AQUA);
        List<Component> stats = List.of(
                Component.literal(holo.lines.size() + " / " + HologramData.MAX_LINES + " ligne(s)")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("La premiere ligne est celle du haut.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (holo.lines.size() < HologramData.MAX_LINES) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                    Icons.label("Ajouter une ligne", ChatFormatting.GREEN),
                    Icons.lore("Ajoutee en bas de l'hologramme", ChatFormatting.GRAY),
                    sp -> Menus.promptFreeText(sp, Icons.label("Texte de la ligne", ChatFormatting.GOLD),
                            List.of(Icons.lore("Laissez un espace pour une ligne vide",
                                    ChatFormatting.GRAY)),
                            Icons.label("Ajouter", ChatFormatting.GREEN), "", 96,
                            text -> {
                                HologramData d = HologramData.get(sp.server);
                                HologramData.Hologram h = d.get(id);
                                if (h != null && text != null && !text.isEmpty()
                                        && h.lines.size() < HologramData.MAX_LINES) {
                                    h.lines.add(new HologramData.Line(text,
                                            ChatFormatting.WHITE.getName(), false));
                                    d.setDirty();
                                    HologramManager.sync(sp.server);
                                }
                                openLines(sp, id, 0);
                            })));
        }
        for (int i = 0; i < holo.lines.size(); i++) {
            final int index = i;
            HologramData.Line line = holo.lines.get(i);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                    HologramManager.render(line),
                    Icons.lore("Ligne " + (i + 1) + " - " + HologramManager.color(line.color).getName()
                            + (line.bold ? " gras" : ""), ChatFormatting.GRAY),
                    sp -> openLine(sp, id, index)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openLines(sp, id, p), sp -> openHologram(sp, id));
    }

    public static void openLine(ServerPlayer admin, String id, int index) {
        HologramData data = HologramData.get(admin.server);
        HologramData.Hologram holo = data.get(id);
        if (holo == null || index < 0 || index >= holo.lines.size()) {
            openLines(admin, id, 0);
            return;
        }
        HologramData.Line line = holo.lines.get(index);
        Component title = Icons.title("Ligne " + (index + 1) + " - " + holo.name,
                ChatFormatting.AQUA);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Apercu", ChatFormatting.GRAY),
                HologramManager.render(line), null, null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Texte", ChatFormatting.GRAY),
                Icons.label(line.text, ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Texte de la ligne", ChatFormatting.GOLD),
                        List.of(), Icons.label("Valider", ChatFormatting.GREEN), line.text, 96,
                        text -> {
                            if (text != null && !text.isEmpty()) {
                                line.text = text;
                                refresh(sp);
                            }
                            openLine(sp, id, index);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Couleur", ChatFormatting.GRAY),
                Component.literal(HologramManager.color(line.color).getName())
                        .withStyle(s -> s.withColor(HologramManager.color(line.color)).withItalic(false)),
                Icons.label("Suivante", ChatFormatting.YELLOW),
                sp -> {
                    line.color = HologramManager.nextColor(line.color);
                    refresh(sp);
                    openLine(sp, id, index);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Gras", ChatFormatting.GRAY),
                Icons.label(line.bold ? "oui" : "non",
                        line.bold ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> {
                    line.bold = !line.bold;
                    refresh(sp);
                    openLine(sp, id, index);
                }));
        if (index > 0) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Ordre", ChatFormatting.GRAY),
                    Icons.label("remonter d'un cran", ChatFormatting.DARK_GRAY),
                    Icons.label("Monter", ChatFormatting.YELLOW),
                    sp -> swap(sp, id, index, index - 1)));
        }
        if (index < holo.lines.size() - 1) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Ordre", ChatFormatting.GRAY),
                    Icons.label("descendre d'un cran", ChatFormatting.DARK_GRAY),
                    Icons.label("Descendre", ChatFormatting.YELLOW),
                    sp -> swap(sp, id, index, index + 1)));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer la ligne", ChatFormatting.RED),
                        sp -> {
                            HologramData d = HologramData.get(sp.server);
                            HologramData.Hologram h = d.get(id);
                            if (h != null && index < h.lines.size()) {
                                h.lines.remove(index);
                                d.setDirty();
                                HologramManager.sync(sp.server);
                            }
                            openLines(sp, id, 0);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openLine(sp, id, index),
                sp -> openLines(sp, id, 0));
    }

    private static void swap(ServerPlayer admin, String id, int from, int to) {
        HologramData data = HologramData.get(admin.server);
        HologramData.Hologram holo = data.get(id);
        if (holo != null && from >= 0 && to >= 0 && from < holo.lines.size() && to < holo.lines.size()) {
            java.util.Collections.swap(holo.lines, from, to);
            data.setDirty();
            HologramManager.sync(admin.server);
            openLine(admin, id, to);
            return;
        }
        openLines(admin, id, 0);
    }

    private static void refresh(ServerPlayer admin) {
        HologramData.get(admin.server).setDirty();
        HologramManager.sync(admin.server);
    }

    // ==============================================================================================
    //  Deplacement
    // ==============================================================================================

    public static void openMove(ServerPlayer admin, String id) {
        HologramData data = HologramData.get(admin.server);
        HologramData.Hologram holo = data.get(id);
        if (holo == null) {
            open(admin);
            return;
        }
        Component title = Icons.title("Position - " + holo.name, ChatFormatting.LIGHT_PURPLE);

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Coordonnees", ChatFormatting.GRAY),
                        Icons.label(String.format("%.2f %.2f %.2f", holo.x, holo.y, holo.z),
                                ChatFormatting.WHITE),
                        Icons.label("Placer ici", ChatFormatting.GREEN),
                        sp -> {
                            placeHere(sp, holo);
                            refresh(sp);
                            sp.sendSystemMessage(Messages.success("Hologramme place a hauteur d'yeux ici."));
                            openMove(sp, id);
                        }),
                nudge(id, "Hauteur", "monter d'un quart de bloc", "Monter", 0, STEP, 0),
                nudge(id, "Hauteur", "descendre d'un quart de bloc", "Descendre", 0, -STEP, 0),
                nudge(id, "Nord / Sud", "vers le nord", "Nord", 0, 0, -STEP),
                nudge(id, "Nord / Sud", "vers le sud", "Sud", 0, 0, STEP),
                nudge(id, "Est / Ouest", "vers l'est", "Est", STEP, 0, 0),
                nudge(id, "Est / Ouest", "vers l'ouest", "Ouest", -STEP, 0, 0));

        OwoMenuServer.openPanel(admin, title, rows, List.of(), sp -> openMove(sp, id),
                sp -> openHologram(sp, id));
    }

    private static OwoMenuServer.PanelRow nudge(String id, String label, String hint, String button,
                                                double dx, double dy, double dz) {
        return new OwoMenuServer.PanelRow(
                Icons.label(label, ChatFormatting.GRAY),
                Icons.label(hint, ChatFormatting.DARK_GRAY),
                Icons.label(button, ChatFormatting.YELLOW),
                sp -> {
                    HologramData data = HologramData.get(sp.server);
                    HologramData.Hologram holo = data.get(id);
                    if (holo != null) {
                        holo.x += dx;
                        holo.y += dy;
                        holo.z += dz;
                        data.setDirty();
                        HologramManager.sync(sp.server);
                    }
                    openMove(sp, id);
                });
    }
}
