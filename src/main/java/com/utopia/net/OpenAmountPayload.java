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
 * S2C : ouvre un ecran de saisie de montant (champ a remplir) cote client.
 * Contient le titre, des lignes d'info, le libelle du bouton de confirmation, et la valeur
 * par defaut + bornes min/max.
 */
public record OpenAmountPayload(int sessionId, Component title, List<Component> info, Component confirmLabel,
                                long defaultValue, long min, long max) implements CustomPacketPayload {

    public static final Type<OpenAmountPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_amount"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> INFO =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAmountPayload> STREAM_CODEC =
            StreamCodec.of(OpenAmountPayload::encode, OpenAmountPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenAmountPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        INFO.encode(buf, p.info);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.confirmLabel);
        buf.writeLong(p.defaultValue);
        buf.writeLong(p.min);
        buf.writeLong(p.max);
    }

    private static OpenAmountPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        List<Component> info = INFO.decode(buf);
        Component confirmLabel = ComponentSerialization.STREAM_CODEC.decode(buf);
        long def = buf.readLong();
        long min = buf.readLong();
        long max = buf.readLong();
        return new OpenAmountPayload(sessionId, title, info, confirmLabel, def, min, max);
    }

    @Override
    public Type<OpenAmountPayload> type() {
        return TYPE;
    }
}
