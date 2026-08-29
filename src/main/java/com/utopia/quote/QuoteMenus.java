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

        Component title = Icons.screenTitle("Mes devis", ChatFormatting.GOLD);
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

    /**
     * Liste paginee de devis, en tableau : un devis par ligne, montants cales a droite les uns sous
     * les autres pour qu'on compare sans ouvrir chaque fiche. {@code showIssuer} affiche l'emetteur
     * plutot que le destinataire.
     */
    private static void list(ServerPlayer player, String heading, ChatFormatting color,
                             List<QuoteData.Quote> quotes, int page, String emptyText,
                             java.util.function.BiConsumer<ServerPlayer, Integer> reopen,
                             java.util.function.BiConsumer<ServerPlayer, String> onClick,
                             boolean showIssuer) {
        QuoteData data = QuoteData.get(player.server);
        int pages = Math.max(1, (quotes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(quotes.size(), from + PAGE_SIZE);

        Component title = Icons.title(heading
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), color);
        List<Component> stats = List.of(Component.literal(
                        quotes.isEmpty() ? emptyText : quotes.size() + " devis")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DEVIS"), 42, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("OBJET"), 72, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("ETAT"), 52, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("MONTANT"), 50, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("AVEC"), 86, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (QuoteData.Quote quote : quotes.subList(Math.min(from, quotes.size()), to)) {
            String id = quote.id;
            String other = showIssuer ? data.nameOf(quote.issuer) : data.nameOf(quote.client);
            ChatFormatting stateColor = QuoteManager.color(quote.status);
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal(quote.id)
                            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                    Component.literal(clip(quote.title, 12))
                            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                    Component.literal(quote.status.label())
                            .withStyle(s -> s.withColor(stateColor).withItalic(false)),
                    Component.literal(String.valueOf(quote.total()))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)),
                    Component.literal(clip(other, 14))
                            .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))),
                    sp -> onClick.accept(sp, id)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal("Aucun devis")
                            .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                    Component.empty(), Component.empty(), Component.empty(), Component.empty()), null));
        }

        Consumer<ServerPlayer> onPrev = pages > 1
                ? sp -> reopen.accept(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> onNext = pages > 1
                ? sp -> reopen.accept(sp, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(player, title, stats, List.of(), columns, rows, List.of(),
                onPrev, onNext, sp -> reopen.accept(sp, cur), QuoteMenus::openHome);
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

        Component title = Icons.title("Devis " + quote.id + " (brouillon)", ChatFormatting.YELLOW);

        List<OwoMenuServer.PanelRow> rows = new ArrayList<>();
        rows.add(new OwoMenuServer.PanelRow(
                Icons.label("Objet", ChatFormatting.GRAY),
                Icons.label(quote.title, ChatFormatting.WHITE),
                Icons.label("Modifier", ChatFormatting.YELLOW),
                sp -> Menus.promptFreeText(sp, Icons.label("Objet du devis", ChatFormatting.GOLD),
                        List.of(Icons.lore("Ce que vous proposez, en une phrase", ChatFormatting.GRAY),
                                Icons.lore("Ex : Construction d'une maison en pierre",
                                        ChatFormatting.DARK_GRAY),
                                Icons.lore("Ex : Livraison de 10 stacks de chene", ChatFormatting.DARK_GRAY),
                                Icons.lore("Ex : Amenagement d'une boutique au marche",
                                        ChatFormatting.DARK_GRAY)),
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
                                        ChatFormatting.GRAY),
                                Icons.lore("La phrase proposee est modifiable mot a mot",
                                        ChatFormatting.DARK_GRAY)),
                        Icons.label("Valider", ChatFormatting.GREEN),
                        // Un modele tout pret vaut mieux qu'un champ vide : on corrige plus vite
                        // qu'on ne redige.
                        quote.note.isBlank()
                                ? "Materiaux fournis par mes soins, livraison sous 3 jours"
                                : quote.note,
                        96,
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
        Component title = Icons.title("Lignes - " + quote.id, ChatFormatting.YELLOW);
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
        QuoteData.Quote quote = QuoteData.get(player.server).quote(id);
        long runningTotal = quote == null ? 0 : quote.total();
        int lineNumber = quote == null ? 1 : quote.lines.size() + 1;
        Menus.promptFreeText(player, Icons.label("Ligne " + lineNumber + " - designation",
                        ChatFormatting.GOLD),
                List.of(Icons.lore("Ce que couvre cette ligne, facturee a l'unite",
                                ChatFormatting.GRAY),
                        Icons.lore("Ex : Main d'oeuvre (par heure)", ChatFormatting.DARK_GRAY),
                        Icons.lore("Ex : Bloc de pierre taillee", ChatFormatting.DARK_GRAY),
                        Icons.lore("Ex : Deplacement sur le chantier", ChatFormatting.DARK_GRAY)),
                Icons.label("Suivant", ChatFormatting.GREEN), "Main d'oeuvre", 48,
                label -> {
                    if (label == null || label.isBlank()) {
                        openLines(player, id, 0);
                        return;
                    }
                    Menus.promptAmount(player, Icons.label("Quantite - " + label, ChatFormatting.GOLD),
                            List.of(Icons.lore("Combien d'unites de \"" + label + "\"",
                                            ChatFormatting.GRAY),
                                    Icons.lore("Le total de la ligne sera quantite x prix unitaire",
                                            ChatFormatting.DARK_GRAY)),
                            Icons.label("Suivant", ChatFormatting.GREEN), 1, 1, 9_999,
                            qty -> Menus.promptAmount(player,
                                    Icons.label("Prix unitaire - " + label, ChatFormatting.GOLD),
                                    List.of(Icons.lore("Prix d'une seule unite, en Utopieces",
                                                    ChatFormatting.GRAY),
                                            Icons.lore("Cette ligne comptera " + qty + " x le prix saisi",
                                                    ChatFormatting.DARK_GRAY),
                                            Icons.lore("Total du devis avant cette ligne : "
                                                    + runningTotal + " Utopieces", ChatFormatting.DARK_GRAY)),
                                    Icons.label("Ajouter", ChatFormatting.GREEN), 100, 0, 1_000_000_000L,
                                    price -> {
                                        QuoteData d = QuoteData.get(player.server);
                                        QuoteData.Quote fresh = d.quote(id);
                                        if (fresh != null && fresh.status == QuoteData.Status.BROUILLON
                                                && fresh.lines.size() < QuoteData.MAX_LINES) {
                                            fresh.lines.add(new QuoteData.Line(label, (int) qty, price));
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

        Component title = Icons.title("Ligne " + (index + 1) + " - " + quote.id,
                ChatFormatting.YELLOW);

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
        Component title = Icons.title("Destinataire - " + quote.id, ChatFormatting.AQUA);

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

        Component title = Icons.title("Devis " + quote.id + " - " + quote.title,
                QuoteManager.color(quote.status));

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
            if (quote.paidCash > 0) {
                rows.add(row("dont en liquide", quote.paidCash
                        + " Utopieces, declare par l'emetteur", ChatFormatting.AQUA));
            }
            if (quote.remaining() > 0) {
                rows.add(row("Reste a payer", quote.remaining() + " Utopieces", ChatFormatting.YELLOW));
            }
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
                                    + " accepte : le bouton \"Payer le solde\" reglera tout d'un coup."));
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
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Payer le solde", ChatFormatting.GREEN),
                    sp -> payAll(sp, id, back)));
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Verser un acompte", ChatFormatting.YELLOW),
                    sp -> promptPay(sp, id, back)));
        }
        if (isIssuer && quote.remaining() > 0
                && (quote.status == QuoteData.Status.ACCEPTE || quote.status == QuoteData.Status.ENVOYE)) {
            footer.add(new OwoMenuServer.PanelAction(Icons.label("Regle en liquide", ChatFormatting.AQUA),
                    sp -> promptCash(sp, id, back)));
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

    /**
     * Liste complete des lignes d'un devis, en lecture seule. Quantite, prix unitaire et total sont
     * cales a droite : c'est la lecture en colonne qui permet de verifier le chiffrage.
     */
    public static void openAllLines(ServerPlayer player, String id, int page, Consumer<ServerPlayer> back) {
        QuoteData data = QuoteData.get(player.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null) {
            openHome(player);
            return;
        }

        int perPage = 10;
        int pages = Math.max(1, (quote.lines.size() + perPage - 1) / perPage);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * perPage;
        int to = Math.min(quote.lines.size(), from + perPage);

        Component title = Icons.title("Detail - " + quote.id
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.GOLD);
        List<Component> stats = List.of(
                stat("Total : ", quote.total() + " Utopieces", ChatFormatting.GOLD),
                stat("Lignes : ", String.valueOf(quote.lines.size()), ChatFormatting.GRAY));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DESIGNATION"), 150, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("QTE"), 24, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PRIX UNIT."), 64, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("TOTAL"), 66, OwoMenuServer.Column.RIGHT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            QuoteData.Line line = quote.lines.get(i);
            final int index = i;
            // La ligne s'ouvre : la colonne coupe une designation longue, et c'est ici le seul ecran
            // ou le destinataire voit les lignes au-dela de la dixieme. Il doit pouvoir les lire en
            // entier avant d'accepter ou de payer.
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal((i + 1) + ". " + clip(line.label, 24))
                            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                    Component.literal(String.valueOf(line.quantity))
                            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)),
                    Component.literal(String.valueOf(line.unitPrice))
                            .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                    Component.literal(String.valueOf(line.total()))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false))),
                    sp -> openLineDetail(sp, id, index, cur, back)));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal("Aucune ligne chiffree")
                            .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                    Component.empty(), Component.empty(), Component.empty()), null));
        }

        Consumer<ServerPlayer> prev = pages > 1
                ? sp -> openAllLines(sp, id, (cur - 1 + pages) % pages, back) : null;
        Consumer<ServerPlayer> next = pages > 1
                ? sp -> openAllLines(sp, id, (cur + 1) % pages, back) : null;
        OwoMenuServer.openTable(player, title, stats, List.of(), columns, rows, List.of(),
                prev, next, sp -> openAllLines(sp, id, cur, back), sp -> openQuote(sp, id, back));
    }

    /**
     * Reglement en un clic : le solde restant part d'un coup, pieces en main d'abord puis compte en
     * banque. Si le compte n'y suffit pas, on dit exactement ce qui manque plutot que d'echouer sec.
     */
    private static void payAll(ServerPlayer client, String id, Consumer<ServerPlayer> back) {
        QuoteData.Quote quote = QuoteData.get(client.server).quote(id);
        if (quote == null || quote.status != QuoteData.Status.ACCEPTE || quote.remaining() <= 0) {
            openQuote(client, id, back);
            return;
        }
        long due = quote.remaining();
        long available = EconomyManager.countCoins(client)
                + EconomyManager.getBalance(client.server, client.getUUID());
        if (available < due) {
            client.sendSystemMessage(Messages.warn("Il vous manque " + (due - available)
                    + " Utopieces pour solder ce devis. Vous pouvez verser un acompte de "
                    + available + " en attendant."));
            openQuote(client, id, back);
            return;
        }
        QuoteManager.PayResult result = QuoteManager.pay(client, quote, due);
        if (result != QuoteManager.PayResult.OK) {
            client.sendSystemMessage(Messages.warn(QuoteManager.reason(result)));
        } else {
            client.sendSystemMessage(Messages.success("Devis " + id + " solde : " + due
                    + " Utopieces verses a " + QuoteData.get(client.server).nameOf(quote.issuer) + "."));
        }
        openQuote(client, id, back);
    }

    /** L'emetteur declare avoir ete paye de la main a la main : rien ne circule, la trace est posee. */
    private static void promptCash(ServerPlayer issuer, String id, Consumer<ServerPlayer> back) {
        QuoteData data = QuoteData.get(issuer.server);
        QuoteData.Quote quote = data.quote(id);
        if (quote == null || quote.remaining() <= 0) {
            openQuote(issuer, id, back);
            return;
        }
        long remaining = quote.remaining();
        Menus.promptAmount(issuer, Icons.label("Regle en liquide - " + quote.id, ChatFormatting.AQUA),
                List.of(Icons.lore("Somme recue de la main a la main : pieces comptees, troc...",
                                ChatFormatting.GRAY),
                        Icons.lore("Reste du : " + remaining + " Utopieces", ChatFormatting.GRAY),
                        Icons.lore("Rien ne circule en banque : seule la trace est posee",
                                ChatFormatting.DARK_GRAY),
                        Icons.lore(data.nameOf(quote.client) + " en sera informe", ChatFormatting.DARK_GRAY)),
                Icons.label("Declarer regle", ChatFormatting.GREEN), remaining, 1, remaining,
                amount -> {
                    QuoteManager.PayResult result = QuoteManager.settleCash(issuer,
                            QuoteData.get(issuer.server).quote(id), amount);
                    if (result != QuoteManager.PayResult.OK) {
                        issuer.sendSystemMessage(Messages.warn(QuoteManager.reason(result)));
                    } else {
                        QuoteData.Quote fresh = QuoteData.get(issuer.server).quote(id);
                        issuer.sendSystemMessage(Messages.success(
                                fresh != null && fresh.remaining() <= 0
                                        ? "Devis " + id + " marque comme regle en liquide."
                                        : "Acompte en liquide de " + amount + " Utopieces enregistre."));
                    }
                    openQuote(issuer, id, back);
                });
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
        if (!QuoteManager.canAdminister(admin)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration et au maire."));
            openHome(admin);
            return;
        }
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

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);

        Component title = Icons.screenTitle("Devis des joueurs"
                + (pages > 1 ? " (" + (cur + 1) + "/" + pages + ")" : ""), ChatFormatting.GOLD);
        List<Component> stats = List.of(
                stat(all.size() + " devis - ", open + " en cours, " + settled + " solde(s)",
                        ChatFormatting.AQUA),
                stat("Volume regle : ", volume + " Utopieces", ChatFormatting.GREEN),
                stat("Taxe de la mairie : ", data.taxPercent() + " % du reglement", ChatFormatting.GRAY));

        List<OwoMenuServer.Column> columns = List.of(
                new OwoMenuServer.Column(head("DEVIS"), 40, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("OBJET"), 68, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("ETAT"), 48, OwoMenuServer.Column.LEFT),
                new OwoMenuServer.Column(head("MONTANT"), 50, OwoMenuServer.Column.RIGHT),
                new OwoMenuServer.Column(head("PARTIES"), 100, OwoMenuServer.Column.LEFT));

        List<OwoMenuServer.TableRow> rows = new ArrayList<>();
        for (QuoteData.Quote quote : all.subList(Math.min(from, all.size()), to)) {
            String id = quote.id;
            ChatFormatting stateColor = QuoteManager.color(quote.status);
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal(quote.id)
                            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                    Component.literal(clip(quote.title, 11))
                            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)),
                    Component.literal(quote.status.label())
                            .withStyle(s -> s.withColor(stateColor).withItalic(false)),
                    Component.literal(String.valueOf(quote.total()))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)),
                    Component.literal(clip(data.nameOf(quote.issuer) + " -> " + data.nameOf(quote.client), 16))
                            .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))),
                    sp -> openQuote(sp, id, s2 -> openAdmin(s2, 0))));
        }
        if (rows.isEmpty()) {
            rows.add(new OwoMenuServer.TableRow(List.of(
                    Component.literal("Aucun devis")
                            .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                    Component.empty(), Component.empty(), Component.empty(), Component.empty()), null));
        }

        List<OwoMenuServer.PanelAction> footer = List.of(
                new OwoMenuServer.PanelAction(Icons.label("Par joueur", ChatFormatting.AQUA),
                        sp -> openAdminPlayers(sp, 0)),
                new OwoMenuServer.PanelAction(Icons.label("Reglages", ChatFormatting.YELLOW),
                        QuoteMenus::openAdminSettings));

        Consumer<ServerPlayer> onPrev = pages > 1 ? sp -> openAdmin(sp, (cur - 1 + pages) % pages) : null;
        Consumer<ServerPlayer> onNext = pages > 1 ? sp -> openAdmin(sp, (cur + 1) % pages) : null;
        OwoMenuServer.openTable(admin, title, stats, List.of(), columns, rows, footer,
                onPrev, onNext, sp -> openAdmin(sp, cur),
                admin.hasPermissions(2) ? com.utopia.menu.AdminMenu::open : QuoteMenus::openHome);
    }

    /** Joueurs apparaissant dans au moins un devis. */
    public static void openAdminPlayers(ServerPlayer admin, int page) {
        if (!QuoteManager.canAdminister(admin)) {
            openHome(admin);
            return;
        }
        QuoteData data = QuoteData.get(admin.server);
        Component title = Icons.title("Devis par joueur", ChatFormatting.AQUA);

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
        if (!QuoteManager.canAdminister(admin)) {
            openHome(admin);
            return;
        }
        QuoteData data = QuoteData.get(admin.server);
        String name = data.nameOf(target);
        List<QuoteData.Quote> issued = data.issuedBy(target);
        List<QuoteData.Quote> received = data.receivedBy(target);

        long billed = 0;
        long billedCash = 0;
        long spent = 0;
        long spentCash = 0;
        for (QuoteData.Quote q : issued) {
            billed += q.paid;
            billedCash += q.paidCash;
        }
        for (QuoteData.Quote q : received) {
            spent += q.paid;
            spentCash += q.paidCash;
        }

        Component title = Icons.title("Devis de " + name, ChatFormatting.AQUA);
        List<Component> stats = List.of(
                stat("Emis : ", issued.size() + " - encaisse " + billed + " Utopieces"
                        + (billedCash > 0 ? " (dont " + billedCash + " en liquide)" : ""),
                        ChatFormatting.GREEN),
                stat("Recus : ", received.size() + " - regle " + spent + " Utopieces"
                        + (spentCash > 0 ? " (dont " + spentCash + " en liquide)" : ""),
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
        openAdminSettings(admin, QuoteMenus::openAdmin);
    }

    /** Variante : {@code back} permet d'y venir depuis le menu de la mairie comme depuis /devis admin. */
    public static void openAdminSettings(ServerPlayer admin, Consumer<ServerPlayer> back) {
        if (!QuoteManager.canAdminister(admin)) {
            admin.sendSystemMessage(Messages.warn("Reserve a l'administration et au maire."));
            openHome(admin);
            return;
        }
        QuoteData data = QuoteData.get(admin.server);
        Component title = Icons.title("Reglages des devis", ChatFormatting.GOLD);

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
                                    openAdminSettings(sp, back);
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
                                    openAdminSettings(sp, back);
                                })),
                new OwoMenuServer.PanelRow(
                        Icons.label("Reglement", ChatFormatting.GRAY),
                        Icons.label("accepter engage, payer reste un geste separe",
                                ChatFormatting.DARK_GRAY), null, null),
                new OwoMenuServer.PanelRow(
                        Icons.label("Reglement en liquide", ChatFormatting.GRAY),
                        Icons.label("declare par l'emetteur, hors banque : aucune taxe prelevee",
                                ChatFormatting.DARK_GRAY), null, null),
                new OwoMenuServer.PanelRow(
                        Icons.label("Destination de la taxe", ChatFormatting.GRAY),
                        Icons.label("caisse de la mairie - solde actuel : "
                                        + com.utopia.economy.EconomyManager.getBalance(admin.server,
                                                com.utopia.data.MarketData.MAIRIE_UUID) + " Utopieces",
                                ChatFormatting.GOLD), null, null));

        OwoMenuServer.openPanel(admin, title, rows, List.of(),
                sp -> openAdminSettings(sp, back), back);
    }

    // ==============================================================================================
    //  Utilitaires
    // ==============================================================================================

    private static OwoMenuServer.PanelRow row(String label, String value, ChatFormatting color) {
        return new OwoMenuServer.PanelRow(Icons.label(label, ChatFormatting.GRAY),
                Icons.label(value, color), null, null);
    }

    /** En-tete de colonne : gris-bleu, en capitales, pour se distinguer des donnees. */
    /**
     * Fiche d'une ligne, en lecture seule : le tableau coupe la designation pour tenir en colonne,
     * cet ecran la redonne en entier. Le panneau replie le texte au lieu de le tronquer.
     */
    private static void openLineDetail(ServerPlayer player, String id, int index, int page,
                                       Consumer<ServerPlayer> back) {
        QuoteData.Quote quote = QuoteData.get(player.server).quote(id);
        if (quote == null || index < 0 || index >= quote.lines.size()) {
            openAllLines(player, id, page, back);
            return;
        }
        QuoteData.Line line = quote.lines.get(index);
        List<OwoMenuServer.PanelRow> rows = List.of(
                row("Designation", line.label, ChatFormatting.WHITE),
                row("Quantite", String.valueOf(line.quantity), ChatFormatting.AQUA),
                row("Prix unitaire", line.unitPrice + " Utopieces", ChatFormatting.GRAY),
                row("Total de la ligne", line.total() + " Utopieces", ChatFormatting.GOLD));
        OwoMenuServer.openPanel(player,
                Icons.title("Ligne " + (index + 1) + " - " + quote.id, ChatFormatting.GOLD),
                rows, List.of(), sp -> openLineDetail(sp, id, index, page, back),
                sp -> openAllLines(sp, id, page, back));
    }

    private static Component head(String text) {
        return Component.literal(text)
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true).withItalic(false));
    }

    /**
     * Une cellule de tableau ne s'arrete pas d'elle-meme : un objet un peu long y reviendrait sur
     * trois lignes et deformerait toute la rangee. On coupe court, la fiche du devis dit le reste.
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 2)) + "...";
    }

    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }
}
