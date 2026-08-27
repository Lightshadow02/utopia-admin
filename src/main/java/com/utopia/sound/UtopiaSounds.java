package com.utopia.sound;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.utopia.UtopiaMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Enregistrement des musiques personnalisees du serveur, regroupees par contexte (jour / nuit / grotte).
 *
 * <p>La liste n'est pas ecrite ici : elle est lue dans {@code music_index.txt}, genere au build par la
 * tache Gradle {@code syncMusic} a partir du dossier prive {@code /Music}. Ajouter un morceau se
 * resume donc a deposer le .ogg dans {@code Music/JOUR}, {@code Music/NUIT} ou {@code Music/Grotte}
 * puis a reconstruire : le fichier est recopie sous un nom normalise, declare dans {@code sounds.json}
 * et enregistre ici automatiquement.
 *
 * <p>La lecture est pilotee cote client par {@code ClientMusicManager}.
 */
public final class UtopiaSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, UtopiaMod.MODID);

    private static final String INDEX = "/assets/" + UtopiaMod.MODID + "/music_index.txt";

    /** Playlists par contexte, dans l'ordre de l'index (alphabetique). */
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> JOUR;
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> NUIT;
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> GROTTE;

    static {
        List<DeferredHolder<SoundEvent, SoundEvent>> jour = new ArrayList<>();
        List<DeferredHolder<SoundEvent, SoundEvent>> nuit = new ArrayList<>();
        List<DeferredHolder<SoundEvent, SoundEvent>> grotte = new ArrayList<>();
        for (String name : readIndex()) {
            DeferredHolder<SoundEvent, SoundEvent> track = reg(name);
            if (name.startsWith("music.jour.")) {
                jour.add(track);
            } else if (name.startsWith("music.nuit.")) {
                nuit.add(track);
            } else if (name.startsWith("music.grotte.")) {
                grotte.add(track);
            }
        }
        JOUR = List.copyOf(jour);
        NUIT = List.copyOf(nuit);
        GROTTE = List.copyOf(grotte);
        UtopiaMod.LOGGER.info("[Utopia] Musiques : {} jour, {} nuit, {} grotte.",
                JOUR.size(), NUIT.size(), GROTTE.size());
    }

    private UtopiaSounds() {
    }

    /**
     * Lit l'index genere au build. Un doublon serait refuse par le registre : on ne garde donc que la
     * premiere occurrence de chaque nom. Index absent (depot sans les musiques privees) = aucune piste,
     * ce que le lecteur client sait deja gerer.
     */
    private static Set<String> readIndex() {
        Set<String> names = new LinkedHashSet<>();
        try (InputStream in = UtopiaSounds.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                UtopiaMod.LOGGER.warn("[Utopia] Index des musiques introuvable ({}) : aucune piste.", INDEX);
                return names;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            UtopiaMod.LOGGER.error("[Utopia] Lecture de l'index des musiques impossible.", e);
        }
        return names;
    }

    private static DeferredHolder<SoundEvent, SoundEvent> reg(String name) {
        return SOUNDS.register(name, () ->
                SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
