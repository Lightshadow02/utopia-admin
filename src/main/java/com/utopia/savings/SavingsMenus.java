package com.utopia.savings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.utopia.data.SavingsData;
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
 * Interfaces des livrets d'epargne : le registre tenu par le banquier, la fiche et le suivi quotidien
 * de chaque joueur, le bareme des taux (lisible par tous, modifiable par les seuls administrateurs) et
 * la vue personnelle du titulaire.
 */
public final class SavingsMenus {

    private static final int PAGE_SIZE = 12;
    private static final int LINES_PER_PAGE = 10;

    /** Or pour l'epargne, bleu pour le plafond : memes codes couleur que les chantiers. */
    private static final int COLOR_SAVINGS = 0xFFE8B23A;
    private static final int COLOR_CEILING = 0xFF4A7FD4;

    private SavingsMenus() {
    }

    // ==============================================================================================
    //  Registre (banquier, maire, op)
    // ==============================================================================================

    public static void openRegistry(ServerPlayer player) {
        openRegistry(player, 0);
    }

    /**
     * Registre en tableau : une ligne par livret, soldes et taux cales a droite les uns sous les
     * autres pour que le banquier compare sans survoler chaque tete. Le bareme et l'ouverture d'un
     * livret restent en tete, le journal et l'epargne personnelle en pied : ce ne sont pas des
     * livrets, ils n'ont rien a faire dans la liste.
     */
    public static void openRegistry(ServerPlayer player, int page) {
        if (!SavingsManager.canKeepRegistry(player)) {
            player.sendSystemMessage(Messages.warn("Seul le banquier tient le registre des livrets."));
            openOwn(player);
            return;
        }
        MinecraftServer server = player.server;
        SavingsData data = SavingsData.get(server);
        List<SavingsData.Account> accounts = data.ranking();

        int pages = Math.max(1, (accounts.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(accounts.size(), from + PAGE_SIZE);

        Component title = Icons.screenTitle("Registre des livrets"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.GOLD);

        List<Component> stats = new ArrayList<>();
        stats.add(stat(data.accounts().size() + " livret(s) - total epargne : ",
                data.totalSaved() + " Utopieces", ChatFormatting.AQUA));
        stats.add(Icons.lore("Interets verses a minuit, heure de Paris.", ChatFormatting.GRAY));
        if (data.lastRunDay() > 0) {
            stats.add(stat("Derniere nuit (" + SavingsManager.day(data.lastRunDay()) + ") : ",
                    "+" + data.lastRunTotal() + " Utopieces", ChatFormatting.GREEN));
        }
        if (!data.enabled()) {
            stats.add(Component.literal("Interets suspendus : le guichet reste ouvert "
                    + "(depots et retraits possibles).")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));
        }

        boolean admin = SavingsManager.canSetRate(player);
        List<OwoMenuServer.PanelRow> controls = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Bareme des taux", ChatFormatting.GRAY),
                        Icons.label("base " + SavingsManager.rate(data.baseRate()) + " - "
                                + data.tiers().size() + " palier(s)", ChatFormatting.GOLD),
                        Icons.label(admin ? "Regler" : "Consulter", ChatFormatting.YELLOW),
                        SavingsMenus::openScale),
                new OwoMenuServer.PanelRow(
                        Icons.label("Ouvrir un livret", ChatFormatting.GRAY),
                        Icons.label("creer le livret d'un joueur", ChatFormatting.DARK_GRAY),
                        Icons.label("Ouvrir", ChatFormatting.GREEN),
                        sp -> openAccountPicker(sp, 0)));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("JOUEUR"), 86, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("SOLDE"), 70, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("TAUX"), 62, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("CETTE NUIT"), 78, OwoMenuServer.Column.RIGHT));

        long today = SavingsManager.today();
        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (SavingsData.Account account : accounts.subList(Math.min(from, accounts.size()), to)) {
            UUID owner = account.owner;
            SavingsData.DayEntry last = account.lastEntry();
            long gained = last != null && last.day == today ? last.interest : 0;
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(data.nameOf(owner), ChatFormatting.WHITE),
                    Icons.label(String.valueOf(account.balance), ChatFormatting.GOLD),
                    Icons.label(SavingsManager.rate(data.rateFor(account.balance)),
                            ChatFormatting.GREEN),
                    Icons.label("+" + gained,
                            gained > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)),
                    sp -> openAccount(sp, owner)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Aucun livret", ChatFormatting.RED),
                    Component.empty(), Component.empty(), Component.empty()), null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Journal des operations", ChatFormatting.YELLOW),
                        sp -> openJournal(sp, 0)),
                new OwoMenuServer.PanelAction(Icons.label("Mon livret", ChatFormatting.AQUA),
                        SavingsMenus::openOwn));

        java.util.function.Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openRegistry(sp, (cur - 1 + pages) % pages) : null;
        java.util.function.Consumer<ServerPlayer> next = pages > 1
                ? sp -> openRegistry(sp, (cur + 1) % pages) : null;

        OwoMenuServer.openTable(player, title, stats, controls, columns, rows, footer, prev, next,
                sp -> openRegistry(sp, cur),
                player.hasPermissions(2) ? com.utopia.menu.AdminMenu::open : null);
    }

    // ==============================================================================================
    //  Fiche d'un livret
    // ==============================================================================================

    public static void openAccount(ServerPlayer player, UUID owner) {
        if (!SavingsManager.canKeepRegistry(player)) {
            player.sendSystemMessage(Messages.warn("Seul le banquier tient le registre des livrets."));
            openOwn(player);
            return;
        }
        SavingsData data = SavingsData.get(player.server);
        SavingsData.Account account = data.account(owner);
        if (account == null) {
            player.sendSystemMessage(Messages.warn("Ce livret n'existe plus."));
            openRegistry(player);
            return;
        }
        String name = data.nameOf(owner);
        double rate = data.rateFor(account.balance);
        SavingsData.DayEntry last = account.lastEntry();

        Component title = Icons.title("Livret de " + name, ChatFormatting.GOLD);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(row("Solde du livret", account.balance + " Utopieces", ChatFormatting.GOLD));
        rows.add(row("Taux applique", SavingsManager.rate(rate) + " par nuit", ChatFormatting.GREEN));
        rows.add(row("Detail du taux", "base " + SavingsManager.rate(data.baseRate())
                + " + paliers atteints", ChatFormatting.DARK_GRAY));
        rows.add(row("Derniere nuit", last == null ? "aucune"
                : "+" + last.interest + " Utopieces le " + SavingsManager.day(last.day),
                ChatFormatting.AQUA));
        rows.add(row("Total des interets", account.totalInterest + " Utopieces", ChatFormatting.GREEN));
        rows.add(row("Depots cumules", account.totalDeposits + " Utopieces", ChatFormatting.WHITE));
        rows.add(row("Retraits cumules", account.totalWithdrawals + " Utopieces", ChatFormatting.WHITE));
        rows.add(row("Livret ouvert le", SavingsManager.day(account.openedDay), ChatFormatting.DARK_GRAY));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Suivi quotidien", ChatFormatting.GRAY),
                Icons.label(account.history.size() + " journee(s) enregistree(s)", ChatFormatting.AQUA),
                Icons.label("Consulter", ChatFormatting.YELLOW),
                sp -> openTracking(sp, owner, 0)));

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Deposer", ChatFormatting.GREEN),
                sp -> promptDeposit(sp, owner)));
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Retirer", ChatFormatting.YELLOW),
                sp -> promptWithdraw(sp, owner)));
        if (SavingsManager.canSetRate(player)) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Ecriture admin", ChatFormatting.LIGHT_PURPLE),
                    sp -> promptAdjust(sp, owner)));
            if (account.balance == 0) {
                footer.add(new OwoMenuServer.PanelAction(Icons.label("Fermer le livret", ChatFormatting.RED),
                        sp -> {
                            // L'ecran a pu rester ouvert pendant qu'un depot creditait le livret :
                            // on relit le solde au moment du clic, jamais celui de l'affichage.
                            SavingsData d = SavingsData.get(sp.server);
                            SavingsData.Account fresh = d.account(owner);
                            if (fresh == null) {
                                sp.sendSystemMessage(Messages.warn("Ce livret n'existe plus."));
                                openRegistry(sp);
                                return;
                            }
                            if (fresh.balance != 0) {
                                sp.sendSystemMessage(Messages.warn("Ce livret n'est plus vide ("
                                        + fresh.balance + " Utopieces) : videz-le au comptoir avant"
                                        + " de le fermer."));
                                openAccount(sp, owner);
                                return;
                            }
                            d.closeAccount(owner);
                            d.log(sp.getGameProfile().getName() + " a ferme le livret de " + name);
                            sp.sendSystemMessage(Messages.success("Livret de " + name + " ferme."));
                            openRegistry(sp);
                        }));
            }
        }

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openAccount(sp, owner),
                SavingsMenus::openRegistry);
    }

    /** Depot : les pieces confiees au banquier quittent son inventaire et rejoignent le livret. */
    private static void promptDeposit(ServerPlayer banker, UUID owner) {
        SavingsData data = SavingsData.get(banker.server);
        String name = data.nameOf(owner);
        int coins = EconomyManager.countCoins(banker);
        if (coins <= 0) {
            banker.sendSystemMessage(Messages.warn("Vous n'avez aucune piece sur vous : "
                    + "le joueur doit d'abord vous les remettre en main propre."));
            openAccount(banker, owner);
            return;
        }
        Menus.promptAmount(banker, Icons.label("Deposer sur le livret de " + name, ChatFormatting.GREEN),
                List.of(Icons.lore("Pieces sur vous : " + coins, ChatFormatting.GRAY),
                        Icons.lore("Elles quittent votre inventaire pour rejoindre le livret",
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Deposer", ChatFormatting.GREEN), coins, 1, coins,
                amount -> {
                    SavingsManager.MoveResult result = SavingsManager.deposit(banker, owner, amount);
                    if (result != SavingsManager.MoveResult.OK) {
                        banker.sendSystemMessage(Messages.warn(SavingsManager.reason(result)));
                    } else {
                        banker.sendSystemMessage(Messages.success("Depose " + amount
                                + " Utopieces sur le livret de " + name + "."));
                    }
                    openAccount(banker, owner);
                });
    }

    /** Retrait : le livret est debite, les pieces reviennent au banquier qui les rend au guichet. */
    private static void promptWithdraw(ServerPlayer banker, UUID owner) {
        SavingsData data = SavingsData.get(banker.server);
        SavingsData.Account account = data.account(owner);
        if (account == null) {
            openRegistry(banker);
            return;
        }
        String name = data.nameOf(owner);
        long space = EconomyManager.freeSpaceForCoins(banker);
        long max = Math.min(account.balance, space);
        if (max <= 0) {
            banker.sendSystemMessage(Messages.warn(account.balance <= 0
                    ? "Ce livret est vide."
                    : "Pas assez de place dans votre inventaire pour sortir les pieces."));
            openAccount(banker, owner);
            return;
        }
        Menus.promptAmount(banker, Icons.label("Retirer du livret de " + name, ChatFormatting.YELLOW),
                List.of(Icons.lore("Solde du livret : " + account.balance, ChatFormatting.GRAY),
                        Icons.lore("Place dans votre inventaire : " + space + " piece(s)",
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Retirer", ChatFormatting.YELLOW), max, 1, max,
                amount -> {
                    SavingsManager.MoveResult result = SavingsManager.withdraw(banker, owner, amount);
                    if (result != SavingsManager.MoveResult.OK) {
                        banker.sendSystemMessage(Messages.warn(SavingsManager.reason(result)));
                    } else {
                        banker.sendSystemMessage(Messages.success("Retire " + amount
                                + " Utopieces : remettez-les a " + name + "."));
                    }
                    openAccount(banker, owner);
                });
    }

    /** Ecriture administrative : ajuste le livret sans pieces physiques (correction, evenement). */
    private static void promptAdjust(ServerPlayer admin, UUID owner) {
        if (!SavingsManager.canSetRate(admin)) {
            admin.sendSystemMessage(Messages.warn("Ecriture reservee a l'administration."));
            openAccount(admin, owner);
            return;
        }
        SavingsData data = SavingsData.get(admin.server);
        String name = data.nameOf(owner);
        List<OwoMenuServer.HubEntry> entries = List.of(
                new OwoMenuServer.HubEntry(new ItemStack(Items.EMERALD),
                        Icons.label("Crediter", ChatFormatting.GREEN),
                        Icons.lore("Ajoute des Utopieces au livret", ChatFormatting.GRAY),
                        sp -> Menus.promptAmount(sp, Icons.label("Crediter " + name, ChatFormatting.GREEN),
                                List.of(Icons.lore("Sans pieces physiques : ecriture comptable",
                                        ChatFormatting.GRAY)),
                                Icons.label("Valider", ChatFormatting.GREEN), 100, 1, 1_000_000_000L,
                                v -> {
                                    sp.sendSystemMessage(SavingsManager.adjust(sp, owner, v)
                                            ? Messages.success("Livret de " + name + " credite de "
                                                    + v + " Utopieces.")
                                            : Messages.warn("Ecriture reservee a l'administration."));
                                    openAccount(sp, owner);
                                })),
                new OwoMenuServer.HubEntry(new ItemStack(Items.REDSTONE),
                        Icons.label("Debiter", ChatFormatting.RED),
                        Icons.lore("Retire des Utopieces du livret", ChatFormatting.GRAY),
                        sp -> Menus.promptAmount(sp, Icons.label("Debiter " + name, ChatFormatting.RED),
                                List.of(Icons.lore("Sans pieces physiques : ecriture comptable",
                                        ChatFormatting.GRAY)),
                                Icons.label("Valider", ChatFormatting.RED), 100, 1, 1_000_000_000L,
                                v -> {
                                    sp.sendSystemMessage(SavingsManager.adjust(sp, owner, -v)
                                            ? Messages.success("Livret de " + name + " debite de "
                                                    + v + " Utopieces.")
                                            : Messages.warn("Ecriture reservee a l'administration."));
                                    openAccount(sp, owner);
                                })));
        OwoMenuServer.openHub(admin, Icons.title("Ecriture admin - " + name, ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("A n'utiliser que pour corriger une erreur.", ChatFormatting.DARK_GRAY)),
                entries, sp -> promptAdjust(sp, owner), sp -> openAccount(sp, owner));
    }

    // ==============================================================================================
    //  Suivi quotidien
    // ==============================================================================================

    public static void openTracking(ServerPlayer player, UUID owner, int page) {
        SavingsData data = SavingsData.get(player.server);
        boolean self = owner.equals(player.getUUID());
        if (!self && !SavingsManager.canKeepRegistry(player)) {
            openOwn(player);
            return;
        }
        SavingsData.Account account = data.account(owner);
        if (account == null) {
            // Le livret a pu etre ferme pendant que l'ecran restait ouvert : on ne renvoie jamais un
            // joueur ordinaire vers le registre.
            if (self) {
                player.sendSystemMessage(Messages.warn("Vous n'avez plus de livret d'epargne."));
                openOwn(player);
            } else {
                openRegistry(player);
            }
            return;
        }
        String name = data.nameOf(owner);

        List<SavingsData.DayEntry> all = new ArrayList<>(account.history);
        Collections.reverse(all); // le plus recent en premier

        int pages = Math.max(1, (all.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * LINES_PER_PAGE;
        int to = Math.min(all.size(), from + LINES_PER_PAGE);

        Component title = Icons.title((self ? "Mon suivi quotidien" : "Suivi - " + name)
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.AQUA);

        List<Component> stats = new ArrayList<>();
        stats.add(stat("Solde du livret : ", account.balance + " Utopieces", ChatFormatting.GOLD));
        stats.add(stat("Taux applique aujourd'hui : ",
                SavingsManager.rate(data.rateFor(account.balance)) + " par nuit", ChatFormatting.GREEN));
        if (all.isEmpty()) {
            stats.add(Icons.lore("Le suivi commence a la premiere nuit d'interets.",
                    ChatFormatting.DARK_GRAY));
        }

        // Le taux a sa colonne : c'est lui qui justifie le montant d'interets de la nuit, et il
        // change avec les paliers. Le lire dans l'en-tete donnerait celui d'aujourd'hui, pas celui
        // qui a reellement ete servi ce jour-la.
        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DATE"), 64, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("TAUX"), 40, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("INTERETS"), 56, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("DEPOTS"), 46, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("RETRAITS"), 54, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("SOLDE"), 48, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (SavingsData.DayEntry e : all.subList(Math.min(from, all.size()), to)) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(SavingsManager.day(e.day), ChatFormatting.GRAY),
                    Icons.label(e.rate > 0 ? SavingsManager.rate(e.rate) : "-",
                            e.rate > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    Icons.label("+" + e.interest,
                            e.interest > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    Icons.label(e.deposits > 0 ? "+" + e.deposits : "-",
                            e.deposits > 0 ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.label(e.withdrawals > 0 ? "-" + e.withdrawals : "-",
                            e.withdrawals > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY),
                    Icons.label(String.valueOf(e.closing), ChatFormatting.GOLD)), null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Aucun jour", ChatFormatting.GRAY),
                    Component.empty(), Component.empty(), Component.empty(), Component.empty(),
                    Component.empty()), null));
        }

        java.util.function.Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openTracking(sp, owner, (cur - 1 + pages) % pages) : null;
        java.util.function.Consumer<ServerPlayer> next = pages > 1
                ? sp -> openTracking(sp, owner, (cur + 1) % pages) : null;

        OwoMenuServer.openTable(player, title, stats, List.of(), columns, rows, List.of(), prev, next,
                sp -> openTracking(sp, owner, cur),
                self ? SavingsMenus::openOwn : sp -> openAccount(sp, owner));
    }

    // ==============================================================================================
    //  Journal
    // ==============================================================================================

    public static void openJournal(ServerPlayer player, int page) {
        if (!SavingsManager.canKeepRegistry(player)) {
            player.sendSystemMessage(Messages.warn("Seul le banquier tient le registre des livrets."));
            openOwn(player);
            return;
        }
        SavingsData data = SavingsData.get(player.server);
        List<SavingsData.LogEntry> all = new ArrayList<>(data.journal());
        Collections.reverse(all);

        int pages = Math.max(1, (all.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * LINES_PER_PAGE;
        int to = Math.min(all.size(), from + LINES_PER_PAGE);

        Component title = Icons.title("Journal des operations"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.YELLOW);

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DATE"), 98, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("OPERATION"), 210, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (SavingsData.LogEntry e : all.subList(Math.min(from, all.size()), to)) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label(com.utopia.job.JobManager.stamp(e.millis()), ChatFormatting.GRAY),
                    Icons.label(e.text(), ChatFormatting.WHITE)), null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Icons.label("Aucune operation", ChatFormatting.GRAY), Component.empty()), null));
        }
        java.util.function.Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openJournal(sp, (cur - 1 + pages) % pages) : null;
        java.util.function.Consumer<ServerPlayer> next = pages > 1
                ? sp -> openJournal(sp, (cur + 1) % pages) : null;

        OwoMenuServer.openTable(player, title, List.of(), List.of(), columns, rows, List.of(),
                prev, next, sp -> openJournal(sp, cur), SavingsMenus::openRegistry);
    }

    // ==============================================================================================
    //  Bareme des taux
    // ==============================================================================================

    public static void openScale(ServerPlayer player) {
        SavingsData data = SavingsData.get(player.server);
        boolean admin = SavingsManager.canSetRate(player);

        Component title = Icons.title("Bareme des taux", ChatFormatting.GOLD);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Taux de base", ChatFormatting.GRAY),
                Icons.label(SavingsManager.rate(data.baseRate()) + " par nuit", ChatFormatting.GOLD),
                admin ? Icons.label("Modifier", ChatFormatting.YELLOW) : null,
                !admin ? null : sp -> promptRate(sp, "Taux de base", data.baseRate(), v -> {
                    SavingsData.get(sp.server).setBaseRate(v);
                    SavingsData.get(sp.server).log(sp.getGameProfile().getName()
                            + " a fixe le taux de base a " + SavingsManager.rate(v));
                    openScale(sp);
                })));
        int index = 0;
        for (SavingsData.Tier tier : data.tiers()) {
            final int i = index++;
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("A partir de " + tier.threshold + " Utopieces", ChatFormatting.GRAY),
                    Icons.label("+" + SavingsManager.rate(tier.bonus), ChatFormatting.GREEN),
                    admin ? Icons.label("Regler", ChatFormatting.YELLOW) : null,
                    !admin ? null : sp -> openTier(sp, i)));
        }
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Plafond par livret", ChatFormatting.GRAY),
                Icons.label(data.ceiling() > 0 ? data.ceiling() + " Utopieces" : "illimite",
                        data.ceiling() > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY),
                admin ? Icons.label("Modifier", ChatFormatting.YELLOW) : null,
                !admin ? null : sp -> Menus.promptAmount(sp,
                        Icons.label("Plafond par livret", ChatFormatting.GOLD),
                        List.of(Icons.lore("0 = aucun plafond", ChatFormatting.GRAY),
                                Icons.lore("Au plafond, les interets cessent d'etre verses",
                                        ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), data.ceiling(), 0, 1_000_000_000L,
                        v -> {
                            SavingsData.get(sp.server).setCeiling(v);
                            SavingsData.get(sp.server).log(sp.getGameProfile().getName()
                                    + " a fixe le plafond des livrets a " + (v > 0 ? v : "illimite"));
                            openScale(sp);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Versement des interets", ChatFormatting.GRAY),
                Icons.label(data.enabled() ? "actif" : "suspendu",
                        data.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED),
                admin ? Icons.label(data.enabled() ? "Suspendre" : "Reactiver", ChatFormatting.YELLOW) : null,
                !admin ? null : sp -> {
                    SavingsData d = SavingsData.get(sp.server);
                    d.setEnabled(!d.enabled());
                    d.log(sp.getGameProfile().getName()
                            + (d.enabled() ? " a reactive" : " a suspendu") + " le versement des interets");
                    openScale(sp);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Heure du versement", ChatFormatting.GRAY),
                Icons.label("minuit, heure de Paris", ChatFormatting.DARK_GRAY), null, null));

        List<OwoMenuServer.PanelAction> footer = !admin ? List.of() : List.of(
                new OwoMenuServer.PanelAction(Icons.label("Ajouter un palier", ChatFormatting.GREEN),
                        SavingsMenus::promptNewTier));

        OwoMenuServer.openPanel(player, title, rows, footer, SavingsMenus::openScale,
                SavingsMenus::openRegistry);
    }

    /** Reglage d'un palier existant : seuil, bonus, suppression. */
    public static void openTier(ServerPlayer admin, int index) {
        if (!SavingsManager.canSetRate(admin)) {
            admin.sendSystemMessage(Messages.warn("Le bareme des taux est fixe par l'administration."));
            openScale(admin);
            return;
        }
        SavingsData data = SavingsData.get(admin.server);
        if (index < 0 || index >= data.tiers().size()) {
            openScale(admin);
            return;
        }
        SavingsData.Tier tier = data.tiers().get(index);

        Component title = Icons.title("Palier - " + tier.threshold + " Utopieces", ChatFormatting.GOLD);

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Seuil", ChatFormatting.GRAY),
                        Icons.label(tier.threshold + " Utopieces", ChatFormatting.AQUA),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptAmount(sp, Icons.label("Seuil du palier", ChatFormatting.GOLD),
                                List.of(Icons.lore("Solde a partir duquel le bonus s'ajoute",
                                        ChatFormatting.GRAY)),
                                Icons.label("Valider", ChatFormatting.GREEN), tier.threshold, 0,
                                1_000_000_000L,
                                v -> {
                                    tier.threshold = v;
                                    SavingsData d = SavingsData.get(sp.server);
                                    d.sortTiers();
                                    d.setDirty();
                                    openScale(sp);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Bonus de taux", ChatFormatting.GRAY),
                        Icons.label("+" + SavingsManager.rate(tier.bonus), ChatFormatting.GREEN),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> promptRate(sp, "Bonus du palier", tier.bonus, v -> {
                            tier.bonus = v;
                            SavingsData.get(sp.server).setDirty();
                            openTier(sp, index);
                        })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Cumul", ChatFormatting.GRAY),
                        Icons.label("s'ajoute au taux de base et aux paliers inferieurs",
                                ChatFormatting.DARK_GRAY), null, null));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer ce palier", ChatFormatting.RED),
                        sp -> {
                            SavingsData d = SavingsData.get(sp.server);
                            d.removeTier(index);
                            d.log(sp.getGameProfile().getName() + " a supprime un palier du bareme");
                            openScale(sp);
                        }));

        OwoMenuServer.openPanel(admin, title, rows, footer, sp -> openTier(sp, index),
                SavingsMenus::openScale);
    }

    private static void promptNewTier(ServerPlayer admin) {
        Menus.promptAmount(admin, Icons.label("Seuil du nouveau palier", ChatFormatting.GOLD),
                List.of(Icons.lore("Solde a partir duquel le bonus s'ajoute", ChatFormatting.GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), 1_000, 0, 1_000_000_000L,
                threshold -> promptRate(admin, "Bonus du palier", 0.25, bonus -> {
                    SavingsData d = SavingsData.get(admin.server);
                    d.addTier(threshold, bonus);
                    d.log(admin.getGameProfile().getName() + " a ajoute un palier : a partir de "
                            + threshold + " Utopieces, +" + SavingsManager.rate(bonus));
                    openScale(admin);
                }));
    }

    /**
     * Saisie d'un taux. Le champ de saisie ne prend que des entiers : on travaille donc en centiemes
     * de pourcent (100 = 1,00 %), ce qui laisse deux decimales sans jamais afficher de virgule a taper.
     */
    private static void promptRate(ServerPlayer admin, String what, double current,
                                   java.util.function.DoubleConsumer onConfirm) {
        Menus.promptAmount(admin, Icons.label(what + " (centiemes de %)", ChatFormatting.GOLD),
                List.of(Icons.lore("100 = 1,00 % - 25 = 0,25 % - 5 = 0,05 %", ChatFormatting.GRAY),
                        Icons.lore("Actuellement : " + SavingsManager.rate(current), ChatFormatting.DARK_GRAY)),
                Icons.label("Valider", ChatFormatting.GREEN), Math.round(current * 100), 0, 10_000,
                v -> onConfirm.accept(v / 100.0));
    }

    // ==============================================================================================
    //  Ouverture d'un livret
    // ==============================================================================================

    public static void openAccountPicker(ServerPlayer player, int page) {
        if (!SavingsManager.canKeepRegistry(player)) {
            player.sendSystemMessage(Messages.warn("Seul le banquier tient le registre des livrets."));
            openOwn(player);
            return;
        }
        MinecraftServer server = player.server;
        SavingsData data = SavingsData.get(server);

        Component title = Icons.title("Ouvrir un livret", ChatFormatting.GREEN);
        List<Component> stats = List.of(Component.literal(
                        "Le livret commence a zero ; les interets tombent des la nuit suivante.")
                .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Saisir un pseudo", ChatFormatting.YELLOW),
                Icons.lore("Pour un joueur hors ligne deja venu sur le serveur", ChatFormatting.GRAY),
                sp -> Menus.promptText(sp, Icons.label("Pseudo du joueur", ChatFormatting.GOLD), List.of(),
                        Icons.label("Ouvrir", ChatFormatting.GREEN), "", 16,
                        pseudo -> {
                            openByName(sp, pseudo);
                            openRegistry(sp);
                        })));
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            String tname = target.getGameProfile().getName();
            boolean already = data.account(tid) != null;
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, already ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                    Icons.lore(already ? "Possede deja un livret" : "Clic : ouvrir un livret",
                            already ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY),
                    sp -> {
                        if (already) {
                            openAccount(sp, tid);
                            return;
                        }
                        SavingsData d = SavingsData.get(sp.server);
                        d.rememberName(tid, tname);
                        d.openAccount(tid, SavingsManager.today());
                        d.log(sp.getGameProfile().getName() + " a ouvert le livret de " + tname);
                        target.sendSystemMessage(Messages.success(
                                "Un livret d'epargne vient d'etre ouvert a votre nom."));
                        sp.sendSystemMessage(Messages.success("Livret ouvert pour " + tname + "."));
                        openAccount(sp, tid);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                SavingsMenus::openAccountPicker, SavingsMenus::openRegistry);
    }

    private static void openByName(ServerPlayer banker, String pseudo) {
        if (pseudo == null || pseudo.isBlank()) {
            return;
        }
        MinecraftServer server = banker.server;
        SavingsData data = SavingsData.get(server);
        ServerPlayer online = server.getPlayerList().getPlayerByName(pseudo.trim());
        UUID target = online != null ? online.getUUID() : data.findByName(pseudo.trim());
        if (target == null) {
            target = com.utopia.data.JobData.get(server).findByName(pseudo.trim());
        }
        if (target == null) {
            banker.sendSystemMessage(Messages.error("Joueur inconnu : \"" + pseudo.trim()
                    + "\". Il doit s'etre connecte au moins une fois."));
            return;
        }
        String name = online != null ? online.getGameProfile().getName() : data.nameOf(target);
        if (data.account(target) != null) {
            banker.sendSystemMessage(Messages.warn(name + " possede deja un livret."));
            return;
        }
        data.rememberName(target, name);
        data.openAccount(target, SavingsManager.today());
        data.log(banker.getGameProfile().getName() + " a ouvert le livret de " + name);
        banker.sendSystemMessage(Messages.success("Livret ouvert pour " + name + "."));
    }

    // ==============================================================================================
    //  Vue du titulaire
    // ==============================================================================================

    /** Ecran personnel : solde, taux applique, gains de la nuit et progression vers le palier suivant. */
    public static void openOwn(ServerPlayer player) {
        SavingsData data = SavingsData.get(player.server);
        SavingsData.Account account = data.account(player.getUUID());

        Component title = Icons.screenTitle("Mon livret d'epargne", ChatFormatting.GOLD);

        if (account == null) {
            OwoMenuServer.openProgress(player, title,
                    List.of(Icons.lore("Vous n'avez pas encore de livret.", ChatFormatting.GRAY),
                            Icons.lore("Rendez-vous chez le banquier pour en ouvrir un.",
                                    ChatFormatting.DARK_GRAY),
                            Icons.lore("Taux de base : " + SavingsManager.rate(data.baseRate())
                                    + " par nuit, verse a minuit.", ChatFormatting.YELLOW)),
                    new OwoMenuServer.ProgressBuilder(), SavingsMenus::openOwn,
                    com.utopia.economy.EconomyMenus::openPlayerMenu);
            return;
        }

        double rate = data.rateFor(account.balance);
        SavingsData.DayEntry last = account.lastEntry();
        long gained = last == null ? 0 : last.interest;
        long nextGain = (long) Math.floor(account.balance * rate / 100.0);

        List<Component> intro = new ArrayList<>();
        intro.add(stat("Solde du livret : ", account.balance + " Utopieces", ChatFormatting.GOLD));
        intro.add(stat("Taux applique : ", SavingsManager.rate(rate) + " par nuit", ChatFormatting.GREEN));
        intro.add(stat("Derniere nuit : ", "+" + gained + " Utopieces"
                + (last != null ? " (" + SavingsManager.day(last.day) + ")" : ""), ChatFormatting.AQUA));
        intro.add(stat("Prochaine nuit, environ : ", "+" + nextGain + " Utopieces", ChatFormatting.YELLOW));
        intro.add(stat("Total percu depuis l'ouverture : ", account.totalInterest + " Utopieces",
                ChatFormatting.GRAY));
        if (!data.enabled()) {
            intro.add(Component.literal("Interets suspendus par l'administration "
                    + "(votre epargne reste disponible au guichet).")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));
        }

        OwoMenuServer.ProgressBuilder bars = new OwoMenuServer.ProgressBuilder();
        SavingsData.Tier next = data.nextTier(account.balance);
        if (next != null) {
            int[] v = scale(account.balance, next.threshold);
            bars.bar(new ItemStack(Items.GOLD_INGOT),
                    Icons.label("Prochain palier : +" + SavingsManager.rate(next.bonus)
                            + " a partir de " + next.threshold + " Utopieces", ChatFormatting.GOLD),
                    v[0], v[1], false, true, COLOR_SAVINGS, null, null);
        } else {
            bars.bar(new ItemStack(Items.GOLD_BLOCK),
                    Icons.label("Palier maximum atteint : " + SavingsManager.rate(rate) + " par nuit",
                            ChatFormatting.GREEN),
                    1, 1, true, true, COLOR_SAVINGS, null, null);
        }
        if (data.ceiling() > 0) {
            int[] v = scale(account.balance, data.ceiling());
            bars.bar(new ItemStack(Items.IRON_BARS),
                    Icons.label("Plafond du livret : " + data.ceiling() + " Utopieces",
                            ChatFormatting.AQUA),
                    v[0], v[1], account.balance >= data.ceiling(), false, COLOR_CEILING, null, null);
        }
        bars.action(Icons.label("Suivi quotidien", ChatFormatting.YELLOW),
                sp -> openTracking(sp, sp.getUUID(), 0));
        if (SavingsManager.canKeepRegistry(player)) {
            bars.action(Icons.label("Registre des livrets", ChatFormatting.LIGHT_PURPLE),
                    SavingsMenus::openRegistry);
        }

        OwoMenuServer.openProgress(player, title, intro, bars, SavingsMenus::openOwn,
                com.utopia.economy.EconomyMenus::openPlayerMenu);
    }

    // ==============================================================================================
    //  Utilitaires
    // ==============================================================================================

    private static OwoMenuServer.PanelRow row(String label, String value, ChatFormatting color) {
        return new OwoMenuServer.PanelRow(Icons.label(label, ChatFormatting.GRAY),
                Icons.label(value, color), null, null);
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

    /** Ramene un couple (courant, objectif) dans les bornes d'un int sans fausser la proportion. */
    private static int[] scale(long current, long required) {
        long div = 1;
        while (Math.max(current, required) / div > Integer.MAX_VALUE) {
            div *= 1000;
        }
        return new int[]{(int) (current / div), (int) Math.max(1, required / div)};
    }
}
