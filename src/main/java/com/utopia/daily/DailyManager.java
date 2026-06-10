package com.utopia.daily;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.UtopiaMod;
import com.utopia.data.DailyData;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Logique des recompenses quotidiennes ({@code /daily}), basee sur un CALENDRIER par date :
 * chaque jour reel a sa recompense planifiee par l'admin (a defaut, la recompense de base de la config).
 * Une reclamation par jour calendaire ; un jour manque reinitialise la serie (streak).
 */
public final class DailyManager {

    /** Calendrier des recompenses (charge depuis le disque). */
    private static volatile DailyCalendar calendar = null;

    private DailyManager() {
    }

    public static Path calendarPath() {
        return FMLPaths.CONFIGDIR.get().resolve(UtopiaMod.MODID).resolve("daily_calendar.json");
    }

    public static synchronized DailyCalendar loadCalendar() {
        calendar = DailyCalendar.load(calendarPath());
        return calendar;
    }

    public static DailyCalendar calendar() {
        DailyCalendar c = calendar;
        if (c == null) {
            c = loadCalendar();
        }
        return c;
    }

    /** Date "du jour" cote serveur. */
    public static LocalDate today() {
        return LocalDate.now();
    }

    /** Specs d'items de la recompense d'une date : calendrier si planifie, sinon la recompense de base. */
    public static List<String> rewardSpecsFor(LocalDate date) {
        DailyCalendar c = calendar();
        if (c.hasReward(date)) {
            return new ArrayList<>(c.getReward(date));
        }
        if (!Config.DAILY_DEFAULT_ENABLED.get()) {
            return new ArrayList<>(); // defaut desactive : un jour non planifie ne donne rien
        }
        List<String> base = new ArrayList<>();
        for (String s : Config.DAILY_ITEMS.get()) {
            base.add(s);
        }
        return base;
    }

    /** Definition d'un palier (streak). */
    private record Milestone(int day, boolean periodic, List<String> items, List<String> commands) {
        boolean matches(int streak) {
            if (streak <= 0) {
                return false;
            }
            return periodic ? (streak % day == 0) : (streak == day);
        }
    }

    public static boolean claim(ServerPlayer player) {
        return claim(player, false, true);
    }

    /**
     * Force la distribution de la recompense du jour (admin), meme si deja reclamee, sans annonce globale.
     * Ne gonfle pas la serie en cas de clics repetes le meme jour (la serie reste inchangee si deja
     * reclame aujourd'hui).
     */
    public static boolean adminForceClaim(ServerPlayer player) {
        return claim(player, true, false);
    }

