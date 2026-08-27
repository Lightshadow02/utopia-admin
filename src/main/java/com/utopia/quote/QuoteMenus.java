package com.utopia.quote;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.utopia.data.QuoteData;
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
 * Interfaces des devis : redaction cote emetteur, reponse et reglement cote destinataire, historique
 * pour les deux parties, et vue d'ensemble pour l'administration.
 */
public final class QuoteMenus {

    private static final int PAGE_SIZE = 12;
    /** Lignes detaillees affichees directement sur la fiche avant de renvoyer vers la liste complete. */
    private static final int DETAIL_LINES = 10;

    private QuoteMenus() {
    }

    // ==============================================================================================
    //  Accueil joueur
    // ==============================================================================================

    public static void openHome(ServerPlayer player) {
        MinecraftServer server = player.server;
        QuoteData data = QuoteData.get(server);
        data.rememberName(player.getUUID(), player.getGameProfile().getName());
        UUID me = player.getUUID();

        int waiting = data.awaitingCount(me);
        List<QuoteData.Quote> issued = data.issuedBy(me);
        int drafts = 0;
        long billed = 0;
        for (QuoteData.Quote q : issued) {
            if (q.status == QuoteData.Status.BROUILLON) {
                drafts++;
            }
            if (q.status == QuoteData.Status.SOLDE) {
                billed += q.total();
            }
        }

        Component title = Component.literal("MES DEVIS")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = new ArrayList<>();
        stats.add(stat("Devis emis : ", issued.size() + " (dont " + drafts + " brouillon(s))",
                ChatFormatting.AQUA));
        stats.add(stat("Devis recus : ", data.receivedBy(me).size() + " ", ChatFormatting.WHITE));
        stats.add(stat("Total encaisse : ", billed + " Utopieces", ChatFormatting.GREEN));
        if (waiting > 0) {
            stats.add(Component.literal(waiting + " devis attend(ent) votre reponse.")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)));
        }

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                Icons.label("Rediger un devis", ChatFormatting.GREEN),
                Icons.lore("Objet, lignes chiffrees, destinataire", ChatFormatting.GRAY),
                sp -> {
                    QuoteData.Quote quote = QuoteManager.create(sp);
                    if (quote == null) {
                        sp.sendSystemMessage(Messages.warn("Vous avez deja " + QuoteData.MAX_DRAFTS
                                + " brouillons en cours : envoyez-en un ou supprimez-le avant d'en ouvrir"
                                + " un nouveau."));
                        openDrafts(sp, 0);
                        return;
                    }
                    sp.sendSystemMessage(Messages.info("Nouveau devis " + quote.id + " ouvert."));
                    openEditor(sp, quote.id);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                Icons.label("Mes brouillons", ChatFormatting.YELLOW),
                Icons.lore(drafts + " / " + QuoteData.MAX_DRAFTS + " devis en cours de redaction",
                        ChatFormatting.GRAY),
                sp -> openDrafts(sp, 0)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITTEN_BOOK),
                Icons.label("Devis recus", waiting > 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE),
                Icons.lore(waiting > 0 ? waiting + " en attente de votre reponse" : "Historique de vos devis recus",
                        waiting > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                sp -> openReceived(sp, 0)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.BOOK),
                Icons.label("Devis emis", ChatFormatting.AQUA),
                Icons.lore("Historique de ce que vous avez adresse", ChatFormatting.GRAY),
                sp -> openIssued(sp, 0)));

        OwoMenuServer.openHub(player, title, stats, entries, QuoteMenus::openHome,
                com.utopia.menu.MainMenu::open);
    }

    // ==============================================================================================
    //  Listes
    // ==============================================================================================

    public static void openDrafts(ServerPlayer player, int page) {
        List<QuoteData.Quote> drafts = new ArrayList<>();
        for (QuoteData.Quote q : QuoteData.get(player.server).issuedBy(player.getUUID())) {
            if (q.status == QuoteData.Status.BROUILLON) {
                drafts.add(q);
            }
        }
        list(player, "Mes brouillons", ChatFormatting.YELLOW, drafts, page,
                "Aucun brouillon en cours.", QuoteMenus::openDrafts,
                (sp, id) -> openEditor(sp, id), false);
    }

    public static void openIssued(ServerPlayer player, int page) {
        list(player, "Devis emis", ChatFormatting.AQUA,
                QuoteData.get(player.server).issuedBy(player.getUUID()), page,
                "Vous n'avez encore adresse aucun devis.", QuoteMenus::openIssued,
                (sp, id) -> {
                    QuoteData.Quote q = QuoteData.get(sp.server).quote(id);
                    if (q != null && q.status == QuoteData.Status.BROUILLON) {
                        openEditor(sp, id); // un brouillon se reprend, il ne se consulte pas
                    } else {
                        openQuote(sp, id, s2 -> openIssued(s2, 0));
                    }
                }, false);
    }

    public static void openReceived(ServerPlayer player, int page) {
        list(player, "Devis recus", ChatFormatting.WHITE,
                QuoteData.get(player.server).receivedBy(player.getUUID()), page,
                "Vous n'avez recu aucun devis.", QuoteMenus::openReceived,
                (sp, id) -> openQuote(sp, id, s2 -> openReceived(s2, 0)), true);
    }

    /** Liste paginee de devis ; {@code showIssuer} affiche l'emetteur plutot que le destinataire. */
    private static void list(ServerPlayer player, String heading, ChatFormatting color,
                             List<QuoteData.Quote> quotes, int page, String emptyText,
                             java.util.function.BiConsumer<ServerPlayer, Integer> reopen,
                             java.util.function.BiConsumer<ServerPlayer, String> onClick,
                             boolean showIssuer) {
        QuoteData data = QuoteData.get(player.server);
        Component title = Component.literal(heading).withStyle(s -> s.withColor(color).withBold(true));
        List<Component> stats = quotes.isEmpty()
                ? List.of(Component.literal(emptyText)
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))
                : List.of(Component.literal(quotes.size() + " devis")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (QuoteData.Quote quote : quotes) {
            String id = quote.id;
            String other = showIssuer ? data.nameOf(quote.issuer) : data.nameOf(quote.client);
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(icon(quote.status)),
                    Icons.label(quote.id + " - " + quote.title, ChatFormatting.WHITE),
                    Icons.lore(quote.status.label() + " - " + quote.total() + " Utopieces - "
                                    + (showIssuer ? "de " : "pour ") + other,
                            QuoteManager.color(quote.status)),
                    sp -> onClick.accept(sp, id)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE, reopen,
                QuoteMenus::openHome);
    }

    private static net.minecraft.world.item.Item icon(QuoteData.Status status) {
        return switch (status) {
            case BROUILLON -> Items.PAPER;
            case ENVOYE -> Items.WRITTEN_BOOK;
            case ACCEPTE -> Items.GOLD_INGOT;
            case SOLDE -> Items.EMERALD;
            case REFUSE, ANNULE -> Items.BARRIER;
            case EXPIRE -> Items.GRAY_DYE;
        };
    }

    // ==============================================================================================
    //  Redaction (emetteur, brouillon)
    // ==============================================================================================

    public static void openEditor(ServerPlayer player, String id) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || !quote.issuer.equals(player.getUUID())) {
            openHome(player);
            return;
        }
        if (quote.status != QuoteData.Status.BROUILLON) {
            // Un devis parti n'est plus modifiable : il devient une piece consultable.
            openQuote(player, id, s -> openIssued(s, 0));
            return;
        }

        Component title = Component.literal("Devis " + quote.id + " (brouillon)")
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Objet", ChatFormatting.GRAY),
                Icons.label(quote.title, ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Objet du devis", ChatFormatting.GOLD),
                        List.of(Icons.lore("Ce que vous proposez, en une phrase", ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), quote.title, 48,
                        text -> {
                            if (text != null && !text.isBlank()) {
                                quote.title = text;
                                QuoteData.get(sp.server).setDirty();
                            }
                            openEditor(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Destinataire", ChatFormatting.GRAY),
                Icons.label(quote.client == null ? "a choisir" : data.nameOf(quote.client),
                        quote.client == null ? ChatFormatting.RED : ChatFormatting.AQUA),
                Icons.label("Choisir", ChatFormatting.YELLOW),
                sp -> openClientPicker(sp, id, 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Lignes", ChatFormatting.GRAY),
                Icons.label(quote.lines.size() + " ligne(s)", ChatFormatting.WHITE),
                Icons.label("Gerer", ChatFormatting.YELLOW),
                sp -> openLines(sp, id, 0)));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Total", ChatFormatting.GRAY),
                Icons.label(quote.total() + " Utopieces", ChatFormatting.GOLD), null, null));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Validite", ChatFormatting.GRAY),
                Icons.label(quote.validityDays > 0 ? quote.validityDays + " jour(s) apres envoi"
                        : "sans date limite", ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptAmount(sp, Icons.label("Validite du devis", ChatFormatting.GOLD),
                        List.of(Icons.lore("En jours reels apres l'envoi ; 0 = sans date limite",
                                ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), quote.validityDays, 0, 365,
                        v -> {
                            quote.validityDays = (int) v;
                            QuoteData.get(sp.server).setDirty();
                            openEditor(sp, id);
                        })));
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Conditions", ChatFormatting.GRAY),
                Icons.label(quote.note.isBlank() ? "aucune" : quote.note,
                        quote.note.isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Conditions du devis", ChatFormatting.GOLD),
                        List.of(Icons.lore("Delai, materiaux fournis, acompte souhaite...",
                                ChatFormatting.GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN), quote.note, 96,
                        text -> {
                            quote.note = text == null ? "" : text;
                            QuoteData.get(sp.server).setDirty();
                            openEditor(sp, id);
                        })));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Envoyer", ChatFormatting.GREEN),
                        sp -> {
                            QuoteManager.SendResult result = QuoteManager.send(sp, quote);
                            if (result != QuoteManager.SendResult.OK) {
                                sp.sendSystemMessage(Messages.warn(QuoteManager.reason(result)));
                                openEditor(sp, id);
                                return;
                            }
                            sp.sendSystemMessage(Messages.success("Devis " + quote.id + " adresse a "
                                    + QuoteData.get(sp.server).nameOf(quote.client) + "."));
                            openQuote(sp, id, s2 -> openIssued(s2, 0));
                        }),
                new OwoMenuServer.PanelAction(Icons.label("Supprimer", ChatFormatting.RED),
                        sp -> {
                            QuoteData d = QuoteData.get(sp.server);
                            QuoteData.Quote fresh = d.quote(id);
                            // Un devis parti est une piece : seul un brouillon peut disparaitre.
                            if (fresh == null || fresh.status != QuoteData.Status.BROUILLON) {
                                sp.sendSystemMessage(Messages.warn("Ce devis n'est plus un brouillon."));
                                openHome(sp);
                                return;
                            }
                            d.remove(id);
                            sp.sendSystemMessage(Messages.info("Brouillon supprime."));
                            openDrafts(sp, 0);
                        }));

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openEditor(sp, id),
                sp -> openDrafts(sp, 0));
    }

    /** Lignes du devis : designation, quantite, prix unitaire. */
    public static void openLines(ServerPlayer player, String id, int page) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || !quote.issuer.equals(player.getUUID())
                || quote.status != QuoteData.Status.BROUILLON) {
            openHome(player);
            return;
        }
        Component title = Component.literal("Lignes - " + quote.id)
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));
        List<Component> stats = List.of(
                stat("Total : ", quote.total() + " Utopieces", ChatFormatting.GOLD),
                stat("Lignes : ", quote.lines.size() + " / " + QuoteData.MAX_LINES, ChatFormatting.GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (quote.lines.size() < QuoteData.MAX_LINES) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LIME_DYE),
                    Icons.label("Ajouter une ligne", ChatFormatting.GREEN),
                    Icons.lore("Designation, quantite, prix unitaire", ChatFormatting.GRAY),
                    sp -> promptNewLine(sp, id)));
        }
        for (int i = 0; i < quote.lines.size(); i++) {
            QuoteData.Line line = quote.lines.get(i);
            final int index = i;
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PAPER),
                    Icons.label(line.label, ChatFormatting.WHITE),
                    Icons.lore(line.quantity + " x " + line.unitPrice + " = " + line.total()
                            + " Utopieces", ChatFormatting.GRAY),
                    sp -> openLine(sp, id, index)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openLines(sp, id, p), sp -> openEditor(sp, id));
    }

    private static void promptNewLine(ServerPlayer player, String id) {
        Menus.promptFreeText(player, Icons.label("Designation", ChatFormatting.GOLD),
                List.of(Icons.lore("Ce que couvre cette ligne", ChatFormatting.GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), "", 48,
                label -> {
                    if (label == null || label.isBlank()) {
                        openLines(player, id, 0);
                        return;
                    }
                    Menus.promptAmount(player, Icons.label("Quantite", ChatFormatting.GOLD), List.of(),
                            Icons.label("Suivant", ChatFormatting.GREEN), 1, 1, 9_999,
                            qty -> Menus.promptAmount(player,
                                    Icons.label("Prix unitaire", ChatFormatting.GOLD),
                                    List.of(Icons.lore("En Utopieces", ChatFormatting.GRAY)),
                                    Icons.label("Ajouter", ChatFormatting.GREEN), 100, 0, 1_000_000_000L,
                                    price -> {
                                        QuoteData d = QuoteData.get(player.server);
                                        QuoteData.Quote quote = d.quote(id);
                                        if (quote != null && quote.status == QuoteData.Status.BROUILLON
                                                && quote.lines.size() < QuoteData.MAX_LINES) {
                                            quote.lines.add(new QuoteData.Line(label, (int) qty, price));
                                            d.setDirty();
                                        }
                                        openLines(player, id, 0);
                                    }));
                });
    }

    public static void openLine(ServerPlayer player, String id, int index) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || !quote.issuer.equals(player.getUUID())
                || quote.status != QuoteData.Status.BROUILLON
                || index < 0 || index >= quote.lines.size()) {
            openLines(player, id, 0);
            return;
        }
        QuoteData.Line line = quote.lines.get(index);

        Component title = Component.literal("Ligne " + (index + 1) + " - " + quote.id)
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true));

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Designation", ChatFormatting.GRAY),
                        Icons.label(line.label, ChatFormatting.WHITE),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptFreeText(sp, Icons.label("Designation", ChatFormatting.GOLD),
                                List.of(), Icons.label("Valider", ChatFormatting.GREEN), line.label, 48,
                                text -> {
                                    if (text != null && !text.isBlank()) {
                                        line.label = text;
                                        QuoteData.get(sp.server).setDirty();
                                    }
                                    openLine(sp, id, index);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Quantite", ChatFormatting.GRAY),
                        Icons.label(String.valueOf(line.quantity), ChatFormatting.AQUA),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptAmount(sp, Icons.label("Quantite", ChatFormatting.GOLD), List.of(),
                                Icons.label("Valider", ChatFormatting.GREEN), line.quantity, 1, 9_999,
                                v -> {
                                    line.quantity = (int) v;
                                    QuoteData.get(sp.server).setDirty();
                                    openLine(sp, id, index);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Prix unitaire", ChatFormatting.GRAY),
                        Icons.label(line.unitPrice + " Utopieces", ChatFormatting.GOLD),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptAmount(sp, Icons.label("Prix unitaire", ChatFormatting.GOLD),
                                List.of(), Icons.label("Valider", ChatFormatting.GREEN),
                                line.unitPrice, 0, 1_000_000_000L,
                                v -> {
                                    line.unitPrice = v;
                                    QuoteData.get(sp.server).setDirty();
                                    openLine(sp, id, index);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Total de la ligne", ChatFormatting.GRAY),
                        Icons.label(line.total() + " Utopieces", ChatFormatting.GREEN), null, null));

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Supprimer la ligne", ChatFormatting.RED),
                        sp -> {
                            QuoteData d = QuoteData.get(sp.server);
                            QuoteData.Quote fresh = d.quote(id);
                            if (fresh != null && fresh.status == QuoteData.Status.BROUILLON
                                    && index < fresh.lines.size()) {
                                fresh.lines.remove(index);
                                d.setDirty();
                            }
                            openLines(sp, id, 0);
                        }));

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openLine(sp, id, index),
                sp -> openLines(sp, id, 0));
    }

    /** Choix du destinataire : joueurs en ligne, ou saisie d'un pseudo deja connu. */
    public static void openClientPicker(ServerPlayer player, String id, int page) {
        MinecraftServer server = player.server;
        QuoteData data = QuoteData.get(server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || !quote.issuer.equals(player.getUUID())
                || quote.status != QuoteData.Status.BROUILLON) {
            openHome(player);
            return;
        }
        Component title = Component.literal("Destinataire - " + quote.id)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.NAME_TAG),
                Icons.label("Saisir un pseudo", ChatFormatting.YELLOW),
                Icons.lore("Pour un joueur hors ligne deja venu sur le serveur", ChatFormatting.GRAY),
                sp -> Menus.promptText(sp, Icons.label("Pseudo du destinataire", ChatFormatting.GOLD),
                        List.of(), Icons.label("Choisir", ChatFormatting.GREEN), "", 16,
                        pseudo -> {
                            chooseByName(sp, id, pseudo);
                            openEditor(sp, id);
                        })));
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            if (tid.equals(player.getUUID())) {
                continue; // on ne s'adresse pas un devis a soi-meme
            }
            String tname = target.getGameProfile().getName();
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, ChatFormatting.WHITE),
                    Icons.lore("Clic : adresser le devis a ce joueur", ChatFormatting.GRAY),
                    sp -> {
                        QuoteData d = QuoteData.get(sp.server);
                        QuoteData.Quote fresh = d.quote(id);
                        if (fresh != null && fresh.status == QuoteData.Status.BROUILLON) {
                            fresh.client = tid;
                            d.rememberName(tid, tname);
                            d.setDirty();
                        }
                        openEditor(sp, id);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, List.of(), entries, page, PAGE_SIZE,
                (sp, p) -> openClientPicker(sp, id, p), sp -> openEditor(sp, id));
    }

    private static void chooseByName(ServerPlayer player, String id, String pseudo) {
        if (pseudo == null || pseudo.isBlank()) {
            return;
        }
        MinecraftServer server = player.server;
        QuoteData data = QuoteData.get(server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || quote.status != QuoteData.Status.BROUILLON) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(pseudo.trim());
        UUID target = online != null ? online.getUUID() : data.findByName(pseudo.trim());
        if (target == null) {
            target = com.utopia.data.JobData.get(server).findByName(pseudo.trim());
        }
        if (target == null) {
            player.sendSystemMessage(Messages.error("Joueur inconnu : \"" + pseudo.trim()
                    + "\". Il doit s'etre connecte au moins une fois."));
            return;
        }
        if (target.equals(player.getUUID())) {
            player.sendSystemMessage(Messages.warn(QuoteManager.reason(QuoteManager.SendResult.SELF)));
            return;
        }
        quote.client = target;
        if (online != null) {
            data.rememberName(target, online.getGameProfile().getName());
        }
        data.setDirty();
        player.sendSystemMessage(Messages.success("Devis adresse a " + data.nameOf(target) + "."));
    }

    // ==============================================================================================
    //  Fiche d'un devis
    // ==============================================================================================

    /** Vue detaillee, lisible par l'emetteur, le destinataire et l'administration. */
    public static void openQuote(ServerPlayer player, String id, Consumer<ServerPlayer> back) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null) {
            player.sendSystemMessage(Messages.warn("Ce devis n'existe plus."));
            openHome(player);
            return;
        }
        UUID me = player.getUUID();
        boolean isIssuer = quote.issuer.equals(me);
        // Tant que le devis n'est pas parti, il n'a pas de destinataire : seul l'emetteur y a acces.
        boolean isClient = me.equals(quote.client) && quote.sentAt > 0;
        boolean isAdmin = player.hasPermissions(2);
        if (!isIssuer && !isClient && !isAdmin) {
            player.sendSystemMessage(Messages.warn("Ce devis ne vous concerne pas."));
            openHome(player);
            return;
        }

        Component title = Component.literal("Devis " + quote.id + " - " + quote.title)
                .withStyle(s -> s.withColor(QuoteManager.color(quote.status)).withBold(true));

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(row("Etat", quote.status.label(), QuoteManager.color(quote.status)));
        rows.add(row("Emetteur", data.nameOf(quote.issuer), ChatFormatting.WHITE));
        rows.add(row("Destinataire", data.nameOf(quote.client), ChatFormatting.WHITE));
        rows.add(row("Redige le", QuoteManager.stamp(quote.createdAt), ChatFormatting.DARK_GRAY));
        if (quote.sentAt > 0) {
            rows.add(row("Envoye le", QuoteManager.stamp(quote.sentAt), ChatFormatting.DARK_GRAY));
        }
        if (quote.deadline() > 0 && quote.status == QuoteData.Status.ENVOYE) {
            rows.add(row("Valable jusqu'au", QuoteManager.stamp(quote.deadline()), ChatFormatting.YELLOW));
        }
        if (!quote.note.isBlank()) {
            rows.add(row("Conditions", quote.note, ChatFormatting.GRAY));
        }

        int shown = Math.min(quote.lines.size(), DETAIL_LINES);
        for (int i = 0; i < shown; i++) {
            QuoteData.Line line = quote.lines.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label(line.label, ChatFormatting.WHITE),
                    Icons.label(line.quantity + " x " + line.unitPrice + " = " + line.total(),
                            ChatFormatting.GOLD), null, null));
        }
        if (quote.lines.size() > shown) {
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label("... et " + (quote.lines.size() - shown) + " autre(s) ligne(s)",
                            ChatFormatting.DARK_GRAY),
                    Icons.label("", ChatFormatting.WHITE),
                    Icons.label("Tout voir", ChatFormatting.YELLOW),
                    sp -> openAllLines(sp, id, 0, back)));
        }

        rows.add(row("TOTAL", quote.total() + " Utopieces", ChatFormatting.GOLD));
        if (quote.paid > 0) {
            rows.add(row("Deja regle", quote.paid + " Utopieces", ChatFormatting.GREEN));
            rows.add(row("Reste a payer", quote.remaining() + " Utopieces", ChatFormatting.YELLOW));
        }
        if (data.taxPercent() > 0) {
            rows.add(row("Taxe de la mairie", data.taxPercent() + " % du reglement",
                    ChatFormatting.DARK_GRAY));
        }

        List<OwoMenuServer.PanelAction> footer = new ArrayList<>();
        if (isClient && quote.awaitingAnswer()) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Accepter", ChatFormatting.GREEN),
                    sp -> {
                        if (QuoteManager.accept(sp, QuoteData.get(sp.server).quote(id))) {
                            sp.sendSystemMessage(Messages.success("Devis " + id
                                    + " accepte. Vous pouvez le regler quand vous le souhaitez."));
                        } else {
                            sp.sendSystemMessage(Messages.warn("Ce devis n'attend plus de reponse."));
                        }
                        openQuote(sp, id, back);
                    }));
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Refuser", ChatFormatting.RED),
                    sp -> {
                        if (QuoteManager.refuse(sp, QuoteData.get(sp.server).quote(id))) {
                            sp.sendSystemMessage(Messages.info("Devis " + id + " refuse."));
                        }
                        openQuote(sp, id, back);
                    }));
        }
        if (isClient && quote.status == QuoteData.Status.ACCEPTE && quote.remaining() > 0) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Regler", ChatFormatting.GREEN),
                    sp -> promptPay(sp, id, back)));
        }
        if (isIssuer && quote.status == QuoteData.Status.ENVOYE) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Annuler le devis", ChatFormatting.RED),
                    sp -> {
                        if (QuoteManager.cancel(sp, QuoteData.get(sp.server).quote(id))) {
                            sp.sendSystemMessage(Messages.info("Devis " + id + " annule."));
                        } else {
                            sp.sendSystemMessage(Messages.warn("Ce devis ne peut plus etre annule."));
                        }
                        openQuote(sp, id, back);
                    }));
        } else if (isIssuer && quote.status == QuoteData.Status.BROUILLON) {
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Reprendre la redaction", ChatFormatting.YELLOW),
                    sp -> openEditor(sp, id)));
            footer.add(new OwoMenuServer.PanelAction(
                    Icons.label("Supprimer le brouillon", ChatFormatting.RED),
                    sp -> {
                        QuoteData d = QuoteData.get(sp.server);
                        QuoteData.Quote fresh = d.quote(id);
                        if (fresh == null || !fresh.issuer.equals(sp.getUUID())
                                || fresh.status != QuoteData.Status.BROUILLON) {
                            sp.sendSystemMessage(Messages.warn("Ce devis n'est plus un brouillon."));
                            openHome(sp);
                            return;
                        }
                        d.remove(id);
                        sp.sendSystemMessage(Messages.info("Brouillon supprime."));
                        openDrafts(sp, 0);
                    }));
        }

        OwoMenuServer.openPanel(player, title, rows, footer, sp -> openQuote(sp, id, back), back);
    }

    /** Liste complete des lignes d'un devis, en lecture seule. */
    public static void openAllLines(ServerPlayer player, String id, int page, Consumer<ServerPlayer> back) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null) {
            openHome(player);
            return;
        }
        Component title = Component.literal("Detail - " + quote.id)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        int perPage = 10;
        int pages = Math.max(1, (quote.lines.size() + perPage - 1) / perPage);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * perPage;
        int to = Math.min(quote.lines.size(), from + perPage);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            QuoteData.Line line = quote.lines.get(i);
            rows.add(new OwoMenuServer.PanelRow(
                    Icons.label((i + 1) + ". " + line.label, ChatFormatting.WHITE),
                    Icons.label(line.quantity + " x " + line.unitPrice + " = " + line.total(),
                            ChatFormatting.GOLD), null, null));
        }
        rows.add(row("TOTAL", quote.total() + " Utopieces", ChatFormatting.GOLD));

        Consumer<ServerPlayer> prev = pages > 1 ? sp -> openAllLines(sp, id, (cur - 1 + pages) % pages, back) : null;
        Consumer<ServerPlayer> next = pages > 1 ? sp -> openAllLines(sp, id, (cur + 1) % pages, back) : null;
        OwoMenuServer.openPanel(player, title, rows, List.of(), false, prev, next,
                sp -> openAllLines(sp, id, cur, back), sp -> openQuote(sp, id, back));
    }

    private static void promptPay(ServerPlayer client, String id, Consumer<ServerPlayer> back) {
        QuoteData data = QuoteData.get(client.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || quote.status != QuoteData.Status.ACCEPTE || quote.remaining() <= 0) {
            openQuote(client, id, back);
            return;
        }
        long remaining = quote.remaining();
        long available = EconomyManager.countCoins(client)
                + EconomyManager.getBalance(client.server, client.getUUID());
        Menus.promptAmount(client, Icons.label("Regler le devis " + quote.id, ChatFormatting.GREEN),
                List.of(Icons.lore("Reste a payer : " + remaining + " Utopieces", ChatFormatting.GRAY),
                        Icons.lore("Disponible (pieces + banque) : " + available, ChatFormatting.DARK_GRAY),
                        Icons.lore("Un montant partiel vaut acompte", ChatFormatting.DARK_GRAY)),
                Icons.label("Payer", ChatFormatting.GREEN), remaining, 1, remaining,
                amount -> {
                    QuoteManager.PayResult result = QuoteManager.pay(client,
                            QuoteData.get(client.server).quote(id), amount);
                    if (result != QuoteManager.PayResult.OK) {
                        client.sendSystemMessage(Messages.warn(QuoteManager.reason(result)));
                    } else {
                        QuoteData.Quote fresh = QuoteData.get(client.server).quote(id);
                        client.sendSystemMessage(Messages.success(
                                fresh != null && fresh.remaining() <= 0
                                        ? "Devis " + id + " solde. Merci !"
                                        : "Acompte de " + amount + " Utopieces verse."));
                    }
                    openQuote(client, id, back);
                });
    }

    // ==============================================================================================
    //  Administration
    // ==============================================================================================

    public static void openAdmin(ServerPlayer admin) {
        openAdmin(admin, 0);
    }

    public static void openAdmin(ServerPlayer admin, int page) {
        QuoteData data = QuoteData.get(admin.server);
        List<QuoteData.Quote> all = data.all();

        long open = 0;
        long settled = 0;
        long volume = 0;
        for (QuoteData.Quote q : all) {
            if (q.status == QuoteData.Status.ENVOYE || q.status == QuoteData.Status.ACCEPTE) {
                open++;
            }
            if (q.status == QuoteData.Status.SOLDE) {
                settled++;
                volume += q.total();
            }
        }

        Component title = Component.literal("DEVIS DES JOUEURS")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                stat(all.size() + " devis - ", open + " en cours, " + settled + " solde(s)",
                        ChatFormatting.AQUA),
                stat("Volume regle : ", volume + " Utopieces", ChatFormatting.GREEN),
                stat("Taxe de la mairie : ", data.taxPercent() + " % du reglement", ChatFormatting.GRAY));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Par joueur", ChatFormatting.AQUA),
                Icons.lore("Devis emis et recus, joueur par joueur", ChatFormatting.GRAY),
                sp -> openAdminPlayers(sp, 0)));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPARATOR),
                Icons.label("Reglages", ChatFormatting.YELLOW),
                Icons.lore("Taxe et validite par defaut", ChatFormatting.GRAY),
                QuoteMenus::openAdminSettings));
        for (QuoteData.Quote quote : all) {
            String id = quote.id;
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(icon(quote.status)),
                    Icons.label(quote.id + " - " + quote.title, ChatFormatting.WHITE),
                    Icons.lore(quote.status.label() + " - " + data.nameOf(quote.issuer) + " -> "
                                    + data.nameOf(quote.client) + " - " + quote.total() + " Utopieces",
                            QuoteManager.color(quote.status)),
                    sp -> openQuote(sp, id, s2 -> openAdmin(s2, 0))));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                QuoteMenus::openAdmin, com.utopia.menu.AdminMenu::open);
    }

    /** Joueurs apparaissant dans au moins un devis. */
    public static void openAdminPlayers(ServerPlayer admin, int page) {
        QuoteData data = QuoteData.get(admin.server);
        Component title = Component.literal("Devis par joueur")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (UUID id : data.participants()) {
            String name = data.nameOf(id);
            int issued = data.issuedBy(id).size();
            int received = data.receivedBy(id).size();
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                    Icons.label(name, ChatFormatting.WHITE),
                    Icons.lore(issued + " emis - " + received + " recus", ChatFormatting.GRAY),
                    sp -> openAdminPlayer(sp, id, 0)));
        }
        List<Component> stats = entries.isEmpty()
                ? List.of(Icons.lore("Aucun devis pour l'instant.", ChatFormatting.GRAY))
                : List.of(Icons.lore(entries.size() + " joueur(s) concerne(s)", ChatFormatting.GRAY));

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                QuoteMenus::openAdminPlayers, QuoteMenus::openAdmin);
    }

    /** Historique complet d'un joueur : ce qu'il a emis et ce qu'il a recu. */
    public static void openAdminPlayer(ServerPlayer admin, UUID target, int page) {
        QuoteData data = QuoteData.get(admin.server);
        String name = data.nameOf(target);
        List<QuoteData.Quote> issued = data.issuedBy(target);
        List<QuoteData.Quote> received = data.receivedBy(target);

        long billed = 0;
        long spent = 0;
        for (QuoteData.Quote q : issued) {
            billed += q.paid;
        }
        for (QuoteData.Quote q : received) {
            spent += q.paid;
        }

        Component title = Component.literal("Devis de " + name)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(
                stat("Emis : ", issued.size() + " - encaisse " + billed + " Utopieces",
                        ChatFormatting.GREEN),
                stat("Recus : ", received.size() + " - regle " + spent + " Utopieces",
                        ChatFormatting.YELLOW));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (QuoteData.Quote quote : issued) {
            entries.add(adminEntry(data, quote, "EMIS vers " + data.nameOf(quote.client), target, page));
        }
        for (QuoteData.Quote quote : received) {
            entries.add(adminEntry(data, quote, "RECU de " + data.nameOf(quote.issuer), target, page));
        }

        OwoMenuServer.openHubPaged(admin, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openAdminPlayer(sp, target, p), sp -> openAdminPlayers(sp, 0));
    }

    private static OwoMenuServer.HubEntry adminEntry(QuoteData data, QuoteData.Quote quote, String sense,
                                                     UUID target, int page) {
        String id = quote.id;
        return new OwoMenuServer.HubEntry(new ItemStack(icon(quote.status)),
                Icons.label(quote.id + " - " + quote.title, ChatFormatting.WHITE),
                Icons.lore(sense + " - " + quote.status.label() + " - " + quote.total() + " Utopieces",
                        QuoteManager.color(quote.status)),
                sp -> openQuote(sp, id, s2 -> openAdminPlayer(s2, target, page)));
    }

    public static void openAdminSettings(ServerPlayer admin) {
        QuoteData data = QuoteData.get(admin.server);
        Component title = Component.literal("Reglages des devis")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        List<OwoMenuServer.PanelRow> rows = List.of(
                new OwoMenuServer.PanelRow(
                        Icons.label("Taxe de la mairie", ChatFormatting.GRAY),
                        Icons.label(data.taxPercent() + " % du reglement",
                                data.taxPercent() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptAmount(sp, Icons.label("Taxe sur les devis", ChatFormatting.GOLD),
                                List.of(Icons.lore("Part prelevee sur chaque reglement, en %",
                                                ChatFormatting.GRAY),
                                        Icons.lore("0 = aucune taxe", ChatFormatting.DARK_GRAY)),
                                Icons.label("Valider", ChatFormatting.GREEN), data.taxPercent(), 0, 100,
                                v -> {
                                    QuoteData.get(sp.server).setTaxPercent((int) v);
                                    openAdminSettings(sp);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Validite par defaut", ChatFormatting.GRAY),
                        Icons.label(data.defaultValidityDays() > 0
                                ? data.defaultValidityDays() + " jour(s)" : "sans date limite",
                                ChatFormatting.WHITE),
                        Icons.label("Modifier", ChatFormatting.YELLOW),
                        sp -> Menus.promptAmount(sp, Icons.label("Validite par defaut", ChatFormatting.GOLD),
                                List.of(Icons.lore("Proposee a la creation d'un devis ; 0 = sans limite",
                                        ChatFormatting.GRAY)),
                                Icons.label("Valider", ChatFormatting.GREEN),
                                data.defaultValidityDays(), 0, 365,
                                v -> {
                                    QuoteData.get(sp.server).setDefaultValidityDays((int) v);
                                    openAdminSettings(sp);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Reglement", ChatFormatting.GRAY),
                        Icons.label("accepter engage, payer reste un geste separe",
                                ChatFormatting.DARK_GRAY), null, null));

        OwoMenuServer.openPanel(admin, title, rows, List.of(), QuoteMenus::openAdminSettings,
                QuoteMenus::openAdmin);
    }

    // ==============================================================================================
    //  Utilitaires
    // ==============================================================================================

    private static OwoMenuServer.PanelRow row(String label, String value, ChatFormatting color) {
        return new OwoMenuServer.PanelRow(Icons.label(label, ChatFormatting.GRAY),
                Icons.label(value, color), null, null);
    }

    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }
}
