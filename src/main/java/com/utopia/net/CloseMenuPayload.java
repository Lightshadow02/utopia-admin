package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C : demande au client de fermer le menu owo (si la session correspond). */
public record CloseMenuPayload(int sessionId) implements CustomPacketPayload {

    public static final Type<CloseMenuPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "close_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseMenuPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, CloseMenuPayload::sessionId, CloseMenuPayload::new);

    @Override
    public Type<CloseMenuPayload> type() {
        return TYPE;
    }
}
