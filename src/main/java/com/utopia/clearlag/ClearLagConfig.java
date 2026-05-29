package com.utopia.clearlag;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utopia.UtopiaMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Configuration JSON du systeme de nettoyage des objets au sol ("clear lag").
 * Fichier : {@code config/utopia/clearlag.json} (cree automatiquement avec des valeurs par defaut).
 *
 * <p>Le chargement est <b>defensif</b> : un champ manquant ou mal forme retombe sur sa valeur par
 * defaut (les champs valides sont conserves) et un avertissement est journalise, sans ecraser le
 * fichier de l'utilisateur.
 */
public final class ClearLagConfig {

    /** Valeur de retour signifiant "ne jamais supprimer". */
    public static final int PROTECTED = -1;

    /** Borne haute des secondes -> ticks pour eviter tout debordement entier. */
    private static final int MAX_INTERVAL_SECONDS = 86_400; // 1 jour

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // -------- Champs serialises (noms = cles JSON) --------

    public boolean enabled = true;
    public int defaultLifetimeSeconds = 300;
    public int scanIntervalSeconds = 5;
    public boolean protectNamedItems = true;
    public boolean broadcastOnClear = false;
    public String broadcastMessage = "&7%count% objet(s) au sol ont ete nettoyes.";
    public Map<String, Integer> perItemLifetimeSeconds = defaultPerItem();
    public List<String> neverDespawn = new ArrayList<>(List.of(
            "minecraft:nether_star",
            "minecraft:elytra",
            "minecraft:totem_of_undying",
            "minecraft:dragon_egg"));

    // -------- Structures normalisees (indexees par Item, non serialisees) --------

    private transient final Set<Item> neverDespawnItems = new HashSet<>();
    private transient final Map<Item, Integer> perItemTicks = new HashMap<>();

