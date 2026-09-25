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

    /** Ce que voit un joueur au clic droit : une borne d'arcade ou une machine a capsules. */
    public static void openMachine(ServerPlayer player, CasinoData.Machine machine) {
        if (machine.kind == CasinoData.Kind.GACHA) {
            openGacha(player, machine);
            return;
        }
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

    // ================================================================= Machine a capsules

    /** Devant une machine a capsules : le prix, ce qu'elle crache, et la manivelle. */
    public static void openGacha(ServerPlayer player, CasinoData.Machine machine) {
        CasinoData data = CasinoData.get(player.server);
        int cartes = data.cardsOf(player.getUUID()).size();
        int tetes = data.headsOf(player.getUUID()).size();
        int totalTetes = data.knownPlayers().size();

        List<Component> stats = new ArrayList<>();
        stats.add(Icons.lore(machine.content.label, ChatFormatting.GRAY));
        stats.add(Component.literal("Une capsule : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(machine.cost <= 0 ? "gratuite" : machine.cost + " Utopiece(s)",
                        machine.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD)));
        stats.add(Icons.lore("Ta collection : " + cartes + " / " + GachaCards.ALL.size()
                + " cartes, " + tetes + " / " + totalTetes + " tetes", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_EYE),
                Icons.label("Tourner la manivelle", ChatFormatting.LIGHT_PURPLE),
                Icons.lore(machine.cost <= 0 ? "Gratuit" : machine.cost + " Utopiece(s) la capsule",
                        ChatFormatting.GRAY),
                sp -> {
                    GachaManager.draw(sp, machine);
                    openGacha(sp, machine);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BOOKSHELF),
                Icons.label("Ma collection", ChatFormatting.AQUA),
                Icons.lore("Ce que tu as, et ce qu'il te manque", ChatFormatting.GRAY),
                sp -> openCollection(sp, 0, sp2 -> openGacha(sp2, machine))));
        if (CasinoManager.canManage(player)) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPARATOR),
                    Icons.label("Regler cette machine", ChatFormatting.AQUA),
                    Icons.lore("Contenu, prix, nom affiche", ChatFormatting.GRAY),
                    sp -> openMachineConfig(sp, machine.key(), sp2 -> openGacha(sp2, machine))));
        }

        String titre = machine.label == null || machine.label.isBlank()
                ? "Machine a capsules" : machine.label;
        OwoMenuServer.openHub(player, Icons.screenTitle(titre, ChatFormatting.LIGHT_PURPLE),
                stats, entries, sp -> openGacha(sp, machine), null);
    }

    // ================================================================= Collection

    /** L'album : les cartes trouvees en couleur, les manquantes en gris. */
    public static void openCollection(ServerPlayer player, int page,
                                      java.util.function.Consumer<ServerPlayer> back) {
        CasinoData data = CasinoData.get(player.server);
        java.util.Set<String> mine = data.cardsOf(player.getUUID());
        java.util.Set<java.util.UUID> mesTetes = data.headsOf(player.getUUID());

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (GachaCards.Card card : GachaCards.ALL) {
            boolean owned = mine.contains(card.id());
            GachaCards.Rarity rarity = data.rarity(card.id());
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(owned ? Items.PAPER : Items.GRAY_DYE),
                    Icons.label(owned ? "\"" + card.text() + "\"" : "? ? ?",
                            owned ? rarity.color : ChatFormatting.DARK_GRAY),
                    Icons.lore(owned ? rarity.label : "Pas encore trouvee",
                            owned ? rarity.color : ChatFormatting.DARK_GRAY),
                    null));
        }
        for (java.util.Map.Entry<java.util.UUID, String> e : data.knownPlayers().entrySet()) {
            boolean owned = mesTetes.contains(e.getKey());
            com.mojang.authlib.GameProfile profile =
                    new com.mojang.authlib.GameProfile(e.getKey(), e.getValue());
            entries.add(new OwoMenuServer.HubEntry(
                    owned ? Icons.playerHead(profile,
                                    Icons.label(e.getValue(), ChatFormatting.YELLOW), List.of())
                            : new ItemStack(Items.GRAY_DYE),
                    Icons.label(owned ? e.getValue() : "? ? ?",
                            owned ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY),
                    Icons.lore(owned ? "Tete collectionnee" : "Tete pas encore trouvee",
                            owned ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    null));
        }

        int totalTetes = data.knownPlayers().size();
        OwoMenuServer.openHubPaged(player,
                Icons.screenTitle("Ma collection", ChatFormatting.AQUA),
                List.of(Icons.lore(mine.size() + " / " + GachaCards.ALL.size() + " cartes - "
                                + mesTetes.size() + " / " + totalTetes + " tetes",
                        ChatFormatting.GRAY)),
                entries, page, 28,
                (sp, p) -> openCollection(sp, p, back), back);
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
        stats.add(Component.literal("Machines posees : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(String.valueOf(data.machines().size()), ChatFormatting.GOLD)));
        stats.add(Icons.lore("Les recettes vont " + (com.utopia.Config.CASINO_REVENUE_TO_MAIRIE.get()
                ? "a la caisse de la mairie." : "au neant : elles sont detruites."),
                ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CRAFTING_TABLE),
                Icons.label("Poser une machine", ChatFormatting.GREEN),
                Icons.lore("Choisis un jeu ou une capsule, puis casse le bloc voulu", ChatFormatting.GRAY),
                CasinoMenus::openGamePicker));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.REPEATER),
                Icons.label("Les machines", ChatFormatting.AQUA),
                Icons.lore(data.machines().size() + " machine(s) - regler, retrouver, retirer",
                        ChatFormatting.GRAY),
                sp -> openMachines(sp, 0)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLDEN_HELMET),
                Icons.label("Tableaux des scores", ChatFormatting.GOLD),
                Icons.lore("Un classement par jeu, commun a toutes les bornes", ChatFormatting.GRAY),
                CasinoMenus::openScoreBoards));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BOOK),
                Icons.label("Raretes des cartes", ChatFormatting.LIGHT_PURPLE),
                Icons.lore(GachaCards.ALL.size() + " cartes - regle lesquelles sont rares",
                        ChatFormatting.GRAY),
                sp -> openCardRarities(sp, 0, CasinoMenus::open)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BOOKSHELF),
                Icons.label("Ma collection", ChatFormatting.AQUA),
                Icons.lore("Ce que tu as trouve, et ce qu'il te manque", ChatFormatting.GRAY),
                sp -> openCollection(sp, 0, CasinoMenus::open)));
        if (com.utopia.Config.CASINO_TABLES.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                    Icons.label("Tables de jeu", ChatFormatting.GOLD),
                    com.utopia.table.TableMenus.resume(player),
                    com.utopia.table.TableMenus::open));
        }
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
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_EYE),
                Icons.label("Machine a capsules", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Tetes des joueurs du serveur et cartes a collectionner", ChatFormatting.GRAY),
                sp -> {
                    CasinoManager.startPlacing(sp.getUUID(), CasinoManager.GACHA_ID);
                    sp.sendSystemMessage(Messages.info(
                            "Mode actif : casse le bloc qui deviendra la machine a capsules."));
                    Menus.close(sp);
                }));
        OwoMenuServer.openHub(player, Icons.screenTitle("Quelle machine ?", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Le bloc que tu casseras ensuite deviendra la machine.", ChatFormatting.GRAY),
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
                new OwoMenuServer.Column(head("ROLE"), 96, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("PRIX"), 48, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("POSITION"), 108, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        int from = cur * ROW_PAGE;
        int to = Math.min(all.size(), from + ROW_PAGE);
        for (int i = from; i < to; i++) {
            CasinoData.Machine m = all.get(i);
            boolean capsule = m.kind == CasinoData.Kind.GACHA;
            CasinoManager.Game g = capsule ? null : CasinoManager.game(m.gameId);
            String role = capsule ? m.content.label : (g == null ? "inconnu" : g.name());
            String defaut = capsule ? "Machine a capsules" : (g == null ? m.gameId : g.name());
            rows.add(new OwoMenuServer.TableRow(
                    new ItemStack(capsule ? Items.ENDER_EYE : (g == null ? Items.BARRIER : g.icon())),
                    List.of(value(m.label == null || m.label.isBlank() ? defaut : m.label,
                                    ChatFormatting.WHITE),
                            value(role, !capsule && g == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                            value(m.cost <= 0 ? "gratuit" : String.valueOf(m.cost),
                                    m.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                            value(m.x + " " + m.y + " " + m.z, ChatFormatting.DARK_GRAY)),
                    sp -> openMachineConfig(sp, m.key(), sp2 -> openMachines(sp2, cur))));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucune machine posee.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle("Les machines", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Clique une machine pour la regler.", ChatFormatting.GRAY)),
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

        boolean capsule = machine.kind == CasinoData.Kind.GACHA;
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        if (capsule) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Contenu", ChatFormatting.GRAY),
                    value(machine.content.label, ChatFormatting.AQUA),
                    Icons.label("Changer", ChatFormatting.AQUA),
                    sp -> {
                        machine.content = machine.content.next();
                        CasinoData.get(sp.server).setDirty();
                        openMachineConfig(sp, key, back);
                    }));
        } else {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Jeu", ChatFormatting.GRAY),
                    value(game == null ? machine.gameId + " (inconnu)" : game.name(),
                            game == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                    Icons.label("Changer", ChatFormatting.AQUA),
                    sp -> openMachineGame(sp, key, back)));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label(capsule ? "Prix de la capsule" : "Prix de la partie", ChatFormatting.GRAY),
                value(machine.cost <= 0 ? "gratuite" : machine.cost + " Utopiece(s)",
                        machine.cost <= 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp,
                        Icons.label(capsule ? "Prix de la capsule" : "Prix de la partie", ChatFormatting.GOLD),
                        List.of(Icons.lore(capsule ? "Utopieces exigees a chaque capsule."
                                        : "Utopieces exigees a chaque partie.", ChatFormatting.GRAY),
                                Icons.lore("0 = machine gratuite.", ChatFormatting.DARK_GRAY)),
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
        if (capsule) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Raretes des cartes", ChatFormatting.AQUA),
                    sp -> openCardRarities(sp, 0, sp2 -> openMachineConfig(sp2, key, back))));
        }
        if (game != null && !capsule) {
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
                ? (capsule ? "Machine a capsules" : (game == null ? "Borne" : game.name()))
                : machine.label;
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

    /**
     * Les raretes, carte par carte. Un clic fait monter la carte d'un cran : commune, rare, epique,
     * legendaire, puis retour a commune. Le poids de tirage suit.
     */
    public static void openCardRarities(ServerPlayer player, int page,
                                        java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        CasinoData data = CasinoData.get(player.server);
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (GachaCards.Card card : GachaCards.ALL) {
            GachaCards.Rarity rarity = data.rarity(card.id());
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(switch (rarity) {
                        case LEGENDAIRE -> Items.ENCHANTED_BOOK;
                        case EPIQUE -> Items.WRITTEN_BOOK;
                        case RARE -> Items.BOOK;
                        default -> Items.PAPER;
                    }),
                    Icons.label("\"" + card.text() + "\"", rarity.color),
                    Icons.lore(rarity.label + " - clique pour monter d'un cran", ChatFormatting.GRAY),
                    sp -> {
                        CasinoData d = CasinoData.get(sp.server);
                        d.setRarity(card.id(), d.rarity(card.id()).next());
                        openCardRarities(sp, page, back);
                    }));
        }
        OwoMenuServer.openHubPaged(player, Icons.screenTitle("Raretes", ChatFormatting.GOLD),
                List.of(Icons.lore("Plus une carte est rare, moins elle sort.", ChatFormatting.GRAY),
                        Icons.lore("Commune 100, rare 30, epique 8, legendaire 2 chances relatives.",
                                ChatFormatting.DARK_GRAY)),
                entries, page, 28,
                (sp, p) -> openCardRarities(sp, p, back), back);
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
