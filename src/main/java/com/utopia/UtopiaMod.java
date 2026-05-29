package com.utopia;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Point d'entree du mod "Utopia Essentials".
 *
 * <p>Ce mod regroupe plusieurs "plugins" cote serveur pour un serveur NeoForge 1.21.1 :
 * <ul>
 *     <li>Recompenses quotidiennes parametrables ({@code /daily}) avec systeme de serie (streak).</li>
 *     <li>Demandes de teleportation entre joueurs ({@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpadeny}).</li>
 *     <li>Gestion du spawn ({@code /spawn}, {@code /setspawn}).</li>
 * </ul>
 */
@Mod(UtopiaMod.MODID)
public final class UtopiaMod {
    /** Identifiant du mod, doit correspondre a {@code mod_id} dans gradle.properties. */
    public static final String MODID = "utopia_admin";

    /** Logger partage du mod. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Version du mod (lue depuis les metadonnees), utilisee par la banniere de demarrage. */
    public static String VERSION = "?";

    public UtopiaMod(IEventBus modEventBus, ModContainer modContainer) {
        // Enregistre la configuration commune (fichier config/utopia-common.toml).
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        VERSION = modContainer.getModInfo().getVersion().toString();
        LOGGER.info("[Utopia] Initialisation du pack d'essentials serveur.");
    }
}
