package com.utopia.job;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.utopia.data.JobData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Versement des salaires : chaque jour a <b>12 h, heure reelle de Paris</b>, independamment du temps
 * Minecraft (cycle jour / nuit, ticks du monde, joueurs connectes ou non).
 *
 * <p>Le suivi se fait par joueur : {@code lastPaidDay} retient le dernier jour paye. Un joueur ne peut
 * donc jamais etre paye deux fois le meme jour, quels que soient les redemarrages, et un serveur
 * eteint a midi rattrape le versement des son retour.
 */
public final class JobManager {

    /** Fuseau de reference : le versement suit l'heure francaise, pas celle de la machine. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    /** Heure du versement quotidien. */
    public static final LocalTime PAY_TIME = LocalTime.NOON;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private JobManager() {
    }

    // ------------------------------------------------------------------ Temps

    public static ZonedDateTime now() {
        return ZonedDateTime.now(ZONE);
    }

    /**
     * Jour de reference du dernier versement du : c'est aujourd'hui une fois midi passe, sinon hier
     * (avant midi, le versement du jour n'a pas encore eu lieu).
     */
    public static long dueDay() {
        ZonedDateTime now = now();
        return now.toLocalTime().isBefore(PAY_TIME)
                ? now.toLocalDate().minusDays(1).toEpochDay()
                : now.toLocalDate().toEpochDay();
    }

    /** Jour courant (heure de Paris). */
    public static long today() {
        return now().toLocalDate().toEpochDay();
    }

    /** Le versement de midi a-t-il deja eu lieu aujourd'hui ? */
    public static boolean pastPayTime() {
        return !now().toLocalTime().isBefore(PAY_TIME);
    }

