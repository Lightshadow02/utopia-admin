package com.utopia.savings;

import java.util.Locale;
import java.util.UUID;

import com.utopia.data.JobData;
import com.utopia.data.MarketData;
import com.utopia.data.SavingsData;
import com.utopia.economy.EconomyManager;
import com.utopia.job.JobManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Moteur des livrets d'epargne : versement des interets et mouvements au comptoir.
 *
 * <p>Les interets tombent chaque nuit a <b>minuit, heure reelle de Paris</b>, independamment du temps
 * Minecraft. Le suivi se fait par livret ({@code lastInterestDay}) : une nuit n'est jamais creditee
 * deux fois, quels que soient les redemarrages, et un serveur eteint plusieurs jours rattrape les
 * nuits manquees (l'argent aurait dormi a la banque de toute facon).
 */
public final class SavingsManager {

    /** Nombre maximal de nuits rattrapees d'un coup apres une longue coupure. */
    private static final int MAX_CATCHUP = 30;

    private SavingsManager() {
    }

    // ------------------------------------------------------------------ Affichage

    /** Formate un taux a la francaise : 0,75 %. */
    public static String rate(double value) {
        return String.format(Locale.FRANCE, "%.2f", value) + " %";
    }

    /** Formate une date de journee (epoch day) en jj/MM. */
    public static String day(long epochDay) {
        return java.time.LocalDate.ofEpochDay(epochDay)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    public static long today() {
        return JobManager.today();
    }

    // ------------------------------------------------------------------ Interets

    /**
     * A appeler periodiquement et au demarrage : credite les nuits non encore versees. Rien ne bouge
     * tant que la date n'a pas change ; a minuit, chaque livret recoit ses interets une seule fois.
     */
    public static void tick(MinecraftServer server) {
        SavingsData data = SavingsData.get(server);
        long today = today();
        if (!data.enabled()) {
            // Suspendre, c'est perdre la nuit, pas la reporter : on avance le repere de chaque livret
            // sans rien verser. Sinon la reactivation paierait d'un coup, et composees, toutes les
            // nuits de la suspension, alors que le registre annonce l'inverse.
            boolean moved = false;
            for (SavingsData.Account account : data.accounts()) {
                if (account.lastInterestDay < today) {
                    account.lastInterestDay = today;
                    moved = true;
                }
            }
            if (moved) {
                data.setDirty();
            }
            return;
        }
        long paid = 0;
        boolean any = false;
        for (SavingsData.Account account : data.accounts()) {
            if (account.lastInterestDay >= today) {
                continue;
            }
            // Premiere ouverture ou donnees anciennes : on ne remonte jamais plus loin que la limite.
            long from = Math.max(account.lastInterestDay, today - MAX_CATCHUP);
            long earned = 0;
            for (long d = from + 1; d <= today; d++) {
                earned += creditNight(data, account, d);
            }
            account.lastInterestDay = today;
            any = true;
            paid += earned;
            if (earned > 0) {
                notify(server, data, account, earned);
            }
        }
        if (any) {
            data.setLastRun(today, paid);
            if (paid > 0) {
                data.log("Interets de la nuit : " + paid + " Utopieces verses sur "
                        + data.accounts().size() + " livret(s)");
            }
            data.setDirty();
        }
    }

    /** Credite une nuit sur un livret et renvoie le montant verse. */
    private static long creditNight(SavingsData data, SavingsData.Account account, long day) {
        if (account.balance <= 0) {
            return 0;
        }
        double rate = data.rateFor(account.balance);
        long interest = (long) Math.floor(account.balance * rate / 100.0);
        long ceiling = data.ceiling();
        if (ceiling > 0) {
            // Le plafond ne rabote que les interets : un depot deja au-dessus n'est jamais ampute.
            interest = Math.min(interest, Math.max(0, ceiling - account.balance));
        }
        SavingsData.DayEntry entry = account.entry(day);
        entry.opening = account.balance;
        entry.rate = rate;
        if (interest <= 0) {
            entry.closing = account.balance;
            return 0;
        }
        account.balance += interest;
        account.totalInterest += interest;
        entry.interest += interest;
        entry.closing = account.balance;
        return interest;
    }

    /** Annonce le versement : tout de suite si le joueur est la, a sa prochaine connexion sinon. */
    private static void notify(MinecraftServer server, SavingsData data, SavingsData.Account account,
                              long earned) {
        ServerPlayer online = server.getPlayerList().getPlayer(account.owner);
        if (online != null) {
            online.sendSystemMessage(interestMessage("Votre livret d'epargne a rapporte cette nuit : ",
                    earned, account.balance));
            return;
        }
        account.pendingInterest += earned;
        account.pendingNights++;
    }

    private static Component interestMessage(String intro, long earned, long balance) {
        return Component.literal("[Banque d'Utopia] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal(intro)
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)))
                .append(Component.literal("+" + earned + " Utopieces")
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)))
                .append(Component.literal(" (livret : " + balance + ").")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false)));
    }

    /** A la connexion : memorise le pseudo et annonce les interets tombes pendant l'absence. */
    public static void onLogin(ServerPlayer player) {
        SavingsData data = SavingsData.get(player.server);
        data.rememberName(player.getUUID(), player.getGameProfile().getName());
        SavingsData.Account account = data.account(player.getUUID());
        if (account == null || account.pendingInterest <= 0) {
            return;
        }
        long earned = account.pendingInterest;
        int nights = Math.max(1, account.pendingNights);
        account.pendingInterest = 0;
        account.pendingNights = 0;
        data.setDirty();
        player.sendSystemMessage(interestMessage("Pendant votre absence, votre livret a rapporte en "
                + nights + " nuit(s) : ", earned, account.balance));
    }

    // ------------------------------------------------------------------ Mouvements au comptoir

    public enum MoveResult {
        OK,
        NOT_ALLOWED,        // seul le teneur du registre manipule les livrets
        NO_ACCOUNT,
        BAD_AMOUNT,
        NOT_ENOUGH_COINS,   // le banquier n'a pas les pieces qu'on lui a confiees
        NOT_ENOUGH_SAVINGS, // le livret ne couvre pas le retrait
        NO_SPACE,           // pas de place pour rendre les pieces au banquier
        CEILING             // le depot ferait depasser le plafond du livret
    }

    public static String reason(MoveResult result) {
        return switch (result) {
            case NOT_ALLOWED -> "Seul le banquier tient le registre des livrets.";
            case NO_ACCOUNT -> "Ce joueur n'a pas encore de livret.";
            case BAD_AMOUNT -> "Montant invalide.";
            case NOT_ENOUGH_COINS -> "Vous n'avez pas autant de pieces sur vous.";
            case NOT_ENOUGH_SAVINGS -> "Le livret ne contient pas cette somme.";
            case NO_SPACE -> "Pas assez de place dans votre inventaire pour sortir les pieces.";
            case CEILING -> "Ce depot ferait depasser le plafond autorise sur ce livret.";
            default -> "";
        };
    }

    /**
     * Depot au comptoir : les pieces que le joueur vient de confier au banquier quittent l'inventaire
     * de celui-ci et rejoignent le livret. Rien n'est cree, rien n'est detruit.
     *
     * <p>Une suspension ne ferme jamais le guichet : elle arrete les interets, pas les mouvements.
     * L'argent depose reste celui du joueur, il doit toujours pouvoir entrer et ressortir.
     */
    public static MoveResult deposit(ServerPlayer banker, UUID target, long amount) {
        MinecraftServer server = banker.server;
        SavingsData data = SavingsData.get(server);
        if (!canKeepRegistry(banker)) {
            return MoveResult.NOT_ALLOWED;
        }
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            return MoveResult.BAD_AMOUNT;
        }
        SavingsData.Account account = data.account(target);
        if (account == null) {
            return MoveResult.NO_ACCOUNT;
        }
        if (data.ceiling() > 0 && account.balance + amount > data.ceiling()) {
            return MoveResult.CEILING;
        }
        if (EconomyManager.countCoins(banker) < amount) {
            return MoveResult.NOT_ENOUGH_COINS;
        }
        int taken = EconomyManager.takeCoins(banker, (int) amount);
        if (taken <= 0) {
            return MoveResult.NOT_ENOUGH_COINS;
        }
        credit(data, account, taken);
        data.log(banker.getGameProfile().getName() + " a depose " + taken + " Utopieces sur le livret de "
                + data.nameOf(target));
        tell(server, target, "Depot enregistre sur votre livret : ", taken, account.balance);
        return MoveResult.OK;
    }

    /**
     * Retrait au comptoir : le livret est debite et les pieces sont remises au banquier, qui les rend
     * en main propre.
     */
    public static MoveResult withdraw(ServerPlayer banker, UUID target, long amount) {
        MinecraftServer server = banker.server;
        SavingsData data = SavingsData.get(server);
        if (!canKeepRegistry(banker)) {
            return MoveResult.NOT_ALLOWED;
        }
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            return MoveResult.BAD_AMOUNT;
        }
        SavingsData.Account account = data.account(target);
        if (account == null) {
            return MoveResult.NO_ACCOUNT;
        }
        if (account.balance < amount) {
            return MoveResult.NOT_ENOUGH_SAVINGS;
        }
        if (EconomyManager.freeSpaceForCoins(banker) < amount) {
            return MoveResult.NO_SPACE;
        }
        debit(data, account, amount);
        EconomyManager.giveCoins(banker, (int) amount);
        data.log(banker.getGameProfile().getName() + " a retire " + amount + " Utopieces du livret de "
                + data.nameOf(target));
        tell(server, target, "Retrait effectue sur votre livret : -", amount, account.balance);
        return MoveResult.OK;
    }

    /**
     * Correction d'ecriture reservee aux administrateurs : ajuste un livret sans passer par des pieces
     * physiques (erreur de saisie, evenement, remise a plat).
     */
    public static boolean adjust(ServerPlayer admin, UUID target, long delta) {
        if (!canSetRate(admin)) {
            return false;
        }
        SavingsData data = SavingsData.get(admin.server);
        SavingsData.Account account = data.openAccount(target, today());
        if (delta >= 0) {
            credit(data, account, delta);
        } else {
            debit(data, account, Math.min(account.balance, -delta));
        }
        data.log(admin.getGameProfile().getName() + " a ajuste le livret de " + data.nameOf(target)
                + " de " + (delta >= 0 ? "+" : "") + delta + " Utopieces");
        return true;
    }

    private static void credit(SavingsData data, SavingsData.Account account, long amount) {
        account.balance += amount;
        account.totalDeposits += amount;
        SavingsData.DayEntry entry = account.entry(today());
        entry.deposits += amount;
        entry.closing = account.balance;
        data.setDirty();
    }

    private static void debit(SavingsData data, SavingsData.Account account, long amount) {
        account.balance -= amount;
        account.totalWithdrawals += amount;
        SavingsData.DayEntry entry = account.entry(today());
        entry.withdrawals += amount;
        entry.closing = account.balance;
        data.setDirty();
    }

    /** Previent le titulaire du livret s'il est connecte (sinon rien : le releve garde la trace). */
    private static void tell(MinecraftServer server, UUID target, String intro, long amount, long balance) {
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online == null) {
            return;
        }
        online.sendSystemMessage(Component.literal("[Banque d'Utopia] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal(intro)
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)))
                .append(Component.literal(amount + " Utopieces")
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(" (livret : " + balance + ").")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false))));
    }

    // ------------------------------------------------------------------ Permissions

    /** Tient le registre : op, maire ou banquier designe. */
    public static boolean canKeepRegistry(ServerPlayer player) {
        return player.hasPermissions(2)
                || MarketData.get(player.server).isMaire(player.getUUID())
                || JobData.get(player.server).isBanker(player.getUUID());
    }

    /**
     * Fixe le bareme : administrateurs uniquement. Ni le banquier ni le maire ne touchent au taux,
     * c'est le garde-fou qui empeche l'interieur de la banque de decider de sa propre rentabilite.
     */
    public static boolean canSetRate(ServerPlayer player) {
        return player.hasPermissions(2);
    }
}
