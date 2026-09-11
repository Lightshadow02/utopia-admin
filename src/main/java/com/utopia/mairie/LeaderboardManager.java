package com.utopia.mairie;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.utopia.data.LeaderboardData;
import com.utopia.data.MairieData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.job.JobManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Le concours de chasse de la mairie : chaque mob tue rapporte les points que le maire lui a donnes,
 * et la journee se solde a une heure reelle choisie par lui, recompenses versees depuis la caisse de
 * la commune.
 *
 * <p>La journee suit l'horloge du monde reel (fuseau de Paris, le meme que les salaires et les
 * livrets) et non le cycle jour/nuit du jeu : un concours de 24 h doit tomber a heure fixe pour les
 * joueurs, pas toutes les vingt minutes.
 */
public final class LeaderboardManager {

    private LeaderboardManager() {
    }

    /**
     * Prochaine cloture : le prochain passage a l'heure choisie, en heure de Paris. On raisonne en
     * echeance et non en numero de jour, pour que deplacer l'heure de cloture n'aille pas rendre
     * une journee deja ouverte retroactivement echue.
     */
    private static long computeNextClose(MairieData mairie) {
        ZonedDateTime now = ZonedDateTime.now(JobManager.ZONE);
        ZonedDateTime next = now.withHour(mairie.leaderboardHour())
                .withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next.toEpochSecond();
    }

    private static long now() {
        return ZonedDateTime.now(JobManager.ZONE).toEpochSecond();
    }

    /**
     * Journee que nomme une echeance. On recule d'une seconde avant de prendre la date : une cloture
     * a minuit tombe deja le lendemain, et le palmares afficherait un jour ou personne n'a chasse.
     */
    private static long dayOf(long closeEpochSecond) {
        return java.time.Instant.ofEpochSecond(closeEpochSecond - 1).atZone(JobManager.ZONE)
                .toLocalDate().toEpochDay();
    }

    /**
     * Le maire vient de changer l'heure de cloture : on deplace l'echeance sans rien solder. Les
     * prises deja faites restent au compteur, elles seront jugees a la nouvelle heure.
     */
    public static void onHourChanged(MinecraftServer server) {
        // Une cloture deja echue mais pas encore passee au tick doit etre soldee AVANT de deplacer
        // l'echeance : sinon changer l'heure quelques secondes trop tard escamote une edition
        // entiere, primes et palmares compris.
        tick(server);
        LeaderboardData data = LeaderboardData.get(server);
        if (data.initialized()) {
            data.setNextClose(computeNextClose(MairieData.get(server)));
        }
    }

    /**
     * Le maire lance ou arrete le concours. Le lancement ouvre une journee neuve ; l'arret efface
     * les compteurs, faute de quoi une relance des semaines plus tard solderait et paierait le
     * classement fige au moment de la pause.
     */
    public static void onEnabledChanged(MinecraftServer server, boolean enabled) {
        LeaderboardData data = LeaderboardData.get(server);
        if (enabled) {
            data.startPeriod(computeNextClose(MairieData.get(server)));
        } else {
            data.clear();
        }
    }

    /** Echeance en cours, telle qu'on l'ecrit au maire : "aujourd'hui a 20h" / "demain a 6h". */
    public static String nextCloseLabel(MinecraftServer server, MairieData mairie) {
        LeaderboardData data = LeaderboardData.get(server);
        if (!data.initialized()) {
            return hourLabel(mairie);
        }
        java.time.LocalDate closeDay = java.time.Instant.ofEpochSecond(data.nextClose())
                .atZone(JobManager.ZONE).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now(JobManager.ZONE);
        String quand = closeDay.equals(today) ? "aujourd'hui"
                : closeDay.equals(today.plusDays(1)) ? "demain"
                : closeDay.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"));
        return quand + " a " + hourLabel(mairie);
    }

    /** Heure de cloture telle qu'on l'ecrit aux joueurs : "20h". */
    public static String hourLabel(MairieData mairie) {
        return mairie.leaderboardHour() + "h";
    }

    // ------------------------------------------------------------------ Comptage

