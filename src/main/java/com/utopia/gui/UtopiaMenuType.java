package com.utopia.gui;

import com.utopia.UtopiaMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Enregistrement du {@link MenuType} custom du mod, pour pouvoir lui associer un ecran cote client. */
public final class UtopiaMenuType {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, UtopiaMod.MODID);

    /**
     * Type de menu d'Utopia. La fabrique cote client lit le nombre de rangees dans le buffer
     * d'ouverture (le contenu des slots est synchronise par le mecanisme vanilla des conteneurs).
     */
    public static final DeferredHolder<MenuType<?>, MenuType<UtopiaMenu>> UTOPIA =
            MENUS.register("utopia_menu", () ->
                    IMenuTypeExtension.create((windowId, inv, buf) -> new UtopiaMenu(windowId, inv, buf.readByte())));

    private UtopiaMenuType() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
