package com.utopia.client;

import com.utopia.client.owo.UtopiaDemoScreen;
import com.utopia.gui.UtopiaMenuType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Initialisation cote client : associe l'ecran custom au MenuType d'Utopia
 * et enregistre la commande de demo owo-ui.
 * Appele uniquement sur le client (garde {@code Dist.CLIENT} dans le constructeur du mod).
 */
public final class UtopiaClient {

    private UtopiaClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(UtopiaClient::onRegisterScreens);
        // RegisterClientCommandsEvent est sur le bus de jeu (pas le bus mod).
        NeoForge.EVENT_BUS.addListener(UtopiaClient::onRegisterClientCommands);
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(UtopiaMenuType.UTOPIA.get(), UtopiaScreen::new);
    }

    /** POC : /utopiaui ouvre l'ecran owo-ui de demonstration cote client. */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("utopiaui").executes(ctx -> {
                    Minecraft.getInstance().execute(
                            () -> Minecraft.getInstance().setScreen(new UtopiaDemoScreen()));
                    return 1;
                }));
    }
}
