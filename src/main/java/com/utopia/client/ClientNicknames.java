package com.utopia.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.utopia.net.NicknamesPayload;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Les noms d'emprunt, cote client, pour la plaque affichee au-dessus des tetes.
 *
 * <p>Le chat et la liste des joueurs se laissent corriger depuis le serveur. La plaque, non : le
 * client la compose a partir du nom du profil recu a la connexion, et ce nom ne peut pas etre
 * change sans deplacer aussi toutes les recherches par nom du serveur - /tp, /msg, les bannissements.
 * On laisse donc le serveur dire son vrai nom, et on corrige le seul endroit qui pose probleme.
 */
public final class ClientNicknames {

    private static final Map<UUID, String> NOMS = new HashMap<>();

    private ClientNicknames() {
    }

    public static void handle(NicknamesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            NOMS.clear();
            NOMS.putAll(payload.noms());
        });
    }

    /** Oublie tout en quittant la partie : le serveur suivant n'a pas les memes deguisements. */
    public static void oublier() {
        NOMS.clear();
    }

    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (NOMS.isEmpty() || !(event.getEntity() instanceof Player joueur)) {
            return;
        }
        String emprunt = NOMS.get(joueur.getUUID());
        if (emprunt != null && !emprunt.isEmpty()) {
            // On repasse par la mise en forme d'equipe : remplacer le contenu par un texte nu ferait
            // perdre au deguise la couleur et le prefixe que tous les autres gardent.
            event.setContent(net.minecraft.world.scores.PlayerTeam.formatNameForTeam(
                    joueur.getTeam(), Component.literal(emprunt)));
        }
    }
}
