package com.utopia.sound;

import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Enregistrement des musiques personnalisees du serveur, regroupees par contexte (jour / nuit / grotte).
 * Les fichiers .ogg sont dans {@code assets/utopia_admin/sounds/music/...} (embarques au build, prives).
 * La lecture est pilotee cote client par {@code ClientMusicManager}.
 */
public final class UtopiaSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, UtopiaMod.MODID);

    // -------- Jour --------
    public static final DeferredHolder<SoundEvent, SoundEvent> JOUR_CHEMIN_FORESTIER = reg("music.jour.chemin_forestier1");
    public static final DeferredHolder<SoundEvent, SoundEvent> JOUR_ROUTE_SURF_1 = reg("music.jour.route_surf_1");
    public static final DeferredHolder<SoundEvent, SoundEvent> JOUR_ROUTE_SURF_2 = reg("music.jour.route_surf_2");
    public static final DeferredHolder<SoundEvent, SoundEvent> JOUR_TEMPLE_CREPUSCULE = reg("music.jour.temple_du_crepuscule2");

    // -------- Nuit --------
    public static final DeferredHolder<SoundEvent, SoundEvent> NUIT_1 = reg("music.nuit.nuit_1");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUIT_2 = reg("music.nuit.nuit_2");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUIT_CLAIRIERE = reg("music.nuit.clairiere_secrete2");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUIT_SANCTUAIRE = reg("music.nuit.sanctuaire_etoile2");

    // -------- Grotte --------
    public static final DeferredHolder<SoundEvent, SoundEvent> GROTTE_CRISTALLINE = reg("music.grotte.grotte_cristalline");
    public static final DeferredHolder<SoundEvent, SoundEvent> GROTTE_CRISTALLINE_2 = reg("music.grotte.grotte_cristalline2");

    /** Playlists par contexte. */
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> JOUR =
            List.of(JOUR_CHEMIN_FORESTIER, JOUR_ROUTE_SURF_1, JOUR_ROUTE_SURF_2, JOUR_TEMPLE_CREPUSCULE);
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> NUIT =
            List.of(NUIT_1, NUIT_2, NUIT_CLAIRIERE, NUIT_SANCTUAIRE);
    public static final List<DeferredHolder<SoundEvent, SoundEvent>> GROTTE =
            List.of(GROTTE_CRISTALLINE, GROTTE_CRISTALLINE_2);

    private UtopiaSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> reg(String name) {
        return SOUNDS.register(name, () ->
                SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
