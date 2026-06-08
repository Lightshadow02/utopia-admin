package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S : le client renvoie le texte saisi (confirmation). */
public record TextResultPayload(int sessionId, String value) implements CustomPacketPayload {

    public static final Type<TextResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "text_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TextResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.sessionId);
                        buf.writeUtf(p.value);
                    },
                    buf -> new TextResultPayload(buf.readVarInt(), buf.readUtf()));

    @Override
    public Type<TextResultPayload> type() {
        return TYPE;
    }
}
