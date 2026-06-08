package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S : le client renvoie le montant saisi (confirmation). */
public record AmountResultPayload(int sessionId, long value) implements CustomPacketPayload {

    public static final Type<AmountResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "amount_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AmountResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.sessionId);
                        buf.writeLong(p.value);
                    },
                    buf -> new AmountResultPayload(buf.readVarInt(), buf.readLong()));

    @Override
    public Type<AmountResultPayload> type() {
        return TYPE;
    }
}
