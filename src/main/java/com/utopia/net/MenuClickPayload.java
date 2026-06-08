package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S : le client signale un clic sur un slot du menu owo (ou sa fermeture si slot &lt; 0).
 * {@code button} : 0 = clic gauche, 1 = clic droit.
 */
public record MenuClickPayload(int sessionId, int slot, int button) implements CustomPacketPayload {

    public static final Type<MenuClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "menu_click"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuClickPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.sessionId);
                        buf.writeInt(p.slot);
                        buf.writeByte(p.button);
                    },
                    buf -> new MenuClickPayload(buf.readVarInt(), buf.readInt(), buf.readByte()));

    @Override
    public Type<MenuClickPayload> type() {
        return TYPE;
    }
}
