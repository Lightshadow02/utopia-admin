package com.utopia.quote;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.utopia.data.MarketData;
import com.utopia.data.QuoteData;
import com.utopia.economy.EconomyManager;
import com.utopia.job.JobManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Logique des devis entre joueurs : redaction, envoi, reponse du destinataire et reglement.
 *
 * <p>Un devis engage mais ne preleve rien : l'acceptation vaut accord sur le montant, le paiement
 * reste un geste separe et explicite du client, en une ou plusieurs fois. C'est ce qui permet de
 * verser un acompte puis le solde, comme sur un vrai devis.
 */
public final class QuoteManager {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private QuoteManager() {
    }

    public static String stamp(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), JobManager.ZONE).format(STAMP);
    }

    /** Couleur d'un etat, pour que l'historique se lise d'un coup d'oeil. */
    public static ChatFormatting color(QuoteData.Status status) {
        return switch (status) {
            case BROUILLON -> ChatFormatting.GRAY;
            case ENVOYE -> ChatFormatting.YELLOW;
            case ACCEPTE -> ChatFormatting.AQUA;
            case SOLDE -> ChatFormatting.GREEN;
            case REFUSE, ANNULE -> ChatFormatting.RED;
            case EXPIRE -> ChatFormatting.DARK_GRAY;
        };
    }

    // ------------------------------------------------------------------ Redaction

    /** Ouvre un brouillon, ou {@code null} si le joueur a deja atteint son plafond. */
    public static QuoteData.Quote create(ServerPlayer issuer) {
        QuoteData data = QuoteData.get(issuer.server);
        data.rememberName(issuer.getUUID(), issuer.getGameProfile().getName());
        QuoteData.Quote quote = data.create(issuer.getUUID());
        if (quote == null) {
            return null;
        }
        quote.title = "Devis de " + issuer.getGameProfile().getName();
        return quote;
    }

    // ------------------------------------------------------------------ Envoi

    public enum SendResult { OK, NO_LINES, NO_TOTAL, NO_CLIENT, SELF, ALREADY_SENT }

    public static String reason(SendResult result) {
        return switch (result) {
            case NO_LINES -> "Ajoutez au moins une ligne avant d'envoyer ce devis.";
            case NO_TOTAL -> "Le total du devis est nul : fixez un prix.";
            case NO_CLIENT -> "Choisissez le joueur a qui adresser ce devis.";
            case SELF -> "Vous ne pouvez pas vous adresser un devis a vous-meme.";
            case ALREADY_SENT -> "Ce devis a deja ete envoye.";
            default -> "";
        };
    }

    /** Envoie le devis a son destinataire : il devient une piece consultable par les deux parties. */
    public static SendResult send(ServerPlayer issuer, QuoteData.Quote quote) {
        if (quote.status != QuoteData.Status.BROUILLON) {
            return SendResult.ALREADY_SENT;
        }
        if (quote.client == null) {
            return SendResult.NO_CLIENT;
        }
        if (quote.client.equals(quote.issuer)) {
            return SendResult.SELF;
        }
        if (quote.lines.isEmpty()) {
            return SendResult.NO_LINES;
        }
        if (quote.total() <= 0) {
            return SendResult.NO_TOTAL;
        }
        QuoteData data = QuoteData.get(issuer.server);
        quote.status = QuoteData.Status.ENVOYE;
        quote.sentAt = System.currentTimeMillis();
        data.setDirty();

        String issuerName = data.nameOf(quote.issuer);
        tell(issuer.server, data, quote.client, Component.literal("[Devis] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal(issuerName + " vous adresse le devis " + quote.id + " \""
                                + quote.title + "\" pour " + quote.total() + " Utopieces.")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)))
                .append(Component.literal(" Ouvrez /devis pour y repondre.")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false))));
        return SendResult.OK;
    }

    // ------------------------------------------------------------------ Reponse du destinataire

    /** Le destinataire accepte : il s'engage sur le montant, rien n'est preleve a cet instant. */
    public static boolean accept(ServerPlayer client, QuoteData.Quote quote) {
        if (!quote.awaitingAnswer() || !client.getUUID().equals(quote.client)) {
            return false;
        }
        QuoteData data = QuoteData.get(client.server);
        quote.status = QuoteData.Status.ACCEPTE;
        quote.decidedAt = System.currentTimeMillis();
        data.setDirty();
        notifyIssuer(client.server, data, quote, data.nameOf(quote.client) + " a accepte votre devis "
                + quote.id + " (" + quote.total() + " Utopieces).", ChatFormatting.GREEN);
        return true;
    }

    public static boolean refuse(ServerPlayer client, QuoteData.Quote quote) {
        if (!quote.awaitingAnswer() || !client.getUUID().equals(quote.client)) {
            return false;
        }
        QuoteData data = QuoteData.get(client.server);
        quote.status = QuoteData.Status.REFUSE;
        quote.decidedAt = System.currentTimeMillis();
        data.setDirty();
        notifyIssuer(client.server, data, quote, data.nameOf(quote.client) + " a refuse votre devis "
                + quote.id + ".", ChatFormatting.RED);
        return true;
    }

    /**
     * L'emetteur retire un devis deja parti, tant que le client ne s'est pas engage. Un brouillon,
     * lui, ne s'annule pas : il se supprime. L'annuler en ferait une piece close que le destinataire
     * verrait apparaitre alors qu'elle ne lui a jamais ete adressee.
     */
    public static boolean cancel(ServerPlayer issuer, QuoteData.Quote quote) {
        if (quote == null || !issuer.getUUID().equals(quote.issuer)
                || quote.status != QuoteData.Status.ENVOYE) {
            return false;
        }
        QuoteData data = QuoteData.get(issuer.server);
        quote.status = QuoteData.Status.ANNULE;
        quote.decidedAt = System.currentTimeMillis();
        data.setDirty();
        if (quote.client != null) {
            tell(issuer.server, data, quote.client, message(
                    data.nameOf(quote.issuer) + " a annule le devis " + quote.id + ".", ChatFormatting.RED));
        }
        return true;
    }

    // ------------------------------------------------------------------ Reglement

    public enum PayResult { OK, NOT_ACCEPTED, NOT_CLIENT, NOT_ISSUER, BAD_AMOUNT, NOT_ENOUGH }

    public static String reason(PayResult result) {
        return switch (result) {
            case NOT_ACCEPTED -> "Ce devis n'est pas en attente de reglement.";
            case NOT_CLIENT -> "Seul le destinataire du devis peut le regler.";
            case NOT_ISSUER -> "Seul l'emetteur peut declarer un reglement en liquide.";
            case BAD_AMOUNT -> "Montant invalide.";
            case NOT_ENOUGH -> "Vous n'avez pas cette somme, pieces et banque reunies.";
            default -> "";
        };
    }

    /**
     * Reglement, total ou partiel. Le client paie en pieces puis sur son solde ; l'emetteur est
     * credite du montant, moins la part eventuellement prelevee par la mairie.
     */
    public static PayResult pay(ServerPlayer client, QuoteData.Quote quote, long amount) {
        if (quote.status != QuoteData.Status.ACCEPTE) {
            return PayResult.NOT_ACCEPTED;
        }
        if (!client.getUUID().equals(quote.client)) {
            return PayResult.NOT_CLIENT;
        }
        long remaining = quote.remaining();
        if (amount <= 0 || amount > remaining) {
            return PayResult.BAD_AMOUNT;
        }
        if (!EconomyManager.payCombined(client, amount)) {
            return PayResult.NOT_ENOUGH;
        }
        MinecraftServer server = client.server;
        QuoteData data = QuoteData.get(server);
        long tax = amount * data.taxPercent() / 100;
        long net = amount - tax;
        EconomyManager.add(server, quote.issuer, net);
        if (tax > 0) {
            EconomyManager.add(server, MarketData.MAIRIE_UUID, tax);
        }
        quote.paid += amount;
        boolean settled = quote.remaining() <= 0;
        if (settled) {
            quote.status = QuoteData.Status.SOLDE;
            quote.decidedAt = System.currentTimeMillis();
        }
        data.setDirty();

        String clientName = data.nameOf(quote.client);
        notifyIssuer(server, data, quote, settled
                        ? clientName + " a solde le devis " + quote.id + " : +" + net + " Utopieces."
                        : clientName + " a verse un acompte sur le devis " + quote.id + " : +" + net
                                + " Utopieces (reste " + quote.remaining() + ").",
                ChatFormatting.GREEN);
        return PayResult.OK;
    }

    /**
     * L'emetteur declare avoir ete paye de la main a la main : pieces comptees au comptoir, troc,
     * arrangement hors du jeu. Rien ne circule dans l'economie, seule la trace est posee — la taxe de
     * la mairie ne s'applique donc pas, ce qu'annoncent les reglages.
     *
     * <p>Un devis simplement envoye peut etre solde ainsi : un client qui paie n'a pas toujours pense
     * a cliquer sur "Accepter". Le destinataire est prevenu dans tous les cas, pour qu'une declaration
     * erronee ne passe jamais inapercue.
     */
    public static PayResult settleCash(ServerPlayer issuer, QuoteData.Quote quote, long amount) {
        if (quote == null || !issuer.getUUID().equals(quote.issuer)) {
            return PayResult.NOT_ISSUER;
        }
        if (quote.status != QuoteData.Status.ACCEPTE && quote.status != QuoteData.Status.ENVOYE) {
            return PayResult.NOT_ACCEPTED;
        }
        long remaining = quote.remaining();
        if (amount <= 0 || amount > remaining) {
            return PayResult.BAD_AMOUNT;
        }
        QuoteData data = QuoteData.get(issuer.server);
        quote.paid += amount;
        quote.paidCash += amount;
        boolean settled = quote.remaining() <= 0;
        if (settled) {
            quote.status = QuoteData.Status.SOLDE;
            quote.decidedAt = System.currentTimeMillis();
        } else if (quote.status == QuoteData.Status.ENVOYE) {
            // Un acompte encaisse vaut engagement : le devis ne peut plus expirer sans reponse.
            quote.status = QuoteData.Status.ACCEPTE;
            quote.decidedAt = System.currentTimeMillis();
        }
        data.setDirty();

        String issuerName = data.nameOf(quote.issuer);
        if (quote.client != null) {
            tell(issuer.server, data, quote.client, message(issuerName + (settled
                            ? " a marque le devis " + quote.id + " comme regle en liquide ("
                                    + amount + " Utopieces). Signalez-le a un administrateur si c'est une erreur."
                            : " a enregistre un acompte en liquide de " + amount + " Utopieces sur le devis "
                                    + quote.id + " (reste " + quote.remaining() + ")."),
                    ChatFormatting.AQUA));
        }
        return PayResult.OK;
    }

    // ------------------------------------------------------------------ Expiration

    /**
     * A appeler periodiquement : un devis envoye dont la validite est passee tombe de lui-meme. Les
     * devis deja acceptes ne expirent pas, l'engagement etant pris.
     */
    public static void tick(MinecraftServer server) {
        QuoteData data = QuoteData.get(server);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (QuoteData.Quote quote : data.all()) {
            if (quote.status == QuoteData.Status.ENVOYE && quote.expired(now)) {
                quote.status = QuoteData.Status.EXPIRE;
                quote.decidedAt = now;
                changed = true;
                notifyIssuer(server, data, quote, "Votre devis " + quote.id + " a expire sans reponse.",
                        ChatFormatting.DARK_GRAY);
                if (quote.client != null) {
                    tell(server, data, quote.client, message("Le devis " + quote.id + " de "
                            + data.nameOf(quote.issuer) + " a expire.", ChatFormatting.DARK_GRAY));
                }
            }
        }
        if (changed) {
            data.setDirty();
        }
        data.prune(); // brouillons abandonnes, puis archives en trop
    }

    // ------------------------------------------------------------------ Notifications

    private static Component message(String text, ChatFormatting color) {
        return Component.literal("[Devis] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal(text).withStyle(s -> s.withColor(color).withBold(false)));
    }

    private static void notifyIssuer(MinecraftServer server, QuoteData data, QuoteData.Quote quote,
                                     String text, ChatFormatting color) {
        tell(server, data, quote.issuer, message(text, color));
    }

    /** Previent un joueur tout de suite s'il est connecte, a sa prochaine venue sinon. */
    private static void tell(MinecraftServer server, QuoteData data, UUID target, Component message) {
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(message);
        } else {
            data.addPending(target, message.getString());
        }
    }

    /** A la connexion : memorise le pseudo et delivre les messages laisses pendant l'absence. */
    public static void onLogin(ServerPlayer player) {
        QuoteData data = QuoteData.get(player.server);
        data.rememberName(player.getUUID(), player.getGameProfile().getName());
        for (String text : data.takePending(player.getUUID())) {
            player.sendSystemMessage(Component.literal(text)
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW)));
        }
        int waiting = data.awaitingCount(player.getUUID());
        if (waiting > 0) {
            player.sendSystemMessage(message(waiting + " devis attend(ent) votre reponse : /devis.",
                    ChatFormatting.YELLOW));
        }
    }
}
