package com.utopia.bet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.BetData;
import com.utopia.economy.EconomyManager;
import com.utopia.job.JobManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.nbt.CompoundTag;

/**
 * Moteur des paris : ouverture, mises, fermeture, redistribution et remboursements.
 *
 * <p>Le systeme travaille en <b>circuit ferme</b>. Chaque Utopiece versee a un gagnant provient d'une
 * mise reellement retiree a un joueur : rien n'est cree pour completer un gain, rien n'est detruit
 * par un arrondi, aucune commission n'est prelevee. Avant tout paiement, les totaux sont recalcules
 * depuis les mises elles-memes ; a la moindre divergence le pari bascule en erreur et l'argent reste
 * ou il est, en attendant une decision humaine.
 */
public final class BetManager {

    private static final String HOLO_TAG = "utopiaBetHolo";
    private static final double LINE_GAP = 0.28;
    private static final double HOLO_BASE = 2.35;
    private static final double SPAWN_EPSILON = 0.05;

    /** Mise minimale. */
    public static final long MIN_WAGER = 1;

    /**
     * Jetons de confirmation : un ecran de confirmation en vaut un, et il est consomme au clic. Un
     * double clic ou un paquet reemis ne peut donc pas retirer deux fois la meme mise.
     */
    private static final Map<UUID, Long> TOKENS = new HashMap<>();
    private static long tokenSeq;

    private BetManager() {
    }

    public static long newToken(ServerPlayer player) {
        long token = ++tokenSeq;
        TOKENS.put(player.getUUID(), token);
        return token;
    }

    private static boolean consumeToken(ServerPlayer player, long token) {
        Long held = TOKENS.get(player.getUUID());
        if (held == null || held != token) {
            return false;
        }
        TOKENS.remove(player.getUUID());
        return true;
    }

    public static void forget(UUID player) {
        TOKENS.remove(player);
    }

    // ------------------------------------------------------------------ Affichage

    public static String stamp(long millis) {
        return millis <= 0 ? "-" : JobManager.stamp(millis);
    }

