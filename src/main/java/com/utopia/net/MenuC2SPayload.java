package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S multiplexe : un seul canal reseau pour tous les paquets client -&gt; serveur lies aux menus
 * (clic sur un slot, montant saisi, texte saisi). Un octet {@code kind} indique la variante ;
 * les codecs des records internes sont reutilises tels quels.
 *
 * @see MenuS2CPayload
 */
public record MenuC2SPayload(int kind, CustomPacketPayload data) implements CustomPacketPayload {

    public static final int CLICK = 0;
    public static final int AMOUNT = 1;
    public static final int TEXT = 2;

    public static final Type<MenuC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "menu_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuC2SPayload> STREAM_CODEC =
            StreamCodec.of(MenuC2SPayload::encode, MenuC2SPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, MenuC2SPayload p) {
        buf.writeByte(p.kind);
        switch (p.kind) {
            case CLICK -> MenuClickPayload.STREAM_CODEC.encode(buf, (MenuClickPayload) p.data);
            case AMOUNT -> AmountResultPayload.STREAM_CODEC.encode(buf, (AmountResultPayload) p.data);
            case TEXT -> TextResultPayload.STREAM_CODEC.encode(buf, (TextResultPayload) p.data);
            default -> throw new IllegalStateException("Variante C2S inconnue : " + p.kind);
        }
    }

    private static MenuC2SPayload decode(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        CustomPacketPayload data = switch (kind) {
            case CLICK -> MenuClickPayload.STREAM_CODEC.decode(buf);
            case AMOUNT -> AmountResultPayload.STREAM_CODEC.decode(buf);
            case TEXT -> TextResultPayload.STREAM_CODEC.decode(buf);
            default -> throw new IllegalStateException("Variante C2S inconnue : " + kind);
        };
        return new MenuC2SPayload(kind, data);
    }

    public static MenuC2SPayload of(MenuClickPayload p) {
        return new MenuC2SPayload(CLICK, p);
    }

    public static MenuC2SPayload of(AmountResultPayload p) {
        return new MenuC2SPayload(AMOUNT, p);
    }

    public static MenuC2SPayload of(TextResultPayload p) {
        return new MenuC2SPayload(TEXT, p);
    }

    @Override
    public Type<MenuC2SPayload> type() {
        return TYPE;
    }
}
