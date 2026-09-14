package com.utopia.casino;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.utopia.data.CasinoData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.net.ArcadeScorePayload;
import com.utopia.net.MenuS2CPayload;
import com.utopia.net.OpenArcadePayload;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * La salle d'arcade, cote serveur : il encaisse la partie, ouvre la borne, et enregistre le score
 * qui revient. Le jeu lui-meme ne tourne jamais ici.
 *
 * <p>On ne gagne rien a l'arcade : les Utopieces glissees dans une borne quittent la poche du
 * joueur pour de bon. C'est un choix d'equilibre, pas un oubli — une borne qui rembourse devient
 * une imprimante a billets des qu'un joueur maitrise le jeu.
 */
public final class CasinoManager {

    /**
     * Un jeu de la salle. {@code maxScore} borne ce que le serveur accepte : la partie tourne chez
     * le client, donc le score est une declaration. Rien d'autre qu'un rang au tableau n'en depend,
     * mais un plafond evite qu'un score absurde ne gele le classement pour toujours.
     */
    public record Game(String id, String name, String desc, Item icon, long maxScore) {
    }

    public static final List<Game> GAMES = List.of(
            new Game("snake", "Snake", "Mange, grandis, ne te mords pas la queue",
                    Items.LIME_DYE, 100_000L),
            new Game("tetris", "Tetris", "Empile les pieces, complete les lignes",
                    Items.CYAN_DYE, 999_999L),
            new Game("breakout", "Casse-briques", "Renvoie la balle, casse le mur",
                    Items.ORANGE_DYE, 500_000L),
            new Game("bubble", "Bubble Shooter", "Vise, tire, fais exploser les grappes",
                    Items.MAGENTA_DYE, 999_999L),
            new Game("pacman", "Pac-Man", "Mange les pastilles, evite les quatre fantomes",
                    Items.YELLOW_DYE, 999_999L),
            new Game("flappy", "Envol", "Une touche, des tuyaux, et beaucoup de patience",
                    Items.FEATHER, 10_000L),
            new Game("2048", "2048", "Pousse la grille, fusionne les tuiles",
                    Items.LIGHT_BLUE_DYE, 9_999_999L));

    /** Sentinelle de pose : ce n'est pas un jeu, c'est une machine a capsules. */
    public static final String GACHA_ID = "gacha";

    public static Game game(String id) {
        for (Game g : GAMES) {
            if (g.id().equals(id)) {
                return g;
            }
        }
        return null;
    }

    /** Une partie payee et pas encore soldee : elle attend le score du client. */
    private record Session(UUID player, String gameId, long openedAt) {
    }

    /** Au-dela, une partie ouverte est consideree abandonnee (le joueur a ferme, ou s'est deconnecte). */
    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Map<Integer, Session> SESSIONS = new HashMap<>();
    /** Gerants en train de choisir le bloc d'une nouvelle borne : uuid -> jeu choisi. */
    private static final Map<UUID, String> PLACING = new HashMap<>();

    private CasinoManager() {
    }

    // ------------------------------------------------------------------ Droits

    /** Ouvre /casino : operateur ou gerant designe. */
    public static boolean canManage(ServerPlayer player) {
        return player.hasPermissions(2)
                || CasinoData.get(player.server).isManager(player.getUUID());
    }

    // ------------------------------------------------------------------ Pose d'une borne

    public static void startPlacing(UUID player, String gameId) {
        PLACING.put(player, gameId);
    }

    public static String placingGame(UUID player) {
        return PLACING.get(player);
    }

    public static void clearPlacing(UUID player) {
        PLACING.remove(player);
    }

    // ------------------------------------------------------------------ Partie

    /**
     * Encaisse la partie et ouvre la borne. Le paiement prend d'abord les pieces en poche, puis le
     * solde en banque, comme partout ailleurs dans le mod.
     */
    public static void play(ServerPlayer player, CasinoData.Machine machine) {
        Game game = game(machine.gameId);
        if (game == null) {
            player.sendSystemMessage(Messages.error("Cette borne est en panne (jeu inconnu)."));
            return;
        }
        if (com.utopia.economy.FreezeManager.blocked(player)) {
            return;
        }
        if (machine.cost > 0 && !EconomyManager.payCombined(player, machine.cost)) {
            player.sendSystemMessage(Messages.error("Il te faut " + machine.cost
                    + " Utopiece(s) pour jouer."));
            return;
        }
        if (machine.cost > 0 && com.utopia.Config.CASINO_REVENUE_TO_MAIRIE.get()) {
            EconomyManager.add(player.server, MarketData.MAIRIE_UUID, machine.cost);
        }
        open(player, game);
    }

    /** Ouvre la borne sans rien encaisser (essai gratuit du gerant). */
    public static void open(ServerPlayer player, Game game) {
        CasinoData data = CasinoData.get(player.server);
        CasinoData.Score best = data.best(game.id());
        int sessionId = COUNTER.incrementAndGet();
        synchronized (SESSIONS) {
            pruneExpired();
            SESSIONS.put(sessionId, new Session(player.getUUID(), game.id(), System.currentTimeMillis()));
        }
        PacketDistributor.sendToPlayer(player, MenuS2CPayload.of(new OpenArcadePayload(
                sessionId, game.id(),
                Component.literal(game.name()).withStyle(s -> s.withColor(ChatFormatting.GOLD)),
                best == null ? 0L : best.score(),
                best == null ? "" : best.name())));
    }

    /** Ferme les parties laissees en plan : sans cela, la carte grossit a chaque borne abandonnee. */
    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> now - e.getValue().openedAt() > SESSION_TTL_MS);
    }

    /** A la deconnexion : les parties en cours de ce joueur n'ont plus d'objet. */
    public static void onLogout(ServerPlayer player) {
        synchronized (SESSIONS) {
            SESSIONS.entrySet().removeIf(e -> e.getValue().player().equals(player.getUUID()));
        }
    }

    // ------------------------------------------------------------------ Score

    /** C2S : le client annonce le score d'une partie terminee. */
    public static void handleScore(ArcadeScorePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Session session;
            synchronized (SESSIONS) {
                session = SESSIONS.remove(payload.sessionId());
            }
            // Une partie ne compte qu'une fois, et seulement pour celui qui l'a payee : sans cette
            // verification, renvoyer le meme paquet en boucle remplirait le tableau.
            if (session == null || !session.player().equals(player.getUUID())) {
                return;
            }
            Game game = game(session.gameId());
            if (game == null) {
                return;
            }
            long score = Math.max(0, Math.min(payload.score(), game.maxScore()));
            if (score <= 0) {
                return;
            }
            CasinoData data = CasinoData.get(player.server);
            int rank = data.submit(game.id(), player.getUUID(),
                    player.getGameProfile().getName(), score, System.currentTimeMillis());
            announce(player, game, score, rank);
        });
    }

    private static void announce(ServerPlayer player, Game game, long score, int rank) {
        if (rank <= 0) {
            player.sendSystemMessage(Messages.info(game.name() + " : " + score
                    + " points. Ton record tient toujours."));
            return;
        }
        player.sendSystemMessage(Messages.success(game.name() + " : " + score
                + " points, " + rank + (rank == 1 ? "er" : "e") + " au classement !"));
        if (rank != 1) {
            return;
        }
        // Un record du monde se crie : c'est la seule recompense d'une borne d'arcade.
        MinecraftServer server = player.server;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(player.getGameProfile().getName() + " prend la tete du "
                                + game.name() + " avec " + score + " points !")
                        .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)),
                false);
    }
}
