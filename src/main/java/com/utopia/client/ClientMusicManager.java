package com.utopia.client;

import java.util.List;
import java.util.Random;

import com.utopia.sound.UtopiaSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Lecteur de musique personnalise (cote client). Remplace la musique de fond vanilla par les pistes du
 * serveur, choisies selon le contexte (jour / nuit / grotte), avec une pause aleatoire entre chaque
 * morceau (a la maniere de Minecraft).
 *
 * <p>La musique vanilla en jeu est supprimee via {@link SelectMusicEvent} ; la lecture est geree
 * manuellement sur le tick client pour controler l'ordre et les pauses.
 */
public final class ClientMusicManager {

    /** Pause entre deux morceaux (en ticks) : ~1 a 2,5 minutes. */
    private static final int MIN_PAUSE_TICKS = 1200;
    private static final int MAX_PAUSE_TICKS = 3000;
    /** Petite pause avant le tout premier morceau a l'arrivee dans le monde. */
    private static final int INITIAL_PAUSE_TICKS = 80;
    /** Delai minimal avant de considerer qu'un morceau est termine (evite les faux positifs au demarrage). */
    private static final int START_GRACE_TICKS = 20;

    private static final Random RNG = new Random();

    private static SimpleSoundInstance current;
    private static DeferredHolder<SoundEvent, SoundEvent> currentTrack;
    private static int pauseTicks;
    private static int playedTicks;
    private static boolean tookOver;

    private ClientMusicManager() {
    }

    /**
     * Supprime la musique vanilla quand on est en jeu (on gere la notre a la place).
     * IMPORTANT : il faut mettre la musique a {@code null} (et NON annuler l'event) : c'est la valeur
     * null qui force le MusicManager a stopper la musique en cours et a ne rien jouer. Annuler ne fait
     * qu'empecher les autres listeners et laisse jouer la musique vanilla.
     */
    public static void onSelectMusic(SelectMusicEvent event) {
        if (Minecraft.getInstance().level != null) {
            event.setMusic(null);
        }
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAndReset(mc);
            return;
        }

        if (!tookOver) {
            // On prend la main : coupe une eventuelle musique vanilla restante (menu, etc.).
            mc.getMusicManager().stopPlaying();
            tookOver = true;
            pauseTicks = INITIAL_PAUSE_TICKS;
            current = null;
            playedTicks = 0;
        }

        if (current != null) {
            playedTicks++;
            if (playedTicks < START_GRACE_TICKS || mc.getSoundManager().isActive(current)) {
                return; // joue encore
            }
            // Morceau termine -> pause aleatoire avant le suivant.
            current = null;
            pauseTicks = MIN_PAUSE_TICKS + RNG.nextInt(MAX_PAUSE_TICKS - MIN_PAUSE_TICKS + 1);
            return;
        }

        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }

        playNext(mc);
    }

    private static void playNext(Minecraft mc) {
        List<DeferredHolder<SoundEvent, SoundEvent>> playlist = playlistFor(mc.level, mc.player.blockPosition());
        if (playlist.isEmpty()) {
            pauseTicks = MIN_PAUSE_TICKS; // rien a jouer : on retente plus tard
            return;
        }
        DeferredHolder<SoundEvent, SoundEvent> track = pick(playlist);
        currentTrack = track;
        current = SimpleSoundInstance.forMusic(track.get());
        playedTicks = 0;
        mc.getSoundManager().play(current);
    }

    /** Choisit une piste au hasard en evitant de rejouer la meme deux fois de suite. */
    private static DeferredHolder<SoundEvent, SoundEvent> pick(List<DeferredHolder<SoundEvent, SoundEvent>> playlist) {
        if (playlist.size() == 1) {
            return playlist.get(0);
        }
        DeferredHolder<SoundEvent, SoundEvent> track;
        do {
            track = playlist.get(RNG.nextInt(playlist.size()));
        } while (track == currentTrack);
        return track;
    }

    /** Selection du contexte : grotte si sous terre, sinon nuit/jour selon l'heure. */
    private static List<DeferredHolder<SoundEvent, SoundEvent>> playlistFor(ClientLevel level, BlockPos pos) {
        boolean underground = !level.canSeeSky(pos) && pos.getY() < level.getSeaLevel();
        if (underground) {
            return UtopiaSounds.GROTTE;
        }
        long dayTime = level.getDayTime() % 24000L;
        boolean night = dayTime >= 13000L && dayTime < 23000L;
        return night ? UtopiaSounds.NUIT : UtopiaSounds.JOUR;
    }

    private static void stopAndReset(Minecraft mc) {
        if (current != null) {
            mc.getSoundManager().stop(current);
        }
        current = null;
        currentTrack = null;
        pauseTicks = 0;
        playedTicks = 0;
        tookOver = false;
    }
}
