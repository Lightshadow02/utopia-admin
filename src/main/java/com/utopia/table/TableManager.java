package com.utopia.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.data.MarketData;
import com.utopia.data.TableData;
import com.utopia.economy.EconomyManager;
import com.utopia.economy.FreezeManager;
import com.utopia.util.Messages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le gestionnaire des tables : qui est assis, qui doit combien, et qui encaisse.
 *
 * <p>L'argent suit toujours le meme chemin. La mise est retiree au moment ou elle est posee, et
 * notee sur le disque dans le meme geste ; la main resolue, elle est liberee et les gains verses.
 * Rien ne peut donc rester en l'air apres une coupure : ce qui l'etait est rendu au demarrage.
 *
 * <p>La maison, c'est la caisse de la mairie, comme pour les bornes d'arcade. Elle encaisse les
 * mises perdues et paie les gains - a condition que le reglage le veuille, sinon les Utopieces
 * sont creees et detruites comme le fait deja la salle d'arcade.
 */
public final class TableManager {

    /** Une seconde : les tables se jouent a l'echelle du geste, pas du tick. */
    public static final int PERIODE_TICKS = 20;

    private static final Map<String, Salle> SALLES = new HashMap<>();

    private TableManager() {
    }

    // ------------------------------------------------------------------ Le registre

    /** L'etat vivant de cette table, cree au besoin. */
    public static Salle salle(TableData.Table table) {
        return SALLES.computeIfAbsent(table.key(), k -> switch (table.jeu) {
            case BLACKJACK -> new BlackjackTable();
            case HOLDEM -> new HoldemTable();
            case ROULETTE -> new RouletteTable();
        });
    }

    /** L'etat vivant s'il existe, sans en creer un. */
    public static Salle salleExistante(String key) {
        return SALLES.get(key);
    }

    public static void oublier(MinecraftServer server, TableData.Table table) {
        Salle s = SALLES.remove(table.key());
        if (s != null) {
            s.toutRembourser(server, table);
        }
    }

    // ------------------------------------------------------------------ La pose

    private static final Map<UUID, Jeu> EN_POSE = new HashMap<>();

    /** Le gerant a choisi un jeu : le prochain bloc qu'il casse deviendra la table. */
    public static void commencerPose(UUID gerant, Jeu jeu) {
        EN_POSE.put(gerant, jeu);
    }

    public static Jeu poseEnCours(UUID gerant) {
        return EN_POSE.get(gerant);
    }

    public static void annulerPose(UUID gerant) {
        EN_POSE.remove(gerant);
    }

    // ------------------------------------------------------------------ Les places

    private static final Map<String, List<UUID>> PLACES = new HashMap<>();
    private static final Map<UUID, String> ASSIS = new HashMap<>();

    public static List<UUID> places(TableData.Table table) {
        return PLACES.computeIfAbsent(table.key(), k -> new ArrayList<>());
    }

    public static boolean estAssis(TableData.Table table, UUID joueur) {
        return places(table).contains(joueur);
    }

    /** La table ou ce joueur est assis, ou nul. On ne s'assoit qu'a une table a la fois. */
    public static String tableDe(UUID joueur) {
        return ASSIS.get(joueur);
    }

    public static boolean asseoir(ServerPlayer joueur, TableData.Table table) {
        List<UUID> assis = places(table);
        if (assis.contains(joueur.getUUID())) {
            return true;
        }
        if (assis.size() >= table.places) {
            joueur.sendSystemMessage(Messages.warn("La table est complete."));
            return false;
        }
        String ailleurs = ASSIS.get(joueur.getUUID());
        if (ailleurs != null && !ailleurs.equals(table.key())) {
            lever(joueur.server, joueur.getUUID());
        }
        assis.add(joueur.getUUID());
        ASSIS.put(joueur.getUUID(), table.key());
        return true;
    }

    /** Leve quelqu'un de la table ou il est assis, en lui rendant ce qui n'a pas ete joue. */
    public static void lever(MinecraftServer server, UUID joueur) {
        String key = ASSIS.remove(joueur);
        if (key == null) {
            return;
        }
        List<UUID> assis = PLACES.get(key);
        if (assis != null) {
            assis.remove(joueur);
        }
        TableData.Table table = TableData.get(server).byKey(key);
        Salle salle = SALLES.get(key);
        if (table != null && salle != null) {
            salle.quitte(server, table, joueur);
        }
    }

    // ------------------------------------------------------------------ Le croupier

    /**
     * Pourquoi la table refuse de jouer, ou nul si elle accepte. Une table a croupier attitre ne
     * tourne que quand il est la : c'est lui qui la tient, et son absence la ferme.
     */
    public static String pourquoiFermee(MinecraftServer server, TableData.Table table) {
        if (!table.aUnCroupier()) {
            return null;
        }
        ServerPlayer croupier = server.getPlayerList().getPlayer(table.croupier);
        if (croupier == null) {
            return "Table fermee : " + (table.croupierNom.isBlank() ? "le croupier" : table.croupierNom)
                    + " n'est pas la.";
        }
        return null;
    }

    public static boolean ouverte(MinecraftServer server, TableData.Table table) {
        return pourquoiFermee(server, table) == null;
    }

    // ------------------------------------------------------------------ L'argent

