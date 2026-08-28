package com.utopia.bet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.utopia.data.BetData;
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
 * Registre administratif des paris : tout ce qui a ete cree sur le serveur, y compris apres la
 * disparition du Bookmaker, avec le detail comptable de chaque participant.
 *
 * <p>Le registre est en lecture seule sur les chiffres : on n'y corrige jamais un total, on suspend
 * ou on annule. C'est ce qui garantit qu'un ecart reste visible au lieu d'etre efface.
 */
public final class BetAdminMenus {

    private static final int PAGE_SIZE = 10;

    /** Criteres de recherche, gardes par administrateur le temps de sa session. */
    public static final class Filter {
        public String search = "";
        public BetData.State state;      // null = tous
        public long minPot;

        public boolean active() {
            return !search.isBlank() || state != null || minPot > 0;
        }

        public void reset() {
            search = "";
            state = null;
            minPot = 0;
        }

        public String summary() {
            if (!active()) {
                return "aucun filtre";
            }
            List<String> parts = new ArrayList<>();
            if (!search.isBlank()) {
                parts.add("\"" + search + "\"");
            }
            if (state != null) {
                parts.add(state.label().toLowerCase(Locale.ROOT));
            }
            if (minPot > 0) {
                parts.add("cagnotte >= " + minPot);
            }
            return String.join(" - ", parts);
        }

