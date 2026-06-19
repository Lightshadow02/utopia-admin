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
        PayloadRegistrar registrar = event.registrar("1");

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
                default -> { /* variante inconnue : ignore */ }
            }
        });

        // C2S : clic / fermeture / montant / texte saisi envoye par le client.
        registrar.playToServer(MenuC2SPayload.TYPE, MenuC2SPayload.STREAM_CODEC, (payload, context) -> {
            switch (payload.kind()) {
                case MenuC2SPayload.CLICK -> OwoMenuServer.handleClick((MenuClickPayload) payload.data(), context);
                case MenuC2SPayload.AMOUNT -> OwoMenuServer.handleAmount((AmountResultPayload) payload.data(), context);
                case MenuC2SPayload.TEXT -> OwoMenuServer.handleText((TextResultPayload) payload.data(), context);
                default -> { /* variante inconnue : ignore */ }
            }
        });

        // Canal proxy Velocity/BungeeCord (bungeecord:main) : changement de serveur via l'API (KubeJS).
        ProxyMessaging.register(registrar);
    }
}
