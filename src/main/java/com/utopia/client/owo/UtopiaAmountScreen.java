package com.utopia.client.owo;

import com.utopia.net.AmountResultPayload;
import com.utopia.net.MenuC2SPayload;
import com.utopia.net.MenuClickPayload;
import com.utopia.net.OpenAmountPayload;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Ecran owo de saisie d'un montant : champ numerique a remplir + boutons Confirmer / Annuler. */
public class UtopiaAmountScreen extends BaseOwoScreen<FlowLayout> {

    private final OpenAmountPayload data;
    private boolean closeSent = false;

    public UtopiaAmountScreen(OpenAmountPayload data) {
        super(data.title());
        this.data = data;
    }

    public int sessionId() {
        return data.sessionId();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout panel = Containers.verticalFlow(Sizing.content(), Sizing.content());
        panel.surface(OwoStyle.PANEL);
        panel.padding(Insets.of(14));
        panel.gap(8);
        panel.horizontalAlignment(HorizontalAlignment.CENTER);

        FlowLayout titleBar = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        titleBar.surface(OwoStyle.HEADER);
        titleBar.padding(Insets.of(6));
        titleBar.horizontalAlignment(HorizontalAlignment.CENTER);
        titleBar.child(Components.label(data.title()).shadow(true));
        panel.child(titleBar);

        for (Component line : data.info()) {
            panel.child(Components.label(line));
        }

        // Champ de saisie (numerique).
        TextBoxComponent field = Components.textBox(Sizing.fixed(110), Long.toString(data.defaultValue()));
        field.setMaxLength(12);
        field.setFilter(s -> s.isEmpty() || s.matches("\\d{1,12}"));
        panel.child((io.wispforest.owo.ui.core.Component) field);

        panel.child(Components.label(Component.literal("Entre " + data.min() + " et " + data.max())
                .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false))));

        FlowLayout buttons = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        buttons.gap(6);
        buttons.horizontalAlignment(HorizontalAlignment.CENTER);
        buttons.child(button(data.confirmLabel(), () -> confirm(field)));
        buttons.child(button(Component.literal("Annuler").withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)),
                this::onClose));
        panel.child(buttons);

        root.child(panel);
    }

    private FlowLayout button(Component label, Runnable action) {
        FlowLayout b = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        b.padding(Insets.of(6));
        b.surface(OwoStyle.BTN);
        b.cursorStyle(CursorStyle.POINTER);
        b.horizontalAlignment(HorizontalAlignment.CENTER);
        b.verticalAlignment(VerticalAlignment.CENTER);
        b.child(Components.label(label));
        b.mouseEnter().subscribe(() -> b.surface(OwoStyle.BTN_HOVER));
        b.mouseLeave().subscribe(() -> b.surface(OwoStyle.BTN));
        b.mouseDown().subscribe((mouseX, mouseY, mouseButton) -> {
            action.run();
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        });
        return b;
    }

    private void confirm(TextBoxComponent field) {
        long value;
        try {
            value = Long.parseLong(field.getValue().trim());
        } catch (NumberFormatException e) {
            value = data.defaultValue();
        }
        value = Math.max(data.min(), Math.min(data.max(), value));
        PacketDistributor.sendToServer(MenuC2SPayload.of(new AmountResultPayload(data.sessionId(), value)));
        // Le serveur ouvrira le menu suivant (qui remplacera cet ecran).
    }

    @Override
    public void removed() {
        super.removed();
        if (!closeSent) {
            closeSent = true;
            PacketDistributor.sendToServer(MenuC2SPayload.of(new MenuClickPayload(data.sessionId(), -1, 0)));
        }
    }
}