        public boolean matches(BetData.Bet bet) {
            if (state != null && bet.state != state) {
                return false;
            }
            if (minPot > 0 && bet.pot() < minPot) {
                return false;
            }
            if (search.isBlank()) {
                return true;
            }
            String needle = search.toLowerCase(Locale.ROOT);
            if (bet.id.toLowerCase(Locale.ROOT).contains(needle)
                    || bet.name.toLowerCase(Locale.ROOT).contains(needle)
                    || bet.creatorName.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            BetData.Option winner = bet.option(bet.winner);
            if (winner != null && winner.label.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            for (BetData.Wager w : bet.wagers) {
                if (w.playerName().toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<UUID, Filter> FILTERS = new HashMap<>();

    private BetAdminMenus() {
    }

    public static void forget(UUID player) {
        FILTERS.remove(player);
    }

    private static Filter filterOf(ServerPlayer admin) {
        return FILTERS.computeIfAbsent(admin.getUUID(), k -> new Filter());
    }

    // ==============================================================================================
    //  Registre
    // ==============================================================================================

    public static void open(ServerPlayer admin) {
        open(admin, 0);
    }

    /**
     * Le registre en tableau : cagnottes et etats se lisent les uns sous les autres, ce qui est
     * la seule facon de reperer une anomalie sans ouvrir chaque pari.
     */
    public static void open(ServerPlayer admin, int page) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration."));
            return;
        }
        BetData data = BetData.get(admin.server);
        Filter filter = filterOf(admin);
        List<BetData.Bet> all = new ArrayList<>();
        long live = 0;
        long held = 0;
        for (BetData.Bet bet : data.all()) {
            if (bet.state.active()) {
                live++;
                held += bet.collected - bet.distributed;
            }
            if (filter.matches(bet)) {
                all.add(bet);
            }
        }

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);

        Component title = Icons.screenTitle("Registre des paris"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.GOLD);
        List<Component> stats = new ArrayList<>();
        stats.add(stat(data.all().size() + " pari(s) au registre - ", live + " en cours",
                ChatFormatting.AQUA));
        stats.add(stat("Utopieces immobilisees : ", held + " Utopieces", ChatFormatting.GOLD));
        if (filter.active()) {
            stats.add(stat("Filtre : ", filter.summary() + " - " + all.size() + " resultat(s)",
                    ChatFormatting.YELLOW));
        }

        List<OwoMenuServer.PanelRow> controls = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Rechercher / filtrer",
                                filter.active() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                        Icons.label(filter.summary(), ChatFormatting.DARK_GRAY),
                        Icons.label("Filtrer", ChatFormatting.YELLOW),
                        BetAdminMenus::openFilter),
                new OwoMenuServer.PanelRow(
                        Icons.label("Par joueur", ChatFormatting.GRAY),
                        Icons.label("Historique complet d'un joueur", ChatFormatting.DARK_GRAY),
                        Icons.label("Ouvrir", ChatFormatting.YELLOW),
                        sp -> openPlayers(sp, 0)),
                new OwoMenuServer.PanelRow(
                        Icons.label("Paris a surveiller", ChatFormatting.RED),
                        Icons.label("Ecarts comptables, annulations en serie, createurs gagnants",
                                ChatFormatting.DARK_GRAY),
                        Icons.label("Consulter", ChatFormatting.YELLOW),
                        sp -> openSuspicious(sp, 0)));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("PARI"), 40, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("NOM"), 84, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("ETAT"), 52, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("CAGNOTTE"), 48, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("CREATEUR"), 76, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (BetData.Bet bet : all.subList(Math.min(from, all.size()), to)) {
            String id = bet.id;
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(bet.id, ChatFormatting.WHITE),
                    Icons.label(bet.name, ChatFormatting.WHITE),
                    Icons.label(shortState(bet.state), color(bet.state)),
                    Icons.label(String.valueOf(bet.pot()), ChatFormatting.GOLD),
                    Icons.label(bet.creatorName, ChatFormatting.AQUA)),
                    sp -> openBet(sp, id)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(filter.active() ? "Aucun resultat" : "Aucun pari au registre",
                            ChatFormatting.RED),
                    Component.empty(), Component.empty(), Component.empty(), Component.empty()), null));
        }

        Consumer<ServerPlayer> prev = pages > 1 ? sp -> open(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1 ? sp -> open(sp, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(admin, title, stats, controls, columns, rows, List.of(),
                prev, next, sp -> open(sp, cur), com.utopia.menu.AdminMenu::open);
    }

    /** Etat en un mot : le libelle complet ne tiendrait pas dans une colonne de tableau. */
    private static String shortState(BetData.State state) {
        return switch (state) {
            case OUVERT -> "Ouvert";
            case FERME -> "Ferme";
            case RESOLU -> "Resolu";
            case ANNULE -> "Annule";
            case ARCHIVE -> "Archive";
            case SUSPENDU -> "Suspendu";
            case ERREUR -> "Erreur";
        };
    }

    private static ChatFormatting color(BetData.State state) {
        return switch (state) {
            case OUVERT -> ChatFormatting.GREEN;
            case FERME -> ChatFormatting.YELLOW;
            case RESOLU -> ChatFormatting.AQUA;
            case ANNULE -> ChatFormatting.GRAY;
            case ARCHIVE -> ChatFormatting.DARK_GRAY;
            case SUSPENDU, ERREUR -> ChatFormatting.RED;
        };
    }

    public static void openFilter(ServerPlayer admin) {
        Filter filter = filterOf(admin);
        Component title = Icons.title("Recherche de paris", ChatFormatting.DARK_AQUA);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Recherche", ChatFormatting.GRAY),
                Icons.label(filter.search.isBlank() ? "tout" : "\"" + filter.search + "\"",
                        filter.search.isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                Icons.label(filter.search.isBlank() ? "Saisir" : "Changer", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Rechercher", ChatFormatting.DARK_AQUA),
                        List.of(Icons.lore("Identifiant, nom, createur, participant ou vainqueur",
                                ChatFormatting.GRAY)),
                        Icons.label("Chercher", ChatFormatting.GREEN), filter.search, 32,
                        text -> {
                            filter.search = text == null ? "" : text.trim();
                            openFilter(sp);
                        })));
        if (!filter.search.isBlank()) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Effacer la recherche", ChatFormatting.GRAY),
                    Icons.label("garde les autres criteres", ChatFormatting.DARK_GRAY),
                    Icons.label("Effacer", ChatFormatting.YELLOW),
                    sp -> {
                        filter.search = "";
                        openFilter(sp);
                    }));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                Icons.label(filter.state == null ? "Tous" : filter.state.label(),
                        filter.state == null ? ChatFormatting.WHITE : color(filter.state)),
                Icons.label("Suivant", ChatFormatting.YELLOW),
                sp -> {
                    BetData.State[] values = BetData.State.values();
                    if (filter.state == null) {
                        filter.state = values[0];
                    } else if (filter.state.ordinal() + 1 >= values.length) {
                        filter.state = null;
                    } else {
                        filter.state = values[filter.state.ordinal() + 1];
                    }
                    openFilter(sp);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Cagnotte minimale", ChatFormatting.GRAY),
                Icons.label(filter.minPot > 0 ? filter.minPot + " Utopieces" : "sans minimum",
                        filter.minPot > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Cagnotte minimale", ChatFormatting.GOLD),
                        List.of(Icons.lore("0 = sans minimum", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), filter.minPot, 0, 1_000_000_000L,
                        v -> {
                            filter.minPot = v;
                            openFilter(sp);
                        })));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Voir les resultats", ChatFormatting.GREEN),
                        BetAdminMenus::open),
                new OwoMenuServer.PanelAction(Icons.label("Tout effacer", ChatFormatting.RED),
                        sp -> {
                            filter.reset();
                            openFilter(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, BetAdminMenus::openFilter,
                BetAdminMenus::open);
    }

    // ==============================================================================================
    //  Fiche d'un pari
    // ==============================================================================================

    public static void openBet(ServerPlayer admin, String betId) {
        if (!admin.hasPermissions(2)) {
            return;
        }
        BetData.Bet bet = BetData.get(admin.server).bet(betId);
        if (bet == null) {
            open(admin);
            return;
        }
        Component title = Icons.title(bet.id + " - " + bet.name, color(bet.state));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(BetMenus.row("Etat", bet.state.label(), color(bet.state)));
        rows.add(BetMenus.row("Createur", bet.creatorName + " (" + bet.creator + ")", ChatFormatting.AQUA));
        if (!bet.description.isBlank()) {
            rows.add(BetMenus.row("Description", bet.description, ChatFormatting.GRAY));
        }
        rows.add(BetMenus.row("Cree le", BetManager.stamp(bet.createdAt) + " - mises "
                + bet.durationMinutes + " min", ChatFormatting.DARK_GRAY));
        rows.add(BetMenus.row("Fermeture", BetManager.stamp(bet.closedAt), ChatFormatting.DARK_GRAY));
        rows.add(BetMenus.row("Resolution", BetManager.stamp(bet.resolvedAt), ChatFormatting.DARK_GRAY));
        rows.add(BetMenus.row("Bookmaker", bet.isPlaced()
                ? String.format("%s %.0f %.0f %.0f", bet.dim, bet.x, bet.y, bet.z) : "non place",
                ChatFormatting.DARK_GRAY));
        BetData.Option winner = bet.option(bet.winner);
        rows.add(BetMenus.row("Vainqueur", winner == null ? "-" : winner.label,
                winner == null ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN));
        if (!bet.cancelReason.isBlank()) {
            rows.add(BetMenus.row("Motif d'annulation", bet.cancelReason, ChatFormatting.RED));
        }
        for (BetData.Option option : bet.options) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(option.label, option.id.equals(bet.winner)
                            ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.label(option.pool + " Utopieces - " + bet.bettors(option.id) + " joueur(s) - cote "
                            + BetManager.odds(bet.odds(option.id)), ChatFormatting.GRAY), null, null));
        }

        // Le controle de cagnotte est la raison d'etre du registre : il s'affiche toujours.
        long diff = bet.collected - bet.distributed;
        boolean settledUp = bet.state.closed() && diff == 0;
        rows.add(BetMenus.row("Total collecte", bet.collected + " Utopieces", ChatFormatting.GOLD));
        rows.add(BetMenus.row(bet.state == BetData.State.ANNULE ? "Total rembourse" : "Total redistribue",
                bet.distributed + " Utopieces", ChatFormatting.GOLD));
        rows.add(BetMenus.row("Difference", diff + " Utopiece(s)"
                        + (settledUp ? " - pari entierement solde" : diff == 0 ? "" : " - en attente"),
                diff == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        String problem = bet.settled ? null : BetManager.verify(bet);
        if (problem != null) {
            rows.add(BetMenus.row("Verification", problem, ChatFormatting.RED));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Participants", ChatFormatting.GRAY),
                Icons.label(bet.choice.size() + " joueur(s)", ChatFormatting.WHITE),
                Icons.label("Detail", ChatFormatting.YELLOW),
                sp -> openParticipants(sp, betId, 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Journal", ChatFormatting.GRAY),
                Icons.label(bet.journal.size() + " evenement(s)", ChatFormatting.WHITE),
                Icons.label("Consulter", ChatFormatting.YELLOW),
                sp -> openJournal(sp, betId, 0)));

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (bet.state == BetData.State.OUVERT || bet.state == BetData.State.FERME) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Suspendre", ChatFormatting.YELLOW),
                    sp -> {
                        BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                        if (fresh != null && BetManager.suspend(sp.server, fresh,
                                sp.getGameProfile().getName())) {
                            sp.sendSystemMessage(Messages.success("Pari suspendu."));
                        } else {
                            sp.sendSystemMessage(Messages.warn("Ce pari ne peut pas etre suspendu."));
                        }
                        openBet(sp, betId);
                    }));
        }
        if (bet.state == BetData.State.SUSPENDU) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Autoriser la reprise", ChatFormatting.GREEN),
                    sp -> {
                        BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                        if (fresh != null && BetManager.resume(sp.server, fresh,
                                sp.getGameProfile().getName())) {
                            sp.sendSystemMessage(Messages.success("Pari repris."));
                        } else {
                            sp.sendSystemMessage(Messages.warn("Ce pari n'est pas suspendu."));
                        }
                        openBet(sp, betId);
                    }));
        }
        if (!bet.state.closed()) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Annuler et rembourser", ChatFormatting.RED),
                    sp -> {
                        BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                        if (fresh == null || fresh.state.closed()) {
                            sp.sendSystemMessage(Messages.warn("Ce pari est deja clos."));
                        } else if (BetManager.cancel(sp.server, fresh, "Annule par l'administration ("
                                + sp.getGameProfile().getName() + ")")) {
                            sp.sendSystemMessage(Messages.success("Pari annule, mises remboursees."));
                        } else {
                            sp.sendSystemMessage(Messages.error("Remboursement bloque : les comptes de "
                                    + "ce pari ne concordent pas. Utilisez le reglement administratif."));
                        }
                        openBet(sp, betId);
                    }));
        }
        if (bet.state == BetData.State.ERREUR) {
            // Seule sortie d'un pari bloque : rendre ce que le journal des mises justifie, sans
            // jamais depasser ce qui reste immobilise, puis archiver l'ecart au registre.
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Regler et archiver", ChatFormatting.GREEN),
                    sp -> {
                        BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                        if (fresh != null && BetManager.settleBlocked(sp.server, fresh,
                                sp.getGameProfile().getName())) {
                            sp.sendSystemMessage(Messages.success("Mises rendues d'apres le journal, "
                                    + "pari archive."));
                        } else {
                            sp.sendSystemMessage(Messages.error("Reglement refuse : le journal reclame "
                                    + "plus que ce qui a ete retire. Rien n'a ete verse."));
                        }
                        openBet(sp, betId);
                    }));
        }
        if (bet.isPlaced()) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Deplacer le Bookmaker",
                    ChatFormatting.LIGHT_PURPLE), sp -> BetMenus.openMove(sp, betId)));
        }

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openBet(sp, betId),
                BetAdminMenus::open);
    }

    /** Detail comptable de chaque participant : mise, retour, benefice ou perte. */
    public static void openParticipants(ServerPlayer admin, String betId, int page) {
        BetData.Bet bet = BetData.get(admin.server).bet(betId);
        if (bet == null) {
            open(admin);
            return;
        }
        List<UUID> players = new ArrayList<>(bet.choice.keySet());
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(players.size(), from + PAGE_SIZE);

        Component title = Icons.title("Participants - " + bet.id
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.AQUA);

        // Le benefice a sa propre colonne : c'est le chiffre qu'on lit en premier sur un pari, le
        // laisser deduire d'une soustraction entre deux colonnes revenait a le cacher.
        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("JOUEUR"), 82, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("CHOIX"), 60, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("MISE"), 42, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("RETOUR"), 46, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("BENEFICE"), 52, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("ETAT"), 50, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (UUID player : players.subList(Math.min(from, players.size()), to)) {
            long stake = bet.stakeOf(player);
            long back = bet.payout.getOrDefault(player, 0L);
            boolean paid = bet.paidOut.contains(player);
            BetData.Option option = bet.option(bet.choice.get(player));
            // Libelles courts : la colonne d'etat est etroite, un mot qui se replie casse la rangee.
            String state = bet.state == BetData.State.ANNULE
                    ? (paid ? "Rendu" : "A rendre")
                    : back > 0 ? (paid ? "Recu" : "A payer")
                    : bet.state == BetData.State.RESOLU ? "Perdu" : "En jeu";
            ChatFormatting tone = back > stake ? ChatFormatting.GREEN
                    : back > 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY;

            // Un pari resolu sans retour est une perte seche : elle s'ecrit en negatif plutot que de
            // laisser une case vide qu'on pourrait lire comme "rien ne s'est passe".
            String profit;
            ChatFormatting profitTone;
            if (back > 0) {
                long delta = back - stake;
                profit = (delta > 0 ? "+" : "") + delta;
                profitTone = delta > 0 ? ChatFormatting.GREEN
                        : delta < 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
            } else if (bet.state == BetData.State.RESOLU) {
                profit = "-" + stake;
                profitTone = ChatFormatting.RED;
            } else {
                profit = "-";
                profitTone = ChatFormatting.DARK_GRAY;
            }

            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(BetMenus.nameOf(bet, player), ChatFormatting.WHITE),
                    Icons.label(option == null ? "?" : option.label, ChatFormatting.GRAY),
                    Icons.label(String.valueOf(stake), ChatFormatting.GOLD),
                    Icons.label(back > 0 ? String.valueOf(back) : "-", tone),
                    Icons.label(profit, profitTone),
                    Icons.label(state, tone)), null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Aucun participant", ChatFormatting.GRAY),
                    Component.empty(), Component.empty(), Component.empty(), Component.empty(),
                    Component.empty()), null));
        }
        Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openParticipants(sp, betId, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1
                ? sp -> openParticipants(sp, betId, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(admin, title, List.of(), List.of(), columns, rows, List.of(),
                prev, next, sp -> openParticipants(sp, betId, cur), sp -> openBet(sp, betId));
    }

    public static void openJournal(ServerPlayer admin, String betId, int page) {
        BetData.Bet bet = BetData.get(admin.server).bet(betId);
        if (bet == null) {
            open(admin);
            return;
        }
        List<BetData.LogEntry> all = new ArrayList<>(bet.journal);
        java.util.Collections.reverse(all);

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);

        Component title = Icons.title("Journal - " + bet.id
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.YELLOW);

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DATE"), 92, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("EVENEMENT"), 204, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (BetData.LogEntry e : all.subList(Math.min(from, all.size()), to)) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(BetManager.stamp(e.millis()), ChatFormatting.DARK_GRAY),
                    Icons.label(e.text(), ChatFormatting.WHITE)), null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Journal vide", ChatFormatting.GRAY), Component.empty()), null));
        }
        Consumer<ServerPlayer> prev = pages > 1 ? sp -> openJournal(sp, betId, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1 ? sp -> openJournal(sp, betId, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(admin, title, List.of(), List.of(), columns, rows, List.of(),
                prev, next, sp -> openJournal(sp, betId, cur), sp -> openBet(sp, betId));
    }

    // ==============================================================================================
    //  Historique par joueur
    // ==============================================================================================

    public static void openPlayers(ServerPlayer admin, int page) {
        BetData data = BetData.get(admin.server);
        Set<UUID> players = new LinkedHashSet<>();
        Map<UUID, String> names = new HashMap<>();
        for (BetData.Bet bet : data.all()) {
            players.add(bet.creator);
            names.putIfAbsent(bet.creator, bet.creatorName);
            for (BetData.Wager w : bet.wagers) {
                players.add(w.player());
                names.putIfAbsent(w.player(), w.playerName());
            }
        }
        Component title = Icons.title("Paris par joueur", ChatFormatting.AQUA);

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (UUID id : players) {
            String name = names.getOrDefault(id, id.toString().substring(0, 8));
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                    Icons.label(name, ChatFormatting.WHITE),
                    Icons.lore(data.createdBy(id).size() + " cree(s) - "
                            + data.participatedBy(id).size() + " joue(s)", ChatFormatting.GRAY),
                    sp -> openPlayer(sp, id, name)));
        }
        List<Component> stats = List.of(Component.literal(entries.size() + " joueur(s) concerne(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                BetAdminMenus::openPlayers, BetAdminMenus::open);
    }

    public static void openPlayer(ServerPlayer admin, UUID target, String name) {
        BetData data = BetData.get(admin.server);
        long staked = 0;
        long returned = 0;
        long refunded = 0;
        long lost = 0;
        for (BetData.Bet bet : data.participatedBy(target)) {
            long stake = bet.stakeOf(target);
            long back = bet.payout.getOrDefault(target, 0L);
            staked += stake;
            if (bet.state == BetData.State.ANNULE) {
                refunded += back;
            } else {
                returned += back;
                if (bet.state == BetData.State.RESOLU && back == 0) {
                    lost += stake;
                }
            }
        }
        long profit = returned - (staked - refunded - lost);

        Component title = Icons.title("Paris de " + name, ChatFormatting.AQUA);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(BetMenus.row("Identifiant", target.toString(), ChatFormatting.DARK_GRAY));
        rows.add(BetMenus.row("Total mise", staked + " Utopieces", ChatFormatting.GOLD));
        rows.add(BetMenus.row("Total recu", returned + " Utopieces", ChatFormatting.GREEN));
        rows.add(BetMenus.row("Total rembourse", refunded + " Utopieces", ChatFormatting.AQUA));
        rows.add(BetMenus.row("Total perdu", lost + " Utopieces", ChatFormatting.RED));
        rows.add(BetMenus.row("Benefice net", profit + " Utopieces",
                profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));

        for (BetData.Bet bet : data.createdBy(target)) {
            String id = bet.id;
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("CREE " + bet.id, ChatFormatting.LIGHT_PURPLE),
                    Icons.label(bet.name + " - " + bet.state.label() + " - " + bet.pot() + " Utopieces",
                            color(bet.state)),
                    Icons.label("Ouvrir", ChatFormatting.YELLOW),
                    sp -> openBet(sp, id)));
        }
        for (BetData.Bet bet : data.participatedBy(target)) {
            String id = bet.id;
            long stake = bet.stakeOf(target);
            long back = bet.payout.getOrDefault(target, 0L);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("JOUE " + bet.id, ChatFormatting.GRAY),
                    Icons.label(bet.name + " - mise " + stake + " - retour " + back, color(bet.state)),
                    Icons.label("Ouvrir", ChatFormatting.YELLOW),
                    sp -> openBet(sp, id)));
        }

        OwoMenuServer.openPanel(admin, title, rows, List.of(),
                sp -> openPlayer(sp, target, name), sp -> openPlayers(sp, 0));
    }

    // ==============================================================================================
    //  Paris a surveiller
    // ==============================================================================================

    /**
     * Signale ce qui merite un second regard : un ecart comptable, un createur qui gagne ses propres
     * paris, une serie d'annulations, une cagnotte inhabituelle. Ce sont des indices, pas des preuves.
     */
    public static void openSuspicious(ServerPlayer admin, int page) {
        BetData data = BetData.get(admin.server);
        List<BetData.Bet> all = data.all();

        Map<UUID, Integer> cancelled = new HashMap<>();
        Map<UUID, Integer> selfWon = new HashMap<>();
        long potSum = 0;
        int potCount = 0;
        for (BetData.Bet bet : all) {
            if (bet.state == BetData.State.ANNULE) {
                cancelled.merge(bet.creator, 1, Integer::sum);
            }
            if (bet.state == BetData.State.RESOLU) {
                potSum += bet.pot();
                potCount++;
                if (bet.payout.getOrDefault(bet.creator, 0L) > bet.stakeOf(bet.creator)) {
                    selfWon.merge(bet.creator, 1, Integer::sum);
                }
            }
        }
        long average = potCount > 0 ? potSum / potCount : 0;

        List<Flagged> flagged = new ArrayList<>();
        for (BetData.Bet bet : all) {
            List<String> flags = new ArrayList<>();
            if (bet.state == BetData.State.ERREUR) {
                flags.add("ecart comptable");
            }
            if (bet.state.closed() && bet.holdsMoney()) {
                flags.add("versement incomplet");
            }
            if (bet.state == BetData.State.RESOLU
                    && bet.payout.getOrDefault(bet.creator, 0L) > bet.stakeOf(bet.creator)) {
                flags.add("le createur a gagne son pari");
            }
            if (cancelled.getOrDefault(bet.creator, 0) >= 3 && bet.state == BetData.State.ANNULE) {
                flags.add(cancelled.get(bet.creator) + " paris annules par ce createur");
            }
            if (selfWon.getOrDefault(bet.creator, 0) >= 3) {
                flags.add(selfWon.get(bet.creator) + " victoires sur ses propres paris");
            }
            if (average > 0 && bet.pot() > average * 5) {
                flags.add("cagnotte tres au-dessus de la moyenne");
            }
            if (bet.state == BetData.State.RESOLU && bet.choice.size() <= 2 && bet.pot() > 0
                    && bet.bettors(bet.winner) == 1) {
                flags.add("un seul gagnant sur un pari a deux");
            }
            if (flags.isEmpty()) {
                continue;
            }
            flagged.add(new Flagged(bet, String.join(", ", flags)));
        }

        int pages = Math.max(1, (flagged.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(flagged.size(), from + PAGE_SIZE);

        Component title = Icons.title("Paris a surveiller"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.RED);
        List<Component> stats = List.of(
                Component.literal(flagged.isEmpty() ? "Rien a signaler."
                                : flagged.size() + " pari(s) meritent un second regard.")
                        .withStyle(s -> s.withColor(flagged.isEmpty() ? ChatFormatting.GREEN
                                : ChatFormatting.YELLOW).withItalic(false)),
                Component.literal("Ce sont des indices, pas des preuves.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("PARI"), 40, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("NOM"), 76, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("CREATEUR"), 76, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("MOTIF"), 108, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (Flagged f : flagged.subList(Math.min(from, flagged.size()), to)) {
            String id = f.bet().id;
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(f.bet().id, ChatFormatting.RED),
                    Icons.label(f.bet().name, ChatFormatting.WHITE),
                    Icons.label(f.bet().creatorName, ChatFormatting.AQUA),
                    Icons.label(f.motif(), ChatFormatting.YELLOW)),
                    sp -> openBet(sp, id)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Rien a signaler", ChatFormatting.GREEN),
                    Component.empty(), Component.empty(), Component.empty()), null));
        }

        Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openSuspicious(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1
                ? sp -> openSuspicious(sp, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(admin, title, stats, List.of(), columns, rows, List.of(),
                prev, next, sp -> openSuspicious(sp, cur), BetAdminMenus::open);
    }

    /** Un pari signale et les motifs qui l'ont fait remonter, le temps de dresser le tableau. */
    private record Flagged(BetData.Bet bet, String motif) {
    }

    /** En-tete de colonne : gris-bleu, en capitales, pour se distinguer des donnees. */
    private static Component head(String text) {
        return Component.literal(text)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }
}
