package com.utopia.net;

import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Ecran "progression" : un titre, un texte de presentation, puis une liste de barres de progression
 * dessinees graphiquement (partie remplie / partie restante), avec un bouton par ligne.
 *
 * <p>Sert aux chantiers communautaires ; concu pour rester generique.
 */
public record OpenProgressPayload(int sessionId, Component title, List<Component> intro,
                                  List<Bar> bars, List<Action> footer,
                                  int refreshId, int backId) implements CustomPacketPayload {

    /**
     * Une barre : icone, libelle, valeurs, et couleur de remplissage. {@code big} donne la barre
     * mise en avant (Utopieces), {@code actionId} vaut -1 si la ligne n'est pas cliquable.
     */
    public record Bar(ItemStack icon, Component label, int current, int required,
                      boolean done, boolean big, int color, int actionId, Component buttonLabel) {
    }

    /** Un bouton du pied de page. */
    public record Action(int id, Component label) {
    }

    public static final Type<OpenProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenProgressPayload> STREAM_CODEC =
            StreamCodec.of(OpenProgressPayload::encode, OpenProgressPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenProgressPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        buf.writeVarInt(p.intro.size());
        for (Component line : p.intro) {
            ComponentSerialization.STREAM_CODEC.encode(buf, line);
        }
        buf.writeVarInt(p.bars.size());
        for (Bar bar : p.bars) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, bar.icon());
            ComponentSerialization.STREAM_CODEC.encode(buf, bar.label());
            buf.writeVarInt(bar.current());
            buf.writeVarInt(bar.required());
            buf.writeBoolean(bar.done());
            buf.writeBoolean(bar.big());
            buf.writeInt(bar.color());
            buf.writeVarInt(bar.actionId());
            ComponentSerialization.STREAM_CODEC.encode(buf, bar.buttonLabel());
        }
        buf.writeVarInt(p.footer.size());
        for (Action action : p.footer) {
            buf.writeVarInt(action.id());
            ComponentSerialization.STREAM_CODEC.encode(buf, action.label());
        }
        buf.writeVarInt(p.refreshId);
        buf.writeVarInt(p.backId);
    }

    private static OpenProgressPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        int introCount = buf.readVarInt();
        List<Component> intro = new java.util.ArrayList<>(introCount);
        for (int i = 0; i < introCount; i++) {
            intro.add(ComponentSerialization.STREAM_CODEC.decode(buf));
        }
        int barCount = buf.readVarInt();
        List<Bar> bars = new java.util.ArrayList<>(barCount);
        for (int i = 0; i < barCount; i++) {
            bars.add(new Bar(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), ComponentSerialization.STREAM_CODEC.decode(buf),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    buf.readInt(), buf.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buf)));
        }
        int footerCount = buf.readVarInt();
        List<Action> footer = new java.util.ArrayList<>(footerCount);
        for (int i = 0; i < footerCount; i++) {
            footer.add(new Action(buf.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buf)));
        }
        return new OpenProgressPayload(sessionId, title, intro, bars, footer, buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
