package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S : la partie est finie, le client annonce son score. */
public record ArcadeScorePayload(int sessionId, long score) implements CustomPacketPayload {

    public static final Type<ArcadeScorePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "arcade_score"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeScorePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.sessionId);
                        buf.writeLong(p.score);
                    },
                    buf -> new ArcadeScorePayload(buf.readVarInt(), buf.readLong()));

    @Override
    public Type<ArcadeScorePayload> type() {
        return TYPE;
    }
}
