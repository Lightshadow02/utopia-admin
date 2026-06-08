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

        // S2C : ouverture / fermeture d'un menu owo (traite cote client uniquement).
        registrar.playToClient(OpenMenuPayload.TYPE, OpenMenuPayload.STREAM_CODEC,
                (payload, context) -> com.utopia.client.owo.OwoMenuClient.handleOpen(payload, context));
        registrar.playToClient(CloseMenuPayload.TYPE, CloseMenuPayload.STREAM_CODEC,
                (payload, context) -> com.utopia.client.owo.OwoMenuClient.handleClose(payload, context));
        registrar.playToClient(OpenAmountPayload.TYPE, OpenAmountPayload.STREAM_CODEC,
                (payload, context) -> com.utopia.client.owo.OwoMenuClient.handleAmountPrompt(payload, context));
        registrar.playToClient(OpenTextPayload.TYPE, OpenTextPayload.STREAM_CODEC,
                (payload, context) -> com.utopia.client.owo.OwoMenuClient.handleTextPrompt(payload, context));

        // C2S : clic / fermeture / montant / texte saisi envoye par le client.
        registrar.playToServer(MenuClickPayload.TYPE, MenuClickPayload.STREAM_CODEC,
                OwoMenuServer::handleClick);
        registrar.playToServer(AmountResultPayload.TYPE, AmountResultPayload.STREAM_CODEC,
                OwoMenuServer::handleAmount);
        registrar.playToServer(TextResultPayload.TYPE, TextResultPayload.STREAM_CODEC,
                OwoMenuServer::handleText);
    }
}
