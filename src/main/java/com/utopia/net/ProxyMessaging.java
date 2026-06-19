package com.utopia.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Messagerie "plugin" vers un proxy Velocity / BungeeCord, via le canal {@code bungeecord:main}.
 * Permet d'envoyer des sous-commandes (Connect, etc.) que le proxy intercepte (le client ne les voit
 * pas). Sert notamment a deplacer un joueur vers un autre serveur backend du reseau.
 *
 * <p>Le payload contient des octets bruts encodes facon BungeeCord ({@code DataOutputStream.writeUTF}),
 * ecrits tels quels (pas de prefixe de longueur supplementaire). Canal enregistre en {@code optional()}
 * pour ne jamais casser une connexion si l'autre cote ne l'a pas.
 */
public final class ProxyMessaging {

    private ProxyMessaging() {
    }

    /** Payload brut sur le canal {@code bungeecord:main}. */
    public record BungeePayload(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BungeePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("bungeecord", "main"));

        public static final StreamCodec<FriendlyByteBuf, BungeePayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> {
                    byte[] d = new byte[buf.readableBytes()];
                    buf.readBytes(d);
                    return new BungeePayload(d);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Enregistre le canal (clientbound, optionnel). Le handler client est vide : le proxy intercepte. */
    public static void register(PayloadRegistrar registrar) {
        registrar.optional().playToClient(BungeePayload.TYPE, BungeePayload.STREAM_CODEC, (payload, context) -> {
            // Intercepte par le proxy : si jamais ca arrive au client, on ignore.
        });
    }

    /** Envoie des octets bruts au proxy via la connexion du joueur. */
    public static void send(ServerPlayer player, byte[] data) {
        PacketDistributor.sendToPlayer(player, new BungeePayload(data));
    }
}
