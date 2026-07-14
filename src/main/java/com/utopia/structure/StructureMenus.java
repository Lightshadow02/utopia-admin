package com.utopia.structure;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.StructureData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menus des structures a etats : liste, creation (selection de zone), et gestion d'une structure. */
public final class StructureMenus {

    private static final int PAGE_SIZE = 12;

    private StructureMenus() {
    }

    // ------------------------------------------------------------------ Liste

    public static void openList(ServerPlayer admin) {
        openList(admin, 0);
    }

    public static void openList(ServerPlayer admin, int page) {
        StructureData data = StructureData.get(admin.server);
        Component title = Component.literal("STRUCTURES")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(Component.literal(data.all().size() + " structure(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.EMERALD),
                Icons.label("Nouvelle structure", ChatFormatting.GREEN),
                Icons.lore("Definir la zone (clic gauche = coin 1, clic droit = coin 2)", ChatFormatting.GRAY),
                StructureMenus::startSelection));

        for (StructureData.Struct st : data.all()) {
            String name = st.name;
            String state = "Etat " + st.current + (st.auto ? " - auto" : " - manuel");
            boolean ready = st.hasState(1) && st.hasState(2);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(ready ? Items.LODESTONE : Items.STRUCTURE_BLOCK),
                    Icons.label(name, ready ? ChatFormatting.AQUA : ChatFormatting.YELLOW),
                    Icons.lore(ready ? state : "Etats incomplets - a configurer", ChatFormatting.GRAY),
                    sp -> openStruct(sp, name)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                StructureMenus::openList, com.utopia.menu.AdminMenu::open);
    }

    // ------------------------------------------------------------------ Creation

    private static void startSelection(ServerPlayer admin) {
        StructureManager.startSelect(admin.getUUID());
        admin.sendSystemMessage(Messages.info("Mode zone actif : CLIC GAUCHE = coin 1, CLIC DROIT = coin 2 "
                + "(la boite rouge s'affiche). Reviens dans /admin -> Structures pour valider."));
        Menus.close(admin);
    }

