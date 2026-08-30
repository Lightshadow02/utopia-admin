package com.utopia.entity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.utopia.UtopiaMod;

import net.minecraft.resources.ResourceLocation;

/**
 * Catalogue des skins de PNJ embarques dans le mod ({@code assets/utopia_admin/textures/entity/npc}).
 *
 * <p>Le serveur ne peut pas parcourir les assets du client : la liste est donc lue dans un index
 * genere au build ({@code npc_skins.txt}), accessible des deux cotes via le classpath.
 *
 * <p>Un skin du pack est stocke sous la forme {@code pack:<nom>} dans le champ "skin" du PNJ, ce qui
 * le distingue d'un skin de joueur (propriete "textures" encodee en base64).
 */
public final class NpcSkins {

    /** Prefixe identifiant un skin du pack embarque. */
    public static final String PREFIX = "pack:";

    private static final String INDEX = "/assets/" + UtopiaMod.MODID + "/npc_skins.txt";
    private static List<String> names;

    private NpcSkins() {
    }

    /** Tous les skins disponibles, tries. Charge une seule fois. */
    public static synchronized List<String> all() {
        if (names != null) {
            return names;
        }
        List<String> loaded = new ArrayList<>();
        try (InputStream in = NpcSkins.class.getResourceAsStream(INDEX)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String name = line.trim();
                        if (!name.isEmpty()) {
                            loaded.add(name);
                        }
                    }
                }
            } else {
                UtopiaMod.LOGGER.warn("[Utopia] Index des skins de PNJ introuvable ({}).", INDEX);
            }
        } catch (Exception e) {
            UtopiaMod.LOGGER.error("[Utopia] Lecture de l'index des skins de PNJ impossible.", e);
        }
        names = List.copyOf(loaded);
        return names;
    }

    /** Skins dont le nom contient {@code query} (recherche insensible a la casse). */
    public static List<String> search(String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : all()) {
            if (name.contains(q)) {
                out.add(name);
            }
        }
        return out;
    }

    public static boolean exists(String name) {
        return name != null && all().contains(name);
    }

    /** Valeur a stocker dans le PNJ pour ce skin du pack. */
    public static String value(String name) {
        return PREFIX + name;
    }

    /** Vrai si cette valeur de skin designe un skin du pack (et non un skin de joueur). */
    public static boolean isPack(String skinValue) {
        return skinValue != null && skinValue.startsWith(PREFIX);
    }

    /** Nom du skin du pack contenu dans cette valeur, ou null. */
    public static String nameOf(String skinValue) {
        return isPack(skinValue) ? skinValue.substring(PREFIX.length()) : null;
    }

    /** Texture correspondante, ou null si la valeur ne designe pas un skin du pack. */
    public static ResourceLocation texture(String skinValue) {
        String name = nameOf(skinValue);
        if (name == null || name.isEmpty()) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "textures/entity/npc/" + name + ".png");
    }

    /** Nom lisible : "adolf_hitler1" -> "Adolf hitler1". */
    /**
     * Copie la propriete "textures" d'un joueur : {@code [valeur, signature]}, ou deux chaines vides
     * si le serveur tourne en mode hors ligne. C'est une <b>copie</b>, pas une reference : le PNJ
     * garde ce visage quand le joueur se deconnecte ou change de skin.
     */
    public static String[] capture(net.minecraft.server.level.ServerPlayer player) {
        for (com.mojang.authlib.properties.Property prop
                : player.getGameProfile().getProperties().get("textures")) {
            return new String[] { prop.value(), prop.signature() == null ? "" : prop.signature() };
        }
        return new String[] { "", "" };
    }

    public static String label(String name) {
        String pretty = name.replace('_', ' ').trim();
        return pretty.isEmpty() ? name : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
    }
}
