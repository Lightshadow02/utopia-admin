package com.utopia.election;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.ElectionData;
import com.utopia.data.ElectionData.Election;
import com.utopia.data.ElectionData.Status;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menus du systeme d'elections : sous-menu admin (/admin -> Elections) et menu de vote (/vote). */
public final class ElectionMenus {

    private ElectionMenus() {
    }

    // ============================================================
    //  Sous-menu admin
    // ============================================================

    public static void openAdminMenu(ServerPlayer player) {
        MinecraftServer server = player.server;
        ElectionData data = ElectionData.get(server);
        Election el = data.current();

        Component title = Component.literal("ELECTIONS")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal(stateLabel(el)).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
        stats.add(Component.literal(data.holoConfigured()
                ? "Hologramme : configure" : "Hologramme : NON configure")
                .withStyle(s -> s.withColor(data.holoConfigured() ? ChatFormatting.GREEN : ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();

        if (el == null || el.status == Status.CLOSED || el.status == Status.CANCELLED) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                    Icons.label("Creer une election", ChatFormatting.GREEN),
                    Icons.lore("Nom + duree, puis ajout des candidats", ChatFormatting.GRAY),
                    ElectionMenus::promptCreate));
        }
        if (el != null && el.status == Status.SETUP) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                    Icons.label("Gerer les candidats (" + el.candidates.size() + ")", ChatFormatting.AQUA),
                    Icons.lore("Ajouter / retirer (avant le lancement)", ChatFormatting.GRAY),
                    ElectionMenus::openCandidates));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                    Icons.label("Lancer l'election", ChatFormatting.GREEN),
                    Icons.lore("Verrouille les candidats et ouvre le vote (irreversible)", ChatFormatting.GRAY),
                    ElectionMenus::doStart));
        }
        if (el != null && el.status == Status.OPEN) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BOOK),
                    Icons.label("Etat du vote", ChatFormatting.YELLOW),
                    Icons.lore("Temps restant, votants, scores", ChatFormatting.GRAY),
                    ElectionMenus::openState));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.REDSTONE_BLOCK),
                    Icons.label("Forcer la cloture", ChatFormatting.GOLD),
                    Icons.lore("Termine le vote maintenant + ceremonie des resultats", ChatFormatting.GRAY),
                    sp -> {
                        ElectionManager.close(sp.server);
                        sp.sendSystemMessage(Messages.success("Election cloturee : ceremonie lancee."));
                        openAdminMenu(sp);
                    }));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                    Icons.label("Annuler (sans resultat)", ChatFormatting.RED),
                    Icons.lore("Clot le vote sans ceremonie ni gagnant", ChatFormatting.GRAY),
                    sp -> {
                        ElectionManager.cancel(sp.server);
                        sp.sendSystemMessage(Messages.warn("Election annulee."));
                        openAdminMenu(sp);
                    }));
        }

        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_EYE),
                Icons.label("Configurer l'hologramme", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Capture TA position comme emplacement des resultats", ChatFormatting.GRAY),
                ElectionMenus::captureHologram));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.FIREWORK_ROCKET),
                Icons.label("Tester l'affichage", ChatFormatting.AQUA),
                Icons.lore("Apercu hologramme / resultats fictifs / feux / ceremonie", ChatFormatting.GRAY),
                ElectionMenus::openTest));

        OwoMenuServer.openHub(player, title, stats, entries, ElectionMenus::openAdminMenu,
                com.utopia.menu.AdminMenu::open);
    }

    private static String stateLabel(Election el) {
        if (el == null) {
            return "Aucune election";
        }
        return switch (el.status) {
            case SETUP -> "Config : \"" + el.name + "\" (" + el.candidates.size() + " candidats)";
            case OPEN -> "EN COURS : \"" + el.name + "\" - " + el.votes.size() + " votant(s) - reste "
                    + Messages.formatDuration(Math.max(0, el.endMillis - System.currentTimeMillis()) / 1000);
            case CLOSED -> "Terminee : \"" + el.name + "\"";
            case CANCELLED -> "Annulee : \"" + el.name + "\"";
        };
    }

    private static void promptCreate(ServerPlayer player) {
        Menus.promptText(player, Icons.label("Nom de l'election", ChatFormatting.GOLD),
                List.of(Icons.lore("Ex : Election du Maire d'Aria", ChatFormatting.GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), "", 48,
                name -> {
                    if (name == null || name.isBlank()) {
                        player.sendSystemMessage(Messages.warn("Nom vide : creation annulee."));
                        openAdminMenu(player);
                        return;
                    }
                    Menus.promptAmount(player, Icons.label("Duree du vote (minutes)", ChatFormatting.GOLD),
                            List.of(Icons.lore("Minimum 1 min - defaut 1440 (24 h)", ChatFormatting.GRAY)),
                            Icons.label("Creer", ChatFormatting.GREEN),
                            ElectionManager.DEFAULT_DURATION_MIN, ElectionManager.MIN_DURATION_MIN, 525_600L,
                            minutes -> {
                                ElectionManager.create(player.server, name.trim(), (int) minutes);
                                player.sendSystemMessage(Messages.success("Election creee : \"" + name.trim()
                                        + "\". Ajoute les candidats puis lance-la."));
                                openCandidates(player);
                            });
                });
    }

    private static void openCandidates(ServerPlayer player) {
        Election el = ElectionData.get(player.server).current();
        if (el == null || el.status != Status.SETUP) {
            openAdminMenu(player);
            return;
        }
        Component title = Component.literal("Candidats - " + el.name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (String c : el.candidates) {
            final String cand = c;
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(c, ChatFormatting.WHITE),
                    Icons.label("", ChatFormatting.GRAY),
                    Icons.label("Retirer", ChatFormatting.RED),
                    sp -> {
                        ElectionManager.removeCandidate(sp.server, cand);
                        openCandidates(sp);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucun candidat", ChatFormatting.GRAY),
                    Icons.label("ajoutes-en", ChatFormatting.DARK_GRAY), null, null));
        }
        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Ajouter un candidat", ChatFormatting.GREEN),
                sp -> Menus.promptText(sp, Icons.label("Nom du candidat", ChatFormatting.GOLD), List.of(),
                        Icons.label("Ajouter", ChatFormatting.GREEN), "", 32,
                        cn -> {
                            if (!ElectionManager.addCandidate(sp.server, cn)) {
                                sp.sendSystemMessage(Messages.warn("Candidat invalide ou deja present."));
                            }
                            openCandidates(sp);
                        })));
        OwoMenuServer.openPanel(player, title, rows, footer, ElectionMenus::openCandidates, ElectionMenus::openAdminMenu);
    }

    private static void doStart(ServerPlayer player) {
        ElectionManager.StartResult r = ElectionManager.start(player.server);
        switch (r) {
            case NOT_ENOUGH_CANDIDATES -> player.sendSystemMessage(Messages.warn("Il faut au moins 2 candidats."));
            case NO_ELECTION, NOT_SETUP -> player.sendSystemMessage(Messages.warn("Aucune election a lancer."));
            default -> player.sendSystemMessage(Messages.success("Election lancee ! Le vote est ouvert."));
        }
        openAdminMenu(player);
    }

    private static void openState(ServerPlayer player) {
        Election el = ElectionData.get(player.server).current();
        if (el == null) {
            openAdminMenu(player);
            return;
        }
        Component title = Component.literal("Etat - " + el.name)
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(Icons.label("Temps restant", ChatFormatting.GRAY),
                Icons.label(Messages.formatDuration(Math.max(0, el.endMillis - System.currentTimeMillis()) / 1000),
                        ChatFormatting.AQUA), null, null));
        rows.add(new OwoMenuServer.PanelRow(Icons.label("Votants", ChatFormatting.GRAY),
                Icons.label(String.valueOf(el.votes.size()), ChatFormatting.AQUA), null, null));
        List<ElectionManager.Scored> scored = ElectionManager.scores(el);
        for (int i = scored.size() - 1; i >= 0; i--) { // du plus haut au plus bas pour la lecture admin
            ElectionManager.Scored sc = scored.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(sc.name(), ChatFormatting.WHITE),
                    Icons.label(sc.votes() + " voix (" + sc.percent() + "%)", ChatFormatting.GOLD),
                    null, null));
        }
        OwoMenuServer.openPanel(player, title, rows, List.of(), ElectionMenus::openState, ElectionMenus::openAdminMenu);
    }

    private static void captureHologram(ServerPlayer player) {
        ElectionData.get(player.server).setHologram(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ());
        ElectionManager.previewEmpty(player.server);
        player.sendSystemMessage(Messages.success("Position de l'hologramme capturee a ta position (apercu spawne)."));
        openAdminMenu(player);
    }

    private static void openTest(ServerPlayer player) {
        Component title = Component.literal("Tester l'affichage")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(Component.literal(
                ElectionData.get(player.server).holoConfigured()
                        ? "Visible par les admins uniquement"
                        : "Configure d'abord l'hologramme !")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(testEntry(Items.ITEM_FRAME, "Preview hologramme vide",
                "Place l'hologramme avec un placeholder",
                sp -> warnIfNoHolo(sp, ElectionManager.previewEmpty(sp.server))));
        entries.add(testEntry(Items.PAPER, "Preview resultats fictifs",
                "Alice 52% / Bob 31% / Charlie 17%",
                sp -> warnIfNoHolo(sp, ElectionManager.previewFakeResults(sp.server))));
        entries.add(testEntry(Items.FIREWORK_ROCKET, "Preview feux d'artifice",
                "Salve autour de la position",
                sp -> {
                    ElectionManager.previewFireworks(sp.server);
                    sp.sendSystemMessage(Messages.info("Feux d'artifice lances."));
                }));
        entries.add(testEntry(Items.GOLD_BLOCK, "Preview ceremonie complete",
                "Hologramme + chat (admins) + feux",
                sp -> warnIfNoHolo(sp, ElectionManager.previewFull(sp.server))));
        entries.add(testEntry(Items.BARRIER, "Supprimer le preview",
                "Retire l'hologramme de test",
                sp -> {
                    ElectionManager.removePreview(sp.server);
                    sp.sendSystemMessage(Messages.success("Preview supprime."));
                }));

        OwoMenuServer.openHub(player, title, stats, entries, ElectionMenus::openTest, ElectionMenus::openAdminMenu);
    }

    private static OwoMenuServer.HubEntry testEntry(net.minecraft.world.item.Item icon, String label, String lore,
                                                    java.util.function.Consumer<ServerPlayer> action) {
        return new OwoMenuServer.HubEntry(new ItemStack(icon),
                Icons.label(label, ChatFormatting.WHITE), Icons.lore(lore, ChatFormatting.GRAY), action);
    }

    private static void warnIfNoHolo(ServerPlayer player, boolean ok) {
        player.sendSystemMessage(ok
                ? Messages.success("Apercu spawne a la position configuree.")
                : Messages.warn("Configure d'abord l'hologramme (Configurer l'hologramme)."));
    }

    // ============================================================
    //  Menu de vote (/vote)
    // ============================================================

    public static void openVote(ServerPlayer player) {
        Election el = ElectionData.get(player.server).current();
        if (el == null || el.status != Status.OPEN) {
            player.sendSystemMessage(Messages.info("Aucune election en cours."));
            return;
        }
        String myVote = el.votes.get(player.getUUID());
        Component title = Component.literal(el.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal(myVote == null ? "Tu n'as pas encore vote." : "Ton vote : " + myVote)
                        .withStyle(s -> s.withColor(myVote == null ? ChatFormatting.GRAY : ChatFormatting.GREEN).withItalic(false)),
                Component.literal("Modifiable jusqu'a la cloture - reste "
                        + Messages.formatDuration(Math.max(0, el.endMillis - System.currentTimeMillis()) / 1000))
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (String c : el.candidates) {
            final String cand = c;
            boolean mine = c.equalsIgnoreCase(myVote);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(mine ? Items.LIME_DYE : Items.PAPER),
                    Icons.label(c, mine ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(mine ? "Ton vote actuel" : "Cliquer pour voter", mine ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    sp -> {
                        ElectionManager.VoteResult r = ElectionManager.vote(sp, cand);
                        switch (r) {
                            case OK_NEW -> sp.sendSystemMessage(Messages.success("Vote enregistre pour " + cand + "."));
                            case OK_CHANGED -> sp.sendSystemMessage(Messages.success("Vote modifie : " + cand + "."));
                            case NOT_OPEN -> sp.sendSystemMessage(Messages.warn("Le vote est clos."));
                            case INVALID -> sp.sendSystemMessage(Messages.warn("Candidat invalide."));
                            default -> sp.sendSystemMessage(Messages.info("Aucune election en cours."));
                        }
                        if (r == ElectionManager.VoteResult.OK_NEW || r == ElectionManager.VoteResult.OK_CHANGED) {
                            openVote(sp);
                        } else {
                            Menus.close(sp);
                        }
                    }));
        }
        OwoMenuServer.openHub(player, title, stats, entries, ElectionMenus::openVote, null);
    }
}
