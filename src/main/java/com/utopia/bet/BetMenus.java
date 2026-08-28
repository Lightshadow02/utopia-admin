package com.utopia.bet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.utopia.data.BetData;
import com.utopia.economy.EconomyManager;
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

/**
 * Interfaces des paris cote joueur : redaction d'un pari, interface du Bookmaker, placement d'une
 * mise, et les commandes reservees au createur.
 *
 * <p>On ne consulte jamais un pari a distance : il faut trouver son Bookmaker et lui parler.
 */
public final class BetMenus {

    private static final int PAGE_SIZE = 12;

    /** Brouillon de creation, garde en memoire : un pari n'existe qu'une fois publie. */
    private static final class Draft {
        String name = "";
        String description = "";
        final List<String> options = new ArrayList<>();
        int minutes = 10;
    }

    private static final Map<UUID, Draft> DRAFTS = new HashMap<>();

    private BetMenus() {
    }

    public static void forget(UUID player) {
        DRAFTS.remove(player);
        BetManager.forget(player);
    }

    // ==============================================================================================
    //  Creation
    // ==============================================================================================

    /** Point d'entree depuis le menu principal : un seul pari a la fois. */
    public static void openCreate(ServerPlayer player) {
        BetData data = BetData.get(player.server);
        BetData.Bet active = data.activeOf(player.getUUID());
        if (active != null) {
            player.sendSystemMessage(Messages.warn("Vous possedez deja un pari en cours. Vous devez le "
                    + "cloturer ou l'annuler avant d'en creer un nouveau."));
            player.sendSystemMessage(Messages.info("Pari en cours : \"" + active.name + "\" ("
                    + active.state.label() + ")."));
            return;
        }
        Draft draft = DRAFTS.computeIfAbsent(player.getUUID(), k -> new Draft());

        Component title = Component.literal("CREER UN PARI")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom du pari", ChatFormatting.GRAY),
                Icons.label(draft.name.isBlank() ? "a definir" : draft.name,
                        draft.name.isBlank() ? ChatFormatting.RED : ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom du pari", ChatFormatting.GOLD),
                        List.of(Icons.lore("La question posee, en une phrase", ChatFormatting.GRAY),
                                Icons.lore("Ex : Qui remportera la course ?", ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        draft.name.isBlank() ? "Qui remportera la course ?" : draft.name, 64,
                        text -> {
                            if (text != null && !text.isBlank()) {
                                draft.name = text;
                            }
                            openCreate(sp);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Description", ChatFormatting.GRAY),
                Icons.label(draft.description.isBlank() ? "a definir" : draft.description,
                        draft.description.isBlank() ? ChatFormatting.RED : ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Description", ChatFormatting.GOLD),
                        List.of(Icons.lore("Expliquez clairement sur quoi on parie", ChatFormatting.GRAY),
                                Icons.lore("Ex : Horacio et Boury s'affrontent autour de l'ile.",
                                        ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), draft.description, 128,
                        text -> {
                            draft.description = text == null ? "" : text;
                            openCreate(sp);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Propositions", ChatFormatting.GRAY),
                Icons.label(draft.options.size() + " / " + BetData.MAX_OPTIONS
                                + (draft.options.size() < 2 ? " - il en faut au moins 2" : ""),
                        draft.options.size() < 2 ? ChatFormatting.RED : ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openOptions(sp, 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Duree des mises", ChatFormatting.GRAY),
                Icons.label(draft.minutes + " minute(s)", ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Duree des mises", ChatFormatting.GOLD),
                        List.of(Icons.lore("En minutes : les mises se ferment toutes seules a la fin",
                                        ChatFormatting.GRAY),
                                Icons.lore("Vous pourrez aussi les fermer avant l'echeance",
                                        ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), draft.minutes, 1, 1_440,
                        v -> {
                            draft.minutes = (int) v;
                            openCreate(sp);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Bookmaker", ChatFormatting.GRAY),
                Icons.label("pose la ou vous vous tenez a la validation", ChatFormatting.DARK_GRAY),
                null, null));

        boolean ready = !draft.name.isBlank() && draft.options.size() >= 2;
        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(
                Icons.label(ready ? "Recapitulatif" : "Incomplet", ready ? ChatFormatting.GREEN : ChatFormatting.RED),
                sp -> {
                    if (!ready) {
                        sp.sendSystemMessage(Messages.warn(draft.name.isBlank()
                                ? "Donnez un nom a votre pari."
                                : "Il faut au moins deux propositions."));
                        openCreate(sp);
                        return;
                    }
                    openRecap(sp);
                }));
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Abandonner", ChatFormatting.RED),
                sp -> {
                    DRAFTS.remove(sp.getUUID());
                    sp.sendSystemMessage(Messages.info("Creation abandonnee."));
                    com.utopia.menu.MainMenu.open(sp);
                }));

        OwoMenuServer.openPanel(player, title, rows, footer, BetMenus::openCreate,
                com.utopia.menu.MainMenu::open);
    }

    /** Propositions du brouillon : autant qu'on veut, deux au minimum, jamais deux fois la meme. */
    public static void openOptions(ServerPlayer player, int page) {
        Draft draft = DRAFTS.get(player.getUUID());
        if (draft == null) {
            openCreate(player);
            return;
        }
        Component title = Component.literal("Propositions")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(
                Component.literal(draft.options.size() + " proposition(s) - deux au minimum")
                        .withStyle(s -> s.withColor(draft.options.size() < 2 ? ChatFormatting.RED
                                : ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Joueurs, equipes, couleurs, objets, resultats... a votre guise.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (draft.options.size() < BetData.MAX_OPTIONS) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                    Icons.label("Ajouter une proposition", ChatFormatting.GREEN),
                    Icons.lore("Une reponse possible au pari", ChatFormatting.GRAY),
                    sp -> Menus.promptFreeText(sp, Icons.label("Nouvelle proposition", ChatFormatting.GOLD),
                            List.of(Icons.lore("Ex : Horacio, Boury, Egalite...", ChatFormatting.GRAY)),
                            Icons.label("Ajouter", ChatFormatting.GREEN), "", 48,
                            text -> {
                                addOption(sp, draft, text);
                                openOptions(sp, 0);
                            })));
        }
        for (int i = 0; i < draft.options.size(); i++) {
            final int index = i;
            String label = draft.options.get(i);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                    Icons.label(label, ChatFormatting.WHITE),
                    Icons.lore("Clic : retirer cette proposition", ChatFormatting.GRAY),
                    sp -> {
                        Draft d = DRAFTS.get(sp.getUUID());
                        if (d != null && index < d.options.size()) {
                            d.options.remove(index);
                        }
                        openOptions(sp, 0);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                BetMenus::openOptions, BetMenus::openCreate);
    }

    private static void addOption(ServerPlayer player, Draft draft, String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        String clean = label.trim();
        for (String existing : draft.options) {
            if (existing.equalsIgnoreCase(clean)) {
                player.sendSystemMessage(Messages.warn("Cette proposition existe deja."));
                return;
            }
        }
        if (draft.options.size() >= BetData.MAX_OPTIONS) {
            player.sendSystemMessage(Messages.warn("Nombre maximal de propositions atteint."));
            return;
        }
        draft.options.add(clean);
    }

    /** Recapitulatif : dernier regard avant publication, rien ne sera modifiable ensuite. */
    public static void openRecap(ServerPlayer player) {
        Draft draft = DRAFTS.get(player.getUUID());
        if (draft == null) {
            openCreate(player);
            return;
        }
        Component title = Component.literal("Recapitulatif du pari")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(row("Nom", draft.name, ChatFormatting.WHITE));
        rows.add(row("Description", draft.description.isBlank() ? "-" : draft.description,
                ChatFormatting.GRAY));
        rows.add(row("Createur", player.getGameProfile().getName(), ChatFormatting.AQUA));
        rows.add(row("Duree des mises", draft.minutes + " minute(s)", ChatFormatting.WHITE));
        for (String option : draft.options) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("Proposition", ChatFormatting.DARK_GRAY),
                    Icons.label(option, ChatFormatting.AQUA), null, null));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Attention", ChatFormatting.RED),
                Icons.label("nom, description et propositions ne seront plus modifiables",
                        ChatFormatting.RED), null, null));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(
                        Icons.label("Creer et placer le Bookmaker", ChatFormatting.GREEN),
                        BetMenus::publish),
                new OwoMenuServer.PanelAction(Icons.label("Revenir en arriere", ChatFormatting.YELLOW),
                        BetMenus::openCreate),
                new OwoMenuServer.PanelAction(Icons.label("Annuler", ChatFormatting.RED),
                        sp -> {
                            DRAFTS.remove(sp.getUUID());
                            com.utopia.menu.MainMenu.open(sp);
                        }));

        OwoMenuServer.openPanel(player, title, rows, footer, BetMenus::openRecap, BetMenus::openCreate);
    }

    private static void publish(ServerPlayer player) {
        Draft draft = DRAFTS.get(player.getUUID());
        if (draft == null) {
            openCreate(player);
            return;
        }
        BetData data = BetData.get(player.server);
        if (data.activeOf(player.getUUID()) != null) {
            player.sendSystemMessage(Messages.warn("Vous possedez deja un pari en cours."));
            return;
        }
        if (draft.name.isBlank() || draft.options.size() < 2) {
            openCreate(player);
            return;
        }
        BetData.Bet bet = data.create(player.getUUID(), player.getGameProfile().getName());
        bet.name = draft.name;
        bet.description = draft.description;
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (String label : draft.options) {
            String id = BetData.slug(label);
            String unique = id;
            int n = 2;
            while (!used.add(unique)) {
                unique = id + "_" + n++;
            }
            bet.options.add(new BetData.Option(unique, label));
        }
        BetManager.publish(player, bet, draft.minutes);
        DRAFTS.remove(player.getUUID());
        player.sendSystemMessage(Messages.success("Pari \"" + bet.name + "\" ouvert : le Bookmaker vous "
                + "attend ici pendant " + draft.minutes + " minute(s)."));
        openBookmaker(player, bet.id);
    }

    // ==============================================================================================
    //  Interface du Bookmaker
    // ==============================================================================================

    public static void openBookmaker(ServerPlayer player, String betId) {
        MinecraftServer server = player.server;
        BetData.Bet bet = BetData.get(server).bet(betId);
        if (bet == null) {
            player.sendSystemMessage(Messages.warn("Ce pari n'existe plus."));
            return;
        }
        boolean isCreator = bet.creator.equals(player.getUUID());
        boolean isAdmin = player.hasPermissions(2);
        long pot = bet.pot();
        long myStake = bet.stakeOf(player.getUUID());
        String myOption = bet.choice.get(player.getUUID());

        Component title = Component.literal(bet.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        if (!bet.description.isBlank()) {
            rows.add(row("Description", bet.description, ChatFormatting.GRAY));
        }
        rows.add(row("Createur", bet.creatorName, ChatFormatting.AQUA));
        rows.add(row("Etat", bet.state.label(), stateColor(bet.state)));
        if (bet.state == BetData.State.OUVERT) {
            rows.add(row("Temps restant", BetManager.countdown(bet.remainingMs())
                    + " (fermeture a " + BetManager.stamp(bet.closesAt) + ")", ChatFormatting.YELLOW));
        }
        rows.add(row("Cagnotte totale", pot + " Utopieces", ChatFormatting.GOLD));

        for (BetData.Option option : bet.options) {
            String optionId = option.id;
            boolean canBet = bet.acceptsWagers() && (myOption == null || myOption.equals(optionId));
            String value = option.pool + " Utopieces - " + bet.bettors(optionId) + " joueur(s) - cote "
                    + BetManager.odds(bet.odds(optionId));
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(option.label + (optionId.equals(myOption) ? " (votre choix)" : ""),
                            optionId.equals(bet.winner) ? ChatFormatting.GREEN
                                    : optionId.equals(myOption) ? ChatFormatting.AQUA : ChatFormatting.WHITE),
                    Icons.label(value, ChatFormatting.GRAY),
                    canBet ? Icons.label(myStake > 0 ? "Ajouter" : "Miser", ChatFormatting.GREEN) : null,
                    !canBet ? null : sp -> promptWager(sp, betId, optionId)));
        }

        if (myStake > 0 && myOption != null) {
            BetData.Option mine = bet.option(myOption);
            double odds = bet.odds(myOption);
            long back = Math.round(myStake * odds);
            rows.add(row("Votre mise", myStake + " Utopieces sur \""
                    + (mine == null ? myOption : mine.label) + "\"", ChatFormatting.AQUA));
            rows.add(row("Retour estime", back + " Utopieces", ChatFormatting.GREEN));
            rows.add(row("Benefice estime", (back - myStake) + " Utopieces", ChatFormatting.GREEN));
            if (bet.state == BetData.State.OUVERT) {
                rows.add(row("Estimation", "les cotes bougent a chaque mise", ChatFormatting.DARK_GRAY));
            }
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Parieurs", ChatFormatting.GRAY),
                Icons.label(bet.choice.size() + " joueur(s)", ChatFormatting.WHITE),
                Icons.label("Voir", ChatFormatting.YELLOW),
                sp -> openBettors(sp, betId, 0)));

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (isCreator || isAdmin) {
            if (bet.state == BetData.State.OUVERT) {
                footer.add(new OwoMenuServer.PanelAction(Icons.label("Fermer les mises", ChatFormatting.YELLOW),
                        sp -> confirmClose(sp, betId)));
            }
            if (bet.state == BetData.State.FERME && isCreator) {
                footer.add(new OwoMenuServer.PanelAction(Icons.label("Designer le vainqueur", ChatFormatting.GREEN),
                        sp -> openResolve(sp, betId)));
            }
            boolean frozen = BetManager.frozenFor(bet, player);
            if (!bet.state.closed() && !frozen) {
                footer.add(new OwoMenuServer.PanelAction(Icons.label("Annuler le pari", ChatFormatting.RED),
                        sp -> confirmCancel(sp, betId)));
            }
            if (!frozen) {
                footer.add(new OwoMenuServer.PanelAction(Icons.label("Deplacer", ChatFormatting.LIGHT_PURPLE),
                        sp -> openMove(sp, betId)));
            }
        }

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openBookmaker(sp, betId), null);
    }