    public static String stamp(long millis) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZONE).format(STAMP);
    }

    // ------------------------------------------------------------------ Gestion des metiers

    /** Cree un metier ; renvoie null si le nom est vide ou deja pris. */
    public static JobData.Job create(MinecraftServer server, String name, long salary, String by) {
        JobData data = JobData.get(server);
        if (name == null || name.isBlank() || data.jobExists(name)) {
            return null;
        }
        JobData.Job job = data.createJob(name, salary);
        data.log(by + " a cree le metier \"" + job.name + "\" (" + job.salary + " Utopieces/jour)");
        return job;
    }

    public static void setSalary(MinecraftServer server, JobData.Job job, long salary, String by) {
        JobData data = JobData.get(server);
        long before = job.salary;
        job.salary = Math.max(0, salary);
        data.setDirty();
        data.log(by + " a change le salaire de \"" + job.name + "\" : " + before + " -> " + job.salary);
    }

    public static void rename(MinecraftServer server, JobData.Job job, String name, String by) {
        JobData data = JobData.get(server);
        String before = job.name;
        job.name = name.trim();
        data.setDirty();
        data.log(by + " a renomme le metier \"" + before + "\" en \"" + job.name + "\"");
    }

    public static void setEnabled(MinecraftServer server, JobData.Job job, boolean enabled, String by) {
        JobData data = JobData.get(server);
        job.enabled = enabled;
        data.setDirty();
        data.log(by + (enabled ? " a reactive " : " a desactive ") + "le metier \"" + job.name + "\"");
    }

    public static void delete(MinecraftServer server, JobData.Job job, String by) {
        JobData data = JobData.get(server);
        int employees = data.employeesOf(job.id).size();
        data.removeJob(job.id);
        data.log(by + " a supprime le metier \"" + job.name + "\" (" + employees + " employe(s) liberes)");
    }

    // ------------------------------------------------------------------ Affectations

    /**
     * Attribue un metier a un joueur. Si le versement de midi est deja passe aujourd'hui, le premier
     * salaire n'aura lieu que demain (le cahier des charges l'exige explicitement).
     */
    public static void assign(MinecraftServer server, UUID player, String playerName, JobData.Job job, String by) {
        JobData data = JobData.get(server);
        data.rememberName(player, playerName);
        boolean first = data.jobsOf(player).isEmpty();
        data.assign(player, job.id);
        if (first && pastPayTime()) {
            // Marque la journee comme deja soldee pour ce joueur : pas de salaire retroactif.
            data.setLastPaidDay(player, today());
        }
        data.log(by + " a attribue le metier \"" + job.name + "\" a " + playerName);
        tell(server, data, player, Component.literal("Vous etes embauche comme ")
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))
                .append(Component.literal(job.name)
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(job.salary > 0
                                ? " : " + job.salary + " Utopieces vous seront versees chaque jour a 12h."
                                : ". Aucun salaire n'est attache a ce metier pour l'instant.")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))));
    }

    public static void unassign(MinecraftServer server, UUID player, JobData.Job job, String by) {
        JobData data = JobData.get(server);
        data.unassign(player, job.id);
        data.log(by + " a retire le metier \"" + job.name + "\" a " + data.nameOf(player));
        tell(server, data, player, Component.literal("Vous n'exercez plus le metier ")
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))
                .append(Component.literal(job.name)
                        .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)))
                .append(Component.literal(" : le salaire correspondant s'arrete.")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false))));
    }

    /**
     * Previent un joueur tout de suite s'il est la, a sa prochaine connexion sinon. Le prefixe
     * marque distingue ces messages libres des notifications de salaire.
     */
    private static void tell(MinecraftServer server, JobData data, UUID target, Component body) {
        Component message = Component.literal("[Banque d'Utopia] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(body);
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(message);
        } else {
            data.addPending(target, PLAIN + message.getString());
        }
    }

    /**
     * Marques des deux genres d'entree de la file d'attente. Ce sont des caracteres de controle :
     * {@code OwoMenuServer.sanitize} retire de toute saisie joueur tout caractere inferieur a
     * l'espace, aucun nom de metier ne peut donc imiter une marque et detourner un message.
     */
    private static final String PLAIN = String.valueOf((char) 1);
    private static final String PAY = String.valueOf((char) 2);

    // ------------------------------------------------------------------ Versement

    /**
     * A appeler periodiquement et au demarrage : verse le salaire du jour a tous ceux qui ne l'ont pas
     * encore recu. Rien ne se passe avant midi ; apres un arret prolonge, chaque joueur touche
     * <b>un seul</b> salaire (celui du jour), jamais un cumul des jours manques.
     */
    public static void tick(MinecraftServer server) {
        if (!pastPayTime()) {
            return;
        }
        JobData data = JobData.get(server);
        long day = today();
        for (UUID player : data.employees()) {
            if (data.lastPaidDay(player) >= day) {
                continue; // deja paye aujourd'hui
            }
            pay(server, data, player, day);
        }
    }

    /** Verse a un joueur le total de ses metiers actifs et le notifie (tout de suite ou a sa connexion). */
    private static void pay(MinecraftServer server, JobData data, UUID player, long day) {
        long total = 0;
        List<String> lines = new ArrayList<>();
        for (String id : data.jobsOf(player)) {
            JobData.Job job = data.job(id);
            if (job == null || !job.enabled || job.salary <= 0) {
                continue;
            }
            total += job.salary;
            lines.add(job.name + " : +" + job.salary + " Utopieces");
        }
        // Meme sans rien a verser, on marque la journee : inutile de re-tester ce joueur en boucle.
        data.setLastPaidDay(player, day);
        if (total <= 0) {
            return;
        }
        EconomyManager.add(server, player, total);
        data.log("Salaire verse a " + data.nameOf(player) + " : " + total + " Utopieces ("
                + String.join(", ", lines) + ")");

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        String jobs = String.join(", ", jobNames(data, player));
        if (online != null) {
            online.sendSystemMessage(paidNow(jobs, total));
        } else {
            data.addPending(player, PAY + jobs + "|" + total);
        }
    }

    private static List<String> jobNames(JobData data, UUID player) {
        List<String> names = new ArrayList<>();
        for (String id : data.jobsOf(player)) {
            JobData.Job job = data.job(id);
            if (job != null && job.enabled && job.salary > 0) {
                names.add(job.name);
            }
        }
        return names;
    }

    /** Message affiche au joueur present au moment du versement. */
    private static Component paidNow(String jobs, long total) {
        return Component.literal("[Banque d'Utopia] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal("Votre salaire de " + jobs + " vient de vous etre verse : ")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)))
                .append(Component.literal("+" + total + " Utopieces.")
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)));
    }

    /** Message affiche a la connexion pour un salaire verse pendant l'absence du joueur. */
    private static Component paidWhileAway(String jobs, long total) {
        return Component.literal("[Banque d'Utopia] ")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal("Pendant votre absence, votre salaire de " + jobs
                                + " vous a ete verse : ")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(false)))
                .append(Component.literal("+" + total + " Utopieces.")
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)));
    }

    /**
     * A la connexion : memorise le pseudo et delivre les notifications de salaire en attente. Elles
     * sont consommees, donc affichees une seule fois.
     */
    public static void onLogin(ServerPlayer player) {
        JobData data = JobData.get(player.server);
        data.rememberName(player.getUUID(), player.getGameProfile().getName());
        for (String raw : data.takePending(player.getUUID())) {
            if (raw.startsWith(PLAIN)) {
                player.sendSystemMessage(Component.literal(raw.substring(PLAIN.length()))
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW)));
                continue;
            }
            // Les entrees d'avant l'introduction des marques n'en portent aucune : elles restent lisibles.
            String body = raw.startsWith(PAY) ? raw.substring(PAY.length()) : raw;
            int sep = body.lastIndexOf('|');
            if (sep <= 0) {
                continue;
            }
            String jobs = body.substring(0, sep);
            long total;
            try {
                total = Long.parseLong(body.substring(sep + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            player.sendSystemMessage(paidWhileAway(jobs, total));
        }
    }

    // ------------------------------------------------------------------ Permissions

    /**
     * Peut ouvrir le panel : op, maire ou banquier. Le banquier n'a que ce panel, aucun autre droit.
     */
    public static boolean canManage(ServerPlayer player) {
        return player.hasPermissions(2)
                || MarketData.get(player.server).isMaire(player.getUUID())
                || JobData.get(player.server).isBanker(player.getUUID());
    }

    /**
     * Peut modifier les metiers eux-memes : creer, renommer, changer un salaire, supprimer. Le
     * banquier en fait partie, la banque tenant le registre des emplois comme celui des livrets.
     * Chaque geste reste horodate au journal, qui sert de garde-fou.
     */
    public static boolean canEditJobs(ServerPlayer player) {
        return canManage(player);
    }

    /** Total verse chaque jour par la banque (utile pour l'affichage du panel). */
    public static long dailyPayroll(MinecraftServer server) {
        JobData data = JobData.get(server);
        long total = 0;
        for (UUID player : data.employees()) {
            for (String id : data.jobsOf(player)) {
                JobData.Job job = data.job(id);
                if (job != null && job.enabled) {
                    total += job.salary;
                }
            }
        }
        return total;
    }
}