    private static Map<String, Integer> defaultPerItem() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:cobblestone", 30);
        m.put("minecraft:dirt", 30);
        m.put("minecraft:netherrack", 30);
        m.put("minecraft:cobbled_deepslate", 30);
        return m;
    }

    /** Construit les structures normalisees (indexees par Item) et borne les valeurs. */
    public void normalize() {
        if (scanIntervalSeconds < 1) {
            scanIntervalSeconds = 1;
        } else if (scanIntervalSeconds > MAX_INTERVAL_SECONDS) {
            scanIntervalSeconds = MAX_INTERVAL_SECONDS;
        }
        if (broadcastMessage == null) {
            broadcastMessage = "";
        }

        neverDespawnItems.clear();
        if (neverDespawn != null) {
            for (String entry : neverDespawn) {
                Item item = resolveItem(entry, "neverDespawn");
                if (item != null) {
                    neverDespawnItems.add(item);
                }
            }
        }

        perItemTicks.clear();
        if (perItemLifetimeSeconds != null) {
            for (Map.Entry<String, Integer> e : perItemLifetimeSeconds.entrySet()) {
                Item item = resolveItem(e.getKey(), "perItemLifetimeSeconds");
                if (item == null || e.getValue() == null) {
                    continue;
                }
                int seconds = e.getValue();
                perItemTicks.put(item, seconds <= 0 ? PROTECTED : secondsToTicks(seconds));
            }
        }
    }

    /** Convertit des secondes (> 0) en ticks sans deborder (cap a Integer.MAX_VALUE). */
    private static int secondsToTicks(int seconds) {
        long ticks = (long) seconds * 20L;
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** Resout un identifiant d'item en {@link Item}, ou nul (avec avertissement) si invalide/inconnu. */
    private static Item resolveItem(String raw, String section) {
        if (raw == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(raw.trim());
        if (rl == null) {
            UtopiaMod.LOGGER.warn("[Utopia] clearlag.json ({}): identifiant invalide '{}' ignore "
                    + "(les ids doivent etre en minuscules, ex. minecraft:diamond).", section, raw);
            return null;
        }
        if (!BuiltInRegistries.ITEM.containsKey(rl)) {
            UtopiaMod.LOGGER.warn("[Utopia] clearlag.json ({}): item inconnu '{}' ignore.", section, rl);
            return null;
        }
        return BuiltInRegistries.ITEM.get(rl);
    }

    /** Renvoie la duree de vie en ticks pour cet item, ou {@link #PROTECTED}. */
    public int lifetimeTicksForItem(Item item) {
        if (neverDespawnItems.contains(item)) {
            return PROTECTED;
        }
        Integer perItem = perItemTicks.get(item);
        if (perItem != null) {
            return perItem;
        }
        return defaultLifetimeSeconds <= 0 ? PROTECTED : secondsToTicks(defaultLifetimeSeconds);
    }

    public int scanIntervalTicks() {
        long ticks = (long) scanIntervalSeconds * 20L;
        if (ticks < 1) {
            ticks = 1;
        } else if (ticks > Integer.MAX_VALUE) {
            ticks = Integer.MAX_VALUE;
        }
        return (int) ticks;
    }

    // -------- Chargement / sauvegarde --------

    /**
     * Charge la configuration depuis {@code path}, en creant le fichier par defaut s'il n'existe pas.
     * Parsing defensif : un champ fautif retombe sur son defaut sans invalider le reste.
     */
    public static ClearLagConfig load(Path path) {
        ClearLagConfig cfg = new ClearLagConfig();
        try {
            if (!Files.exists(path)) {
                cfg.save(path);
                cfg.normalize();
                UtopiaMod.LOGGER.info("[Utopia] clearlag.json cree avec les valeurs par defaut : {}", path);
                return cfg;
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonObject.class);
            }
            if (root == null) {
                UtopiaMod.LOGGER.warn("[Utopia] clearlag.json vide ou invalide, valeurs par defaut utilisees.");
                cfg.normalize();
                return cfg;
            }
            cfg.enabled = getBool(root, "enabled", cfg.enabled);
            cfg.defaultLifetimeSeconds = getInt(root, "defaultLifetimeSeconds", cfg.defaultLifetimeSeconds);
            cfg.scanIntervalSeconds = getInt(root, "scanIntervalSeconds", cfg.scanIntervalSeconds);
            cfg.protectNamedItems = getBool(root, "protectNamedItems", cfg.protectNamedItems);
            cfg.broadcastOnClear = getBool(root, "broadcastOnClear", cfg.broadcastOnClear);
            cfg.broadcastMessage = getString(root, "broadcastMessage", cfg.broadcastMessage);
            cfg.perItemLifetimeSeconds = getIntMap(root, "perItemLifetimeSeconds", cfg.perItemLifetimeSeconds);
            cfg.neverDespawn = getStringList(root, "neverDespawn", cfg.neverDespawn);
        } catch (Exception e) {
            UtopiaMod.LOGGER.error("[Utopia] Lecture de clearlag.json impossible, valeurs par defaut utilisees.", e);
            cfg = new ClearLagConfig();
        }
        cfg.normalize();
        return cfg;
    }

    /** Ecrit la configuration courante sur le disque (cree les dossiers parents au besoin). */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    // -------- Helpers de parsing defensif --------

    private static boolean getBool(JsonObject o, String key, boolean def) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.getAsJsonPrimitive(key).getAsBoolean();
            }
        } catch (Exception e) {
            warnField(key);
        }
        return def;
    }

    private static int getInt(JsonObject o, String key, int def) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.getAsJsonPrimitive(key).getAsInt();
            }
        } catch (Exception e) {
            warnField(key);
        }
        return def;
    }

    private static String getString(JsonObject o, String key, String def) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.getAsJsonPrimitive(key).getAsString();
            }
        } catch (Exception e) {
            warnField(key);
        }
        return def;
    }

    private static Map<String, Integer> getIntMap(JsonObject o, String key, Map<String, Integer> def) {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            return def;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
            try {
                result.put(e.getKey(), e.getValue().getAsInt());
            } catch (Exception ex) {
                UtopiaMod.LOGGER.warn("[Utopia] clearlag.json ({}): valeur invalide pour '{}', entree ignoree.",
                        key, e.getKey());
            }
        }
        return result;
    }

    private static List<String> getStringList(JsonObject o, String key, List<String> def) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return def;
        }
        List<String> result = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(key)) {
            try {
                result.add(el.getAsString());
            } catch (Exception ex) {
                UtopiaMod.LOGGER.warn("[Utopia] clearlag.json ({}): entree de liste invalide ignoree.", key);
            }
        }
        return result;
    }

    private static void warnField(String key) {
        UtopiaMod.LOGGER.warn("[Utopia] clearlag.json: champ '{}' mal forme, valeur par defaut utilisee.", key);
    }
}
