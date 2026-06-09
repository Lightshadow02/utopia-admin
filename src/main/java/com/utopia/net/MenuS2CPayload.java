package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C multiplexe : un seul canal reseau pour tous les paquets serveur -&gt; client lies aux menus
 * (ouverture de menu, fermeture, saisie de montant, saisie de texte). Un octet {@code kind}
 * indique la variante ; les codecs des records internes sont reutilises tels quels.
 *
 * <p>But : reduire le nombre de canaux reseau enregistres par le mod (1 au lieu de 4), afin de
 * limiter l'empreinte du mod sur la negociation reseau de NeoForge dans les gros modpacks.
 */
public record MenuS2CPayload(int kind, CustomPacketPayload data) implements CustomPacketPayload {

    public static final int OPEN_MENU = 0;
    public static final int CLOSE = 1;
    public static final int OPEN_AMOUNT = 2;
    public static final int OPEN_TEXT = 3;
    public static final int OPEN_HUB = 4;

    public static final Type<MenuS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "menu_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuS2CPayload> STREAM_CODEC =
            StreamCodec.of(MenuS2CPayload::encode, MenuS2CPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, MenuS2CPayload p) {
        buf.writeByte(p.kind);
        switch (p.kind) {
            case OPEN_MENU -> OpenMenuPayload.STREAM_CODEC.encode(buf, (OpenMenuPayload) p.data);
            case CLOSE -> CloseMenuPayload.STREAM_CODEC.encode(buf, (CloseMenuPayload) p.data);
            case OPEN_AMOUNT -> OpenAmountPayload.STREAM_CODEC.encode(buf, (OpenAmountPayload) p.data);
            case OPEN_TEXT -> OpenTextPayload.STREAM_CODEC.encode(buf, (OpenTextPayload) p.data);
            case OPEN_HUB -> OpenHubPayload.STREAM_CODEC.encode(buf, (OpenHubPayload) p.data);
            default -> throw new IllegalStateException("Variante S2C inconnue : " + p.kind);
        }
    }

    private static MenuS2CPayload decode(RegistryFriendlyByteBuf buf) {
        int kind = buf.readByte();
        CustomPacketPayload data = switch (kind) {
            case OPEN_MENU -> OpenMenuPayload.STREAM_CODEC.decode(buf);
            case CLOSE -> CloseMenuPayload.STREAM_CODEC.decode(buf);
            case OPEN_AMOUNT -> OpenAmountPayload.STREAM_CODEC.decode(buf);
            case OPEN_TEXT -> OpenTextPayload.STREAM_CODEC.decode(buf);
            case OPEN_HUB -> OpenHubPayload.STREAM_CODEC.decode(buf);
            default -> throw new IllegalStateException("Variante S2C inconnue : " + kind);
        };
        return new MenuS2CPayload(kind, data);
    }

    public static MenuS2CPayload of(OpenMenuPayload p) {
        return new MenuS2CPayload(OPEN_MENU, p);
    }

    public static MenuS2CPayload of(CloseMenuPayload p) {
        return new MenuS2CPayload(CLOSE, p);
    }

    public static MenuS2CPayload of(OpenAmountPayload p) {
        return new MenuS2CPayload(OPEN_AMOUNT, p);
    }

    public static MenuS2CPayload of(OpenTextPayload p) {
        return new MenuS2CPayload(OPEN_TEXT, p);
    }

    public static MenuS2CPayload of(OpenHubPayload p) {
        return new MenuS2CPayload(OPEN_HUB, p);
    }

    @Override
    public Type<MenuS2CPayload> type() {
        return TYPE;
    }
}