    private static ChatFormatting stateColor(BetData.State state) {
        return switch (state) {
            case OUVERT -> ChatFormatting.GREEN;
            case FERME -> ChatFormatting.YELLOW;
            case RESOLU -> ChatFormatting.AQUA;
            case ANNULE -> ChatFormatting.GRAY;
            case ARCHIVE -> ChatFormatting.DARK_GRAY;
            case SUSPENDU, ERREUR -> ChatFormatting.RED;
        };
    }

    /** Liste des parieurs : qui a choisi quoi, et pour combien. */
    public static void openBettors(ServerPlayer player, String betId, int page) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null) {
            return;
        }
        Component title = Component.literal("Parieurs - " + bet.name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<Map.Entry<UUID, String>> all = new ArrayList<>(bet.choice.entrySet());
        int perPage = 10;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * perPage;
        int to = Math.min(all.size(), from + perPage);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, String> e : all.subList(Math.min(from, all.size()), to)) {
            BetData.Option option = bet.option(e.getValue());
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(nameOf(bet, e.getKey()), ChatFormatting.WHITE),
                    Icons.label((option == null ? e.getValue() : option.label) + " - "
                            + bet.stakeOf(e.getKey()) + " Utopieces", ChatFormatting.GOLD), null, null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.PanelRow(Icons.label("Aucune mise", ChatFormatting.GRAY),
                    Icons.label("soyez le premier", ChatFormatting.DARK_GRAY), null, null));
        }
        Consumer<ServerPlayer> prev = pages > 1 ? sp -> openBettors(sp, betId, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1 ? sp -> openBettors(sp, betId, (cur + 1) % pages) : null;
        OwoMenuServer.openPanel(player, title, rows, List.of(), false, prev, next,
                sp -> openBettors(sp, betId, cur), sp -> openBookmaker(sp, betId));
    }

    static String nameOf(BetData.Bet bet, UUID player) {
        for (BetData.Wager w : bet.wagers) {
            if (w.player().equals(player)) {
                return w.playerName();
            }
        }
        return player.toString().substring(0, 8);
    }

    // ==============================================================================================
    //  Mise
    // ==============================================================================================

    private static void promptWager(ServerPlayer player, String betId, String optionId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || !bet.acceptsWagers()) {
            openBookmaker(player, betId);
            return;
        }
        BetData.Option option = bet.option(optionId);
        if (option == null) {
            openBookmaker(player, betId);
            return;
        }
        String mine = bet.choice.get(player.getUUID());
        if (mine != null && !mine.equals(optionId)) {
            player.sendSystemMessage(Messages.warn(
                    BetManager.reason(BetManager.WagerResult.LOCKED_OPTION)));
            openBookmaker(player, betId);
            return;
        }
        long available = EconomyManager.countCoins(player)
                + EconomyManager.getBalance(player.server, player.getUUID());
        if (available < BetManager.MIN_WAGER) {
            player.sendSystemMessage(Messages.warn("Vous n'avez pas d'Utopieces a miser."));
            openBookmaker(player, betId);
            return;
        }
        long stake = bet.stakeOf(player.getUUID());
        Menus.promptAmount(player, Icons.label("Miser sur \"" + option.label + "\"", ChatFormatting.GOLD),
                List.of(Icons.lore("Disponible (pieces + banque) : " + available + " Utopieces",
                                ChatFormatting.GRAY),
                        Icons.lore("Cote actuelle : " + BetManager.odds(bet.odds(optionId))
                                + " - cagnotte " + bet.pot(), ChatFormatting.DARK_GRAY),
                        Icons.lore(stake > 0 ? "Deja mise : " + stake + " Utopieces"
                                : "Votre choix deviendra definitif", ChatFormatting.DARK_GRAY)),
                Icons.label("Continuer", ChatFormatting.GREEN),
                Math.min(100, available), BetManager.MIN_WAGER, available,
                amount -> confirmWager(player, betId, optionId, amount));
    }

    private static void confirmWager(ServerPlayer player, String betId, String optionId, long amount) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || !bet.acceptsWagers()) {
            openBookmaker(player, betId);
            return;
        }
        BetData.Option option = bet.option(optionId);
        if (option == null) {
            openBookmaker(player, betId);
            return;
        }
        long stake = bet.stakeOf(player.getUUID()) + amount;
        double odds = bet.oddsWith(optionId, amount);
        long back = Math.round(stake * odds);
        long token = BetManager.newToken(player);

        Component title = Component.literal("Confirmer la mise")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal(amount + " Utopieces sur \"" + option.label + "\"")
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                Component.literal("Cote estimee " + BetManager.odds(odds) + " - retour estime "
                                + back + " Utopieces (benefice " + (back - stake) + ")")
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)),
                Component.literal("Definitif : la mise ne peut ni etre reprise ni changer de proposition.")
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("Confirmer", ChatFormatting.GREEN),
                        Icons.lore("Les Utopieces partent immediatement dans la cagnotte",
                                ChatFormatting.GRAY),
                        sp -> {
                            BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                            if (fresh == null) {
                                sp.sendSystemMessage(Messages.warn("Ce pari n'existe plus."));
                                return;
                            }
                            BetManager.WagerResult result =
                                    BetManager.wager(sp, fresh, optionId, amount, token);
                            if (result != BetManager.WagerResult.OK) {
                                sp.sendSystemMessage(Messages.warn(BetManager.reason(result)));
                            } else {
                                sp.sendSystemMessage(Messages.success("Mise enregistree : " + amount
                                        + " Utopieces sur \"" + option.label + "\"."));
                            }
                            openBookmaker(sp, betId);
                        }),
                new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                        Icons.label("Renoncer", ChatFormatting.RED),
                        Icons.lore("Revenir au Bookmaker", ChatFormatting.GRAY),
                        sp -> openBookmaker(sp, betId)));

        OwoMenuServer.openHub(player, title, stats, entries, null, sp -> openBookmaker(sp, betId));
    }

    // ==============================================================================================
    //  Commandes du createur
    // ==============================================================================================

    private static void confirmClose(ServerPlayer player, String betId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || bet.state != BetData.State.OUVERT) {
            openBookmaker(player, betId);
            return;
        }
        Component title = Component.literal("Fermer les mises maintenant")
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Plus aucune mise ne sera acceptee et les cotes seront figees.")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Cette action est irreversible.")
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));
        OwoMenuServer.openHub(player, title, stats, List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("Fermer les mises", ChatFormatting.YELLOW),
                        Icons.lore(bet.pot() + " Utopieces en jeu", ChatFormatting.GRAY),
                        sp -> {
                            BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                            if (fresh == null || fresh.state != BetData.State.OUVERT) {
                                sp.sendSystemMessage(Messages.warn("Les mises ne sont plus ouvertes."));
                            } else if (BetManager.close(sp.server, fresh, sp.getGameProfile().getName())) {
                                sp.sendSystemMessage(fresh.state == BetData.State.ANNULE
                                        ? Messages.warn("Mises fermees, mais le pari a du etre annule : "
                                                + fresh.cancelReason)
                                        : Messages.success("Mises fermees."));
                            } else {
                                sp.sendSystemMessage(Messages.error("Fermeture bloquee : "
                                        + "un administrateur doit verifier ce pari."));
                            }
                            openBookmaker(sp, betId);
                        }),
                new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                        Icons.label("Revenir en arriere", ChatFormatting.GRAY), Component.empty(),
                        sp -> openBookmaker(sp, betId))),
                null, sp -> openBookmaker(sp, betId));
    }

    public static void openResolve(ServerPlayer player, String betId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || bet.state != BetData.State.FERME
                || !bet.creator.equals(player.getUUID())) {
            openBookmaker(player, betId);
            return;
        }
        Component title = Component.literal("Designer le vainqueur")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Cagnotte : " + bet.pot() + " Utopieces")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)),
                Component.literal("Choisissez la proposition qui l'a emporte.")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (BetData.Option option : bet.options) {
            String optionId = option.id;
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                    Icons.label(option.label, ChatFormatting.WHITE),
                    Icons.lore(option.pool + " Utopieces - " + bet.bettors(optionId) + " joueur(s)",
                            ChatFormatting.GRAY),
                    sp -> confirmResolve(sp, betId, optionId)));
        }
        OwoMenuServer.openHubPaged(player, title, stats, entries, 0, PAGE_SIZE,
                (sp, p) -> openResolve(sp, betId), sp -> openBookmaker(sp, betId));
    }

    private static void confirmResolve(ServerPlayer player, String betId, String optionId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || bet.state != BetData.State.FERME) {
            openBookmaker(player, betId);
            return;
        }
        BetData.Option option = bet.option(optionId);
        if (option == null) {
            openResolve(player, betId);
            return;
        }
        Component title = Component.literal("Confirmer le resultat")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal(bet.name).withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                Component.literal("Vainqueur : " + option.label)
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)),
                Component.literal("Cagnotte : " + bet.pot() + " Utopieces - " + bet.bettors(optionId)
                                + " gagnant(s)")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)),
                Component.literal("La decision est definitive.")
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));

        OwoMenuServer.openHub(player, title, stats, List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("Confirmer definitivement", ChatFormatting.GREEN),
                        Icons.lore("Les gains seront verses immediatement", ChatFormatting.GRAY),
                        sp -> {
                            BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                            if (fresh == null || fresh.state != BetData.State.FERME) {
                                sp.sendSystemMessage(Messages.warn("Ce pari n'attend plus de resultat."));
                                openBookmaker(sp, betId);
                                return;
                            }
                            if (BetManager.resolve(sp.server, fresh, optionId,
                                    sp.getGameProfile().getName())) {
                                sp.sendSystemMessage(Messages.success("Resultat enregistre et cagnotte "
                                        + "repartie."));
                            } else {
                                sp.sendSystemMessage(Messages.error("Repartition bloquee : "
                                        + "un administrateur doit verifier ce pari."));
                            }
                            openBookmaker(sp, betId);
                        }),
                new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                        Icons.label("Revenir en arriere", ChatFormatting.GRAY), Component.empty(),
                        sp -> openResolve(sp, betId))),
                null, sp -> openResolve(sp, betId));
    }

    public static void confirmCancel(ServerPlayer player, String betId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || bet.state.closed() || BetManager.frozenFor(bet, player)) {
            openBookmaker(player, betId);
            return;
        }
        Component title = Component.literal("Annuler le pari")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Toutes les mises seront integralement remboursees.")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Aucun gagnant ne sera designe ; le Bookmaker et son hologramme "
                                + "disparaitront apres remboursement.")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal(bet.pot() + " Utopieces a rendre.")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));

        OwoMenuServer.openHub(player, title, stats, List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.BARRIER),
                        Icons.label("Annuler definitivement", ChatFormatting.RED),
                        Icons.lore("Rembourser tout le monde", ChatFormatting.GRAY),
                        sp -> {
                            BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                            if (BetManager.cancelBy(sp, fresh, "Annule par "
                                    + sp.getGameProfile().getName())) {
                                sp.sendSystemMessage(Messages.success("Pari annule, mises remboursees."));
                                com.utopia.menu.MainMenu.open(sp);
                            } else {
                                sp.sendSystemMessage(Messages.warn("Annulation impossible : ce pari est "
                                        + "entre les mains de l'administration ou ses comptes sont bloques."));
                                openBookmaker(sp, betId);
                            }
                        }),
                new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                        Icons.label("Revenir en arriere", ChatFormatting.GRAY), Component.empty(),
                        sp -> openBookmaker(sp, betId))),
                null, sp -> openBookmaker(sp, betId));
    }

    /** Deplacement du Bookmaker et reglage vertical de son hologramme. */
    public static void openMove(ServerPlayer player, String betId) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet == null || (!bet.creator.equals(player.getUUID()) && !player.hasPermissions(2))
                || BetManager.frozenFor(bet, player)) {
            openBookmaker(player, betId);
            return;
        }
        Component title = Component.literal("Bookmaker - " + bet.name)
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true));
        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Position", ChatFormatting.GRAY),
                        Icons.label(String.format("%.0f %.0f %.0f", bet.x, bet.y, bet.z),
                                ChatFormatting.WHITE),
                        Icons.label("Placer ici", ChatFormatting.GREEN),
                        sp -> {
                            BetData.Bet fresh = BetData.get(sp.server).bet(betId);
                            if (fresh != null && !BetManager.frozenFor(fresh, sp)) {
                                BetManager.place(sp, fresh);
                                fresh.log(sp.getGameProfile().getName() + " a deplace le Bookmaker");
                                BetManager.syncWorld(sp.server);
                                sp.sendSystemMessage(Messages.success("Bookmaker deplace ici."));
                            }
                            openMove(sp, betId);
                        }),
                new OwoMenuServer.PanelRow(
                        Icons.label("Hologramme", ChatFormatting.GRAY),
                        Icons.label(String.format("%+.2f bloc", bet.holoDy), ChatFormatting.AQUA),
                        Icons.label("Monter", ChatFormatting.YELLOW),
                        sp -> shiftHolo(sp, betId, 0.25)),
                new OwoMenuServer.PanelRow(
                        Icons.label("Hologramme", ChatFormatting.GRAY),
                        Icons.label("descendre d'un quart de bloc", ChatFormatting.DARK_GRAY),
                        Icons.label("Descendre", ChatFormatting.YELLOW),
                        sp -> shiftHolo(sp, betId, -0.25)));

        OwoMenuServer.openPanel(player, title, rows, List.of(), sp -> openMove(sp, betId),
                sp -> openBookmaker(sp, betId));
    }

    private static void shiftHolo(ServerPlayer player, String betId, double delta) {
        BetData.Bet bet = BetData.get(player.server).bet(betId);
        if (bet != null) {
            bet.holoDy = Math.max(-2.0, Math.min(4.0, bet.holoDy + delta));
            BetData.get(player.server).setDirty();
            BetManager.syncWorld(player.server);
        }
        openMove(player, betId);
    }

    // ==============================================================================================
    //  Utilitaires
    // ==============================================================================================

    static OwoMenuServer.PanelRow row(String label, String value, ChatFormatting color) {
        return new OwoMenuServer.PanelRow(Icons.label(label, ChatFormatting.GRAY),
                Icons.label(value, color), null, null);
    }
}