    /**
     * Retire une mise et la note comme engagee. Faux si le joueur n'a pas de quoi, si ses fonds
     * sont geles, ou si la mise sort des limites de la table - avec le motif au chat.
     */
    public static boolean engager(ServerPlayer joueur, TableData.Table table, long montant) {
        if (montant < table.miseMin) {
            joueur.sendSystemMessage(Messages.warn("Mise minimum a cette table : " + table.miseMin + "."));
            return false;
        }
        if (montant > table.miseMax) {
            joueur.sendSystemMessage(Messages.warn("Mise maximum a cette table : " + table.miseMax + "."));
            return false;
        }
        if (FreezeManager.blocked(joueur)) {
            return false; // FreezeManager dit lui-meme pourquoi
        }
        if (!EconomyManager.payCombined(joueur, montant)) {
            joueur.sendSystemMessage(Messages.error("Il te manque " + montant + " Utopiece(s)."));
            return false;
        }
        TableData.get(joueur.server).engager(joueur.getUUID(), montant);
        return true;
    }

    /**
     * Solde une mise : elle cesse d'etre en l'air, le gain est verse, la commission du croupier
     * prelevee sur la mise, et le reste va - ou vient - de la caisse de la maison.
     *
     * @param gain ce que le joueur recoit en retour, zero s'il a tout perdu ; une mise rendue
     *             telle quelle (egalite) se passe le gain egal a la mise
     */
    public static void solder(MinecraftServer server, TableData.Table table, UUID joueur,
            long mise, long gain) {
        TableData.get(server).liberer(joueur, mise);
        if (gain > 0) {
            EconomyManager.add(server, joueur, gain);
        }
        long commission = table.aUnCroupier() ? mise * table.commission / 100 : 0;
        if (commission > 0) {
            EconomyManager.add(server, table.croupier, commission);
        }
        if (!Config.CASINO_REVENUE_TO_MAIRIE.get()) {
            return; // la maison ne tient pas de caisse : les Utopieces sont creees et detruites
        }
        long pourLaMaison = mise - gain - commission;
        if (pourLaMaison > 0) {
            EconomyManager.add(server, MarketData.MAIRIE_UUID, pourLaMaison);
        } else if (pourLaMaison < 0) {
            // La caisse ne descend pas sous zero : le joueur est paye dans tous les cas, c'est la
            // mairie qui encaisse le decouvert plutot que le gagnant.
            EconomyManager.remove(server, MarketData.MAIRIE_UUID, -pourLaMaison);
        }
    }

    /** Rend une mise qui n'aura pas ete jouee : table fermee, depart, arret du serveur. */
    public static void rembourser(MinecraftServer server, UUID joueur, long montant) {
        if (montant <= 0) {
            return;
        }
        TableData.get(server).liberer(joueur, montant);
        EconomyManager.add(server, joueur, montant);
    }

    // ------------------------------------------------------------------ Le temps qui passe

    public static void tick(MinecraftServer server) {
        TableData data = TableData.get(server);
        List<String> mortes = new ArrayList<>();
        for (Map.Entry<String, Salle> e : new LinkedHashMap<>(SALLES).entrySet()) {
            TableData.Table table = data.byKey(e.getKey());
            if (table == null) {
                // La table a ete cassee pendant qu'on jouait : rien a faire de plus que rendre.
                mortes.add(e.getKey());
                continue;
            }
            if (!ouverte(server, table)) {
                e.getValue().toutRembourser(server, table);
                viderPlaces(e.getKey());
                continue;
            }
            e.getValue().tick(server, table);
            if (e.getValue().dormante() && places(table).isEmpty()) {
                mortes.add(e.getKey());
            }
        }
        for (String key : mortes) {
            Salle s = SALLES.remove(key);
            TableData.Table table = data.byKey(key);
            if (s != null && table != null) {
                s.toutRembourser(server, table);
            }
            viderPlaces(key);
        }
    }

    private static void viderPlaces(String key) {
        List<UUID> assis = PLACES.remove(key);
        if (assis != null) {
            for (UUID id : assis) {
                ASSIS.remove(id);
            }
        }
    }

    public static void onLogout(ServerPlayer joueur) {
        lever(joueur.server, joueur.getUUID());
    }

    /**
     * Au demarrage : ce qui etait encore engage a la derniere sauvegarde est une main que l'arret
     * du serveur a coupee en deux. On rend, et on le dit dans le journal - un remboursement muet
     * ressemble trop a une creation d'Utopieces.
     */
    public static void onServerStarted(MinecraftServer server) {
        TableData data = TableData.get(server);
        List<Map.Entry<UUID, Long>> dues = data.reprendreTout();
        for (Map.Entry<UUID, Long> e : dues) {
            EconomyManager.add(server, e.getKey(), e.getValue());
            com.utopia.UtopiaMod.LOGGER.info(
                    "[Utopia] Mise de table interrompue par l'arret : {} Utopiece(s) rendue(s) a {}.",
                    e.getValue(), e.getKey());
        }
    }

    /** A l'arret : les mains en cours n'auront pas lieu, tout est rendu avant la sauvegarde. */
    public static void onServerStopping(MinecraftServer server) {
        TableData data = TableData.get(server);
        for (Map.Entry<String, Salle> e : new LinkedHashMap<>(SALLES).entrySet()) {
            TableData.Table table = data.byKey(e.getKey());
            if (table != null) {
                e.getValue().toutRembourser(server, table);
            }
        }
        SALLES.clear();
        PLACES.clear();
        ASSIS.clear();
    }
}