    /**
     * A appeler a la mort d'une creature. Le tueur retenu est celui a qui le jeu attribue la prise :
     * cela couvre la fleche, le loup apprivoise et le coup porte a distance, sans code special.
     */
    public static void onKill(MinecraftServer server, LivingEntity dead) {
        if (!com.utopia.Config.MAIRIE_LEADERBOARD.get()) {
            return;
        }
        MairieData mairie = MairieData.get(server);
        if (!mairie.leaderboardEnabled()) {
            return;
        }
        // Un joueur n'est pas du gibier : le concours porte sur les mobs, pas sur les autres joueurs.
        if (dead instanceof Player) {
            return;
        }
        // Les PNJ du mod portent tous la meme interface. Ils sont deja invulnerables, mais un
        // classement ne doit pas dependre de cette invulnerabilite pour rester honnete.
        if (dead instanceof com.utopia.entity.SkinNpc) {
            return;
        }
        if (!(dead.getKillCredit() instanceof ServerPlayer killer)
                || killer instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            return; // un faux joueur est une machine du modpack, pas un chasseur
        }
        if (!mairie.countSpawnerMobs() && dead instanceof Mob mob
                && net.minecraft.world.entity.MobSpawnType.isSpawner(mob.getSpawnType())) {
            return; // une ferme a generateur remplirait le podium toute seule
        }
        net.minecraft.resources.ResourceLocation typeId =
                BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType());
        if (typeId == null) {
            return;
        }
        int points = mairie.pointsFor(typeId.toString());
        if (points <= 0) {
            return; // rien a compter : on evite aussi de creer une ligne vide au classement
        }
        // Le controle periodique ne passe que toutes les 30 s : sans ce rattrapage, une prise faite
        // juste apres l'heure de cloture irait grossir le classement qui vient de se terminer, et
        // serait emportee par la remise a zero quelques secondes plus tard.
        tick(server);
        LeaderboardData data = LeaderboardData.get(server);
        data.add(killer.getUUID(), killer.getGameProfile().getName(), points);
    }

    // ------------------------------------------------------------------ Cloture

    /**
     * A appeler periodiquement : solde la journee quand l'heure de cloture est passee. Une journee
     * manquee (serveur eteint) est soldee au demarrage suivant, une seule fois.
     */
    public static void tick(MinecraftServer server) {
        if (!com.utopia.Config.MAIRIE_LEADERBOARD.get()) {
            return;
        }
        MairieData mairie = MairieData.get(server);
        if (!mairie.leaderboardEnabled()) {
            return;
        }
        LeaderboardData data = LeaderboardData.get(server);
        if (!data.initialized()) {
            data.startPeriod(computeNextClose(mairie));
            return; // premiere ouverture : il n'y a rien a solder
        }
        if (now() < data.nextClose()) {
            return;
        }
        close(server, mairie, data);
    }

    /** Solde le classement : recompenses, annonce, archivage, puis remise a zero. */
    private static void close(MinecraftServer server, MairieData mairie, LeaderboardData data) {
        long closedDay = dayOf(data.nextClose());
        List<LeaderboardData.Entry> standings = data.standings();
        List<MairieData.PodiumEntry> awarded = new ArrayList<>();
        long caisse = EconomyManager.getBalance(server, MarketData.MAIRIE_UUID);
        boolean shortOfFunds = false;

        int places = Math.min(standings.size(), MairieData.MAX_PODIUM);
        for (int i = 0; i < places; i++) {
            LeaderboardData.Entry entry = standings.get(i);
            long reward = mairie.rewardForRank(i + 1);
            long paid = 0;
            // La recompense sort de la caisse de la mairie : sans ce retrait, le classement
            // fabriquerait des Utopieces a chaque cloture.
            if (reward > 0 && !shortOfFunds && caisse >= reward
                    && EconomyManager.remove(server, MarketData.MAIRIE_UUID, reward)) {
                EconomyManager.add(server, entry.player(), reward);
                caisse -= reward;
                paid = reward;
            } else if (reward > 0) {
                // Des qu'une place n'est pas payable, les suivantes ne le sont pas non plus : les
                // primes decroissent, payer le dauphin quand le vainqueur repart les mains vides
                // renverserait le classement.
                shortOfFunds = true;
            }
            awarded.add(new MairieData.PodiumEntry(entry.player(), entry.name(),
                    entry.points(), entry.kills(), paid));
            notifyWinner(server, entry, i + 1, paid);
        }

        if (!awarded.isEmpty()) {
            mairie.archive(new MairieData.Podium(closedDay, awarded));
            announce(server, awarded, shortOfFunds);
        }
        data.startPeriod(computeNextClose(mairie));
    }

    private static void notifyWinner(MinecraftServer server, LeaderboardData.Entry entry,
                                     int rank, long paid) {
        ServerPlayer online = server.getPlayerList().getPlayer(entry.player());
        if (online == null) {
            return;
        }
        Component line = Component.literal("[Mairie] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal("Vous finissez " + rank + (rank == 1 ? "er" : "e")
                                + " du concours de chasse avec " + entry.points() + " points.")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)));
        if (paid > 0) {
            line = line.copy().append(Component.literal(" Prime : +" + paid + " Utopieces.")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)));
        }
        online.sendSystemMessage(line);
    }

    private static void announce(MinecraftServer server, List<MairieData.PodiumEntry> podium,
                                 boolean shortOfFunds) {
        Component message = Component.literal("Concours de chasse : le classement est tombe !")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        for (int i = 0; i < podium.size(); i++) {
            MairieData.PodiumEntry e = podium.get(i);
            int rank = i + 1;
            String name = e.name() == null || e.name().isBlank() ? "Un joueur" : e.name();
            String prime = e.reward() > 0 ? " (+" + e.reward() + " Utopieces)" : "";
            message = message.copy().append(Component.literal("\n" + rank + ". " + name + " - "
                            + e.points() + " points" + prime)
                    .withStyle(s -> s.withColor(rankColor(rank)).withBold(false)));
        }
        if (shortOfFunds) {
            message = message.copy().append(Component.literal(
                            "\nLa caisse de la mairie n'a pas suffi a payer toutes les primes.")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(false)));
        }
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static ChatFormatting rankColor(int rank) {
        return switch (rank) {
            case 1 -> ChatFormatting.GOLD;
            case 2 -> ChatFormatting.GRAY;
            case 3 -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.DARK_GRAY;
        };
    }
}
