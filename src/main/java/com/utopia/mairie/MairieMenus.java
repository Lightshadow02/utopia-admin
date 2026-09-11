package com.utopia.mairie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.utopia.data.LeaderboardData;
import com.utopia.data.MairieData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Les ecrans que le maire pilote depuis /maire : impots, taxes nommees, concours de chasse et droit
 * du PVP. Tout est regle en jeu, sans toucher au serveur — c'est un mandat electif, pas un poste
 * d'administrateur.
 */
public final class MairieMenus {

    /** Mobs affiches par page du selecteur : le hub tient 52 actions, on garde de la marge. */
    private static final int MOB_PAGE = 28;
    /** Lignes de classement par page : au-dela, le tableau deborde des 54 slots d'action. */
    private static final int ROW_PAGE = 40;

    private MairieMenus() {
    }

    /**
     * Seul le maire (ou un operateur) passe ces portes. Chaque ecran le verifie pour lui-meme :
     * /maire n'est pas le seul chemin possible vers une methode publique.
     */
    private static boolean denied(ServerPlayer player) {
        if (player.hasPermissions(2) || MarketData.get(player.server).isMaire(player.getUUID())) {
            return false;
        }
        player.sendSystemMessage(Messages.error("Reserve au maire."));
        return true;
    }

    private static Component head(String text) {
        return Component.literal(text)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    private static Component value(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }

    // ================================================================= PVP

    /**
     * Bascule le droit du PVP dans les parcelles. Le detour par une confirmation est volontaire :
     * l'interrupteur change les regles du jeu pour tout le monde d'un seul clic.
     */
    public static void confirmPvp(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MairieData data = MairieData.get(player.server);
        boolean allowed = data.pvpInParcels();
        OwoMenuServer.openConfirm(player,
                Icons.title(allowed ? "Interdire le PVP dans les parcelles"
                        : "Autoriser le PVP dans les parcelles", ChatFormatting.GOLD),
                List.of(Icons.lore(allowed
                                ? "Les joueurs ne pourront plus se frapper a l'interieur d'une parcelle."
                                : "Les joueurs pourront se frapper a l'interieur de n'importe quelle parcelle.",
                        ChatFormatting.GRAY),
                        Icons.lore("En dehors des parcelles, rien ne change : le PVP y suit les regles du serveur.",
                                ChatFormatting.DARK_GRAY)),
                Icons.label(allowed ? "Interdire" : "Autoriser",
                        allowed ? ChatFormatting.RED : ChatFormatting.GREEN),
                sp -> {
                    if (denied(sp)) {
                        return;
                    }
                    MairieData d = MairieData.get(sp.server);
                    d.setPvpInParcels(!allowed);
                    sp.server.getPlayerList().broadcastSystemMessage(
                            Component.literal(d.pvpInParcels()
                                            ? "La mairie autorise desormais le PVP dans les parcelles."
                                            : "La mairie interdit desormais le PVP dans les parcelles.")
                                    .withStyle(s -> s.withColor(d.pvpInParcels()
                                            ? ChatFormatting.RED : ChatFormatting.GREEN).withBold(true)),
                            false);
                    back.accept(sp);
                },
                back);
    }

    // ================================================================= Taxes

    /** Registre des taxes : l'impot de droit commun en tete, puis les taxes nommees du maire. */
    public static void openTaxes(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MinecraftServer server = player.server;
        MairieData data = MairieData.get(server);

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Caisse de la mairie : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(EconomyManager.getBalance(server, MarketData.MAIRIE_UUID) + " Utopieces",
                        ChatFormatting.GOLD)));
        stats.add(Icons.lore("Le cumul de toutes les taxes d'un meme flux ne depassera jamais "
                + MairieData.MAX_TOTAL_TAX + " % du montant.", ChatFormatting.DARK_GRAY));

