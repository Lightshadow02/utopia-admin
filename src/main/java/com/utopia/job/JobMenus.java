package com.utopia.job;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.utopia.data.JobData;
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
 * Panel des metiers et des salaires : creation et reglage des metiers, affectation des joueurs,
 * historique. Accessible aux op, au maire et aux banquiers (permission independante).
 */
public final class JobMenus {

    private static final int PAGE_SIZE = 12;

    /** Une ligne d'historique peut se replier sur deux lignes : on en met moins par page. */
    private static final int HISTORY_PAGE_SIZE = 10;

    private JobMenus() {
    }

    // ==============================================================================================
    //  Accueil
    // ==============================================================================================

    public static void open(ServerPlayer player) {
        open(player, 0);
    }

    /**
     * Accueil : un tableau des metiers, une ligne par poste. Salaires et effectifs sont cales a
     * droite les uns sous les autres, ce qui fait ressortir sans survol le poste sans montant ou
     * celui que personne n'exerce.
     */
    public static void open(ServerPlayer player, int page) {
        MinecraftServer server = player.server;
        JobData data = JobData.get(server);
        List<JobData.Job> jobs = new ArrayList<>(data.jobs());

        int pages = Math.max(1, (jobs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(jobs.size(), from + PAGE_SIZE);

        Component title = Icons.screenTitle("Metiers et salaires"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.GOLD);
        long unpaidJobs = jobs.stream().filter(j -> j.salary <= 0).count();
        List<Component> stats = List.of(
                Component.literal(jobs.size() + " metier(s) - " + data.employees().size() + " employe(s)"
                                + (unpaidJobs > 0 ? " - " + unpaidJobs + " sans salaire" : ""))
                        .withStyle(s -> s.withColor(unpaidJobs > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY)
                                .withItalic(false)),
                Component.literal("Masse salariale : " + JobManager.dailyPayroll(server)
                                + " Utopieces / jour, versee a 12h (heure de Paris)")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.PanelRow> controls = new ArrayList<>();
        if (JobManager.canSetSalary(player)) {
            // Le statut de banquier n'est pas un metier : sans cette ligne, son salaire n'existe nulle part.
            long bankerPay = data.bankerSalary();
            // L'effectif multiplie le montant qu'on est en train de fixer. La ligne "Banquiers" plus
            // bas est reservee aux op : sans ce rappel ici, le maire et le banquier regleraient le
            // salaire sans jamais voir combien de personnes le toucheront.
            int bankerCount = data.bankers().size();
            controls.add(new OwoMenuServer.PanelRow(
                    Icons.label("Salaire du banquier", ChatFormatting.LIGHT_PURPLE),
                    Icons.label(bankerPay > 0
                                    ? bankerPay + " x " + bankerCount + " banquier(s)"
                                    : "aucun salaire - " + bankerCount + " banquier(s)",
                            bankerPay > 0 ? ChatFormatting.GOLD : ChatFormatting.YELLOW),
                    Icons.label("Modifier", ChatFormatting.YELLOW),
                    sp -> Menus.promptAmount(sp,
                            Icons.label("Salaire du banquier", ChatFormatting.LIGHT_PURPLE),
                            List.of(Icons.lore("Verse chaque jour a 12h a chaque banquier designe",
                                            ChatFormatting.GRAY),
                                    Icons.lore("S'ajoute aux metiers qu'il exerce par ailleurs",
                                            ChatFormatting.DARK_GRAY),
                                    Icons.lore("0 = le statut ne rapporte rien", ChatFormatting.DARK_GRAY)),
                            Icons.label("Valider", ChatFormatting.GREEN), bankerPay, 0, 1_000_000L,
                            v -> {
                                JobData d = JobData.get(sp.server);
                                d.setBankerSalary(v);
                                d.log(sp.getGameProfile().getName() + " a fixe le salaire du banquier a "
                                        + v + " Utopieces/jour");
                                sp.sendSystemMessage(Messages.success(v > 0
                                        ? "Salaire du banquier : " + v + " Utopieces/jour."
                                        : "Le statut de banquier ne rapporte plus rien."));
                                open(sp);
                            })));
        }
        if (player.hasPermissions(2)) {
            controls.add(new OwoMenuServer.PanelRow(
                    Icons.label("Banquiers", ChatFormatting.LIGHT_PURPLE),
                    Icons.label(data.bankers().size() + " designe(s)", ChatFormatting.GRAY),
                    Icons.label("Designer", ChatFormatting.YELLOW),
                    sp -> openBankerPicker(sp, 0)));
        }

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("METIER"), 100, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("SALAIRE/JOUR"), 86, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("EMPLOYES"), 58, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("ETAT"), 56, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (JobData.Job job : jobs.subList(Math.min(from, jobs.size()), to)) {
            String id = job.id;
            int count = data.employeesOf(id).size();
            // Un metier ouvert par le banquier arrive sans montant : il doit sauter aux yeux de qui
            // peut le fixer, sinon il resterait a zero sans que personne ne s'en apercoive.
            boolean unpaid = job.salary <= 0;
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal(job.name).withStyle(x -> x.withColor(
                            job.enabled ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY).withItalic(false)),
                    Component.literal(unpaid ? "A FIXER" : String.valueOf(job.salary))
                            .withStyle(x -> x.withColor(unpaid ? ChatFormatting.YELLOW : ChatFormatting.GOLD)
                                    .withItalic(false)),
                    Component.literal(String.valueOf(count)).withStyle(x -> x.withColor(
                            count > 0 ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY).withItalic(false)),
                    Component.literal(job.enabled ? "actif" : "desactive").withStyle(x -> x.withColor(
                            job.enabled ? ChatFormatting.GREEN : ChatFormatting.RED).withItalic(false))),
                    sp -> openJob(sp, id)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal("Aucun metier")
                            .withStyle(x -> x.withColor(ChatFormatting.RED).withItalic(false)),
                    Component.empty(), Component.empty(), Component.empty()), null));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (JobManager.canEditJobs(player)) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Creer un metier", ChatFormatting.GREEN), JobMenus::promptCreate));
        }
        if (com.utopia.savings.SavingsManager.canKeepRegistry(player)) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Livrets d'epargne", ChatFormatting.GOLD),
                    com.utopia.savings.SavingsMenus::openRegistry));
        }
        footer.add(new OwoMenuServer.PanelAction(
                Icons.label("Historique", ChatFormatting.YELLOW), sp -> openHistory(sp, 0)));

        Consumer<ServerPlayer> onPrev = pages > 1 ? sp -> open(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> onNext = pages > 1 ? sp -> open(sp, (cur + 1) % pages) : null;
        Consumer<ServerPlayer> onBack = player.hasPermissions(2) ? com.utopia.menu.AdminMenu::open : null;
        OwoMenuServer.openTable(player, title, stats, controls, columns, rows, footer,
                onPrev, onNext, sp -> open(sp, cur), onBack);
    }

    /** En-tete de colonne : gris-bleu, en capitales, pour se distinguer des donnees. */
    private static Component head(String text) {
        return Component.literal(text)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    private static void promptCreate(ServerPlayer player) {
        Menus.promptFreeText(player, Icons.label("Nom du metier", ChatFormatting.GOLD),
                List.of(Icons.lore("Ex : Aubergiste, Banquier, Garde...", ChatFormatting.GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), "", 32,
                name -> {
                    if (name == null || name.isBlank()) {
                        player.sendSystemMessage(Messages.warn("Nom vide."));
                        open(player);
                        return;
                    }
                    if (JobData.get(player.server).jobExists(name)) {
                        player.sendSystemMessage(Messages.warn("Ce metier existe deja."));
                        open(player);
                        return;
                    }
                    if (!JobManager.canSetSalary(player)) {
                        // Le banquier ouvre le poste ; le montant reste a la main de l'administration.
                        JobData.Job created = JobManager.create(player.server, name, 0,
                                player.getGameProfile().getName());
                        if (created == null) {
                            player.sendSystemMessage(Messages.warn("Creation impossible."));
                            open(player);
                            return;
                        }
                        player.sendSystemMessage(Messages.success("Metier \"" + created.name
                                + "\" cree, sans salaire pour l'instant."));
                        player.sendSystemMessage(Messages.info(
                                "Un administrateur ou le maire doit en fixer le montant."));
                        openJob(player, created.id);
                        return;
                    }
                    Menus.promptAmount(player, Icons.label("Salaire quotidien de " + name.trim(), ChatFormatting.GOLD),
                            List.of(Icons.lore("En Utopieces, verse chaque jour a 12h", ChatFormatting.GRAY)),
                            Icons.label("Creer", ChatFormatting.GREEN), 100, 0, 1_000_000L,
                            salary -> {
                                JobData.Job job = JobManager.create(player.server, name, salary,
                                        player.getGameProfile().getName());
                                if (job == null) {
                                    player.sendSystemMessage(Messages.warn("Creation impossible."));
                                    open(player);
                                    return;
                                }
                                player.sendSystemMessage(Messages.success("Metier \"" + job.name
                                        + "\" cree (" + job.salary + " Utopieces/jour)."));
                                openJob(player, job.id);
                            });
                });
    }

    // ==============================================================================================
    //  Fiche d'un metier
    // ==============================================================================================

    public static void openJob(ServerPlayer player, String id) {
        JobData data = JobData.get(player.server);
        JobData.Job job = data.job(id);
        if (job == null) {
            open(player);
            return;
        }
        List<UUID> employees = data.employeesOf(id);

        Component title = Icons.title(job.name, ChatFormatting.AQUA);

        boolean canEdit = JobManager.canEditJobs(player);
        boolean canPay = JobManager.canSetSalary(player);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Salaire / jour", ChatFormatting.GRAY),
                Icons.label(job.salary + " Utopieces"
                                + (canPay ? "" : " - lecture seule"),
                        job.salary > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                canPay ? Icons.label("Modifier", ChatFormatting.YELLOW) : null,
                !canPay ? null :
                sp -> Menus.promptAmount(sp, Icons.label("Salaire de " + job.name, ChatFormatting.GOLD),
                        List.of(Icons.lore("Applique des le prochain versement de midi", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), job.salary, 0, 1_000_000L,
                        v -> {
                            JobManager.setSalary(sp.server, job, v, sp.getGameProfile().getName());
                            sp.sendSystemMessage(Messages.success("Salaire : " + v + " Utopieces/jour."));
                            openJob(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom", ChatFormatting.GRAY),
                Icons.label(job.name, ChatFormatting.WHITE),
                canEdit ? Icons.label("Renommer", ChatFormatting.YELLOW) : null,
                !canEdit ? null :
                sp -> Menus.promptFreeText(sp, Icons.label("Nouveau nom", ChatFormatting.GOLD), List.of(),
                        Icons.label("Valider", ChatFormatting.GREEN), job.name, 32,
                        n -> {
                            if (n != null && !n.isBlank()) {
                                JobManager.rename(sp.server, job, n, sp.getGameProfile().getName());
                            }
                            openJob(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Etat", ChatFormatting.GRAY),
                Icons.label(job.enabled ? "actif" : "desactive",
                        job.enabled ? ChatFormatting.GREEN : ChatFormatting.RED),
                canEdit ? Icons.label(job.enabled ? "Desactiver" : "Activer", ChatFormatting.YELLOW) : null,
                !canEdit ? null : sp -> {
                    JobManager.setEnabled(sp.server, job, !job.enabled, sp.getGameProfile().getName());
                    sp.sendSystemMessage(job.enabled
                            ? Messages.success("Metier actif : les salaires reprennent.")
                            : Messages.warn("Metier desactive : plus aucun salaire verse (les employes le gardent)."));
                    openJob(sp, id);
                }));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Employes", ChatFormatting.GRAY),
                Icons.label(String.valueOf(employees.size()), ChatFormatting.AQUA),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openEmployees(sp, id, 0)));

        List<OwoMenuServer.PanelAction> footer = !canEdit ? List.of() : List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            JobManager.delete(sp.server, job, sp.getGameProfile().getName());
                            sp.sendSystemMessage(Messages.success("Metier \"" + job.name + "\" supprime."));
                            open(sp);
                        }));

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openJob(sp, id), JobMenus::open);
    }

    // ==============================================================================================
    //  Employes d'un metier
    // ==============================================================================================

    public static void openEmployees(ServerPlayer player, String id, int page) {
        JobData data = JobData.get(player.server);
        JobData.Job job = data.job(id);
        if (job == null) {
            open(player);
            return;
        }
        Component title = Icons.title("Employes - " + job.name, ChatFormatting.AQUA);
        List<Component> stats = List.of(Component.literal(
                        data.employeesOf(id).size() + " employe(s) - " + job.salary + " Utopieces/jour chacun")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                Icons.label("Embaucher un joueur", ChatFormatting.GREEN),
                Icons.lore("Choisir dans la liste ou saisir un pseudo", ChatFormatting.GRAY),
                sp -> openHire(sp, id, 0)));
        for (UUID employee : data.employeesOf(id)) {
            String employeeName = data.nameOf(employee);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                    Icons.label(employeeName, ChatFormatting.WHITE),
                    Icons.lore("Clic : retirer ce metier", ChatFormatting.GRAY),
                    sp -> {
                        JobManager.unassign(sp.server, employee, job, sp.getGameProfile().getName());
                        sp.sendSystemMessage(Messages.info(employeeName + " n'exerce plus \"" + job.name + "\"."));
                        openEmployees(sp, id, page);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openEmployees(sp, id, p), sp -> openJob(sp, id));
    }

    /** Embauche : joueurs en ligne, puis joueurs deja connus, plus une saisie libre de pseudo. */
    public static void openHire(ServerPlayer player, String id, int page) {
        MinecraftServer server = player.server;
        JobData data = JobData.get(server);
        JobData.Job job = data.job(id);
        if (job == null) {
            open(player);
            return;
        }
        Component title = Icons.title("Embaucher - " + job.name, ChatFormatting.GREEN);
        List<Component> stats = List.of(Component.literal(
                        JobManager.pastPayTime()
                                ? "Midi est passe : le premier salaire sera verse demain."
                                : "Le salaire sera verse au prochain versement de midi.")
                .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Saisir un pseudo", ChatFormatting.YELLOW),
                Icons.lore("Pour un joueur hors ligne deja venu sur le serveur", ChatFormatting.GRAY),
                sp -> Menus.promptText(sp, Icons.label("Pseudo du joueur", ChatFormatting.GOLD), List.of(),
                        Icons.label("Embaucher", ChatFormatting.GREEN), "", 16,
                        pseudo -> {
                            hireByName(sp, id, pseudo);
                            openEmployees(sp, id, 0);
                        })));
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            String tname = target.getGameProfile().getName();
            boolean already = data.hasJob(tid, id);
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, already ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                    Icons.lore(already ? "Exerce deja ce metier" : "Clic : embaucher",
                            already ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY),
                    sp -> {
                        if (already) {
                            sp.sendSystemMessage(Messages.warn(tname + " exerce deja ce metier."));
                        } else {
                            JobManager.assign(sp.server, tid, tname, job, sp.getGameProfile().getName());
                            sp.sendSystemMessage(Messages.success(tname + " exerce desormais \"" + job.name + "\"."));
                        }
                        openEmployees(sp, id, 0);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openHire(sp, id, p), sp -> openEmployees(sp, id, 0));
    }

    /** Embauche par pseudo : joueur en ligne, sinon joueur deja connu du systeme. */
    private static void hireByName(ServerPlayer admin, String id, String pseudo) {
        if (pseudo == null || pseudo.isBlank()) {
            return;
        }
        MinecraftServer server = admin.server;
        JobData data = JobData.get(server);
        JobData.Job job = data.job(id);
        if (job == null) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(pseudo.trim());
        UUID target = online != null ? online.getUUID() : data.findByName(pseudo.trim());
        if (target == null) {
            admin.sendSystemMessage(Messages.error("Joueur inconnu : \"" + pseudo.trim()
                    + "\". Il doit s'etre connecte au moins une fois."));
            return;
        }
        String name = online != null ? online.getGameProfile().getName() : data.nameOf(target);
        if (data.hasJob(target, id)) {
            admin.sendSystemMessage(Messages.warn(name + " exerce deja ce metier."));
            return;
        }
        JobManager.assign(server, target, name, job, admin.getGameProfile().getName());
        admin.sendSystemMessage(Messages.success(name + " exerce desormais \"" + job.name + "\"."));
    }

    // ==============================================================================================
    //  Historique
    // ==============================================================================================

    public static void openHistory(ServerPlayer player, int page) {
        JobData data = JobData.get(player.server);
        List<JobData.HistoryEntry> all = new ArrayList<>(data.history());
        java.util.Collections.reverse(all); // le plus recent en premier

        int pages = Math.max(1, (all.size() + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * HISTORY_PAGE_SIZE;
        int to = Math.min(all.size(), from + HISTORY_PAGE_SIZE);

        Component title = Icons.title("Historique"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.YELLOW);

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DATE"), 100, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("EVENEMENT"), 212, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (JobData.HistoryEntry e : all.subList(Math.min(from, all.size()), to)) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal(JobManager.stamp(e.millis()))
                            .withStyle(x -> x.withColor(ChatFormatting.DARK_GRAY).withItalic(false)),
                    Component.literal(e.text())
                            .withStyle(x -> x.withColor(ChatFormatting.WHITE).withItalic(false))), null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(Component.empty(),
                    Component.literal("Aucun evenement")
                            .withStyle(x -> x.withColor(ChatFormatting.GRAY).withItalic(false))), null));
        }

        Consumer<ServerPlayer> prev = pages > 1 ? sp -> openHistory(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> next = pages > 1 ? sp -> openHistory(sp, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(player, title, List.of(), List.of(), columns, rows, List.of(),
                prev, next, sp -> openHistory(sp, cur), JobMenus::open);
    }

    // ==============================================================================================
    //  Banquiers (op uniquement)
    // ==============================================================================================

    public static void openBankerPicker(ServerPlayer admin, int page) {
        MinecraftServer server = admin.server;
        JobData data = JobData.get(server);

        Component title = Icons.title("Banquiers", ChatFormatting.LIGHT_PURPLE);
        List<Component> stats = List.of(
                Component.literal(data.bankers().size() + " banquier(s) designe(s)")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Ils accedent a ce panel via /metiers, sans aucun autre droit admin.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            String tname = target.getGameProfile().getName();
            boolean banker = data.isBanker(tid);
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, banker ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE),
                    Icons.lore(banker ? "Banquier : OUI (clic pour retirer)" : "Banquier : non (clic pour nommer)",
                            banker ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY),
                    sp -> {
                        boolean now = JobData.get(sp.server).toggleBanker(tid);
                        JobData.get(sp.server).rememberName(tid, tname);
                        sp.server.getCommands().sendCommands(target); // /metiers apparait ou disparait
                        target.sendSystemMessage(now
                                ? Messages.success("Vous etes nomme banquier : /metiers est disponible.")
                                : Messages.warn("Vous n'etes plus banquier."));
                        JobData.get(sp.server).log(sp.getGameProfile().getName()
                                + (now ? " a nomme " : " a retire ") + tname + " banquier");
                        sp.sendSystemMessage(now
                                ? Messages.success(tname + " est nomme banquier.")
                                : Messages.info(tname + " n'est plus banquier."));
                        openBankerPicker(sp, page);
                    }));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                JobMenus::openBankerPicker, JobMenus::open);
    }
}
