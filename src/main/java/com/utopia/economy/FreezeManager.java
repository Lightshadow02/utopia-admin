package com.utopia.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.FreezeData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le gel global des fonds : pendant un gel, plus une seule Utopiece ne bouge, d'ou qu'elle vienne.
 *
 * <p>Deux verrous se completent. Le premier est <b>dur</b> et vit dans {@link EconomyManager} : il
 * refuse toute ecriture de solde, y compris celles d'un circuit que personne n'aurait pense a
 * couvrir. Le second est <b>poli</b> et vit a l'entree de chaque action de joueur : il refuse avant
 * que quoi que ce soit n'ait bouge, pour qu'un achat bloque ne laisse ni objet livre ni objet
 * consomme. Le verrou dur seul suffirait a proteger les soldes, mais pas les objets.
 *
 * <p>En l'absence d'etat lisible, on bloque. Un gel qu'on ne sait pas lire doit etre suppose actif :
 * laisser passer par defaut, c'est vider la banque le jour ou la sauvegarde ne repond pas.
 */
public final class FreezeManager {

    /** Delai minimal entre deux rappels au meme joueur : un menu clique vite en enverrait dix. */
    private static final long MESSAGE_COOLDOWN_MS = 3000L;

    /**
     * Deux horloges distinctes : un refus de transaction et un versement bloque sont deux
     * evenements differents. Avec une horloge commune, cliquer sur un bouton ferait taire
     * l'annonce du salaire suspendu qui tombe dans la meme seconde.
     */
    private static final Map<UUID, Long> lastRefusal = new HashMap<>();
    private static final Map<UUID, Long> lastSuspended = new HashMap<>();

    /**
     * Vrai quand le verrou dur doit laisser passer sans rien verifier. Sert aux operations que le
     * gel lui-meme doit pouvoir mener, s'il y en avait : aujourd'hui aucune, le drapeau existe pour
     * que le jour ou l'on en ajoute une, elle soit visible ici et nulle part ailleurs.
     */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private FreezeManager() {
    }

    // ------------------------------------------------------------------ Etat

    /**
     * L'economie est-elle gelee ? Repond <b>vrai</b> si l'etat ne peut pas etre lu : mieux vaut une
     * banque injustement fermee qu'une banque ouverte pendant un piratage.
     */
    public static boolean isFrozen(MinecraftServer server) {
        if (server == null) {
            return true;
        }
        try {
            return FreezeData.get(server).frozen();
        } catch (RuntimeException e) {
            com.utopia.UtopiaMod.LOGGER.error(
                    "[Utopia] Etat du gel illisible : les ecritures restent bloquees.", e);
            return true;
        }
    }

