package com.utopia.net;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C : la liste des noms d'emprunt en cours, pour la plaque affichee au-dessus des tetes.
 *
 * <p>La plaque n'est pas dessinee a partir de ce que dit le serveur : le client la compose lui-meme
 * avec le nom du profil qu'il a recu a la connexion, et le serveur n'a aucune prise dessus. Le seul
 * moyen honnete de la faire mentir est donc de dire au client quel nom porter.
 *
 * <p>La liste entiere est renvoyee a chaque changement plutot que la difference : elle ne contient
 * que les joueurs deguises, elle tient dans quelques dizaines d'octets, et une liste complete ne
 * peut pas se desynchroniser.
 */
public record NicknamesPayload(Map<UUID, String> noms) implements CustomPacketPayload {

    /** Longueur retenue a l'encodage. Elle suit celle acceptee par /nick. */
    private static final int MAX_NOM = 32;

    public static final Type<NicknamesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "nicknames"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NicknamesPayload> STREAM_CODEC =
            StreamCodec.of(NicknamesPayload::encode, NicknamesPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, NicknamesPayload p) {
        buf.writeVarInt(p.noms.size());
        for (Map.Entry<UUID, String> e : p.noms.entrySet()) {
            buf.writeUUID(e.getKey());
            buf.writeUtf(e.getValue(), MAX_NOM);
        }
    }

    private static NicknamesPayload decode(RegistryFriendlyByteBuf buf) {
        // Borne de bon sens : la liste ne contient que les joueurs connectes et deguises. Un entete
        // abime ne doit pas faire reserver de la place pour un million d'entrees avant d'echouer.
        int n = Math.min(buf.readVarInt(), 1000);
        Map<UUID, String> noms = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            noms.put(id, buf.readUtf(MAX_NOM));
        }
        return new NicknamesPayload(noms);
    }

    @Override
    public Type<NicknamesPayload> type() {
        return TYPE;
    }
}
