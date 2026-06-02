package com.utopia.client;

import com.utopia.gui.UtopiaMenuType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Initialisation cote client : associe l'ecran custom au MenuType d'Utopia.
 * Appele uniquement sur le client (garde {@code Dist.CLIENT} dans le constructeur du mod).
 */
public final class UtopiaClient {

    private UtopiaClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(UtopiaClient::onRegisterScreens);
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(UtopiaMenuType.UTOPIA.get(), UtopiaScreen::new);
    }
}
