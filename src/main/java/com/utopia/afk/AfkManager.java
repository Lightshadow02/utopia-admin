package com.utopia.afk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * L'anti-AFK : une place occupee sans personne derriere finit par etre rendue.
 *
 * <p>Deux compteurs sont consultes, parce qu'aucun des deux ne suffit seul.
 *
 * <p>Minecraft tient deja le sien, remis a zero par une quinzaine de gestes : ouvrir un coffre,
 * poser un bloc, lancer une canne a peche, frapper dans le vide, parler, taper une commande. Il
 * ignore en revanche le <b>deplacement</b> : un joueur qui ne ferait que marcher pendant des heures
 * y passerait pour absent.
 *
 * <p>On releve donc aussi la position et le regard, toutes les cinq secondes. Sur trois heures de
 * delai cette precision-la ne change rien, et le releve ne coute presque rien.
 *
 * <p>Restent les gestes qui ne passent ni par l'un ni par l'autre : cliquer dans un menu du mod ou
 * finir une partie d'arcade n'envoie que des paquets du mod, que Minecraft ne compte pas. Ceux-la
 * appellent {@link #reveiller} directement.
 */
public final class AfkManager {

    /** Interval entre deux releves, en ticks. */
    public static final int PERIODE_TICKS = 100;

    /**
     * Distance, au carre, a partir de laquelle un deplacement compte. Le serveur remue les joueurs
     * tout seul - une bousculade a l'attroupement du spawn, un courant d'eau, une barque qui tangue.
     * Sous ce seuil, ce n'est pas quelqu'un qui joue, c'est quelqu'un qu'on pousse.
     */
    private static final double SEUIL_DEPLACEMENT = 1.0;

    /** Ce qu'on retient d'un joueur entre deux releves. */
    private static final class Veille {
        double x;
        double y;
        double z;
        float lacet;
        float tangage;
        /** Dernier signe de vie vu par le mod, sur l'horloge de {@link Util#getMillis()}. */
        long vuA;
        /** Delai en vigueur au dernier passage, pour reperer un changement de reglage a chaud. */
        long limite;
        boolean prevenu;

        Veille(ServerPlayer player, long maintenant, long limite) {
            relever(player);
            this.vuA = maintenant;
            this.limite = limite;
        }

        void relever(ServerPlayer player) {
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.lacet = player.getYRot();
            this.tangage = player.getXRot();
        }

        /**
         * Le point de repere n'est pas rafraichi a chaque passage mais seulement quand il a vraiment
         * bouge : sinon une derive lente, metre par metre, passerait indefiniment sous le seuil.
         */
        boolean aBouge(ServerPlayer player) {
            return player.getYRot() != lacet
                    || player.getXRot() != tangage
                    || player.distanceToSqr(x, y, z) > SEUIL_DEPLACEMENT;
        }
    }

    private static final Map<UUID, Veille> VEILLES = new HashMap<>();

    private AfkManager() {
    }

    /**
     * Note que ce joueur vient de se manifester. Reserve aux gestes que Minecraft ne compte pas :
     * les paquets propres au mod.
     */
    public static void reveiller(ServerPlayer player) {
        Veille v = VEILLES.get(player.getUUID());
        if (v == null) {
            return; // pas encore releve : le prochain passage partira de maintenant de toute facon
        }
        v.vuA = Util.getMillis();
        v.prevenu = false;
    }

    public static void onLogout(ServerPlayer player) {
        VEILLES.remove(player.getUUID());
    }

    /** Appele toutes les {@link #PERIODE_TICKS} graduations. */
    public static void tick(MinecraftServer server) {
        if (!Config.AFK_ENABLED.get()) {
            // Le module se coupe a chaud : on oublie tout, sinon un joueur inactif depuis longtemps
            // serait deconnecte a la seconde ou on le rallume.
            VEILLES.clear();
            return;
        }
        // Meme horloge que getLastActionTime : celle-ci compte depuis le demarrage de la machine et
        // non depuis 1970. Les melanger donnerait un ecart de plusieurs annees, donc un renvoi
        // immediat de tout le serveur.
        long maintenant = Util.getMillis();
        long limite = Config.AFK_MINUTES.get() * 60_000L;
        long preavis = Config.AFK_WARN_MINUTES.get() * 60_000L;
        long seuil = limite - preavis;
        // Un preavis aussi long que le delai ne previendrait pas, il annoncerait le depart des la
        // premiere pause. Dans ce cas on s'en passe.
        boolean avecPreavis = preavis > 0 && seuil > 0;
        boolean epargnerOps = Config.AFK_EXEMPT_OPS.get();
        List<ServerPlayer> aDeconnecter = null;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (epargnerOps && player.hasPermissions(2)) {
                VEILLES.remove(player.getUUID());
                continue;
            }
            Veille v = VEILLES.get(player.getUUID());
            if (v == null) {
                VEILLES.put(player.getUUID(), new Veille(player, maintenant, limite));
                continue;
            }
            if (v.limite != limite) {
                // Le delai vient d'etre change : le preavis deja donne ne vaut plus pour ce delai-la.
                v.limite = limite;
                v.prevenu = false;
            }
            if (v.aBouge(player)) {
                v.relever(player);
                v.vuA = maintenant;
                v.prevenu = false;
                continue;
            }
            long inactif = maintenant - Math.max(v.vuA, player.getLastActionTime());
            if (avecPreavis && !v.prevenu && inactif >= seuil) {
                v.prevenu = true;
                long reste = Math.max(1, (limite - inactif + 59_999L) / 60_000L);
                player.sendSystemMessage(Messages.warn("Toujours la ? Sans un signe de vie, tu seras "
                        + "deconnecte dans " + reste + " minute(s)."));
                // On ne previent pas et on ne renvoie pas du meme souffle : meme quand le delai
                // vient d'etre raccourci sous le temps deja ecoule, l'avertissement part d'abord.
                continue;
            }
            if (inactif >= limite && (!avecPreavis || v.prevenu)) {
                if (aDeconnecter == null) {
                    aDeconnecter = new ArrayList<>();
                }
                aDeconnecter.add(player);
            }
        }

        // Toujours apres la boucle : une deconnexion peut retirer le joueur de la liste sur-le-champ
        // quand sa connexion est deja morte, et la liste rendue par le serveur est la vraie.
        if (aDeconnecter != null) {
            for (ServerPlayer player : aDeconnecter) {
                VEILLES.remove(player.getUUID());
                player.connection.disconnect(Component.literal("Deconnecte pour inactivite apres "
                                + Config.AFK_MINUTES.get() + " minutes.\n"
                                + "Reconnecte-toi quand tu veux, rien n'est perdu.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }

        if (VEILLES.size() > server.getPlayerList().getPlayerCount()) {
            java.util.Set<UUID> presents = new java.util.HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                presents.add(p.getUUID());
            }
            VEILLES.keySet().retainAll(presents);
        }
    }
}