    private static boolean claim(ServerPlayer player, boolean force, boolean announce) {
        MinecraftServer server = player.server;
        DailyData data = DailyData.get(server);
        LocalDate today = today();
        long todayDay = today.toEpochDay();
        DailyData.Entry entry = data.getEntry(player.getUUID());
        boolean streakEnabled = Config.DAILY_STREAK_ENABLED.get();

        boolean alreadyToday = entry != null && entry.lastClaimDay == todayDay;
        if (alreadyToday && !force) {
            player.sendSystemMessage(Messages.warn("Vous avez deja recupere votre recompense aujourd'hui. Revenez demain !"));
            if (streakEnabled) {
                player.sendSystemMessage(Messages.info("Serie actuelle : " + entry.streak + " jour(s)."));
            }
            return false;
        }

        // Calcul de la serie.
        int newStreak;
        if (alreadyToday) {
            newStreak = entry.streak; // force-claim le meme jour : ne change pas la serie
        } else if (entry == null) {
            newStreak = 1;
        } else if (streakEnabled && entry.lastClaimDay == todayDay - 1) {
            newStreak = entry.streak + 1; // reclame hier -> serie continue
        } else {
            newStreak = 1; // jour(s) manque(s) -> serie reinitialisee
        }

        // Recompense du jour (calendrier ou base) + commandes de base (si le defaut est actif).
        List<String> items = rewardSpecsFor(today);
        int itemsGiven = giveItems(player, items);
        boolean defaultCommands = Config.DAILY_DEFAULT_ENABLED.get() && !Config.DAILY_COMMANDS.get().isEmpty();
        if (defaultCommands) {
            runCommands(server, player, Config.DAILY_COMMANDS.get());
        }

        // Recompenses de palier (streak).
        List<Integer> reachedMilestones = new ArrayList<>();
        if (streakEnabled) {
            for (Milestone m : parseMilestones()) {
                if (m.matches(newStreak)) {
                    itemsGiven += giveItems(player, m.items());
                    runCommands(server, player, m.commands());
                    reachedMilestones.add(m.day());
                }
            }
        }

        data.setEntry(player.getUUID(), todayDay, newStreak);
        data.markClaimed(player.getUUID(), todayDay);

        // Messages.
        player.sendSystemMessage(Messages.success("Recompense quotidienne du " + today + " recuperee !"));
        if (streakEnabled) {
            player.sendSystemMessage(Messages.info("Serie : " + newStreak + " jour(s) consecutif(s).")
                    .withStyle(ChatFormatting.GOLD));
        }
        for (int day : reachedMilestones) {
            player.sendSystemMessage(Messages.success("Palier de serie atteint : recompense bonus du jour " + day + " !")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (itemsGiven == 0 && !defaultCommands) {
            player.sendSystemMessage(Messages.info("(Aucune recompense particuliere prevue aujourd'hui.)"));
        }

        if (announce && Config.DAILY_ANNOUNCE.get()) {
            Component announceMsg = Messages.info(player.getGameProfile().getName()
                    + " a recupere sa recompense quotidienne"
                    + (streakEnabled ? " (serie : " + newStreak + ") !" : " !"));
            server.getPlayerList().broadcastSystemMessage(announceMsg, false);
        }
        return true;
    }

    /** Affiche l'etat (/daily status) sans rien distribuer. */
    public static void showStatus(ServerPlayer player) {
        boolean available = isAvailable(player.server, player.getUUID());
        if (available) {
            player.sendSystemMessage(Messages.success("Votre recompense quotidienne est disponible ! Tapez /daily."));
        } else {
            player.sendSystemMessage(Messages.info("Recompense deja recuperee. Prochaine dans "
                    + Messages.formatDuration(secondsUntilTomorrow()) + " (minuit)."));
        }
        if (Config.DAILY_STREAK_ENABLED.get()) {
            player.sendSystemMessage(Messages.info("Serie actuelle : " + currentStreak(player.server, player.getUUID()) + " jour(s).")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers utilises par les menus (GUI) et les events
    // ---------------------------------------------------------------------------------------------

    /** Disponible si le joueur n'a pas encore reclame aujourd'hui. */
    public static boolean isAvailable(MinecraftServer server, UUID playerId) {
        DailyData.Entry entry = DailyData.get(server).getEntry(playerId);
        return entry == null || entry.lastClaimDay != today().toEpochDay();
    }

    /** Le joueur a-t-il deja recupere aujourd'hui ? */
    public static boolean claimedToday(MinecraftServer server, UUID playerId) {
        DailyData.Entry entry = DailyData.get(server).getEntry(playerId);
        return entry != null && entry.lastClaimDay == today().toEpochDay();
    }

    /** Secondes jusqu'a minuit (prochaine disponibilite). */
    public static long secondsUntilTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Math.max(0, Duration.between(now, midnight).getSeconds());
    }

    public static int currentStreak(MinecraftServer server, UUID playerId) {
        DailyData.Entry entry = DailyData.get(server).getEntry(playerId);
        return entry == null ? 0 : entry.streak;
    }

    /** Jour epoch de la derniere reclamation (0 si jamais). */
    public static long lastClaimDay(MinecraftServer server, UUID playerId) {
        DailyData.Entry entry = DailyData.get(server).getEntry(playerId);
        return entry == null ? 0L : entry.lastClaimDay;
    }

    /** Reinitialise la reclamation, la serie et l'historique d'un joueur (recompense de nouveau disponible). */
    public static void reset(MinecraftServer server, UUID playerId) {
        DailyData.get(server).resetEntry(playerId);
    }

    /** Ce jour epoch a-t-il ete reclame par le joueur (historique) ? */
    public static boolean hasClaimed(MinecraftServer server, UUID playerId, long epochDay) {
        return DailyData.get(server).hasClaimed(playerId, epochDay);
    }

    /** Ajuste la serie d'un joueur de {@code delta} (sans distribuer de recompense). */
    public static int adjustStreak(MinecraftServer server, UUID playerId, int delta) {
        DailyData data = DailyData.get(server);
        DailyData.Entry entry = data.getEntry(playerId);
        long last = entry == null ? 0L : entry.lastClaimDay; // 0 = ne met pas en "deja reclame"
        int newStreak = Math.max(0, (entry == null ? 0 : entry.streak) + delta);
        data.setEntry(playerId, last, newStreak);
        return newStreak;
    }

    /** Convertit une entree "modid:item quantite" en ItemStack d'affichage (AIR si invalide). */
    public static ItemStack specToStack(String spec) {
        if (spec == null) {
            return ItemStack.EMPTY;
        }
        String[] parts = spec.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(parts[0]);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        int count = 1;
        if (parts.length >= 2) {
            try {
                count = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id), count);
    }

    /** Construit une entree de config "modid:item quantite" a partir d'un ItemStack. */
    public static String stackToSpec(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id + " " + stack.getCount();
    }

    private static List<Milestone> parseMilestones() {
        List<Milestone> result = new ArrayList<>();
        for (String raw : Config.DAILY_STREAK_MILESTONES.get()) {
            String[] fields = raw.split("\\|", -1);
            String dayToken = fields[0].trim();
            boolean periodic = dayToken.startsWith("*");
            if (periodic) {
                dayToken = dayToken.substring(1).trim();
            }
            int day;
            try {
                day = Integer.parseInt(dayToken);
            } catch (NumberFormatException e) {
                continue;
            }
            List<String> items = fields.length > 1 ? splitList(fields[1]) : List.of();
            List<String> commands = fields.length > 2 ? splitList(fields[2]) : List.of();
            result.add(new Milestone(day, periodic, items, commands));
        }
        return result;
    }

    private static List<String> splitList(String field) {
        List<String> out = new ArrayList<>();
        for (String part : field.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Donne tous les items decrits par la liste de specs. Renvoie le nombre d'entrees distribuees. */
    private static int giveItems(ServerPlayer player, List<? extends String> itemSpecs) {
        int given = 0;
        for (String spec : itemSpecs) {
            String trimmed = spec.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            ResourceLocation id = ResourceLocation.tryParse(parts[0]);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                UtopiaMod.LOGGER.warn("[Utopia] Item de recompense inconnu, ignore : {}", parts[0]);
                continue;
            }
            int count = 1;
            if (parts.length >= 2) {
                try {
                    count = Math.max(1, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            giveStacked(player, item, count);
            given++;
        }
        return given;
    }

    /** Donne {@code count} exemplaires de l'item en respectant la taille de pile maximale. */
    private static void giveStacked(ServerPlayer player, Item item, int count) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, maxStack);
            ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(item, chunk));
            remaining -= chunk;
        }
    }

    /** Execute les commandes serveur, en remplacant {player} par le pseudo, avec permission 4. */
    private static void runCommands(MinecraftServer server, ServerPlayer player, List<? extends String> commands) {
        if (commands.isEmpty()) {
            return;
        }
        CommandSourceStack source = server.createCommandSourceStack()
                .withEntity(player)
                .withPermission(4)
                .withSuppressedOutput();
        String name = player.getGameProfile().getName();
        for (String cmd : commands) {
            String trimmed = cmd.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String finalCmd = trimmed.replace("{player}", name);
            try {
                server.getCommands().performPrefixedCommand(source, finalCmd);
            } catch (Exception e) {
                UtopiaMod.LOGGER.error("[Utopia] Echec de la commande de recompense '{}'", finalCmd, e);
            }
        }
    }
}
