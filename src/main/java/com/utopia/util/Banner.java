package com.utopia.util;

import com.utopia.UtopiaMod;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Affiche une banniere ASCII recapitulant les modules au demarrage du serveur. */
public final class Banner {

    private static final String[] ART = {
            "  _   _ _____ ___  ____ ___    _    ",
            " | | | |_   _/ _ \\|  _ \\_ _|  / \\   ",
            " | | | | | || | | | |_) | |  / _ \\  ",
            " | |_| | | || |_| |  __/| | / ___ \\ ",
            "  \\___/  |_| \\___/|_|  |___/_/   \\_\\ ",
    };

    private Banner() {
    }

    public static void print(MinecraftServer server) {
        Logger log = UtopiaMod.LOGGER;
        log.info("");
        for (String line : ART) {
            log.info(line);
        }
        log.info("   utopia-admin  v{}", UtopiaMod.VERSION);
        log.info("   Running on NeoForge / MC {} / Java {}",
                server.getServerVersion(), System.getProperty("java.version"));
        log.info("");
        log.info("   Modules:");
        log.info("    [+] Teleportation OK ");
        log.info("    [+] Daily Rewards OK ");
        log.info("    [+] Clear-Lag     OK ");
        log.info("    [+] Economie      OK ");
        log.info("    [+] Parcelles     OK ");
        log.info("");
        log.info("   Boot done. Bon jeu !");
        log.info("");
    }
}
