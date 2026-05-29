package com.utopia.daily;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.utopia.UtopiaMod;

/**
 * Calendrier des recompenses quotidiennes : a chaque date (ISO {@code yyyy-MM-dd}) correspond une
 * liste d'items ("modid:item quantite"). Permet de planifier les recompenses plusieurs semaines /
 * mois a l'avance. Sauvegarde en local dans {@code config/utopia/daily_calendar.json}.
 */
public final class DailyCalendar {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** date ISO -> liste de specs d'items. TreeMap pour un fichier trie par date. */
    private final Map<String, List<String>> rewards = new TreeMap<>();
    private final Path path;

    private DailyCalendar(Path path) {
        this.path = path;
    }

    public static DailyCalendar load(Path path) {
        DailyCalendar cal = new DailyCalendar(path);
        try {
            if (!Files.exists(path)) {
                cal.save();
                UtopiaMod.LOGGER.info("[Utopia] daily_calendar.json cree : {}", path);
                return cal;
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                var root = GSON.fromJson(reader, com.google.gson.JsonObject.class);
                if (root != null) {
                    for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                        if (!e.getValue().isJsonArray()) {
                            continue;
                        }
                        List<String> specs = new ArrayList<>();
                        for (JsonElement el : e.getValue().getAsJsonArray()) {
                            try {
                                specs.add(el.getAsString());
                            } catch (Exception ignored) {
                                // entree invalide ignoree
                            }
                        }
                        if (!specs.isEmpty()) {
                            cal.rewards.put(e.getKey(), specs);
                        }
                    }
                }
            }
        } catch (Exception e) {
            UtopiaMod.LOGGER.error("[Utopia] Lecture de daily_calendar.json impossible.", e);
        }
        return cal;
    }

    public void save() {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(rewards, writer);
            }
        } catch (IOException e) {
            UtopiaMod.LOGGER.error("[Utopia] Ecriture de daily_calendar.json impossible.", e);
        }
    }

    /** Recompense prevue pour une date (liste vide si aucune). */
    public List<String> getReward(LocalDate date) {
        List<String> list = rewards.get(date.toString());
        return list == null ? List.of() : list;
    }

    /** Une recompense est-elle planifiee pour cette date ? */
    public boolean hasReward(LocalDate date) {
        List<String> list = rewards.get(date.toString());
        return list != null && !list.isEmpty();
    }

    /** Nombre d'items planifies pour cette date. */
    public int rewardSize(LocalDate date) {
        return getReward(date).size();
    }

    /** Nombre de jours ayant une recompense planifiee. */
    public int plannedDays() {
        return rewards.size();
    }

    /** Definit (ou efface si liste vide) la recompense d'une date, puis sauvegarde. */
    public void setReward(LocalDate date, List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            rewards.remove(date.toString());
        } else {
            rewards.put(date.toString(), new ArrayList<>(specs));
        }
        save();
    }
}
