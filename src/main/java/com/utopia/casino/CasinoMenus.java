package com.utopia.casino;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.CasinoData;
import com.utopia.economy.EconomyManager;
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
 * Les ecrans de la salle d'arcade : celui du gerant (/casino) et celui que voit un joueur devant
 * une borne.
 */
public final class CasinoMenus {

    /** Bornes listees par page : le tableau tient 54 slots d'action, on garde de la marge. */
    private static final int ROW_PAGE = 40;

    private CasinoMenus() {
    }

    private static boolean denied(ServerPlayer player) {
        if (CasinoManager.canManage(player)) {
            return false;
        }
        player.sendSystemMessage(Messages.error("Reserve au gerant de la salle."));
        return true;
    }

    private static Component head(String text) {
        return Component.literal(text)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    private static Component value(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }

    // ================================================================= Joueur devant une borne

    /** Ce que voit un joueur au clic droit sur une borne : le prix, le record, et le bouton jouer. */
    public static void openMachine(ServerPlayer player, CasinoData.Machine machine) {
        CasinoManager.Game game = CasinoManager.game(machine.gameId);
        if (game == null) {
            player.sendSystemMessage(Messages.error("Cette borne est en panne (jeu inconnu)."));
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        CasinoData.Score best = data.best(game.id());
        long solde = EconomyManager.getBalance(player.server, player.getUUID());

        List<Component> stats = new ArrayList<>();
        stats.add(Icons.lore(game.desc(), ChatFormatting.GRAY));
        stats.add(Component.literal("Partie : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(machine.cost <= 0 ? "gratuite" : machine.cost + " Utopiece(s)",
                        machine.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD)));
        stats.add(best == null
                ? Icons.lore("Aucun record : la premiere partie fera le record.", ChatFormatting.DARK_GRAY)
                : Icons.lore("Record : " + best.score() + " par " + best.name(), ChatFormatting.YELLOW));
        stats.add(Icons.lore("Ton solde : " + solde + " Utopieces", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(game.icon()),
                Icons.label("Jouer", ChatFormatting.GREEN),
                Icons.lore(machine.cost <= 0 ? "Gratuit" : machine.cost + " Utopiece(s) la partie",
                        ChatFormatting.GRAY),
                sp -> {
                    // On libere la session de menu sans envoyer de fermeture : la borne remplace
                    // l'ecran de toute facon, et un ecran vide entre les deux ferait revenir
                    // l'echelle du joueur le temps d'une image.
                    com.utopia.net.OwoMenuServer.clear(sp);
                    CasinoManager.play(sp, machine);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLDEN_HELMET),
                Icons.label("Tableau des scores", ChatFormatting.GOLD),
                Icons.lore("Tous les joueurs, toutes les bornes de " + game.name(), ChatFormatting.GRAY),
                sp -> openScores(sp, game.id(), sp2 -> openMachine(sp2, machine))));
        if (CasinoManager.canManage(player)) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPARATOR),
                    Icons.label("Regler cette borne", ChatFormatting.AQUA),
                    Icons.lore("Jeu, prix, nom affiche", ChatFormatting.GRAY),
                    sp -> openMachineConfig(sp, machine.key(), sp2 -> openMachine(sp2, machine))));
        }

        String titre = machine.label == null || machine.label.isBlank()
                ? game.name() : machine.label;
        OwoMenuServer.openHub(player, Icons.screenTitle(titre, ChatFormatting.LIGHT_PURPLE),
                stats, entries, sp -> openMachine(sp, machine), null);
    }

    // ================================================================= Tableau des scores

    /** Le classement d'un jeu : commun a toutes les bornes qui le font tourner. */
    public static void openScores(ServerPlayer player, String gameId,
                                  java.util.function.Consumer<ServerPlayer> back) {
        CasinoManager.Game game = CasinoManager.game(gameId);
        if (game == null) {
            back.accept(player);
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        List<CasinoData.Score> rows = data.scores(gameId);

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("#"), 24, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("JOUEUR"), 140, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("SCORE"), 70, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> table = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            CasinoData.Score s = rows.get(i);
            int rank = i + 1;
            boolean moi = s.player().equals(player.getUUID());
            table.add(new OwoMenuServer.TableRow(
                    List.of(value(String.valueOf(rank), rankColor(rank)),
                            value(s.name() == null || s.name().isBlank() ? "?" : s.name(),
                                    moi ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                            value(String.valueOf(s.score()), ChatFormatting.GOLD)),
                    null));
        }
        if (table.isEmpty()) {
            table.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Personne n'a encore joue.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty()),
                    null));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (CasinoManager.canManage(player) && !rows.isEmpty()) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Remettre le tableau a zero", ChatFormatting.RED),
                    sp -> OwoMenuServer.openConfirm(sp,
                            Icons.title("Effacer les scores de " + game.name() + " ?", ChatFormatting.RED),
                            List.of(Icons.lore("Tous les records de ce jeu sont perdus.", ChatFormatting.GRAY)),
                            Icons.label("Effacer", ChatFormatting.RED),
                            s2 -> {
                                CasinoData.get(s2.server).clearScores(gameId);
                                s2.sendSystemMessage(Messages.success("Tableau remis a zero."));
                                openScores(s2, gameId, back);
                            },
                            s2 -> openScores(s2, gameId, back))));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle(game.name(), ChatFormatting.GOLD),
                List.of(Icons.lore("Classement commun a toutes les bornes de " + game.name() + ".",
                        ChatFormatting.GRAY)),
                List.of(), columns, table, footer, null, null,
                sp -> openScores(sp, gameId, back), back);
    }

    private static ChatFormatting rankColor(int rank) {
        return switch (rank) {
            case 1 -> ChatFormatting.GOLD;
            case 2 -> ChatFormatting.WHITE;
            case 3 -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.GRAY;
        };
    }

    // ================================================================= Gerant (/casino)

    public static void open(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        CasinoData data = CasinoData.get(player.server);

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Bornes posees : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(String.valueOf(data.machines().size()), ChatFormatting.GOLD)));
        stats.add(Icons.lore("Les recettes vont " + (com.utopia.Config.CASINO_REVENUE_TO_MAIRIE.get()
                ? "a la caisse de la mairie." : "au neant : elles sont detruites."),
                ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CRAFTING_TABLE),
                Icons.label("Poser une borne", ChatFormatting.GREEN),
                Icons.lore("Choisis un jeu, puis casse le bloc qui deviendra la borne", ChatFormatting.GRAY),
                CasinoMenus::openGamePicker));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.REPEATER),
                Icons.label("Les bornes", ChatFormatting.AQUA),
                Icons.lore(data.machines().size() + " borne(s) - regler, retrouver, retirer",
                        ChatFormatting.GRAY),
                sp -> openMachines(sp, 0)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLDEN_HELMET),
                Icons.label("Tableaux des scores", ChatFormatting.GOLD),
                Icons.lore("Un classement par jeu, commun a toutes les bornes", ChatFormatting.GRAY),
                CasinoMenus::openScoreBoards));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Gerants", ChatFormatting.YELLOW),
                Icons.lore(data.managers().size() + " gerant(s) - ils ouvrent /casino sans etre op",
                        ChatFormatting.GRAY),
                sp -> openManagers(sp, 0)));

        OwoMenuServer.openHub(player, Icons.screenTitle("Salle d'arcade", ChatFormatting.LIGHT_PURPLE),
                stats, entries, CasinoMenus::open, null);
    }

    /** Choix du jeu, puis passage en mode pose : le prochain bloc casse devient la borne. */
    private static void openGamePicker(ServerPlayer player) {
        if (denied(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (CasinoManager.Game game : CasinoManager.GAMES) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(game.icon()),
                    Icons.label(game.name(), ChatFormatting.WHITE),
                    Icons.lore(game.desc(), ChatFormatting.GRAY),
                    sp -> {
                        CasinoManager.startPlacing(sp.getUUID(), game.id());
                        sp.sendSystemMessage(Messages.info(
                                "Mode actif : casse le bloc qui deviendra la borne de " + game.name() + "."));
                        Menus.close(sp);
                    }));
        }
        OwoMenuServer.openHub(player, Icons.screenTitle("Quel jeu ?", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Le bloc que tu casseras ensuite deviendra la borne.", ChatFormatting.GRAY),
                        Icons.lore("N'importe quel bloc convient : les joueurs feront clic droit dessus.",
                                ChatFormatting.DARK_GRAY)),
                entries, CasinoMenus::openGamePicker, CasinoMenus::open);
    }

    /** La liste des bornes posees, avec de quoi les retrouver et les regler. */
    public static void openMachines(ServerPlayer player, int page) {
        if (denied(player)) {
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        List<CasinoData.Machine> all = new ArrayList<>(data.machines());
        int totalPages = Math.max(1, (all.size() + ROW_PAGE - 1) / ROW_PAGE);
        final int cur = Math.max(0, Math.min(page, totalPages - 1));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("BORNE"), 104, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("JEU"), 76, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("PRIX"), 48, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("POSITION"), 108, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        int from = cur * ROW_PAGE;
        int to = Math.min(all.size(), from + ROW_PAGE);
        for (int i = from; i < to; i++) {
            CasinoData.Machine m = all.get(i);
            CasinoManager.Game g = CasinoManager.game(m.gameId);
            rows.add(new OwoMenuServer.TableRow(
                    new ItemStack(g == null ? Items.BARRIER : g.icon()),
                    List.of(value(m.label == null || m.label.isBlank()
                                    ? (g == null ? m.gameId : g.name()) : m.label, ChatFormatting.WHITE),
                            value(g == null ? "inconnu" : g.name(),
                                    g == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                            value(m.cost <= 0 ? "gratuit" : String.valueOf(m.cost),
                                    m.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                            value(m.x + " " + m.y + " " + m.z, ChatFormatting.DARK_GRAY)),
                    sp -> openMachineConfig(sp, m.key(), sp2 -> openMachines(sp2, cur))));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucune borne posee.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle("Les bornes", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Clique une borne pour la regler.", ChatFormatting.GRAY)),
                List.of(), columns, rows, List.of(),
                cur > 0 ? sp -> openMachines(sp, cur - 1) : null,
                cur < totalPages - 1 ? sp -> openMachines(sp, cur + 1) : null,
                sp -> openMachines(sp, cur), CasinoMenus::open);
    }

    /** Reglages d'une borne : le jeu, le prix, le nom affiche. */
    public static void openMachineConfig(ServerPlayer player, String key,
                                         java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        CasinoData.Machine machine = data.machineByKey(key);
        if (machine == null) {
            player.sendSystemMessage(Messages.warn("Cette borne n'existe plus."));
            back.accept(player);
            return;
        }
        CasinoManager.Game game = CasinoManager.game(machine.gameId);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Jeu", ChatFormatting.GRAY),
                value(game == null ? machine.gameId + " (inconnu)" : game.name(),
                        game == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                Icons.label("Changer", ChatFormatting.AQUA),
                sp -> openMachineGame(sp, key, back)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Prix de la partie", ChatFormatting.GRAY),
                value(machine.cost <= 0 ? "gratuite" : machine.cost + " Utopiece(s)",
                        machine.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Prix de la partie", ChatFormatting.GOLD),
                        List.of(Icons.lore("Utopieces exigees a chaque partie.", ChatFormatting.GRAY),
                                Icons.lore("0 = borne gratuite.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), machine.cost, 0, 1_000_000,
                        v -> {
                            machine.cost = v;
                            CasinoData.get(sp.server).setDirty();
                            openMachineConfig(sp, key, back);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom affiche", ChatFormatting.GRAY),
                value(machine.label == null || machine.label.isBlank()
                                ? "(le nom du jeu)" : machine.label,
                        ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.AQUA),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom de la borne", ChatFormatting.GOLD),
                        List.of(Icons.lore("Le titre que lisent les joueurs devant la borne.",
                                        ChatFormatting.GRAY),
                                Icons.lore("Vide = le nom du jeu.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        machine.label == null ? "" : machine.label, 40,
                        v -> {
                            machine.label = v == null ? "" : v.trim();
                            CasinoData.get(sp.server).setDirty();
                            openMachineConfig(sp, key, back);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Position", ChatFormatting.GRAY),
                value(machine.x + " " + machine.y + " " + machine.z, ChatFormatting.DARK_GRAY),
                Component.empty(), null));

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (game != null) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Essayer (gratuit)", ChatFormatting.GREEN),
                    sp -> {
                        com.utopia.net.OwoMenuServer.clear(sp);
                        CasinoManager.open(sp, game);
                    }));
        }
        footer.add(new OwoMenuServer.PanelAction(
                Icons.label("Retirer la borne", ChatFormatting.RED),
                sp -> OwoMenuServer.openConfirm(sp,
                        Icons.title("Retirer cette borne ?", ChatFormatting.RED),
                        List.of(Icons.lore("Le bloc reste en place, il cesse simplement d'etre une borne.",
                                        ChatFormatting.GRAY),
                                Icons.lore("Les scores du jeu sont conserves.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Retirer", ChatFormatting.RED),
                        s2 -> {
                            CasinoData.get(s2.server).removeMachine(key);
                            s2.sendSystemMessage(Messages.success("Borne retiree."));
                            openMachines(s2, 0);
                        },
                        s2 -> openMachineConfig(s2, key, back))));

        String titre = machine.label == null || machine.label.isBlank()
                ? (game == null ? "Borne" : game.name()) : machine.label;
        OwoMenuServer.openPanel(player, Icons.title(titre, ChatFormatting.LIGHT_PURPLE), rows, footer,
                sp -> openMachineConfig(sp, key, back), back);
    }

    private static void openMachineGame(ServerPlayer player, String key,
                                        java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (CasinoManager.Game game : CasinoManager.GAMES) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(game.icon()),
                    Icons.label(game.name(), ChatFormatting.WHITE),
                    Icons.lore(game.desc(), ChatFormatting.GRAY),
                    sp -> {
                        CasinoData.Machine m = CasinoData.get(sp.server).machineByKey(key);
                        if (m != null) {
                            m.gameId = game.id();
                            CasinoData.get(sp.server).setDirty();
                        }
                        openMachineConfig(sp, key, back);
                    }));
        }
        OwoMenuServer.openHub(player, Icons.screenTitle("Quel jeu ?", ChatFormatting.LIGHT_PURPLE),
                List.of(), entries, null, sp -> openMachineConfig(sp, key, back));
    }

    /** Un tableau par jeu : on choisit d'abord le jeu. */
    private static void openScoreBoards(ServerPlayer player) {
        CasinoData data = CasinoData.get(player.server);
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (CasinoManager.Game game : CasinoManager.GAMES) {
            CasinoData.Score best = data.best(game.id());
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(game.icon()),
                    Icons.label(game.name(), ChatFormatting.WHITE),
                    Icons.lore(best == null ? "aucun record"
                                    : best.score() + " par " + best.name(),
                            best == null ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW),
                    sp -> openScores(sp, game.id(), CasinoMenus::openScoreBoards)));
        }
        OwoMenuServer.openHub(player, Icons.screenTitle("Scores", ChatFormatting.GOLD),
                List.of(), entries, CasinoMenus::openScoreBoards, CasinoMenus::open);
    }

    /** Les gerants : ils ouvrent /casino et reglent les bornes sans etre operateurs. */
    public static void openManagers(ServerPlayer player, int page) {
        if (denied(player)) {
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
            boolean gerant = data.isManager(online.getUUID());
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(online, Icons.label(online.getGameProfile().getName(),
                            gerant ? ChatFormatting.GREEN : ChatFormatting.WHITE), List.of()),
                    Icons.label(online.getGameProfile().getName(),
                            gerant ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(gerant ? "Gerant - clique pour retirer" : "Clique pour nommer gerant",
                            gerant ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    sp -> {
                        boolean added = CasinoData.get(sp.server).toggleManager(online.getUUID());
                        sp.sendSystemMessage(added
                                ? Messages.success(online.getGameProfile().getName() + " est gerant de la salle.")
                                : Messages.warn(online.getGameProfile().getName() + " n'est plus gerant."));
                        openManagers(sp, page);
                    }));
        }
        OwoMenuServer.openHubPaged(player, Icons.screenTitle("Gerants", ChatFormatting.YELLOW),
                List.of(Icons.lore("Seuls les joueurs connectes sont proposes.", ChatFormatting.GRAY),
                        Icons.lore(data.managers().size() + " gerant(s) au total.", ChatFormatting.DARK_GRAY)),
                entries, page, 28, CasinoMenus::openManagers, CasinoMenus::open);
    }
}