    /** Le gel etait-il actif a cet instant ? Question posee par tout rattrapage d'echeance. */
    public static boolean wasFrozenAt(MinecraftServer server, long millis) {
        if (server == null) {
            return true;
        }
        try {
            return FreezeData.get(server).wasFrozenAt(millis);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Bascule le gel. L'appelant a deja verifie les droits ; on journalise ici, une fois, avec le
     * nom de l'administrateur.
     */
    public static boolean setFrozen(MinecraftServer server, ServerPlayer admin, boolean value) {
        FreezeData data = FreezeData.get(server);
        long start = data.currentStart();
        if (!data.setFrozen(value, System.currentTimeMillis())) {
            return false;
        }
        String who = admin == null ? "console" : admin.getGameProfile().getName();
        if (!value && start > 0) {
            compensateLicenses(server, System.currentTimeMillis() - start);
        }
        data.log(who + (value ? " a GELE" : " a DEGELE") + " les fonds d'Utopia");
        com.utopia.UtopiaMod.LOGGER.info("[Utopia] Gel global des fonds {} par {}",
                value ? "ACTIVE" : "DESACTIVE", who);
        return true;
    }

    /**
     * Rend aux licences commerciales le temps passe sous gel. Leur echeance a continue d'avancer
     * pendant que le renouvellement etait impossible : sans ce rattrapage, la levee du gel ferait
     * expirer d'un coup des commerces que personne n'avait les moyens de sauver.
     */
    private static void compensateLicenses(MinecraftServer server, long frozenMillis) {
        if (frozenMillis <= 0) {
            return;
        }
        com.utopia.data.ParcelData data = com.utopia.data.ParcelData.get(server);
        boolean dirty = false;
        for (com.utopia.parcel.Parcel p : data.all()) {
            if (p.licenseExpiry() > 0L && !p.licenseFrozen()) {
                p.setLicenseExpiry(p.licenseExpiry() + frozenMillis);
                dirty = true;
            }
        }
        if (dirty) {
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ Verrou dur

    /**
     * Le verrou d'{@link EconomyManager} : vrai quand l'ecriture doit etre refusee. Chaque refus est
     * trace, c'est ce qui permet de retrouver apres coup le circuit qu'on avait oublie de couvrir.
     */
    public static boolean blockWrite(MinecraftServer server, UUID account, String operation) {
        if (Boolean.TRUE.equals(BYPASS.get()) || !isFrozen(server)) {
            return false;
        }
        com.utopia.UtopiaMod.LOGGER.debug("[Utopia] Gel : ecriture refusee ({} sur {})",
                operation, account);
        return true;
    }

    // ------------------------------------------------------------------ Verrou poli

    /**
     * A appeler en tete de toute action monetaire d'un joueur. Renvoie vrai quand l'action doit etre
     * abandonnee, et previent le joueur au passage.
     */
    public static boolean blocked(ServerPlayer player) {
        if (player == null || !isFrozen(player.server)) {
            return false;
        }
        warn(player);
        return true;
    }

    /** Variante silencieuse : pour les traitements automatiques, qui ont leur propre message. */
    public static boolean blockedSilently(MinecraftServer server) {
        return isFrozen(server);
    }

    /**
     * Le message de refus. L'anti-repetition ne protege que le chat : il n'autorise jamais une
     * transaction et n'efface jamais le premier refus, qui passe toujours.
     */
    public static void warn(ServerPlayer player) {
        if (throttled(lastRefusal, player.getUUID())) {
            return;
        }
        player.sendSystemMessage(refusalMessage());
    }

    /**
     * Vrai quand le message doit etre tu. Le tout premier passe toujours : la carte est vide au
     * depart, et une entree absente n'a jamais pu expirer.
     */
    private static boolean throttled(Map<UUID, Long> clock, UUID player) {
        long now = System.currentTimeMillis();
        Long previous = clock.get(player);
        if (previous != null && now - previous < MESSAGE_COOLDOWN_MS) {
            return true;
        }
        clock.put(player, now);
        if (clock.size() > 256) {
            clock.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_COOLDOWN_MS * 10);
        }
        return false;
    }

    // ------------------------------------------------------------------ Messages RP

    private static MutableComponent line(String text, ChatFormatting color, boolean bold) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withBold(bold));
    }

    /** Refus d'une transaction : le texte valide du scenario de la CGT. */
    public static Component refusalMessage() {
        return line("[CGT - RESEAU BANCAIRE PIRATE]", ChatFormatting.DARK_RED, true)
                .append(line("\nUn hacker de la Confederation des Gilets Tropicaux a verrouille tous"
                        + " les comptes d'Utopia.", ChatFormatting.RED, false))
                .append(line("\nRetraits, depots, paiements, virements, salaires et interets :"
                        + " tout est gele !", ChatFormatting.RED, false))
                .append(line("\nAucune utopiece ne circulera tant que les revendications de la CGT"
                        + " n'auront pas ete prises en compte et acceptees.", ChatFormatting.GRAY, false))
                .append(line("\nPas d'accord, pas d'utopieces !", ChatFormatting.GOLD, true));
    }

    /** Versement bloque, gel toujours actif. */
    public static Component suspendedMessage() {
        return line("[CGT - VERSEMENT BLOQUE]", ChatFormatting.DARK_RED, true)
                .append(line("\nVotre versement n'a pas pu etre credite : salaires et interets"
                        + " d'epargne sont geles.", ChatFormatting.RED, false))
                .append(line("\nLe hacker de la Confederation des Gilets Tropicaux maintient le"
                        + " blocage jusqu'a l'acceptation des revendications de la CGT.",
                        ChatFormatting.GRAY, false))
                .append(line("\nPas d'accord, pas d'utopieces !", ChatFormatting.GOLD, true));
    }

    /** Reconnexion apres la levee : on parle au passe, sans annoncer un blocage encore actif. */
    public static Component historyMessage() {
        return line("[CGT - HISTORIQUE DU BLOCAGE]", ChatFormatting.DARK_RED, true)
                .append(line("\nPendant le gel des comptes, un ou plusieurs versements de salaire ou"
                        + " d'interets n'ont pas pu etre credites.", ChatFormatting.RED, false))
                .append(line("\nLe reseau bancaire est de nouveau disponible. Les versements"
                        + " suspendus pendant le gel ne seront pas rattrapes.",
                        ChatFormatting.GRAY, false));
    }

    // ------------------------------------------------------------------ Echeances suspendues

    /**
     * Une echeance due n'a pas pu etre versee. Le joueur present l'apprend tout de suite ; l'absent
     * le lira a sa prochaine connexion, et une seule fois pour toutes les echeances manquees.
     */
    public static void noteSuspended(MinecraftServer server, UUID player, String detail) {
        FreezeData data = FreezeData.get(server);
        data.log("Suspendu par le gel : " + detail);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            // Plusieurs echeances tombent dans la meme seconde (salaire puis interets) : une seule
            // annonce suffit, sur l'horloge propre aux versements.
            if (!throttled(lastSuspended, player)) {
                online.sendSystemMessage(suspendedMessage());
            }
            return;
        }
        data.addPendingNotice(player);
    }

    /**
     * A la connexion : annonce en une fois les echeances manquees, au present si le gel dure encore,
     * au passe s'il est leve. Rien n'est dit quand rien n'etait du.
     */
    public static void onLogin(ServerPlayer player) {
        FreezeData data = FreezeData.get(player.server);
        int missed = data.takePendingNotices(player.getUUID());
        if (missed <= 0) {
            return;
        }
        player.sendSystemMessage(data.frozen() ? suspendedMessage() : historyMessage());
    }
}