    /** Duree restante au format mm:ss (ou h:mm:ss au-dela de l'heure). */
    public static String countdown(long millis) {
        long total = Math.max(0, millis) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** Une cote a deux decimales, a la francaise. */
    public static String odds(double value) {
        return value <= 0 ? "-" : String.format(Locale.FRANCE, "%.2f", value);
    }

    // ------------------------------------------------------------------ Cycle de vie

    /** Publie le pari : les mises s'ouvrent et le Bookmaker se pose a l'endroit indique. */
    public static void publish(ServerPlayer creator, BetData.Bet bet, int durationMinutes) {
        BetData data = BetData.get(creator.server);
        bet.durationMinutes = Math.max(1, durationMinutes);
        bet.createdAt = System.currentTimeMillis();
        bet.closesAt = bet.createdAt + bet.durationMinutes * 60_000L;
        bet.state = BetData.State.OUVERT;
        place(creator, bet);
        bet.log(bet.creatorName + " a ouvert le pari (" + bet.durationMinutes + " min de mises)");
        data.setDirty();
        syncWorld(creator.server);
    }

    /** Enregistre la position et l'orientation de repos du Bookmaker depuis celles du joueur. */
    public static void place(ServerPlayer player, BetData.Bet bet) {
        bet.dim = player.level().dimension().location().toString();
        bet.x = player.getX();
        bet.y = player.getY();
        bet.z = player.getZ();
        bet.restYaw = player.getYRot();
        BetData.get(player.server).setDirty();
    }

    public enum WagerResult {
        OK, CLOSED, SUSPENDED, NO_OPTION, LOCKED_OPTION, TOO_SMALL, NOT_ENOUGH, DOUBLE_CLICK
    }

    public static String reason(WagerResult result) {
        return switch (result) {
            case CLOSED -> "Les mises sont fermees sur ce pari.";
            case SUSPENDED -> "Ce pari est suspendu : aucune mise n'est acceptee.";
            case NO_OPTION -> "Cette proposition n'existe plus.";
            case LOCKED_OPTION -> "Vous avez deja mise sur une autre proposition : le choix est definitif.";
            case TOO_SMALL -> "La mise minimale est de " + MIN_WAGER + " Utopiece.";
            case NOT_ENOUGH -> "Vous n'avez pas cette somme, pieces et banque reunies.";
            case DOUBLE_CLICK -> "Mise deja enregistree.";
            default -> "";
        };
    }

    /**
     * Enregistre une mise. Les Utopieces quittent immediatement le joueur pour la cagnotte ; le
     * jeton de confirmation garantit qu'un double clic ne les retire pas deux fois.
     */
    public static WagerResult wager(ServerPlayer player, BetData.Bet bet, String optionId,
                                    long amount, long token) {
        if (bet.state == BetData.State.SUSPENDU) {
            return WagerResult.SUSPENDED;
        }
        if (!bet.acceptsWagers()) {
            return WagerResult.CLOSED;
        }
        BetData.Option option = bet.option(optionId);
        if (option == null) {
            return WagerResult.NO_OPTION;
        }
        String already = bet.choice.get(player.getUUID());
        if (already != null && !already.equals(optionId)) {
            return WagerResult.LOCKED_OPTION;
        }
        if (amount < MIN_WAGER) {
            return WagerResult.TOO_SMALL;
        }
        if (!consumeToken(player, token)) {
            return WagerResult.DOUBLE_CLICK;
        }
        if (!EconomyManager.payCombined(player, amount)) {
            return WagerResult.NOT_ENOUGH;
        }

        BetData data = BetData.get(player.server);
        long seq = data.nextWagerSeq();
        long now = System.currentTimeMillis();
        bet.wagers.add(new BetData.Wager(seq, player.getUUID(), player.getGameProfile().getName(),
                optionId, amount, now));
        bet.choice.put(player.getUUID(), optionId);
        bet.staked.merge(player.getUUID(), amount, Long::sum);
        option.pool += amount;
        bet.collected += amount;
        bet.lastBetAt = now;
        bet.log(player.getGameProfile().getName() + " mise " + amount + " sur \"" + option.label + "\"");
        data.setDirty();
        syncWorld(player.server);
        return WagerResult.OK;
    }

    /** Ferme les mises : les montants et les cotes sont figes. Renvoie false si rien n'a bouge. */
    public static boolean close(MinecraftServer server, BetData.Bet bet, String by) {
        if (bet.state != BetData.State.OUVERT) {
            return false;
        }
        bet.state = BetData.State.FERME;
        bet.closedAt = System.currentTimeMillis();
        bet.closesAt = Math.min(bet.closesAt, bet.closedAt);
        bet.log("Mises fermees" + (by == null ? " (fin du chronometre)" : " par " + by));
        BetData.get(server).setDirty();

        // Un pari dont toutes les Utopieces sont sur une seule proposition n'oppose rien : on rend tout.
        if (bet.collected > 0 && bet.fundedOptions() < 2) {
            return cancel(server, bet,
                    "Une seule proposition a recu des mises : le pari ne peut pas etre juge.");
        }
        if (bet.collected <= 0) {
            return cancel(server, bet, "Aucune mise n'a ete enregistree.");
        }
        notifyAll(server, bet, Component.literal("Les mises sont fermees sur \"" + bet.name
                + "\". Le resultat sera annonce sous peu.").withStyle(ChatFormatting.YELLOW));
        syncWorld(server);
        return true;
    }

    // ------------------------------------------------------------------ Verification comptable

    /**
     * Recalcule tous les totaux depuis les mises elles-memes et renvoie la premiere divergence
     * trouvee, ou null si les comptes sont justes. C'est le garde-fou qui precede tout paiement.
     */
    public static String verify(BetData.Bet bet) {
        long fromWagers = 0;
        Map<String, Long> pools = new HashMap<>();
        Map<UUID, Long> perPlayer = new HashMap<>();
        for (BetData.Wager w : bet.wagers) {
            if (w.amount() <= 0) {
                return "Une mise enregistree porte un montant nul ou negatif.";
            }
            fromWagers += w.amount();
            pools.merge(w.optionId(), w.amount(), Long::sum);
            perPlayer.merge(w.player(), w.amount(), Long::sum);
        }
        if (fromWagers != bet.collected) {
            return "Somme des mises (" + fromWagers + ") differente du total retire aux joueurs ("
                    + bet.collected + ").";
        }
        long fromOptions = 0;
        for (BetData.Option o : bet.options) {
            long expected = pools.getOrDefault(o.id, 0L);
            if (o.pool != expected) {
                return "Cagnotte de \"" + o.label + "\" (" + o.pool + ") differente des mises enregistrees ("
                        + expected + ").";
            }
            fromOptions += o.pool;
        }
        if (fromOptions != bet.collected) {
            return "Somme des cagnottes (" + fromOptions + ") differente du total collecte ("
                    + bet.collected + ").";
        }
        for (Map.Entry<UUID, Long> e : perPlayer.entrySet()) {
            if (!e.getValue().equals(bet.staked.get(e.getKey()))) {
                return "Le total mise par un joueur ne correspond pas a ses mises enregistrees.";
            }
        }
        if (perPlayer.size() != bet.staked.size() || perPlayer.size() != bet.choice.size()) {
            return "Le nombre de parieurs ne correspond pas aux mises enregistrees.";
        }
        // La repartition suppose qu'un joueur n'a mise que sur la proposition qu'il a choisie : c'est
        // cette regle qui fait que la somme des mises des gagnants egale la cagnotte gagnante.
        for (BetData.Wager w : bet.wagers) {
            String chosen = bet.choice.get(w.player());
            if (chosen == null || !chosen.equals(w.optionId())) {
                return "Une mise porte sur une autre proposition que celle choisie par le joueur.";
            }
        }
        if (bet.settled) {
            return "Les gains de ce pari ont deja ete calcules.";
        }
        return null;
    }

    private static void fail(MinecraftServer server, BetData.Bet bet, String problem) {
        boolean alreadyBlocked = bet.state == BetData.State.ERREUR;
        bet.state = BetData.State.ERREUR;
        BetData.get(server).setDirty();
        if (alreadyBlocked) {
            // Deja signale : inutile de reveiller tous les op a chaque nouvelle tentative, et
            // surtout de noyer le journal des mises que la verification doit pouvoir relire.
            return;
        }
        bet.log("BLOCAGE : " + problem);
        Component alert = Component.literal("[Paris] ")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true))
                .append(Component.literal("Le pari " + bet.id + " (" + bet.name
                                + ") est bloque : " + problem)
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(false)));
        for (ServerPlayer op : server.getPlayerList().getPlayers()) {
            if (op.hasPermissions(2)) {
                op.sendSystemMessage(alert);
            }
        }
        notify(server, bet.creator, Component.literal("Votre pari \"" + bet.name
                        + "\" est bloque en attente d'une verification administrative.")
                .withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ Resolution

    /**
     * Designe la proposition gagnante et repartit la cagnotte. La part de chacun est proportionnelle
     * a ce qu'il a mise ; les Utopieces indivisibles vont aux plus fortes decimales, la premiere mise
     * enregistree l'emportant a egalite. La cagnotte est distribuee en entier, ni plus ni moins.
     */
    public static boolean resolve(MinecraftServer server, BetData.Bet bet, String optionId, String by) {
        if (bet.state != BetData.State.FERME) {
            return false;
        }
        BetData.Option winner = bet.option(optionId);
        if (winner == null) {
            return false;
        }
        String problem = verify(bet);
        if (problem != null) {
            fail(server, bet, problem);
            return false;
        }
        long pot = bet.collected;
        if (winner.pool <= 0) {
            // Personne n'avait vu juste : il n'y a rien a repartir proportionnellement, on rend tout.
            return cancel(server, bet,
                    "La proposition gagnante \"" + winner.label + "\" n'avait recu aucune mise.");
        }

        // Part entiere de chacun, puis distribution des Utopieces restantes aux plus fortes decimales.
        record Share(UUID player, long base, BigInteger remainder, long firstSeq) {
        }
        Map<UUID, Long> firstSeq = new HashMap<>();
        for (BetData.Wager w : bet.wagers) {
            firstSeq.putIfAbsent(w.player(), w.seq());
        }
        BigInteger potBig = BigInteger.valueOf(pot);
        BigInteger poolBig = BigInteger.valueOf(winner.pool);
        List<Share> shares = new ArrayList<>();
        long assigned = 0;
        for (Map.Entry<UUID, String> e : bet.choice.entrySet()) {
            if (!e.getValue().equals(optionId)) {
                continue;
            }
            long stake = bet.stakeOf(e.getKey());
            if (stake <= 0) {
                continue;
            }
            BigInteger[] qr = potBig.multiply(BigInteger.valueOf(stake)).divideAndRemainder(poolBig);
            long base = qr[0].longValueExact();
            assigned += base;
            shares.add(new Share(e.getKey(), base, qr[1], firstSeq.getOrDefault(e.getKey(), Long.MAX_VALUE)));
        }
        if (shares.isEmpty()) {
            return cancel(server, bet, "Aucun gagnant identifiable sur \"" + winner.label + "\".");
        }
        long leftover = pot - assigned;
        if (leftover < 0 || leftover >= shares.size()) {
            fail(server, bet, "Repartition incoherente : reste de " + leftover + " Utopiece(s) pour "
                    + shares.size() + " gagnant(s).");
            return false;
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed()
                .thenComparingLong(Share::firstSeq));

        Map<UUID, Long> payouts = new HashMap<>();
        long total = 0;
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            long amount = share.base() + (i < leftover ? 1 : 0);
            payouts.put(share.player(), amount);
            total += amount;
        }
        if (total != pot) {
            fail(server, bet, "Le total des gains (" + total + ") ne correspond pas a la cagnotte ("
                    + pot + ").");
            return false;
        }

        bet.payout.clear();
        bet.payout.putAll(payouts);
        bet.settled = true;
        bet.winner = optionId;
        bet.state = BetData.State.RESOLU;
        bet.resolvedAt = System.currentTimeMillis();
        bet.log((by == null ? "Resolution" : by + " a designe le vainqueur") + " : \"" + winner.label
                + "\" - cagnotte " + pot + " Utopieces repartie entre " + shares.size() + " gagnant(s)");
        BetData.get(server).setDirty();

        flush(server, bet, true, winner.label);
        // Les perdants meritent de savoir que la partie est finie. Ce message part une seule fois,
        // ici : flush() peut etre rejoue par l'horloge si un versement a ete interrompu.
        for (Map.Entry<UUID, String> e : bet.choice.entrySet()) {
            if (bet.payout.containsKey(e.getKey())) {
                continue;
            }
            BetData.Option chosen = bet.option(e.getValue());
            notify(server, e.getKey(), Component.literal("Le pari est termine. La proposition gagnante "
                            + "etait \"" + winner.label + "\". Votre mise sur \""
                            + (chosen == null ? e.getValue() : chosen.label) + "\" est perdue.")
                    .withStyle(ChatFormatting.GRAY));
        }
        syncWorld(server);
        return true;
    }

    /**
     * Annule le pari et rembourse integralement chaque participant. Renvoie false si l'annulation
     * n'a pas pu aboutir : le pari est alors bloque et rien n'a bouge.
     */
    public static boolean cancel(MinecraftServer server, BetData.Bet bet, String reason) {
        if (bet.state.closed()) {
            return false;
        }
        if (!bet.settled) {
            String problem = verify(bet);
            if (problem != null) {
                fail(server, bet, problem);
                return false;
            }
            bet.payout.clear();
            long total = 0;
            for (Map.Entry<UUID, Long> e : bet.staked.entrySet()) {
                if (e.getValue() > 0) {
                    bet.payout.put(e.getKey(), e.getValue());
                    total += e.getValue();
                }
            }
            if (total != bet.collected) {
                fail(server, bet, "Le total des remboursements (" + total + ") ne correspond pas au "
                        + "total collecte (" + bet.collected + ").");
                return false;
            }
            bet.settled = true;
        }
        bet.state = BetData.State.ANNULE;
        bet.cancelReason = reason;
        bet.resolvedAt = System.currentTimeMillis();
        bet.log("Annule : " + reason);
        BetData.get(server).setDirty();

        flush(server, bet, false, null);
        syncWorld(server);
        return true;
    }

    /**
     * Un pari suspendu ou bloque est entre les mains de l'administration : son createur ne peut plus
     * ni l'annuler ni deplacer son Bookmaker, sans quoi il pourrait mettre fin de lui-meme a la
     * verification qui le vise.
     */
    public static boolean frozenFor(BetData.Bet bet, ServerPlayer player) {
        return (bet.state == BetData.State.SUSPENDU || bet.state == BetData.State.ERREUR)
                && !player.hasPermissions(2);
    }

    /** Annulation demandee depuis un menu : refusee sur un pari gele si le demandeur n'est pas op. */
    public static boolean cancelBy(ServerPlayer player, BetData.Bet bet, String reason) {
        if (bet == null || bet.state.closed() || frozenFor(bet, player)) {
            return false;
        }
        return cancel(player.server, bet, reason);
    }

    /**
     * Verse a chacun ce qui lui revient. Le joueur est marque comme regle <b>avant</b> d'etre credite
     * et dans le meme instant : ni un double clic ni un redemarrage ne peut le payer deux fois.
     */
    private static void flush(MinecraftServer server, BetData.Bet bet, boolean won, String winnerLabel) {
        BetData data = BetData.get(server);
        boolean changed = false;
        for (Map.Entry<UUID, Long> e : bet.payout.entrySet()) {
            UUID player = e.getKey();
            long amount = e.getValue();
            if (amount <= 0 || bet.paidOut.contains(player)) {
                continue;
            }
            bet.paidOut.add(player);
            bet.distributed += amount;
            EconomyManager.add(server, player, amount);
            changed = true;
            long stake = bet.stakeOf(player);
            notify(server, player, won
                    ? Component.literal("Vous avez remporte votre pari sur \"" + winnerLabel
                            + "\" ! Votre mise de " + stake + " Utopieces vous rapporte un retour total de "
                            + amount + " Utopieces, soit un benefice de " + (amount - stake) + " Utopieces.")
                            .withStyle(ChatFormatting.GREEN)
                    : Component.literal("Le pari \"" + bet.name + "\" a ete annule. Vos " + amount
                            + " Utopieces vous ont ete integralement remboursees.")
                            .withStyle(ChatFormatting.AQUA));
        }
        if (changed) {
            bet.log("Verse " + bet.distributed + " Utopieces sur " + bet.collected + " collectees");
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ Administration

    public static boolean suspend(MinecraftServer server, BetData.Bet bet, String by) {
        if (bet.state != BetData.State.OUVERT && bet.state != BetData.State.FERME) {
            return false;
        }
        bet.beforeSuspend = bet.state;
        bet.state = BetData.State.SUSPENDU;
        bet.log(by + " a suspendu le pari pour verification");
        BetData.get(server).setDirty();
        notify(server, bet.creator, Component.literal("Votre pari \"" + bet.name
                + "\" est suspendu le temps d'une verification.").withStyle(ChatFormatting.YELLOW));
        syncWorld(server);
        return true;
    }

    public static boolean resume(MinecraftServer server, BetData.Bet bet, String by) {
        if (bet.state != BetData.State.SUSPENDU) {
            return false;
        }
        BetData.State back = bet.beforeSuspend == null ? BetData.State.FERME : bet.beforeSuspend;
        bet.state = back;
        bet.beforeSuspend = null;
        // Filet : un etat FERME restaure sans horodatage de fermeture serait juge expire aussitot,
        // le delai de resolution se comptant depuis closedAt.
        if (bet.state == BetData.State.FERME && bet.closedAt <= 0) {
            bet.closedAt = System.currentTimeMillis();
        }
        bet.log(by + " a autorise la reprise du pari");
        BetData.get(server).setDirty();
        notify(server, bet.creator, Component.literal("Votre pari \"" + bet.name + "\" peut reprendre.")
                .withStyle(ChatFormatting.GREEN));
        // Un pari dont l'echeance est passee pendant la suspension ne rouvre pas les mises : on le
        // ferme par close(), seul endroit qui horodate la fermeture et applique ses garde-fous.
        if (bet.state == BetData.State.OUVERT && System.currentTimeMillis() >= bet.closesAt) {
            close(server, bet, by);
        }
        syncWorld(server);
        return true;
    }

    /**
     * Sortie administrative d'un pari bloque : rend a chaque joueur ce que le journal des mises
     * permet de justifier, sans jamais depasser ce qui reste immobilise, puis archive le pari. Si le
     * journal reclame plus que ce qui a ete retire, rien n'est verse et le blocage est maintenu :
     * on ne cree pas d'Utopiece pour combler un ecart.
     */
    public static boolean settleBlocked(MinecraftServer server, BetData.Bet bet, String by) {
        if (bet.state != BetData.State.ERREUR) {
            return false;
        }
        long cap = bet.collected - bet.distributed;
        Map<UUID, Long> owed = new java.util.LinkedHashMap<>();
        long total = 0;
        for (BetData.Wager w : bet.wagers) {
            if (w.amount() > 0 && !bet.paidOut.contains(w.player())) {
                owed.merge(w.player(), w.amount(), Long::sum);
                total += w.amount();
            }
        }
        if (total > cap) {
            bet.log(by + " a tente un reglement administratif : le journal reclame " + total
                    + " Utopieces pour " + cap + " immobilisee(s) - refuse");
            BetData.get(server).setDirty();
            return false;
        }
        bet.payout.clear();
        bet.payout.putAll(owed);
        bet.settled = true;
        bet.state = BetData.State.ARCHIVE;
        bet.cancelReason = "Regle par l'administration apres constat d'ecart (" + by + ")";
        bet.resolvedAt = System.currentTimeMillis();
        bet.log(by + " a regle le pari bloque : " + total + " Utopiece(s) rendues sur " + cap
                + " immobilisee(s)");
        BetData.get(server).setDirty();
        flush(server, bet, false, null);
        syncWorld(server);
        return true;
    }

    // ------------------------------------------------------------------ Horloge

    /**
     * A appeler periodiquement : ferme les mises a l'echeance, annule les paris restes sans mise ou
     * sans resultat, et rattrape un versement qui n'aurait pas abouti.
     */
    public static void tick(MinecraftServer server) {
        BetData data = BetData.get(server);
        long now = System.currentTimeMillis();
        for (BetData.Bet bet : data.all()) {
            switch (bet.state) {
                case OUVERT -> {
                    if (bet.collected <= 0 && now - bet.createdAt > BetData.IDLE_MS) {
                        cancel(server, bet, "Aucune mise en 24 heures.");
                    } else if (now >= bet.closesAt) {
                        close(server, bet, null);
                    }
                }
                case FERME -> {
                    if (now - bet.closedAt > BetData.RESOLVE_MS) {
                        cancel(server, bet, "Aucun resultat designe dans les 24 heures.");
                    }
                }
                default -> {
                    // Un versement interrompu se termine tout seul au tick suivant.
                    if (bet.settled && bet.holdsMoney()) {
                        flush(server, bet, bet.state == BetData.State.RESOLU,
                                bet.option(bet.winner) == null ? bet.winner : bet.option(bet.winner).label);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Bookmaker et hologramme

    /** Le Bookmaker peut-il disparaitre ? Jamais tant qu'une Utopiece reste immobilisee. */
    public static boolean canRemoveNpc(BetData.Bet bet) {
        return bet.state.closed() && !bet.holdsMoney();
    }

    /** Recree les Bookmakers manquants, retire ceux dont le pari est solde, rafraichit les hologrammes. */
    public static void syncWorld(MinecraftServer server) {
        BetData data = BetData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, com.utopia.entity.BookmakerNpc> npcs = new HashMap<>();
            Map<String, List<ArmorStand>> holos = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.BookmakerNpc npc) {
                    if (npcs.putIfAbsent(npc.ownerKey(), npc) != null) {
                        npc.discard();
                    }
                } else if (e instanceof ArmorStand stand) {
                    String key = stand.getPersistentData().getString(HOLO_TAG);
                    if (!key.isEmpty()) {
                        holos.computeIfAbsent(key, k -> new ArrayList<>()).add(stand);
                    }
                }
            }
            for (BetData.Bet bet : data.all()) {
                com.utopia.entity.BookmakerNpc npc = npcs.remove(bet.id);
                List<ArmorStand> lines = holos.remove(bet.id);
                ServerLevel target = bet.isPlaced() ? resolveLevel(server, bet.dim) : null;
                boolean wanted = target == level && !canRemoveNpc(bet);
                if (!wanted) {
                    if (npc != null) {
                        npc.discard();
                    }
                    if (lines != null) {
                        lines.forEach(Entity::discard);
                    }
                    continue;
                }
                BlockPos pos = BlockPos.containing(bet.x, bet.y, bet.z);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                if (npc == null || npc.isRemoved()) {
                    npc = new com.utopia.entity.BookmakerNpc(
                            com.utopia.entity.UtopiaEntities.BOOKMAKER_NPC.get(), level);
                    npc.setOwnerKey(bet.id);
                    npc.moveTo(bet.x, bet.y, bet.z, bet.restYaw, 0.0f);
                    npc.setRestYaw(bet.restYaw);
                    npc.applyLook("Bookmaker", "", "", true);
                    level.addFreshEntity(npc);
                } else {
                    npc.setRestYaw(bet.restYaw);
                    npc.applyLook("Bookmaker", "", "", true);
                    if (npc.distanceToSqr(bet.x, bet.y, bet.z) > SPAWN_EPSILON) {
                        npc.moveTo(bet.x, bet.y, bet.z, npc.getYRot(), 0.0f);
                    }
                }
                syncHologram(level, bet, lines);
            }
            npcs.values().forEach(Entity::discard);
            holos.values().forEach(list -> list.forEach(Entity::discard));
        }
    }

    /** Lignes de l'hologramme : le strict necessaire, jamais le detail des parieurs. */
    private static List<Component> holoLines(BetData.Bet bet) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(bet.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        for (BetData.Option o : bet.options) {
            lines.add(Component.literal(o.label + " - " + o.pool + " Utopieces")
                    .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)));
        }
        Component status = switch (bet.state) {
            case OUVERT -> Component.literal("Mises ouvertes - " + countdown(bet.remainingMs()) + " restantes")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false));
            case FERME -> Component.literal("Mises fermees - resultat en attente")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false));
            case SUSPENDU -> Component.literal("Suspendu - verification en cours")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false));
            case ERREUR -> Component.literal("Bloque - verification administrative")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false));
            case RESOLU -> {
                BetData.Option w = bet.option(bet.winner);
                yield Component.literal("Termine - vainqueur : " + (w == null ? "?" : w.label))
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false));
            }
            case ANNULE -> Component.literal("Annule - mises remboursees")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false));
            case ARCHIVE -> Component.literal("Clos par l'administration")
                    .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false));
        };
        lines.add(status);
        return lines;
    }

    private static void syncHologram(ServerLevel level, BetData.Bet bet, List<ArmorStand> existing) {
        List<Component> lines = holoLines(bet);
        double cx = bet.x;
        double cz = bet.z;
        double topY = bet.y + HOLO_BASE + bet.holoDy + (lines.size() - 1) * LINE_GAP;
        if (existing != null && existing.size() == lines.size()) {
            existing.sort(Comparator.comparingInt(s -> s.getPersistentData().getInt("line")));
            for (int i = 0; i < lines.size(); i++) {
                ArmorStand stand = existing.get(i);
                stand.setCustomName(lines.get(i));
                stand.setCustomNameVisible(true);
                stand.teleportTo(cx, topY - i * LINE_GAP, cz);
            }
            return;
        }
        if (existing != null) {
            existing.forEach(Entity::discard);
        }
        for (int i = 0; i < lines.size(); i++) {
            spawnLine(level, bet.id, i, cx, topY - i * LINE_GAP, cz, lines.get(i));
        }
    }

    private static void spawnLine(ServerLevel level, String key, int index,
                                  double x, double y, double z, Component text) {
        ArmorStand stand = new ArmorStand(level, x, y, z);
        CompoundTag tag = stand.saveWithoutId(new CompoundTag());
        tag.putBoolean("Marker", true);
        stand.load(tag);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setNoBasePlate(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.getPersistentData().putString(HOLO_TAG, key);
        stand.getPersistentData().putInt("line", index);
        stand.setPos(x, y, z);
        level.addFreshEntity(stand);
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        if (loc == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    // ------------------------------------------------------------------ Messages

    private static void notify(MinecraftServer server, UUID target, Component body) {
        Component message = Component.literal("[Paris] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(body);
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(message);
        } else {
            BetData.get(server).addPending(target, message.getString());
        }
    }

    private static void notifyAll(MinecraftServer server, BetData.Bet bet, Component body) {
        for (UUID player : bet.choice.keySet()) {
            notify(server, player, body);
        }
    }

    /** A la connexion : delivre les messages laisses pendant l'absence. */
    public static void onLogin(ServerPlayer player) {
        BetData data = BetData.get(player.server);
        for (String text : data.takePending(player.getUUID())) {
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
        }
    }
}
