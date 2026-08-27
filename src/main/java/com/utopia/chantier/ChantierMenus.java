package com.utopia.chantier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.ChantierData;
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
 * Interfaces des chantiers communautaires : la vitrine du chantier avec ses barres de progression cote
 * joueur, et toute la configuration cote administration.
 */
public final class ChantierMenus {

    private static final int PAGE_SIZE = 12;
    /** Couleur de remplissage : doree pour les Utopieces, bleue pour les autres ressources. */
    private static final int COLOR_COIN = 0xFFE8B23A;
    private static final int COLOR_ITEM = 0xFF4A7FD4;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Europe/Paris"));

    private ChantierMenus() {
    }

    // ==============================================================================================
    //  Cote joueur : la vitrine du chantier
    // ==============================================================================================

    public static void openChantier(ServerPlayer player, String id) {
        ChantierData.Chantier chantier = ChantierData.get(player.server).get(id);
        if (chantier == null) {
            player.sendSystemMessage(Messages.warn("Ce chantier n'existe plus."));
            return;
        }
        Component title = Component.literal(chantier.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<Component> intro = new ArrayList<>();
        if (chantier.presentation != null && !chantier.presentation.isBlank()) {
            intro.add(Component.literal(chantier.presentation)
                    .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)));
        }
        if (chantier.state == ChantierData.State.REUNIES) {
            intro.add(Component.literal("Toutes les ressources necessaires a la construction de "
                            + chantier.name + " ont ete reunies par les Utopiens. "
                            + "Bravo a toutes et a tous pour votre participation !")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)));
        } else if (chantier.state == ChantierData.State.TERMINE) {
            intro.add(Component.literal("Chantier termine. Merci a tous les Utopiens qui y ont contribue !")
                    .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        }

        // Les Utopieces d'abord, puis les autres ressources.
        List<ChantierData.Goal> ordered = ordered(chantier);
        OwoMenuServer.ProgressBuilder builder = new OwoMenuServer.ProgressBuilder();
        for (ChantierData.Goal goal : ordered) {
            boolean coin = ChantierManager.isCoinGoal(goal);
            boolean canGive = chantier.acceptsDeposits() && !goal.done();
            builder.bar(goal.model.copyWithCount(1),
                    Icons.label(goal.display, coin ? ChatFormatting.GOLD : ChatFormatting.AQUA),
                    goal.current, goal.required, goal.done(), coin,
                    coin ? COLOR_COIN : COLOR_ITEM,
                    canGive ? Icons.label("Donner", ChatFormatting.GREEN) : null,
                    canGive ? sp -> promptGive(sp, id, indexOf(chantier, goal)) : null);
        }
        builder.action(Icons.label("Registre", ChatFormatting.YELLOW), sp -> openRegistry(sp, id, 0));

        OwoMenuServer.openProgress(player, title, intro, builder, sp -> openChantier(sp, id), null);
    }

    /** Objectifs tries : ceux mis en avant (Utopieces) en tete. */
    private static List<ChantierData.Goal> ordered(ChantierData.Chantier chantier) {
        List<ChantierData.Goal> out = new ArrayList<>();
        for (ChantierData.Goal g : chantier.goals) {
            if (g.highlight || ChantierManager.isCoinGoal(g)) {
                out.add(g);
            }
        }
        for (ChantierData.Goal g : chantier.goals) {
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    private static int indexOf(ChantierData.Chantier chantier, ChantierData.Goal goal) {
        return chantier.goals.indexOf(goal);
    }

    /** Choix de la quantite a donner, puis confirmation (la contribution est definitive). */
    private static void promptGive(ServerPlayer player, String id, int goalIndex) {
        ChantierData.Chantier chantier = ChantierData.get(player.server).get(id);
        if (chantier == null || goalIndex < 0 || goalIndex >= chantier.goals.size()) {
            openChantier(player, id);
            return;
        }
        ChantierData.Goal goal = chantier.goals.get(goalIndex);
        if (!chantier.acceptsDeposits()) {
            player.sendSystemMessage(Messages.warn("Ce chantier n'accepte plus de contributions."));
            openChantier(player, id);
            return;
        }
        if (goal.done()) {
            player.sendSystemMessage(Messages.warn("Cet objectif est deja atteint."));
            openChantier(player, id);
            return;
        }
        int owned = ChantierManager.count(player, goal);
        if (owned <= 0) {
            player.sendSystemMessage(Messages.warn("Tu n'as pas de " + goal.display + " sur toi."));
            openChantier(player, id);
            return;
        }
        int max = Math.min(owned, goal.remaining());
        Menus.promptAmount(player, Icons.label("Donner : " + goal.display, ChatFormatting.GOLD),
                List.of(Icons.lore("Tu en possedes " + owned + " - il en manque " + goal.remaining(),
                                ChatFormatting.GRAY),
                        Icons.lore("Une contribution est definitive : elle ne peut pas etre reprise.",
                                ChatFormatting.RED)),
                Icons.label("Donner", ChatFormatting.GREEN), max, 1, max,
                qty -> confirmGive(player, id, goalIndex, (int) qty));
    }

    private static void confirmGive(ServerPlayer player, String id, int goalIndex, int qty) {
        ChantierData.Chantier chantier = ChantierData.get(player.server).get(id);
        if (chantier == null || goalIndex >= chantier.goals.size()) {
            openChantier(player, id);
            return;
        }
        ChantierData.Goal goal = chantier.goals.get(goalIndex);
        Component title = Component.literal("Confirmer le don")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal(qty + " x " + goal.display)
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                Component.literal("Ce don est definitif et ne peut pas etre repris.")
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                Icons.label("Confirmer", ChatFormatting.GREEN),
                Icons.lore("Offrir " + qty + " " + goal.display + " au chantier", ChatFormatting.GRAY),
                sp -> {
                    ChantierManager.Deposit d = ChantierManager.deposit(sp, chantier, goal, qty);
                    switch (d.result()) {
                        case CLOSED -> sp.sendSystemMessage(Messages.warn("Ce chantier n'accepte plus de dons."));
                        case ALREADY_DONE -> sp.sendSystemMessage(Messages.warn("Objectif deja atteint."));
                        case NONE_OWNED -> sp.sendSystemMessage(Messages.warn("Tu n'as plus cet objet."));
                        case INVALID -> sp.sendSystemMessage(Messages.warn("Don impossible."));
                        default -> {
                            sp.sendSystemMessage(Messages.success("Merci ! Tu as donne " + d.amount()
                                    + " x " + goal.display + " au chantier " + chantier.name + "."));
                            if (d.goalCompleted()) {
                                sp.sendSystemMessage(Messages.success("Objectif atteint : " + goal.display
                                        + " " + goal.required + " / " + goal.required + " !"));
                            }
                        }
                    }
                    openChantier(sp, id);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                Icons.label("Annuler", ChatFormatting.RED),
                Icons.lore("Revenir au chantier", ChatFormatting.GRAY),
                sp -> openChantier(sp, id)));

        OwoMenuServer.openHub(player, title, stats, entries, null, sp -> openChantier(sp, id));
    }

    // ==============================================================================================
    //  Registre et classement
    // ==============================================================================================

    public static void openRegistry(ServerPlayer player, String id, int page) {
        ChantierData.Chantier chantier = ChantierData.get(player.server).get(id);
        if (chantier == null) {
            return;
        }
        List<Map.Entry<UUID, Integer>> ranking = chantier.ranking();
        Component title = Component.literal("Registre - " + chantier.name)
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Participants", ChatFormatting.GRAY),
                Icons.label(ranking.size() + " joueur(s)", ChatFormatting.AQUA),
                null, null));
        int rank = 1;
        for (Map.Entry<UUID, Integer> e : ranking) {
            String who = chantier.nameOf(e.getKey());
            int position = rank++;
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("#" + position + " " + who,
                            position <= 3 ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    Icons.label(e.getValue() + " items", ChatFormatting.AQUA),
                    Icons.label("Detail", ChatFormatting.YELLOW),
                    sp -> openPlayerDetail(sp, id, e.getKey())));
        }
        if (ranking.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucune contribution", ChatFormatting.GRAY),
                    Icons.label("", ChatFormatting.WHITE), null, null));
        }

        OwoMenuServer.openPanel(player, title, rows, List.of(),
                sp -> openRegistry(sp, id, page), sp -> openChantier(sp, id));
    }

    /** Detail des dons d'un joueur sur ce chantier. */
    public static void openPlayerDetail(ServerPlayer player, String id, UUID target) {
        ChantierData.Chantier chantier = ChantierData.get(player.server).get(id);
        if (chantier == null) {
            return;
        }
        Component title = Component.literal(chantier.nameOf(target))
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        List<ChantierData.Contribution> mine = new ArrayList<>();
        for (ChantierData.Contribution c : chantier.log) {
            if (c.player().equals(target)) {
                mine.add(c);
            }
        }
        java.util.Collections.reverse(mine);
        for (ChantierData.Contribution c : mine.subList(0, Math.min(20, mine.size()))) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(STAMP.format(Instant.ofEpochMilli(c.millis())), ChatFormatting.DARK_GRAY),
                    Icons.label(c.amount() + " x " + c.item(), ChatFormatting.WHITE),
                    null, null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucun don enregistre", ChatFormatting.GRAY),
                    Icons.label("", ChatFormatting.WHITE), null, null));
        }
        OwoMenuServer.openPanel(player, title, rows, List.of(),
                sp -> openPlayerDetail(sp, id, target), sp -> openRegistry(sp, id, 0));
    }

    // ==============================================================================================
    //  Cote administration
    // ==============================================================================================

    public static void openAdmin(ServerPlayer admin) {
        openAdmin(admin, 0);
    }

    public static void openAdmin(ServerPlayer admin, int page) {
        ChantierData data = ChantierData.get(admin.server);
        Component title = Component.literal("CHANTIERS")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(Component.literal(data.all().size() + " chantier(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.SCAFFOLDING),
                Icons.label("Nouveau chantier", ChatFormatting.GREEN),
                Icons.lore("Cree le chantier et place son PNJ a ta position", ChatFormatting.GRAY),
                ChantierMenus::promptCreate));
        for (ChantierData.Chantier chantier : data.all()) {
            String id = chantier.id;
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BRICKS),
                    Icons.label(chantier.name, ChatFormatting.AQUA),
                    Icons.lore(chantier.state.label() + " - " + chantier.goals.size() + " objectif(s)",
                            chantier.state == ChantierData.State.COLLECTE
                                    ? ChatFormatting.GRAY : ChatFormatting.GREEN),
                    sp -> openAdminChantier(sp, id)));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                ChantierMenus::openAdmin, com.utopia.menu.AdminMenu::open);
    }

    private static void promptCreate(ServerPlayer admin) {
        Menus.promptFreeText(admin, Icons.label("Nom du chantier", ChatFormatting.GOLD),
                List.of(Icons.lore("Ex : le pont, l'eglise...", ChatFormatting.GRAY)),
                Icons.label("Creer ici", ChatFormatting.GREEN), "", 32,
                name -> {
                    if (name == null || name.isBlank()) {
                        admin.sendSystemMessage(Messages.warn("Nom vide."));
                        openAdmin(admin);
                        return;
                    }
                    ChantierData data = ChantierData.get(admin.server);
                    if (data.exists(name)) {
                        admin.sendSystemMessage(Messages.warn("Un chantier porte deja ce nom."));
                        openAdmin(admin);
                        return;
                    }
                    ChantierData.Chantier chantier = data.create(name);
                    place(admin, chantier);
                    data.setDirty();
                    ChantierManager.sync(admin.server);
                    admin.sendSystemMessage(Messages.success("Chantier \"" + chantier.name
                            + "\" cree. Ajoute son texte de presentation et ses objectifs."));
                    openAdminChantier(admin, chantier.id);
                });
    }

    private static void place(ServerPlayer admin, ChantierData.Chantier chantier) {
        chantier.dim = admin.level().dimension().location().toString();
        chantier.x = admin.getX();
        chantier.y = admin.getY();
        chantier.z = admin.getZ();
        chantier.restYaw = admin.getYRot();
    }

    public static void openAdminChantier(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        Component title = Component.literal(chantier.name + " (admin)")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                Icons.label(chantier.state.label(),
                        chantier.state == ChantierData.State.COLLECTE ? ChatFormatting.YELLOW : ChatFormatting.GREEN),
                Icons.label(chantier.state == ChantierData.State.REUNIES ? "-> Termine" : "Changer",
                        ChatFormatting.YELLOW),
                sp -> openState(sp, id)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Objectifs", ChatFormatting.GRAY),
                Icons.label(chantier.goals.size() + " item(s)", ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openGoals(sp, id)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Presentation", ChatFormatting.GRAY),
                Icons.label(chantier.presentation == null || chantier.presentation.isBlank()
                        ? "vide" : "definie", ChatFormatting.AQUA),
                Icons.label("Ecrire", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Texte de presentation", ChatFormatting.GOLD),
                        List.of(Icons.lore("Affiche en haut de l'interface du chantier", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        chantier.presentation == null ? "" : chantier.presentation, 256,
                        text -> {
                            chantier.presentation = text == null ? "" : text.trim();
                            data.setDirty();
                            openAdminChantier(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom du PNJ", ChatFormatting.GRAY),
                Icons.label(chantier.npcName, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom du PNJ", ChatFormatting.GOLD), List.of(),
                        Icons.label("Valider", ChatFormatting.GREEN), chantier.npcName, 32,
                        n -> {
                            if (n != null && !n.isBlank()) {
                                chantier.npcName = n.trim();
                                data.setDirty();
                                ChantierManager.sync(sp.server);
                            }
                            openAdminChantier(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Skin du PNJ", ChatFormatting.GRAY),
                Icons.label(skinLabel(chantier), ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.YELLOW),
                sp -> openSkin(sp, id, "", 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Emplacement", ChatFormatting.GRAY),
                Icons.label(chantier.isPlaced()
                        ? String.format("%.0f %.0f %.0f", chantier.x, chantier.y, chantier.z) : "non place",
                        chantier.isPlaced() ? ChatFormatting.AQUA : ChatFormatting.RED),
                Icons.label("Ici", ChatFormatting.GREEN),
                sp -> {
                    place(sp, chantier);
                    data.setDirty();
                    ChantierManager.sync(sp.server);
                    sp.sendSystemMessage(Messages.success("PNJ deplace ici "
                            + "(progression et registre conserves)."));
                    openAdminChantier(sp, id);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("PNJ", ChatFormatting.GRAY),
                Icons.label(chantier.npcEnabled ? "visible" : "masque",
                        chantier.npcEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label(chantier.npcEnabled ? "Masquer" : "Afficher", ChatFormatting.YELLOW),
                sp -> {
                    chantier.npcEnabled = !chantier.npcEnabled;
                    data.setDirty();
                    ChantierManager.sync(sp.server);
                    openAdminChantier(sp, id);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Hologramme Top 3", ChatFormatting.GRAY),
                Icons.label(chantier.hologram ? "affiche" : "masque",
                        chantier.hologram ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                Icons.label(chantier.hologram ? "Masquer" : "Afficher", ChatFormatting.YELLOW),
                sp -> {
                    chantier.hologram = !chantier.hologram;
                    data.setDirty();
                    ChantierManager.sync(sp.server);
                    openAdminChantier(sp, id);
                }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Voir la vitrine", ChatFormatting.AQUA),
                        sp -> openChantier(sp, id)),
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> confirmDelete(sp, id)));

        OwoMenuServer.openPanel(admin, title, rows, footer,
                sp -> openAdminChantier(sp, id), ChantierMenus::openAdmin);
    }

    private static String skinLabel(ChantierData.Chantier chantier) {
        if (chantier.npcSkinValue == null || chantier.npcSkinValue.isEmpty()) {
            return "Steve";
        }
        String packed = com.utopia.entity.NpcSkins.nameOf(chantier.npcSkinValue);
        return packed == null ? "personnalise" : com.utopia.entity.NpcSkins.label(packed);
    }

    /** Changement d'etat : le passage en "Chantier termine" reste toujours manuel. */
    private static void openState(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        Component title = Component.literal("Etat - " + chantier.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Actuel : " + chantier.state.label())
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                Component.literal("Le passage en \"Chantier termine\" n'est jamais automatique.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ChantierData.State state : ChantierData.State.values()) {
            boolean current = chantier.state == state;
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(current ? Items.LIME_DYE : Items.PAPER),
                    Icons.label(state.label(), current ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(current ? "Etat actuel" : "Clic pour appliquer",
                            current ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    sp -> {
                        chantier.state = state;
                        data.setDirty();
                        sp.sendSystemMessage(Messages.success("Chantier \"" + chantier.name
                                + "\" : " + state.label() + "."));
                        openAdminChantier(sp, id);
                    }));
        }

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openState(sp, id), sp -> openAdminChantier(sp, id));
    }

    private static void confirmDelete(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        Component title = Component.literal("Supprimer " + chantier.name + " ?")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true));
        List<Component> stats = List.of(Component.literal(
                        "Le PNJ, l'hologramme, la progression et le registre seront perdus definitivement.")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                Icons.label("Oui, supprimer", ChatFormatting.RED),
                Icons.lore("Action irreversible", ChatFormatting.GRAY),
                sp -> {
                    ChantierManager.removeEntities(sp.server, id);
                    data.remove(id);
                    sp.sendSystemMessage(Messages.success("Chantier supprime."));
                    openAdmin(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                Icons.label("Non, annuler", ChatFormatting.GREEN),
                Icons.lore("Revenir a la configuration", ChatFormatting.GRAY),
                sp -> openAdminChantier(sp, id)));

        OwoMenuServer.openHub(admin, title, stats, entries, null, sp -> openAdminChantier(sp, id));
    }

    // -------- Objectifs --------

    public static void openGoals(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        Component title = Component.literal("Objectifs - " + chantier.name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        // L'objectif en Utopieces se pose d'un clic, sans avoir a sortir une piece de sa poche : c'est
        // le plus courant, il ouvre donc la liste.
        boolean hasCoinGoal = chantier.goals.stream().anyMatch(ChantierManager::isCoinGoal);
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Objectif en Utopieces", ChatFormatting.GOLD),
                Icons.label(hasCoinGoal ? "deja pose" : "aucun pour l'instant",
                        hasCoinGoal ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                hasCoinGoal ? null : Icons.label("Ajouter", ChatFormatting.GREEN),
                hasCoinGoal ? null : sp -> promptAddCoinGoal(sp, id)));
        for (int i = 0; i < chantier.goals.size(); i++) {
            final int index = i;
            ChantierData.Goal goal = chantier.goals.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(goal.display + (goal.highlight ? " (en avant)" : ""),
                            goal.done() ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.label(goal.current + " / " + goal.required, ChatFormatting.GOLD),
                    Icons.label("Regler", ChatFormatting.YELLOW),
                    sp -> openGoal(sp, id, index)));
        }
        if (chantier.goals.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucun autre objectif", ChatFormatting.GRAY),
                    Icons.label("tiens un item en main puis Ajouter", ChatFormatting.DARK_GRAY), null, null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Ajouter (item en main)", ChatFormatting.GREEN),
                        sp -> promptAddGoal(sp, id)));

        OwoMenuServer.openPanel(admin, title, rows, footer,
                sp -> openGoals(sp, id), sp -> openAdminChantier(sp, id));
    }

    /**
     * Ajoute l'objectif en Utopieces sans passer par l'item tenu en main : c'est l'objectif que porte
     * presque tout chantier, il est mis en avant dans la vitrine et sa cagnotte revient a la mairie.
     */
    private static void promptAddCoinGoal(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        if (chantier.goals.stream().anyMatch(ChantierManager::isCoinGoal)) {
            admin.sendSystemMessage(Messages.warn("Ce chantier a deja un objectif en Utopieces."));
            openGoals(admin, id);
            return;
        }
        ItemStack model = new ItemStack(ChantierManager.coinItem());
        String display = model.getHoverName().getString();
        Menus.promptAmount(admin, Icons.label("Utopieces demandees", ChatFormatting.GOLD),
                List.of(Icons.lore("Total a reunir par l'ensemble du serveur", ChatFormatting.GRAY),
                        Icons.lore("Les pieces versees rejoignent la caisse de la mairie",
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Ajouter", ChatFormatting.GREEN), 1_000, 1, 1_000_000L,
                required -> {
                    ChantierData d = ChantierData.get(admin.server);
                    ChantierData.Chantier c = d.get(id);
                    if (c == null || c.goals.stream().anyMatch(ChantierManager::isCoinGoal)) {
                        openGoals(admin, id);
                        return;
                    }
                    // En tete de la liste : la vitrine joueur montre les Utopieces en premier.
                    c.goals.add(0, new ChantierData.Goal(model, display, (int) required, true));
                    d.setDirty();
                    admin.sendSystemMessage(Messages.success("Objectif ajoute : " + required + " Utopieces."));
                    openGoals(admin, id);
                });
    }

    /** Ajoute l'item tenu en main comme objectif : sa version exacte sert de reference. */
    private static void promptAddGoal(ServerPlayer admin, String id) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        ItemStack held = admin.getMainHandItem();
        if (held.isEmpty()) {
            admin.sendSystemMessage(Messages.warn("Tiens en main l'item demande, puis reessaie."));
            openGoals(admin, id);
            return;
        }
        ItemStack model = held.copyWithCount(1);
        String display = model.getHoverName().getString();
        Menus.promptAmount(admin, Icons.label("Quantite demandee : " + display, ChatFormatting.GOLD),
                List.of(Icons.lore("Total a reunir par l'ensemble du serveur", ChatFormatting.GRAY)),
                Icons.label("Ajouter", ChatFormatting.GREEN), 100, 1, 1_000_000L,
                required -> {
                    boolean coin = model.is(ChantierManager.coinItem());
                    ChantierData.Goal goal = new ChantierData.Goal(model, display, (int) required, coin);
                    chantier.goals.add(goal);
                    data.setDirty();
                    admin.sendSystemMessage(Messages.success("Objectif ajoute : " + required + " x " + display
                            + (coin ? " (mis en avant : Utopieces)" : "")));
                    openGoals(admin, id);
                });
    }

    public static void openGoal(ServerPlayer admin, String id, int index) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null || index < 0 || index >= chantier.goals.size()) {
            openGoals(admin, id);
            return;
        }
        ChantierData.Goal goal = chantier.goals.get(index);
        Component title = Component.literal(goal.display)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Quantite demandee", ChatFormatting.GRAY),
                Icons.label(String.valueOf(goal.required), ChatFormatting.GOLD),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Quantite demandee", ChatFormatting.GOLD),
                        List.of(Icons.lore("Deja reuni : " + goal.current, ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), goal.required, 1, 1_000_000L,
                        v -> {
                            goal.required = (int) v;
                            data.setDirty();
                            openGoal(sp, id, index);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Deja reuni", ChatFormatting.GRAY),
                Icons.label(goal.current + " (" + goal.percent() + " %)", ChatFormatting.AQUA),
                Icons.label("Corriger", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Progression", ChatFormatting.GOLD),
                        List.of(Icons.lore("Corrige la quantite deja reunie", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), goal.current, 0, 1_000_000L,
                        v -> {
                            goal.current = (int) v;
                            data.setDirty();
                            openGoal(sp, id, index);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom affiche", ChatFormatting.GRAY),
                Icons.label(goal.display, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom affiche", ChatFormatting.GOLD), List.of(),
                        Icons.label("Valider", ChatFormatting.GREEN), goal.display, 32,
                        n -> {
                            if (n != null && !n.isBlank()) {
                                goal.display = n.trim();
                                data.setDirty();
                            }
                            openGoal(sp, id, index);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Mise en avant", ChatFormatting.GRAY),
                Icons.label(goal.highlight ? "oui (grande barre)" : "non",
                        goal.highlight ? ChatFormatting.GOLD : ChatFormatting.GRAY),
                Icons.label("Basculer", ChatFormatting.YELLOW),
                sp -> {
                    goal.highlight = !goal.highlight;
                    data.setDirty();
                    openGoal(sp, id, index);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Position", ChatFormatting.GRAY),
                Icons.label((index + 1) + " / " + chantier.goals.size(), ChatFormatting.AQUA),
                Icons.label("Monter", ChatFormatting.YELLOW),
                sp -> {
                    if (index > 0) {
                        chantier.goals.add(index - 1, chantier.goals.remove(index));
                        data.setDirty();
                        openGoal(sp, id, index - 1);
                    } else {
                        openGoal(sp, id, index);
                    }
                }));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Retirer l'objectif", ChatFormatting.RED),
                        sp -> {
                            chantier.goals.remove(index);
                            data.setDirty();
                            sp.sendSystemMessage(Messages.info("Objectif retire."));
                            openGoals(sp, id);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer,
                sp -> openGoal(sp, id, index), sp -> openGoals(sp, id));
    }

    // -------- Skin du PNJ --------

    public static void openSkin(ServerPlayer admin, String id, String query, int page) {
        ChantierData data = ChantierData.get(admin.server);
        ChantierData.Chantier chantier = data.get(id);
        if (chantier == null) {
            openAdmin(admin);
            return;
        }
        List<String> found = com.utopia.entity.NpcSkins.search(query);
        String currentName = com.utopia.entity.NpcSkins.nameOf(chantier.npcSkinValue);

        Component title = Component.literal("Skin - " + chantier.npcName)
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true));
        List<Component> stats = List.of(
                Component.literal(query.isBlank() ? found.size() + " skins disponibles"
                                : found.size() + " resultat(s) pour \"" + query + "\"")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Clique un skin : il s'applique aussitot sur le PNJ.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Rechercher...", ChatFormatting.YELLOW),
                Icons.lore(query.isBlank() ? "Filtrer par nom" : "Filtre : " + query, ChatFormatting.GRAY),
                sp -> Menus.promptFreeText(sp, Icons.label("Rechercher un skin", ChatFormatting.GOLD),
                        List.of(Icons.lore("Laisse vide pour tout afficher", ChatFormatting.GRAY)),
                        Icons.label("Chercher", ChatFormatting.GREEN), query, 32,
                        q -> openSkin(sp, id, q == null ? "" : q, 0))));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                Icons.label("Steve (defaut)", ChatFormatting.GRAY),
                Icons.lore("Retire le skin personnalise", ChatFormatting.GRAY),
                sp -> {
                    chantier.npcSkinValue = "";
                    chantier.npcSkinSignature = "";
                    data.setDirty();
                    ChantierManager.sync(sp.server);
                    openSkin(sp, id, query, page);
                }));
        for (String skin : found) {
            boolean isCurrent = skin.equals(currentName);
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(isCurrent ? Items.LIME_DYE : Items.PLAYER_HEAD),
                    Icons.label(com.utopia.entity.NpcSkins.label(skin),
                            isCurrent ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(isCurrent ? "Skin actuel" : "Clic : essayer", ChatFormatting.GRAY),
                    sp -> {
                        chantier.npcSkinValue = com.utopia.entity.NpcSkins.value(skin);
                        chantier.npcSkinSignature = "";
                        data.setDirty();
                        ChantierManager.sync(sp.server);
                        openSkin(sp, id, query, page);
                    }));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openSkin(sp, id, query, p), sp -> openAdminChantier(sp, id));
    }
}