    /** Ecran de validation de la zone en cours (affiche quand une selection est active). */
    public static void openSelection(ServerPlayer admin) {
        Vec3i size = StructureManager.size(admin.getUUID());
        BlockPos min = StructureManager.min(admin.getUUID());

        Component title = Component.literal("Nouvelle structure")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true));
        List<Component> stats = new ArrayList<>();
        if (size == null) {
            stats.add(Component.literal("Pose les 2 coins (clic gauche / clic droit)")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
        } else {
            long vol = (long) size.getX() * size.getY() * size.getZ();
            stats.add(Component.literal("Zone : " + size.getX() + " x " + size.getY() + " x " + size.getZ()
                    + " (" + vol + " blocs)")
                    .withStyle(s -> s.withColor(vol > StructureManager.MAX_VOLUME ? ChatFormatting.RED : ChatFormatting.AQUA)
                            .withItalic(false)));
            stats.add(Component.literal("Coin min : " + min.getX() + " " + min.getY() + " " + min.getZ())
                    .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
        }

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (size != null) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                    Icons.label("Valider et nommer", ChatFormatting.GREEN),
                    Icons.lore("Capture l'etat 1 a partir du monde actuel", ChatFormatting.GRAY),
                    StructureMenus::promptName));
        }
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                Icons.label("Annuler la selection", ChatFormatting.RED),
                Icons.lore("Quitte le mode zone", ChatFormatting.GRAY),
                sp -> {
                    StructureManager.clearSelect(sp.getUUID());
                    sp.sendSystemMessage(Messages.info("Selection annulee."));
                    openList(sp);
                }));

        OwoMenuServer.openHub(admin, title, stats, entries, StructureMenus::openSelection, StructureMenus::openList);
    }

    private static void promptName(ServerPlayer admin) {
        Vec3i size = StructureManager.size(admin.getUUID());
        BlockPos min = StructureManager.min(admin.getUUID());
        if (size == null || min == null) {
            openSelection(admin);
            return;
        }
        long vol = (long) size.getX() * size.getY() * size.getZ();
        if (vol > StructureManager.MAX_VOLUME) {
            admin.sendSystemMessage(Messages.error("Zone trop grande (" + vol + " blocs, max "
                    + StructureManager.MAX_VOLUME + ")."));
            openSelection(admin);
            return;
        }
        Menus.promptText(admin, Icons.label("Nom de la structure", ChatFormatting.GOLD),
                List.of(Icons.lore("Ex : montgolfiere", ChatFormatting.GRAY),
                        Icons.lore("L'etat 1 sera capture tout de suite.", ChatFormatting.DARK_GRAY)),
                Icons.label("Creer", ChatFormatting.GREEN), "", 24,
                name -> {
                    if (name == null || name.isBlank()) {
                        admin.sendSystemMessage(Messages.warn("Nom vide."));
                        openSelection(admin);
                        return;
                    }
                    StructureData data = StructureData.get(admin.server);
                    if (data.exists(name.trim())) {
                        admin.sendSystemMessage(Messages.warn("Une structure porte deja ce nom."));
                        openSelection(admin);
                        return;
                    }
                    StructureData.Struct st = new StructureData.Struct(name.trim());
                    st.dim = admin.level().dimension().location().toString();
                    st.min = min;
                    st.size = size;
                    data.put(st);
                    StructureManager.clearSelect(admin.getUUID());
                    if (StructureManager.capture(admin.server, st, 1)) {
                        admin.sendSystemMessage(Messages.success("Structure \"" + st.name
                                + "\" creee, etat 1 capture. Modifie la zone puis definis l'etat 2."));
                    } else {
                        admin.sendSystemMessage(Messages.error("Capture de l'etat 1 impossible."));
                    }
                    openStruct(admin, st.name);
                });
    }

    // ------------------------------------------------------------------ Gestion d'une structure

    public static void openStruct(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal(st.name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat actuel", ChatFormatting.GRAY),
                Icons.label("Etat " + st.current, ChatFormatting.AQUA),
                Icons.label("Basculer", ChatFormatting.YELLOW),
                sp -> {
                    int target = st.current == 1 ? 2 : 1;
                    if (StructureManager.applyAnimated(sp.server, st, target)) {
                        sp.sendSystemMessage(Messages.success("Structure \"" + st.name + "\" -> etat " + target
                                + " (dissolution en cours)."));
                    } else {
                        sp.sendSystemMessage(Messages.warn("Etat " + target + " pas encore defini."));
                    }
                    openStruct(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Mode", ChatFormatting.GRAY),
                Icons.label(st.auto ? "Auto (jour/nuit)" : "Manuel",
                        st.auto ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label(st.auto ? "-> Manuel" : "-> Auto", ChatFormatting.YELLOW),
                sp -> {
                    if (!st.auto && !(st.hasState(1) && st.hasState(2))) {
                        sp.sendSystemMessage(Messages.warn("Definis les deux etats avant le mode auto."));
                        openStruct(sp, name);
                        return;
                    }
                    st.auto = !st.auto;
                    StructureData.get(sp.server).setDirty();
                    sp.sendSystemMessage(Messages.success("Mode : " + (st.auto
                            ? "AUTO (nuit = etat 2, jour = etat 1)" : "manuel")));
                    openStruct(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat 1", ChatFormatting.GRAY),
                Icons.label(st.hasState(1) ? "defini" : "manquant",
                        st.hasState(1) ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.label("Capturer", ChatFormatting.GOLD),
                sp -> captureState(sp, name, 1)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat 2", ChatFormatting.GRAY),
                Icons.label(st.hasState(2) ? "defini" : "manquant",
                        st.hasState(2) ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.label("Capturer", ChatFormatting.GOLD),
                sp -> captureState(sp, name, 2)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Zone", ChatFormatting.GRAY),
                Icons.label(st.size.getX() + "x" + st.size.getY() + "x" + st.size.getZ()
                        + " (" + st.volume() + ")", ChatFormatting.AQUA),
                Icons.label("Voir", ChatFormatting.YELLOW),
                sp -> {
                    StructureManager.showZone(sp, st);
                    sp.sendSystemMessage(Messages.info("Zone affichee en rouge."));
                    Menus.close(sp);
                }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            StructureData.get(sp.server).remove(name);
                            sp.sendSystemMessage(Messages.success("Structure \"" + name + "\" supprimee "
                                    + "(le monde n'est pas modifie)."));
                            openList(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openStruct(sp, name), StructureMenus::openList);
    }

    /** Capture l'etat du monde actuel dans le slot demande (ecrase l'ancien). */
    private static void captureState(ServerPlayer admin, String name, int slot) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        if (StructureManager.capture(admin.server, st, slot)) {
            st.current = slot; // ce qui est dans le monde correspond desormais a cet etat
            StructureData.get(admin.server).setDirty();
            admin.sendSystemMessage(Messages.success("Etat " + slot + " capture pour \"" + st.name + "\"."));
        } else {
            admin.sendSystemMessage(Messages.error("Capture impossible (zone trop grande ou dimension absente)."));
        }
        openStruct(admin, name);
    }
}
