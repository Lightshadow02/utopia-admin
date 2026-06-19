package com.utopia.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.utopia.net.ProxyMessaging;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * API publique et stable pour piloter un proxy <b>Velocity</b> (ou BungeeCord) depuis le backend :
 * principalement deplacer un joueur vers un autre serveur du reseau. Concue pour etre appelee depuis
 * KubeJS via {@code Java.loadClass('com.utopia.api.UtopiaProxyAPI')}.
 *
 * <p><b>Pre-requis :</b> le serveur tourne derriere un proxy Velocity/BungeeCord, le serveur cible est
 * declare dans la config du proxy, et le mod utopia-admin est present (il enregistre le canal
 * {@code bungeecord:main}). Les messages sont envoyes via la connexion du joueur et interceptes par le proxy.
 */
public final class UtopiaProxyAPI {

    private UtopiaProxyAPI() {
    }

    /** Deplace le joueur vers le serveur backend nomme {@code serverName} (tel que declare dans le proxy). */
    public static void connectToServer(ServerPlayer player, String serverName) {
        send(player, "Connect", serverName);
    }

    /** Deplace, par pseudo (joueur en ligne), vers {@code serverName}. Renvoie false si le joueur est absent. */
    public static boolean connectToServer(MinecraftServer server, String playerName, String serverName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            return false;
        }
        connectToServer(player, serverName);
        return true;
    }

    /**
     * Envoi generique d'un message sur le canal proxy : un sous-canal (ex. "Connect", "Message",
     * "ConnectOther"...) suivi de ses arguments, encodes facon BungeeCord ({@code writeUTF}).
     */
    public static void sendBungee(ServerPlayer player, String subChannel, String... args) {
        String[] fields = new String[args.length + 1];
        fields[0] = subChannel;
        System.arraycopy(args, 0, fields, 1, args.length);
        send(player, fields);
    }

    private static void send(ServerPlayer player, String... fields) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String f : fields) {
                out.writeUTF(f);
            }
        } catch (IOException e) {
            throw new RuntimeException("Echec d'encodage du message proxy", e);
        }
        ProxyMessaging.send(player, bytes.toByteArray());
    }
}
