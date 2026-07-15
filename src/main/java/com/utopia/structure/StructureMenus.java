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
            String state = "Etat " + st.current + "/" + st.stateCount + (st.auto ? " - auto" : " - manuel");
            boolean ready = st.allStatesReady();
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
        // A 2 etats une simple bascule suffit ; au-dela, on ouvre un selecteur.
        boolean many = st.stateCount > 2;
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat actuel", ChatFormatting.GRAY),
                Icons.label("Etat " + st.current + " / " + st.stateCount, ChatFormatting.AQUA),
                Icons.label(many ? "Choisir" : "Suivant", ChatFormatting.YELLOW),
                sp -> {
                    if (many) {
                        openStatePicker(sp, name);
                        return;
                    }
                    int target = st.nextState();
                    if (StructureManager.applyAnimated(sp.server, st, target)) {
                        sp.sendSystemMessage(Messages.success("Structure \"" + st.name + "\" -> etat " + target + "."));
                    } else {
                        sp.sendSystemMessage(Messages.warn("Etat " + target + " pas encore defini."));
                    }
                    openStruct(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nombre d'etats", ChatFormatting.GRAY),
                Icons.label(String.valueOf(st.stateCount), ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> {
                    int next = st.stateCount >= StructureData.MAX_STATES
                            ? StructureData.MIN_STATES : st.stateCount + 1;
                    st.setStateCount(next);
                    StructureData.get(sp.server).setDirty();
                    sp.sendSystemMessage(Messages.info(st.name + " : " + st.stateCount + " etats."));
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
                Icons.label("Animation", ChatFormatting.GRAY),
                Icons.label(st.anim.label(), ChatFormatting.LIGHT_PURPLE),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> {
                    st.anim = st.anim.next();
                    StructureData.get(sp.server).setDirty();
                    sp.sendSystemMessage(Messages.info("Animation : " + st.anim.label()));
                    openStruct(sp, name);
                }));
        // Une ligne par etat : capture depuis le monde, et pose directe si deja defini.
        for (int slot = 1; slot <= st.stateCount; slot++) {
            final int s = slot;
            boolean defined = st.hasState(s);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Etat " + s + (st.current == s ? " (actuel)" : ""),
                            st.current == s ? ChatFormatting.AQUA : ChatFormatting.GRAY),
                    Icons.label(defined ? "defini" : "manquant",
                            defined ? ChatFormatting.GREEN : ChatFormatting.RED),
                    Icons.label(defined ? "Capturer / Poser" : "Capturer", ChatFormatting.GOLD),
                    sp -> openStateMenu(sp, name, s)));
        }
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

        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Blocs concernes", ChatFormatting.GRAY),
                Icons.label(st.blockFilter.isEmpty() ? "tous" : st.blockFilter.size() + " bloc(s)",
                        st.blockFilter.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.LIGHT_PURPLE),
                Icons.label("Filtrer", ChatFormatting.YELLOW),
                sp -> openFilter(sp, name)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Marchand", ChatFormatting.GRAY),
                Icons.label(st.npcEnabled ? st.npcName + " (etat " + st.npcState + ")" : "desactive",
                        st.npcEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label("Configurer", ChatFormatting.YELLOW),
                sp -> openShopAdmin(sp, name)));

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

    /**
     * Filtre de blocs : par defaut une bascule touche tous les blocs de la zone. En ajoutant des
     * blocs ici, seuls ceux-la changent (ex. n'animer que les lanternes : allumees la nuit, eteintes
     * le jour), le reste de la construction restant intact.
     */
    public static void openFilter(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal("Blocs concernes - " + st.name)
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (String id : new ArrayList<>(st.blockFilter)) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(id, ChatFormatting.WHITE),
                    Icons.label("", ChatFormatting.GRAY),
                    Icons.label("Retirer", ChatFormatting.RED),
                    sp -> {
                        st.blockFilter.remove(id);
                        StructureData.get(sp.server).setDirty();
                        openFilter(sp, name);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Aucun filtre", ChatFormatting.GRAY),
                    Icons.label("toute la zone change", ChatFormatting.DARK_GRAY), null, null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Ajouter (bloc en main)", ChatFormatting.GREEN),
                        sp -> {
                            ItemStack held = sp.getMainHandItem();
                            if (!(held.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                                sp.sendSystemMessage(Messages.warn("Tiens un BLOC en main (ex. une lanterne)."));
                                openFilter(sp, name);
                                return;
                            }
                            String id = StructureManager.blockId(bi.getBlock().defaultBlockState());
                            if (st.blockFilter.contains(id)) {
                                sp.sendSystemMessage(Messages.warn(id + " est deja dans le filtre."));
                            } else {
                                st.blockFilter.add(id);
                                StructureData.get(sp.server).setDirty();
                                sp.sendSystemMessage(Messages.success("Filtre : " + id
                                        + " (seuls ces blocs changeront)."));
                            }
                            openFilter(sp, name);
                        }),
                new OwoMenuServer.PanelAction(Icons.label("Tout vider", ChatFormatting.YELLOW),
                        sp -> {
                            st.blockFilter.clear();
                            StructureData.get(sp.server).setDirty();
                            sp.sendSystemMessage(Messages.info("Filtre vide : toute la zone change de nouveau."));
                            openFilter(sp, name);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openFilter(sp, name), sp -> openStruct(sp, name));
    }

    /**
     * Selecteur d'etat : la liste des etats, un clic pose celui choisi. Propose des qu'il y a plus de
     * deux etats (a deux, la bascule "Suivant" suffit).
     */
    public static void openStatePicker(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal("Choisir l'etat - " + st.name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(Component.literal("Actuel : etat " + st.current + " / " + st.stateCount)
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (int slot = 1; slot <= st.stateCount; slot++) {
            final int s = slot;
            boolean defined = st.hasState(s);
            boolean isCurrent = st.current == s;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(isCurrent ? Items.LIME_DYE : defined ? Items.LODESTONE : Items.BARRIER),
                    Icons.label("Etat " + s, isCurrent ? ChatFormatting.GREEN
                            : defined ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.lore(isCurrent ? "Etat actuel" : defined ? "Cliquer pour poser" : "Non capture",
                            isCurrent ? ChatFormatting.GREEN : defined ? ChatFormatting.GRAY : ChatFormatting.RED),
                    sp -> {
                        if (!defined) {
                            sp.sendSystemMessage(Messages.warn("L'etat " + s + " n'est pas encore capture."));
                            openStatePicker(sp, name);
                            return;
                        }
                        if (isCurrent) {
                            sp.sendSystemMessage(Messages.info("La structure est deja a l'etat " + s + "."));
                            openStatePicker(sp, name);
                            return;
                        }
                        StructureManager.applyAnimated(sp.server, st, s);
                        sp.sendSystemMessage(Messages.success(st.name + " -> etat " + s + "."));
                        openStruct(sp, name);
                    }));
        }

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openStatePicker(sp, name), sp -> openStruct(sp, name));
    }

    /** Fiche d'un etat : le (re)capturer depuis le monde, ou le poser tout de suite. */
    public static void openStateMenu(ServerPlayer admin, String name, int slot) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        boolean defined = st.hasState(slot);
        Component title = Component.literal(st.name + " - etat " + slot)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(Component.literal(defined
                ? (st.current == slot ? "Etat defini, actuellement pose" : "Etat defini")
                : "Etat non capture")
                .withStyle(s -> s.withColor(defined ? ChatFormatting.GREEN : ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.STRUCTURE_BLOCK),
                Icons.label(defined ? "Re-capturer" : "Capturer", ChatFormatting.GOLD),
                Icons.lore("Memorise la zone telle qu'elle est maintenant", ChatFormatting.GRAY),
                sp -> {
                    captureState(sp, name, slot);
                    openStateMenu(sp, name, slot);
                }));
        if (defined) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LODESTONE),
                    Icons.label("Poser cet etat", ChatFormatting.GREEN),
                    Icons.lore("Applique l'etat " + slot + " dans le monde", ChatFormatting.GRAY),
                    sp -> {
                        StructureManager.applyAnimated(sp.server, st, slot);
                        sp.sendSystemMessage(Messages.success(st.name + " -> etat " + slot + "."));
                        openStateMenu(sp, name, slot);
                    }));
        }

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openStateMenu(sp, name, slot), sp -> openStruct(sp, name));
    }

    // ------------------------------------------------------------------ Marchand (admin)

    /** Configuration du marchand d'une structure : activation, etat, position, nom, skin, articles. */
    public static void openShopAdmin(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal("Marchand - " + st.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Actif", ChatFormatting.GRAY),
                Icons.label(st.npcEnabled ? "oui" : "non", st.npcEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label("Basculer", ChatFormatting.YELLOW),
                sp -> {
                    if (!st.npcEnabled && st.npcPos == null) {
                        sp.sendSystemMessage(Messages.warn("Place d'abord le marchand (Position)."));
                        openShopAdmin(sp, name);
                        return;
                    }
                    st.npcEnabled = !st.npcEnabled;
                    StructureData.get(sp.server).setDirty();
                    StructureManager.syncShopNpcs(sp.server);
                    openShopAdmin(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Present a l'etat", ChatFormatting.GRAY),
                Icons.label("Etat " + st.npcState + " / " + st.stateCount, ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> {
                    st.npcState = st.npcState >= st.stateCount ? 1 : st.npcState + 1;
                    StructureData.get(sp.server).setDirty();
                    StructureManager.syncShopNpcs(sp.server);
                    sp.sendSystemMessage(Messages.info("Le marchand apparait a l'etat " + st.npcState + "."));
                    openShopAdmin(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Position", ChatFormatting.GRAY),
                Icons.label(st.npcPos == null ? "non definie"
                        : st.npcPos.getX() + " " + st.npcPos.getY() + " " + st.npcPos.getZ(),
                        st.npcPos == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                Icons.label("Ici", ChatFormatting.GREEN),
                sp -> {
                    st.npcPos = sp.blockPosition();
                    StructureData.get(sp.server).setDirty();
                    StructureManager.syncShopNpcs(sp.server);
                    sp.sendSystemMessage(Messages.success("Marchand place a ta position."));
                    openShopAdmin(sp, name);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom", ChatFormatting.GRAY),
                Icons.label(st.npcName, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.YELLOW),
                sp -> Menus.promptText(sp, Icons.label("Nom du marchand", ChatFormatting.GOLD), List.of(),
                        Icons.label("Valider", ChatFormatting.GREEN), st.npcName, 24,
                        n -> {
                            if (n != null && !n.isBlank()) {
                                st.npcName = n.trim();
                                StructureData.get(sp.server).setDirty();
                                StructureManager.syncShopNpcs(sp.server);
                            }
                            openShopAdmin(sp, name);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Skin", ChatFormatting.GRAY),
                Icons.label(st.npcSkinValue == null || st.npcSkinValue.isEmpty() ? "Steve" : "personnalise",
                        ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> openSkinMenu(sp, name)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Articles", ChatFormatting.GRAY),
                Icons.label(st.trades.size() + " article(s)", ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openTrades(sp, name)));

        OwoMenuServer.openPanel(admin, title, rows, List.of(),
                sp -> openShopAdmin(sp, name), sp -> openStruct(sp, name));
    }

    /** Choix du skin du marchand : celui de l'admin, une URL de skin, ou Steve. */
    public static void openSkinMenu(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal("Skin - " + st.npcName)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(Component.literal(
                st.npcSkinValue == null || st.npcSkinValue.isEmpty() ? "Actuel : Steve" : "Actuel : personnalise")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD),
                Icons.label("Mon skin", ChatFormatting.GREEN),
                Icons.lore("Le marchand prend ton apparence", ChatFormatting.GRAY),
                sp -> {
                    String value = "";
                    String sig = "";
                    for (com.mojang.authlib.properties.Property p
                            : sp.getGameProfile().getProperties().get("textures")) {
                        value = p.value();
                        sig = p.signature() == null ? "" : p.signature();
                        break;
                    }
                    if (value.isEmpty()) {
                        sp.sendSystemMessage(Messages.warn("Skin indisponible (serveur hors ligne ?)."));
                    } else {
                        st.npcSkinValue = value;
                        st.npcSkinSignature = sig;
                        applySkin(sp, st, "Le marchand porte desormais ton skin.");
                    }
                    openSkinMenu(sp, name);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.PAINTING),
                Icons.label("Depuis une URL", ChatFormatting.AQUA),
                Icons.lore("URL textures.minecraft.net (ou juste le hash)", ChatFormatting.GRAY),
                sp -> Menus.promptText(sp, Icons.label("URL du skin", ChatFormatting.GOLD),
                        List.of(Icons.lore("http://textures.minecraft.net/texture/<hash>", ChatFormatting.GRAY),
                                Icons.lore("Le hash seul suffit (NameMC, MineSkin...).", ChatFormatting.DARK_GRAY),
                                Icons.lore("Seul ce domaine est accepte par le client.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Appliquer", ChatFormatting.GREEN), "", 256,
                        url -> {
                            String value = StructureManager.skinValueFromUrl(url);
                            if (value == null) {
                                sp.sendSystemMessage(Messages.error(
                                        "URL invalide. Attendu : un hash, ou une URL textures.minecraft.net."));
                            } else {
                                st.npcSkinValue = value;
                                st.npcSkinSignature = ""; // skin non signe : le client l'accepte quand meme
                                applySkin(sp, st, "Skin applique depuis l'URL.");
                            }
                            openSkinMenu(sp, name);
                        })));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.BARRIER),
                Icons.label("Steve (defaut)", ChatFormatting.GRAY),
                Icons.lore("Retire le skin personnalise", ChatFormatting.GRAY),
                sp -> {
                    st.npcSkinValue = "";
                    st.npcSkinSignature = "";
                    applySkin(sp, st, "Skin remis a Steve.");
                    openSkinMenu(sp, name);
                }));

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openSkinMenu(sp, name), sp -> openShopAdmin(sp, name));
    }

    /** Persiste le skin et rafraichit le marchand en jeu. */
    private static void applySkin(ServerPlayer admin, StructureData.Struct st, String message) {
        StructureData.get(admin.server).setDirty();
        StructureManager.syncShopNpcs(admin.server);
        admin.sendSystemMessage(Messages.success(message));
    }

    /** Liste des articles du marchand : retrait, et ajout depuis l'objet en main. */
    public static void openTrades(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        Component title = Component.literal("Articles - " + st.npcName)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int i = 0; i < st.trades.size(); i++) {
            final int idx = i;
            StructureData.Trade t = st.trades.get(i);
            String prices = (t.canBuy() ? "achat " + t.buyPrice() : "achat -")
                    + " / " + (t.canSell() ? "revente " + t.sellPrice() : "revente -");
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(t.stack().getHoverName().getString(), ChatFormatting.WHITE),
                    Icons.label(prices, ChatFormatting.GOLD),
                    Icons.label("Retirer", ChatFormatting.RED),
                    sp -> {
                        st.trades.remove(idx);
                        StructureData.get(sp.server).setDirty();
                        openTrades(sp, name);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucun article", ChatFormatting.GRAY),
                    Icons.label("tiens un objet en main", ChatFormatting.DARK_GRAY), null, null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Ajouter (objet en main)", ChatFormatting.GREEN),
                        sp -> promptAddTrade(sp, name)));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openTrades(sp, name),
                sp -> openShopAdmin(sp, name));
    }

    /** Ajoute l'objet en main comme article : on demande le prix d'achat puis de revente. */
    private static void promptAddTrade(ServerPlayer admin, String name) {
        StructureData.Struct st = StructureData.get(admin.server).get(name);
        if (st == null) {
            openList(admin);
            return;
        }
        ItemStack held = admin.getMainHandItem();
        if (held.isEmpty()) {
            admin.sendSystemMessage(Messages.warn("Tiens l'objet a vendre dans ta main, puis reessaie."));
            openTrades(admin, name);
            return;
        }
        ItemStack model = held.copyWithCount(1); // le prix est unitaire, stock illimite
        String label = model.getHoverName().getString();
        Menus.promptAmount(admin, Icons.label("Prix d'ACHAT (joueur -> marchand)", ChatFormatting.GOLD),
                List.of(Icons.lore("Objet : " + label, ChatFormatting.GRAY),
                        Icons.lore("Prix unitaire en Utopieces. 0 = achat impossible.", ChatFormatting.DARK_GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), 10, 0, 1_000_000_000L,
                buy -> Menus.promptAmount(admin, Icons.label("Prix de REVENTE (marchand -> joueur)", ChatFormatting.GOLD),
                        List.of(Icons.lore("Objet : " + label, ChatFormatting.GRAY),
                                Icons.lore("Ce que le marchand paie. 0 = il ne rachete pas.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Ajouter", ChatFormatting.GREEN), 0, 0, 1_000_000_000L,
                        sell -> {
                            // 0 = sens desactive (on stocke -1 pour le distinguer d'un prix nul).
                            st.trades.add(new StructureData.Trade(model,
                                    buy <= 0 ? -1 : buy, sell <= 0 ? -1 : sell));
                            StructureData.get(admin.server).setDirty();
                            admin.sendSystemMessage(Messages.success("Article ajoute : " + label
                                    + " (achat " + (buy <= 0 ? "-" : buy) + ", revente " + (sell <= 0 ? "-" : sell) + ")."));
                            openTrades(admin, name);
                        }));
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
