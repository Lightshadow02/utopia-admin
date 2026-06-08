package com.utopia.client.owo;

import com.utopia.net.CloseMenuPayload;
import com.utopia.net.OpenAmountPayload;
import com.utopia.net.OpenMenuPayload;
import com.utopia.net.OpenTextPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Cote client : recoit les paquets de menu owo et ouvre/ferme l'ecran correspondant. */
public final class OwoMenuClient {

    private OwoMenuClient() {
    }

    public static void handleOpen(OpenMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new UtopiaOwoMenuScreen(payload)));
    }

    public static void handleClose(CloseMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof UtopiaOwoMenuScreen screen && screen.sessionId() == payload.sessionId()) {
                mc.setScreen(null);
            }
        });
    }

    public static void handleAmountPrompt(OpenAmountPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new UtopiaAmountScreen(payload)));
    }

    public static void handleTextPrompt(OpenTextPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new UtopiaTextScreen(payload)));
    }
}
