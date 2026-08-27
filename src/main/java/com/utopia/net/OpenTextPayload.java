package com.utopia.net;

import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C : ouvre un ecran de saisie de texte (champ a remplir) cote client.
 *
 * <p>{@code free} distingue les deux usages : un identifiant (lettres, chiffres, {@code _} et
 * {@code -}, sans espace) ou une phrase libre, accents et ponctuation compris, pour tout ce qui est
 * lu par un humain (objet d'un devis, designation d'une ligne, message).
 */
public record OpenTextPayload(int sessionId, Component title, List<Component> info, Component confirmLabel,
                              String defaultText, int maxLength, boolean free) implements CustomPacketPayload {

    public static final Type<OpenTextPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_text"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> INFO =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTextPayload> STREAM_CODEC =
            StreamCodec.of(OpenTextPayload::encode, OpenTextPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenTextPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        INFO.encode(buf, p.info);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.confirmLabel);
        buf.writeUtf(p.defaultText);
        buf.writeVarInt(p.maxLength);
        buf.writeBoolean(p.free);
    }

    private static OpenTextPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        List<Component> info = INFO.decode(buf);
        Component confirmLabel = ComponentSerialization.STREAM_CODEC.decode(buf);
        String defaultText = buf.readUtf();
        int maxLength = buf.readVarInt();
        boolean free = buf.readBoolean();
        return new OpenTextPayload(sessionId, title, info, confirmLabel, defaultText, maxLength, free);
    }

    @Override
    public Type<OpenTextPayload> type() {
        return TYPE;
    }
}
