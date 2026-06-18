package com.utopia.api;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * API publique et stable de l'economie d'utopia-admin, destinee aux integrations externes
 * (scripts KubeJS, autres mods...). Toutes les methodes sont statiques et n'utilisent que des types
 * simples (UUID, String, long, ServerPlayer) pour etre faciles a appeler depuis KubeJS via
 * {@code Java.loadClass('com.utopia.api.UtopiaEconomyAPI')}.
 *
 * <p>Les montants sont des entiers ({@code long}). Le "solde" est le compte bancaire du joueur
 * (distinct des pieces physiques dans l'inventaire). La mairie est un compte virtuel partage.
 *
 * <p>Cette classe est un contrat stable : sa signature ne changera pas sans raison. La logique
 * interne vit dans {@code EconomyManager} (non garanti stable).
 */
public final class UtopiaEconomyAPI {

    private UtopiaEconomyAPI() {
    }

    // ============================================================
    //  Soldes bancaires (par joueur)
    // ============================================================

    /** Solde bancaire du joueur (en ligne). */
    public static long getBalance(ServerPlayer player) {
        return EconomyManager.getBalance(player.server, player.getUUID());
    }

    /** Solde bancaire d'un joueur par UUID (fonctionne hors ligne). */
    public static long getBalance(MinecraftServer server, UUID playerId) {
        return EconomyManager.getBalance(server, playerId);
    }

    /** Solde bancaire d'un joueur par pseudo (resolu via le cache de profils ; 0 si inconnu). */
    public static long getBalance(MinecraftServer server, String playerName) {
        UUID id = resolvePlayer(server, playerName);
        return id == null ? 0L : EconomyManager.getBalance(server, id);
    }

    /** Definit le solde bancaire (valeur absolue). */
    public static void setBalance(ServerPlayer player, long amount) {
        EconomyManager.setBalance(player.server, player.getUUID(), amount);
    }

    public static void setBalance(MinecraftServer server, UUID playerId, long amount) {
        EconomyManager.setBalance(server, playerId, amount);
    }

    /** Ajoute des Utopieces au solde (montant negatif = retire, peut rendre le solde negatif). */
    public static void add(ServerPlayer player, long amount) {
        EconomyManager.add(player.server, player.getUUID(), amount);
    }

    public static void add(MinecraftServer server, UUID playerId, long amount) {
        EconomyManager.add(server, playerId, amount);
    }

    /** Retire {@code amount} si le solde est suffisant. Renvoie false (sans rien retirer) sinon. */
    public static boolean remove(ServerPlayer player, long amount) {
        return EconomyManager.remove(player.server, player.getUUID(), amount);
    }

    public static boolean remove(MinecraftServer server, UUID playerId, long amount) {
        return EconomyManager.remove(server, playerId, amount);
    }

    /** Vrai si le solde du joueur est au moins egal a {@code amount}. */
    public static boolean has(ServerPlayer player, long amount) {
        return EconomyManager.getBalance(player.server, player.getUUID()) >= amount;
    }

    public static boolean has(MinecraftServer server, UUID playerId, long amount) {
        return EconomyManager.getBalance(server, playerId) >= amount;
    }

    /** Transfere {@code amount} d'un compte a un autre. Renvoie false si solde source insuffisant. */
    public static boolean transfer(MinecraftServer server, UUID from, UUID to, long amount) {
        return EconomyManager.transfer(server, from, to, amount);
    }

    /** Paiement d'un joueur a un autre (via leur solde bancaire). False si solde insuffisant. */
    public static boolean pay(ServerPlayer from, ServerPlayer to, long amount) {
        return EconomyManager.transfer(from.server, from.getUUID(), to.getUUID(), amount);
    }

    // ============================================================
    //  Pieces physiques (Utopieces dans l'inventaire)
    // ============================================================

    /** Donne {@code amount} pieces physiques au joueur (surplus au sol si l'inventaire est plein). */
    public static void giveCoins(ServerPlayer player, int amount) {
        EconomyManager.giveCoins(player, amount);
    }

    /** Nombre de pieces physiques dans l'inventaire du joueur. */
    public static int countCoins(ServerPlayer player) {
        return EconomyManager.countCoins(player);
    }

    /** Retire jusqu'a {@code amount} pieces physiques ; renvoie le nombre reellement retire. */
    public static int takeCoins(ServerPlayer player, int amount) {
        return EconomyManager.takeCoins(player, amount);
    }

    /**
     * Paie {@code amount} en prelevant d'abord les pieces physiques, puis le solde bancaire.
     * Renvoie false (sans rien prelever) si le total disponible est insuffisant.
     */
    public static boolean payCombined(ServerPlayer player, long amount) {
        return EconomyManager.payCombined(player, amount);
    }

    // ============================================================
    //  Compte de la mairie
    // ============================================================

    /** UUID du compte virtuel de la mairie (taxe du marche, parcelles serveur...). */
    public static UUID mairieId() {
        return MarketData.MAIRIE_UUID;
    }

    /** Solde du compte de la mairie. */
    public static long getMairieBalance(MinecraftServer server) {
        return EconomyManager.getBalance(server, MarketData.MAIRIE_UUID);
    }

    /** Ajoute (ou retire si negatif) des Utopieces au compte de la mairie. */
    public static void addToMairie(MinecraftServer server, long amount) {
        EconomyManager.add(server, MarketData.MAIRIE_UUID, amount);
    }

    // ============================================================
    //  Utilitaires
    // ============================================================

    /** Formate un montant pour l'affichage, ex. "1234 Utopieces". */
    public static String format(long amount) {
        return EconomyManager.format(amount);
    }

    /** Nom de la monnaie configuree. */
    public static String currencyName() {
        return EconomyManager.currencyName();
    }

    /**
     * Resout l'UUID d'un joueur par son pseudo : d'abord les joueurs en ligne, sinon le cache de
     * profils du serveur (joueurs deja vus). Renvoie null si introuvable.
     */
    public static UUID resolvePlayer(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        if (server.getProfileCache() != null) {
            Optional<GameProfile> profile = server.getProfileCache().get(name);
            if (profile.isPresent()) {
                return profile.get().getId();
            }
        }
        return null;
    }
}