        List<OwoMenuServer.PanelRow> controls = new ArrayList<>();
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Impot sur les salaires", ChatFormatting.YELLOW),
                value(data.salaryTaxPercent() + " % de chaque salaire",
                        data.salaryTaxPercent() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Impot sur les salaires", ChatFormatting.GOLD),
                        List.of(Icons.lore("Part prelevee sur chaque salaire verse a 12h.", ChatFormatting.GRAY),
                                Icons.lore("0 = aucun impot.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        data.salaryTaxPercent(), 0, MairieData.MAX_TOTAL_TAX,
                        v -> {
                            MairieData.get(sp.server).setSalaryTaxPercent((int) v);
                            sp.sendSystemMessage(Messages.success("Impot sur les salaires : " + v + " %."));
                            openTaxes(sp, back);
                        })));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Taxe sur les devis", ChatFormatting.YELLOW),
                value(com.utopia.data.QuoteData.get(server).taxPercent()
                                + " % du reglement, avant les taxes nommees ci-dessous",
                        ChatFormatting.GRAY),
                Icons.label("Ouvrir", ChatFormatting.AQUA),
                sp -> com.utopia.quote.QuoteMenus.openAdminSettings(sp, s2 -> openTaxes(s2, back))));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("TAXE"), 104, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("TAUX"), 60, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("S'APPLIQUE A"), 92, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("PERCU"), 56, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (MairieData.Tax tax : data.taxes()) {
            String flows = tax.flows.isEmpty() ? "aucun flux" : flowsLabel(tax);
            rows.add(new OwoMenuServer.TableRow(
                    new ItemStack(tax.enabled ? Items.GOLD_NUGGET : Items.GRAY_DYE),
                    List.of(value(tax.name, tax.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                            value(tax.rateLabel(), ChatFormatting.GOLD),
                            value(flows, tax.flows.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA),
                            value(String.valueOf(tax.collected), ChatFormatting.GREEN)),
                    sp -> openTax(sp, tax.id, back)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucune taxe nommee pour l'instant.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(new OwoMenuServer.PanelAction(
                Icons.label("Nouvelle taxe", ChatFormatting.GREEN),
                sp -> promptNewTax(sp, back)));

        OwoMenuServer.openTable(player, Icons.screenTitle("Taxes", ChatFormatting.GOLD), stats,
                controls, columns, rows, footer, null, null,
                sp -> openTaxes(sp, back), back);
    }

    private static String flowsLabel(MairieData.Tax tax) {
        List<String> parts = new ArrayList<>();
        for (MairieData.Flow flow : MairieData.Flow.values()) {
            if (tax.flows.contains(flow)) {
                parts.add(flow.label);
            }
        }
        return String.join(", ", parts);
    }

    /**
     * Creation d'une taxe. La taxe n'existe qu'a la toute derniere etape : une saisie abandonnee
     * (Echap) n'appelle aucun retour, un objet cree plus tot resterait a moitie reglé dans le registre.
     */
    private static void promptNewTax(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        Menus.promptFreeText(player, Icons.label("Nom de la nouvelle taxe", ChatFormatting.GOLD),
                List.of(Icons.lore("Le nom que liront les joueurs sur leurs reglements.", ChatFormatting.GRAY),
                        Icons.lore("Exemple : Taxe biodiversite", ChatFormatting.DARK_GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), "", 40,
                name -> {
                    if (denied(player)) {
                        return;
                    }
                    String clean = name == null ? "" : name.trim();
                    if (clean.isEmpty()) {
                        player.sendSystemMessage(Messages.warn("Il faut un nom."));
                        openTaxes(player, back);
                        return;
                    }
                    Menus.promptAmount(player, Icons.label("Part prelevee (%)", ChatFormatting.GOLD),
                            List.of(Icons.lore("\"" + clean + "\"", ChatFormatting.YELLOW),
                                    Icons.lore("Part du montant de chaque transaction visee.", ChatFormatting.GRAY),
                                    Icons.lore("Tu pourras y ajouter une somme fixe ensuite.", ChatFormatting.DARK_GRAY)),
                            Icons.label("Creer", ChatFormatting.GREEN), 5, 0, MairieData.MAX_TOTAL_TAX,
                            percent -> {
                                if (denied(player)) {
                                    return;
                                }
                                MairieData data = MairieData.get(player.server);
                                MairieData.Tax tax = data.createTax(clean);
                                if (tax == null) {
                                    player.sendSystemMessage(Messages.error(
                                            data.taxes().size() >= MairieData.MAX_TAXES
                                                    ? "Limite atteinte : " + MairieData.MAX_TAXES
                                                            + " taxes nommees au maximum."
                                                    : "Ce nom ne donne aucun identifiant utilisable."));
                                    openTaxes(player, back);
                                    return;
                                }
                                tax.percent = (int) percent;
                                data.setDirty();
                                player.sendSystemMessage(Messages.success("Taxe \"" + tax.name
                                        + "\" creee. Choisis maintenant ou elle s'applique."));
                                openTax(player, tax.id, back);
                            });
                });
    }

    /** Reglage d'une taxe nommee : son taux, son assiette, et les flux qu'elle frappe. */
    public static void openTax(ServerPlayer player, String taxId,
                               java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MairieData data = MairieData.get(player.server);
        MairieData.Tax tax = data.tax(taxId);
        if (tax == null) {
            player.sendSystemMessage(Messages.warn("Cette taxe n'existe plus."));
            openTaxes(player, back);
            return;
        }

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Nom", ChatFormatting.GRAY), value(tax.name, ChatFormatting.WHITE),
                Icons.label("Renommer", ChatFormatting.AQUA),
                sp -> Menus.promptFreeText(sp, Icons.label("Nom de la taxe", ChatFormatting.GOLD),
                        List.of(Icons.lore("Le nom que liront les joueurs.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), tax.name, 40,
                        v -> {
                            if (v != null && !v.isBlank()) {
                                tax.name = v.trim();
                                MairieData.get(sp.server).setDirty();
                            }
                            openTax(sp, taxId, back);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Part proportionnelle", ChatFormatting.GRAY),
                value(tax.percent + " % du montant",
                        tax.percent > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Part prelevee (%)", ChatFormatting.GOLD),
                        List.of(Icons.lore("Part du montant de chaque transaction visee.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), tax.percent, 0, MairieData.MAX_TOTAL_TAX,
                        v -> {
                            tax.percent = (int) v;
                            MairieData.get(sp.server).setDirty();
                            openTax(sp, taxId, back);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Somme fixe", ChatFormatting.GRAY),
                value(tax.flat + " par transaction",
                        tax.flat > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Somme fixe", ChatFormatting.GOLD),
                        List.of(Icons.lore("S'ajoute a la part proportionnelle, quel que soit le montant.",
                                        ChatFormatting.GRAY),
                                Icons.lore("Un droit de timbre, par exemple. 0 = aucun.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), tax.flat, 0, 1_000_000,
                        v -> {
                            tax.flat = v;
                            MairieData.get(sp.server).setDirty();
                            openTax(sp, taxId, back);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Taxe active", ChatFormatting.GRAY),
                value(tax.enabled ? "oui" : "non",
                        tax.enabled ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.label(tax.enabled ? "Suspendre" : "Activer",
                        tax.enabled ? ChatFormatting.RED : ChatFormatting.GREEN),
                sp -> {
                    tax.enabled = !tax.enabled;
                    MairieData.get(sp.server).setDirty();
                    openTax(sp, taxId, back);
                }));

        for (MairieData.Flow flow : MairieData.Flow.values()) {
            boolean on = tax.flows.contains(flow);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("  " + flow.label, on ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY),
                    value(on ? flow.detail : "non appliquee",
                            on ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY),
                    Icons.label(on ? "Retirer" : "Appliquer", on ? ChatFormatting.RED : ChatFormatting.GREEN),
                    sp -> {
                        if (on) {
                            tax.flows.remove(flow);
                        } else {
                            tax.flows.add(flow);
                        }
                        MairieData.get(sp.server).setDirty();
                        openTax(sp, taxId, back);
                    }));
        }

        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Percu depuis la creation", ChatFormatting.GRAY),
                value(tax.collected + " Utopieces", ChatFormatting.GREEN), Component.empty(), null));

        List<OwoMenuServer.PanelAction> footer = List.of(new OwoMenuServer.PanelAction(
                Icons.label("Supprimer cette taxe", ChatFormatting.RED),
                sp -> OwoMenuServer.openConfirm(sp,
                        Icons.title("Supprimer \"" + tax.name + "\" ?", ChatFormatting.RED),
                        List.of(Icons.lore("Elle cessera immediatement de s'appliquer.", ChatFormatting.GRAY),
                                Icons.lore("Le cumul deja percu est perdu.", ChatFormatting.DARK_GRAY)),
                        Icons.label("Supprimer", ChatFormatting.RED),
                        s2 -> {
                            MairieData.get(s2.server).removeTax(taxId);
                            s2.sendSystemMessage(Messages.success("Taxe supprimee."));
                            openTaxes(s2, back);
                        },
                        s2 -> openTax(s2, taxId, back))));

        OwoMenuServer.openPanel(player, Icons.title(tax.name, ChatFormatting.GOLD), rows, footer,
                sp -> openTax(sp, taxId, back), sp -> openTaxes(sp, back));
    }

    // ================================================================= Concours de chasse

    /** Le classement en cours, et tous les leviers du concours. */
    public static void openLeaderboard(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        openLeaderboard(player, 0, back);
    }

    public static void openLeaderboard(ServerPlayer player, int page,
                                       java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MinecraftServer server = player.server;
        MairieData mairie = MairieData.get(server);
        LeaderboardData board = LeaderboardData.get(server);
        List<LeaderboardData.Entry> all = board.standings();

        int totalPages = Math.max(1, (all.size() + ROW_PAGE - 1) / ROW_PAGE);
        final int cur = Math.max(0, Math.min(page, totalPages - 1));

        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Concours : ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(value(mairie.leaderboardEnabled() ? "en cours" : "arrete",
                        mairie.leaderboardEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        stats.add(Icons.lore("Prochaine cloture : "
                + LeaderboardManager.nextCloseLabel(server, mairie) + " (heure reelle) - "
                + all.size() + " chasseur(s) classe(s)", ChatFormatting.DARK_GRAY));
        if (totalPages > 1) {
            stats.add(Icons.lore("Page " + (cur + 1) + " / " + totalPages, ChatFormatting.DARK_GRAY));
        }

        List<OwoMenuServer.PanelRow> controls = new ArrayList<>();
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Concours de chasse", ChatFormatting.YELLOW),
                value(mairie.leaderboardEnabled() ? "en cours" : "arrete",
                        mairie.leaderboardEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.label(mairie.leaderboardEnabled() ? "Arreter" : "Lancer",
                        mairie.leaderboardEnabled() ? ChatFormatting.RED : ChatFormatting.GREEN),
                sp -> {
                    MairieData d = MairieData.get(sp.server);
                    boolean on = !d.leaderboardEnabled();
                    d.setLeaderboardEnabled(on);
                    LeaderboardManager.onEnabledChanged(sp.server, on);
                    sp.sendSystemMessage(on
                            ? Messages.success("Concours lance : les prises comptent a partir de maintenant.")
                            : Messages.warn("Concours arrete : le classement en cours est efface."));
                    openLeaderboard(sp, cur, back);
                }));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Heure de cloture", ChatFormatting.YELLOW),
                value(LeaderboardManager.nextCloseLabel(server, mairie) + " (heure reelle)",
                        ChatFormatting.AQUA),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Heure de cloture", ChatFormatting.GOLD),
                        List.of(Icons.lore("Heure du monde reel (fuseau de Paris) a laquelle le", ChatFormatting.GRAY),
                                Icons.lore("classement est solde et repart a zero. 0 = minuit.", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), mairie.leaderboardHour(), 0, 23,
                        v -> {
                            MairieData.get(sp.server).setLeaderboardHour((int) v);
                            LeaderboardManager.onHourChanged(sp.server);
                            sp.sendSystemMessage(Messages.success("Cloture a " + v
                                    + "h. Le classement en cours est conserve."));
                            openLeaderboard(sp, cur, back);
                        })));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Primes du podium", ChatFormatting.YELLOW),
                value(podiumLabel(mairie), ChatFormatting.GOLD),
                Icons.label("Regler", ChatFormatting.AQUA),
                sp -> openPodium(sp, back)));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Points par mob", ChatFormatting.YELLOW),
                value(mairie.killPoints().size() + " mob(s) au bareme", ChatFormatting.AQUA),
                Icons.label("Regler", ChatFormatting.AQUA),
                sp -> openKillPoints(sp, back)));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Palmares", ChatFormatting.YELLOW),
                value(mairie.palmares().size() + " journee(s) archivee(s)", ChatFormatting.GRAY),
                Icons.label("Consulter", ChatFormatting.AQUA),
                sp -> openPalmares(sp, back)));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("#"), 24, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("CHASSEUR"), 130, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("POINTS"), 62, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRISES"), 56, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRIME"), 56, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        int from = cur * ROW_PAGE;
        int to = Math.min(all.size(), from + ROW_PAGE);
        for (int i = from; i < to; i++) {
            LeaderboardData.Entry e = all.get(i);
            int rank = i + 1;
            long prime = mairie.rewardForRank(rank);
            rows.add(new OwoMenuServer.TableRow(
                    List.of(value(String.valueOf(rank), rankColor(rank)),
                            value(e.name() == null || e.name().isBlank() ? "?" : e.name(), ChatFormatting.WHITE),
                            value(String.valueOf(e.points()), ChatFormatting.GOLD),
                            value(String.valueOf(e.kills()), ChatFormatting.GRAY),
                            value(prime > 0 ? String.valueOf(prime) : "-",
                                    prime > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)),
                    null));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore(mairie.leaderboardEnabled()
                                    ? "Personne n'a encore marque aujourd'hui."
                                    : "Le concours est arrete.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle("Concours de chasse", ChatFormatting.GOLD),
                stats, controls, columns, rows, List.of(),
                cur > 0 ? sp -> openLeaderboard(sp, cur - 1, back) : null,
                cur < totalPages - 1 ? sp -> openLeaderboard(sp, cur + 1, back) : null,
                sp -> openLeaderboard(sp, cur, back), back);
    }

    private static String podiumLabel(MairieData mairie) {
        List<Long> podium = mairie.podium();
        if (podium.isEmpty()) {
            return "aucune prime";
        }
        List<String> parts = new ArrayList<>(podium.size());
        for (long r : podium) {
            parts.add(String.valueOf(r));
        }
        return String.join(" / ", parts);
    }

    private static ChatFormatting rankColor(int rank) {
        return switch (rank) {
            case 1 -> ChatFormatting.GOLD;
            case 2 -> ChatFormatting.WHITE;
            case 3 -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.GRAY;
        };
    }

    /** Les primes, place par place. Une prime a zero retire simplement la place du podium. */
    public static void openPodium(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MairieData mairie = MairieData.get(player.server);
        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int rank = 1; rank <= MairieData.MAX_PODIUM; rank++) {
            final int r = rank;
            long reward = mairie.rewardForRank(rank);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(rank + (rank == 1 ? "er" : "e") + " place", rankColor(rank)),
                    value(reward > 0 ? reward + " Utopieces" : "aucune prime",
                            reward > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    Icons.label("Modifier", ChatFormatting.AQUA),
                    sp -> Menus.promptAmount(sp,
                            Icons.label("Prime de la " + r + (r == 1 ? "ere" : "e") + " place",
                                    ChatFormatting.GOLD),
                            List.of(Icons.lore("Versee depuis la caisse de la mairie a la cloture.",
                                            ChatFormatting.GRAY),
                                    Icons.lore("0 = cette place n'est pas dotee.", ChatFormatting.DARK_GRAY)),
                            Icons.label("Valider", ChatFormatting.GREEN), reward, 0, 1_000_000,
                            v -> {
                                MairieData.get(sp.server).setRewardForRank(r, v);
                                openPodium(sp, back);
                            })));
        }
        OwoMenuServer.openPanel(player, Icons.title("Primes du podium", ChatFormatting.GOLD), rows,
                List.of(), sp -> openPodium(sp, back), sp -> openLeaderboard(sp, back));
    }

    // ================================================================= Bareme par mob

    /** Le bareme : seuls les mobs dotes de points y figurent, les autres se choisissent au selecteur. */
    public static void openKillPoints(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        openKillPoints(player, 0, back);
    }

    public static void openKillPoints(ServerPlayer player, int page,
                                      java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MairieData mairie = MairieData.get(player.server);
        List<Map.Entry<String, Integer>> bareme = new ArrayList<>(mairie.killPoints().entrySet());
        bareme.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        int totalPages = Math.max(1, (bareme.size() + ROW_PAGE - 1) / ROW_PAGE);
        final int cur = Math.max(0, Math.min(page, totalPages - 1));

        List<Component> stats = new ArrayList<>();
        stats.add(Icons.lore("Chaque mob tue rapporte les points inscrits ici.", ChatFormatting.GRAY));
        if (totalPages > 1) {
            stats.add(Icons.lore("Page " + (cur + 1) + " / " + totalPages, ChatFormatting.DARK_GRAY));
        }

        List<OwoMenuServer.PanelRow> controls = new ArrayList<>();
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Points par defaut", ChatFormatting.YELLOW),
                value(mairie.defaultKillPoints() > 0
                                ? mairie.defaultKillPoints() + " pour tout mob non inscrit"
                                : "les mobs non inscrits ne rapportent rien",
                        mairie.defaultKillPoints() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                Icons.label("Modifier", ChatFormatting.GREEN),
                sp -> Menus.promptAmount(sp, Icons.label("Points par defaut", ChatFormatting.GOLD),
                        List.of(Icons.lore("S'applique a tout mob absent du bareme.", ChatFormatting.GRAY),
                                Icons.lore("0 = seuls les mobs inscrits rapportent des points.",
                                        ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), mairie.defaultKillPoints(), 0, 10_000,
                        v -> {
                            MairieData.get(sp.server).setDefaultKillPoints((int) v);
                            openKillPoints(sp, cur, back);
                        })));
        controls.add(new OwoMenuServer.PanelRow(
                Icons.label("Mobs de generateur", ChatFormatting.YELLOW),
                value(mairie.countSpawnerMobs() ? "comptes" : "ignores",
                        mairie.countSpawnerMobs() ? ChatFormatting.GOLD : ChatFormatting.GREEN),
                Icons.label(mairie.countSpawnerMobs() ? "Ignorer" : "Compter", ChatFormatting.AQUA),
                sp -> {
                    MairieData d = MairieData.get(sp.server);
                    d.setCountSpawnerMobs(!d.countSpawnerMobs());
                    openKillPoints(sp, cur, back);
                }));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("MOB"), 150, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("POINTS"), 60, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("IDENTIFIANT"), 118, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        int from = cur * ROW_PAGE;
        int to = Math.min(bareme.size(), from + ROW_PAGE);
        for (int i = from; i < to; i++) {
            Map.Entry<String, Integer> e = bareme.get(i);
            EntityType<?> type = typeOf(e.getKey());
            rows.add(new OwoMenuServer.TableRow(
                    mobIcon(type),
                    List.of(value(type != null ? type.getDescription().getString() : e.getKey(),
                                    ChatFormatting.WHITE),
                            value(String.valueOf(e.getValue()), ChatFormatting.GOLD),
                            value(e.getKey(), ChatFormatting.DARK_GRAY)),
                    sp -> promptPoints(sp, e.getKey(), s2 -> openKillPoints(s2, cur, back))));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucun mob au bareme.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty()),
                    null));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        footer.add(new OwoMenuServer.PanelAction(Icons.label("Ajouter un mob", ChatFormatting.GREEN),
                sp -> openMobPicker(sp, 0, back)));
        if (!bareme.isEmpty()) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Vider le bareme", ChatFormatting.RED),
                    sp -> OwoMenuServer.openConfirm(sp, Icons.title("Vider le bareme ?", ChatFormatting.RED),
                            List.of(Icons.lore("Tous les mobs inscrits perdent leurs points.", ChatFormatting.GRAY)),
                            Icons.label("Vider", ChatFormatting.RED),
                            s2 -> {
                                MairieData.get(s2.server).clearKillPoints();
                                openKillPoints(s2, 0, back);
                            },
                            s2 -> openKillPoints(s2, cur, back))));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle("Points par mob", ChatFormatting.GOLD),
                stats, controls, columns, rows, footer,
                cur > 0 ? sp -> openKillPoints(sp, cur - 1, back) : null,
                cur < totalPages - 1 ? sp -> openKillPoints(sp, cur + 1, back) : null,
                sp -> openKillPoints(sp, cur, back), sp -> openLeaderboard(sp, back));
    }

    /** Selecteur de mob, pagine : le registre en compte bien plus que les 54 slots d'un ecran. */
    public static void openMobPicker(ServerPlayer player, int page,
                                     java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        final int cur = Math.max(0, page);
        MairieData mairie = MairieData.get(player.server);
        List<EntityType<?>> types = pickableTypes();

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>(types.size());
        for (EntityType<?> type : types) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) {
                continue;
            }
            String key = id.toString();
            int current = mairie.hasPointsFor(key) ? mairie.pointsFor(key) : 0;
            entries.add(new OwoMenuServer.HubEntry(mobIcon(type),
                    Icons.label(type.getDescription().getString(),
                            current > 0 ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    Icons.lore(current > 0 ? current + " point(s) - " + key : key,
                            current > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                    // On revient au selecteur, a la page ou l'on etait : regler un bareme se fait
                    // mob apres mob, renvoyer au tableau a chaque saisie ferait perdre le fil.
                    sp -> promptPoints(sp, key, s2 -> openMobPicker(s2, cur, back))));
        }

        OwoMenuServer.openHubPaged(player, Icons.screenTitle("Choisir un mob", ChatFormatting.GOLD),
                List.of(Icons.lore("Clique sur un mob pour lui donner des points.", ChatFormatting.GRAY)),
                entries, cur, MOB_PAGE,
                (sp, p) -> openMobPicker(sp, p, back),
                sp -> openKillPoints(sp, back));
    }

    /** Les types qu'un joueur peut reellement tuer : le reste n'a rien a faire dans un bareme. */
    private static List<EntityType<?>> pickableTypes() {
        List<EntityType<?>> out = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue; // projectiles, barques, cadres : ce ne sont pas des prises
            }
            out.add(type);
        }
        out.sort(Comparator.comparing(t -> t.getDescription().getString()));
        return out;
    }

    private static EntityType<?> typeOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)
                ? BuiltInRegistries.ENTITY_TYPE.get(rl) : null;
    }

    /** L'oeuf de spawn du mob quand il en a un : c'est l'icone que les joueurs reconnaissent. */
    private static ItemStack mobIcon(EntityType<?> type) {
        if (type != null) {
            SpawnEggItem egg = SpawnEggItem.byId(type);
            if (egg != null) {
                return new ItemStack(egg);
            }
        }
        return new ItemStack(Items.BONE);
    }

    private static void promptPoints(ServerPlayer player, String entityTypeId,
                                     java.util.function.Consumer<ServerPlayer> onDone) {
        if (denied(player)) {
            return;
        }
        MairieData mairie = MairieData.get(player.server);
        EntityType<?> type = typeOf(entityTypeId);
        String label = type != null ? type.getDescription().getString() : entityTypeId;
        int current = mairie.hasPointsFor(entityTypeId) ? mairie.pointsFor(entityTypeId) : 0;
        Menus.promptAmount(player, Icons.label("Points pour " + label, ChatFormatting.GOLD),
                List.of(Icons.lore(entityTypeId, ChatFormatting.DARK_GRAY),
                        Icons.lore("Points gagnes a chaque prise. 0 = retire du bareme.", ChatFormatting.GRAY)),
                Icons.label("Valider", ChatFormatting.GREEN), current, 0, 10_000,
                v -> {
                    MairieData.get(player.server).setPointsFor(entityTypeId, (int) v);
                    player.sendSystemMessage(v > 0
                            ? Messages.success(label + " : " + v + " point(s) par prise.")
                            : Messages.warn(label + " retire du bareme."));
                    onDone.accept(player);
                });
    }

    // ================================================================= Palmares

    /** Les podiums des journees passees, du plus recent au plus ancien. */
    public static void openPalmares(ServerPlayer player, java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        MairieData mairie = MairieData.get(player.server);
        List<MairieData.Podium> history = mairie.palmares();

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("JOURNEE"), 86, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("VAINQUEUR"), 120, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("POINTS"), 60, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRIMES"), 62, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (MairieData.Podium p : history) {
            MairieData.PodiumEntry first = p.entries().isEmpty() ? null : p.entries().get(0);
            long primes = 0;
            for (MairieData.PodiumEntry e : p.entries()) {
                primes += e.reward();
            }
            final long total = primes;
            rows.add(new OwoMenuServer.TableRow(
                    new ItemStack(Items.GOLDEN_HELMET),
                    List.of(value(dayLabel(p.day()), ChatFormatting.AQUA),
                            value(first == null ? "-" : first.name(), ChatFormatting.WHITE),
                            value(first == null ? "-" : String.valueOf(first.points()), ChatFormatting.GOLD),
                            value(total > 0 ? String.valueOf(total) : "-",
                                    total > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)),
                    sp -> openPodiumDetail(sp, p, back)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(
                    List.of(Icons.lore("Aucune journee soldee pour l'instant.", ChatFormatting.DARK_GRAY),
                            Component.empty(), Component.empty(), Component.empty()),
                    null));
        }

        OwoMenuServer.openTable(player, Icons.screenTitle("Palmares", ChatFormatting.GOLD),
                List.of(Icons.lore("Les " + MairieData.MAX_PALMARES
                        + " dernieres journees soldees.", ChatFormatting.GRAY)),
                List.of(), columns, rows, List.of(), null, null,
                sp -> openPalmares(sp, back), sp -> openLeaderboard(sp, back));
    }

    private static void openPodiumDetail(ServerPlayer player, MairieData.Podium podium,
                                         java.util.function.Consumer<ServerPlayer> back) {
        if (denied(player)) {
            return;
        }
        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("#"), 24, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("CHASSEUR"), 130, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("POINTS"), 62, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRISES"), 56, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRIME"), 56, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (int i = 0; i < podium.entries().size(); i++) {
            MairieData.PodiumEntry e = podium.entries().get(i);
            int rank = i + 1;
            rows.add(new OwoMenuServer.TableRow(
                    List.of(value(String.valueOf(rank), rankColor(rank)),
                            value(e.name() == null || e.name().isBlank() ? "?" : e.name(), ChatFormatting.WHITE),
                            value(String.valueOf(e.points()), ChatFormatting.GOLD),
                            value(String.valueOf(e.kills()), ChatFormatting.GRAY),
                            value(e.reward() > 0 ? String.valueOf(e.reward()) : "-",
                                    e.reward() > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)),
                    null));
        }

        OwoMenuServer.openTable(player, Icons.title(dayLabel(podium.day()), ChatFormatting.GOLD),
                List.of(), List.of(), columns, rows, List.of(), null, null,
                null, sp -> openPalmares(sp, back));
    }

    private static String dayLabel(long epochDay) {
        return java.time.LocalDate.ofEpochDay(epochDay)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
}
