package com.utopia.net;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C : lance une partie sur une borne d'arcade. La partie elle-meme se joue <b>entierement chez le
 * client</b> : le serveur n'envoie que ce paquet au depart et ne recoit que le score a l'arrivee.
 *
 * <p>C'est la seule facon de faire tourner un jeu a 60 images par seconde sans inonder le reseau,
 * et le modpack est deja au seuil de negociation de NeoForge. La contrepartie est que le score est
 * declare par le client : il est borne cote serveur, et rien d'autre qu'un rang au tableau n'en
 * depend.
 */
public record OpenArcadePayload(int sessionId, String gameId, Component title,
                                long bestScore, String bestHolder) implements CustomPacketPayload {

    public static final Type<OpenArcadePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_arcade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenArcadePayload> STREAM_CODEC =
            StreamCodec.of(OpenArcadePayload::encode, OpenArcadePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenArcadePayload p) {
        buf.writeVarInt(p.sessionId);
        ByteBufCodecs.STRING_UTF8.encode(buf, p.gameId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        buf.writeLong(p.bestScore);
        ByteBufCodecs.STRING_UTF8.encode(buf, p.bestHolder);
    }

    private static OpenArcadePayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        String gameId = ByteBufCodecs.STRING_UTF8.decode(buf);
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        long best = buf.readLong();
        String holder = ByteBufCodecs.STRING_UTF8.decode(buf);
        return new OpenArcadePayload(sessionId, gameId, title, best, holder);
    }

    @Override
    public Type<OpenArcadePayload> type() {
        return TYPE;
    }
}
