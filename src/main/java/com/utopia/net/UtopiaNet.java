package com.utopia.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Enregistrement des paquets reseau du mod (menus owo).
 * Appele sur le bus mod, des deux cotes (les paquets doivent exister client ET serveur).
 */
public final class UtopiaNet {

    private UtopiaNet() {
    }

    public static void onRegister(RegisterPayloadHandlersEvent event) {
        // Version "2" depuis la variante NICKNAMES. Elle part des la connexion, sans attendre qu'on
        // ouvre un menu : un client reste sur l'ancien jar ne saurait pas la lire et tomberait sur
        // une erreur interne en pleine partie. La version fait refuser la negociation d'entree, avec
        // un ecran qui dit ce qui se passe.
        PayloadRegistrar registrar = event.registrar("2");

        // Deux canaux multiplexes seulement (au lieu de 7) : un S2C et un C2S. Chaque paquet porte
        // un octet "kind" qui indique la variante reelle (ouverture de menu, montant, texte, clic...).
        // Cela reduit l'empreinte du mod sur la negociation reseau de NeoForge dans les gros modpacks.

        // S2C : ouverture / fermeture de menu, saisie de montant / texte (traite cote client).
        registrar.playToClient(MenuS2CPayload.TYPE, MenuS2CPayload.STREAM_CODEC, (payload, context) -> {
            switch (payload.kind()) {
                case MenuS2CPayload.OPEN_MENU ->
                        com.utopia.client.owo.OwoMenuClient.handleOpen((OpenMenuPayload) payload.data(), context);
                case MenuS2CPayload.CLOSE ->
                        com.utopia.client.owo.OwoMenuClient.handleClose((CloseMenuPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_AMOUNT ->
                        com.utopia.client.owo.OwoMenuClient.handleAmountPrompt((OpenAmountPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_TEXT ->
                        com.utopia.client.owo.OwoMenuClient.handleTextPrompt((OpenTextPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_HUB ->
                        com.utopia.client.owo.OwoMenuClient.handleHub((OpenHubPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_DAILY ->
                        com.utopia.client.owo.OwoMenuClient.handleDaily((OpenDailyPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_PANEL ->
                        com.utopia.client.owo.OwoMenuClient.handlePanel((OpenPanelPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_PROGRESS ->
                        com.utopia.client.owo.OwoMenuClient.handleProgress((OpenProgressPayload) payload.data(), context);
                case MenuS2CPayload.OPEN_TABLE ->
                        com.utopia.client.owo.OwoMenuClient.handleTable((OpenTablePayload) payload.data(), context);
                case MenuS2CPayload.OPEN_ARCADE ->
                        com.utopia.client.owo.OwoMenuClient.handleArcade((OpenArcadePayload) payload.data(), context);
                case MenuS2CPayload.NICKNAMES ->
                        com.utopia.client.ClientNicknames.handle((NicknamesPayload) payload.data(), context);
                default -> { /* variante inconnue : ignore */ }
            }
        });

        // C2S : clic / fermeture / montant / texte saisi envoye par le client.
        registrar.playToServer(MenuC2SPayload.TYPE, MenuC2SPayload.STREAM_CODEC, (payload, context) -> {
            // Un clic de menu et un score d'arcade sont des signes de vie que Minecraft ne compte
            // pas : ils n'arrivent que par nos propres paquets. Pendant une partie, en revanche, la
            // borne n'envoie rien du tout - c'est le score de fin de partie qui fait foi.
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.utopia.afk.AfkManager.reveiller(sp);
            }
            switch (payload.kind()) {
                case MenuC2SPayload.CLICK -> OwoMenuServer.handleClick((MenuClickPayload) payload.data(), context);
                case MenuC2SPayload.AMOUNT -> OwoMenuServer.handleAmount((AmountResultPayload) payload.data(), context);
                case MenuC2SPayload.TEXT -> OwoMenuServer.handleText((TextResultPayload) payload.data(), context);
                case MenuC2SPayload.ARCADE_SCORE ->
                        com.utopia.casino.CasinoManager.handleScore((ArcadeScorePayload) payload.data(), context);
                default -> { /* variante inconnue : ignore */ }
            }
        });

        // Canal proxy Velocity/BungeeCord (bungeecord:main) : changement de serveur via l'API (KubeJS).
        ProxyMessaging.register(registrar);
    }
}
