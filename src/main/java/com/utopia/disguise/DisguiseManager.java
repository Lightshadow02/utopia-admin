package com.utopia.disguise;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.properties.Property;
import com.utopia.data.DisguiseData;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le deguisement d'un joueur : son nom d'emprunt et le visage qu'il porte.
 *
 * <p>Le nom passe par les evenements de NeoForge et ne coute rien. Le <b>visage</b>, lui, ne se
 * change pas aussi simplement : le client dessine un joueur a partir de la propriete "textures" de
 * son profil, recue une seule fois a la connexion. Changer le profil ne suffit donc pas, il faut
 * demander aux autres clients d'oublier ce joueur puis de le redecouvrir - d'ou le retrait et le
 * renvoi de sa ligne dans la liste des joueurs, suivis d'un retrait et d'un renvoi de son entite.
 *
 * <p>Le porteur, lui, ne voit pas son propre changement tout de suite : son modele a lui est deja
 * charge. Il le verra a sa prochaine connexion, et le deguisement est reapplique a ce moment-la.
 */
public final class DisguiseManager {

    private DisguiseManager() {
    }

    // ------------------------------------------------------------------ Nom

    /** Longueur maximale d'un nom d'emprunt. Au-dela, le paquet des plaques refuserait de partir. */
    public static final int MAX_NOM = 32;

    public static void setNom(ServerPlayer player, String nom) {
        DisguiseData data = DisguiseData.get(player.server);
        DisguiseData.Disguise d = data.getOrCreate(player.getUUID());
        // Le plafond est pose ici et non a l'appelant : la commande, le pupitre de /dieux et tout ce
        // qui viendra ensuite doivent tomber sur la meme limite, sans quoi le chat et la plaque
        // finiraient par afficher deux noms differents.
        String propre = nom == null ? "" : nom.trim();
        d.nom = propre.length() > MAX_NOM ? propre.substring(0, MAX_NOM) : propre;
        data.nettoyer(player.getUUID());
        data.setDirty();
        rafraichirNom(player);
    }

    public static void clearNom(ServerPlayer player) {
        setNom(player, "");
    }

    /**
     * Fait relire le nom par les autres clients. La liste des joueurs porte un nom fige a la
     * connexion : sans ce renvoi, le nouveau nom n'apparaitrait qu'au chat.
     */
    private static void rafraichirNom(ServerPlayer player) {
        player.refreshTabListName();
        MinecraftServer server = player.server;
        server.getPlayerList().broadcastAll(
                new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
        diffuserNoms(server);
    }

    /**
     * Renvoie a tout le monde la liste des noms d'emprunt, pour la plaque au-dessus des tetes.
     *
     * <p>La plaque est le seul endroit que le serveur ne sait pas corriger : le client la compose
     * avec le nom du profil, et changer ce nom-la deplacerait aussi /tp, /msg et les bannissements.
     * On lui dit donc quoi ecrire, et le serveur garde le vrai nom pour lui.
     */
    public static void diffuserNoms(MinecraftServer server) {
        DisguiseData data = DisguiseData.get(server);
        java.util.Map<UUID, String> noms = new java.util.LinkedHashMap<>();
        // Seuls les connectes ont une plaque a dessiner. Parcourir la sauvegarde entiere enverrait a
        // tout le monde le pseudo de chaque joueur passe par la, et la liste ne ferait que grossir.
        for (ServerPlayer present : server.getPlayerList().getPlayers()) {
            DisguiseData.Disguise d = data.get(present.getUUID());
            if (d != null && d.aUnNom()) {
                // Le paquet refuse au-dela de 32 caracteres, et il part a tout le monde a la fois :
                // un nom trop long venu d'une ancienne sauvegarde couperait la partie de chacun.
                String nom = d.nom;
                noms.put(present.getUUID(), nom.length() > MAX_NOM ? nom.substring(0, MAX_NOM) : nom);
            }
        }
        com.utopia.net.MenuS2CPayload paquet =
                com.utopia.net.MenuS2CPayload.of(new com.utopia.net.NicknamesPayload(noms));
        for (ServerPlayer autre : server.getPlayerList().getPlayers()) {
            // Un client sans le mod n'a pas ce canal : lui ecrire dessus couperait sa connexion.
            if (autre.connection.hasChannel(com.utopia.net.MenuS2CPayload.TYPE)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(autre, paquet);
            }
        }
    }

    // ------------------------------------------------------------------ Visage

    /**
     * Fait porter a {@code player} le visage de {@code modele}. Le skin est copie sur-le-champ :
     * le modele peut ensuite se deconnecter ou changer de skin sans rien changer au deguisement.
     */
    public static void setSkin(ServerPlayer player, ServerPlayer modele) {
        String[] textures = com.utopia.entity.NpcSkins.capture(modele);
        appliquer(player, textures[0], textures[1], modele.getGameProfile().getName());
    }

    /**
     * Fait porter a {@code player} un visage deja recupere ailleurs - typiquement celui d'un joueur
     * qui n'est pas connecte, ramene depuis Mojang par {@link com.utopia.entity.NpcSkins#fetchParNom}.
     */
    public static void setSkin(ServerPlayer player, String value, String signature, String source) {
        appliquer(player, value, signature, source);
    }

    public static void clearSkin(ServerPlayer player) {
        appliquer(player, "", "", "");
    }

    private static void appliquer(ServerPlayer player, String value, String signature, String source) {
        DisguiseData data = DisguiseData.get(player.server);
        DisguiseData.Disguise d = data.getOrCreate(player.getUUID());
        d.skinValue = value == null ? "" : value;
        d.skinSignature = signature == null ? "" : signature;
        d.skinSource = source == null ? "" : source;
        data.nettoyer(player.getUUID());
        data.setDirty();
        poserSurLeProfil(player, d);
        rafraichirVisage(player);
    }

    /**
     * Ecrit la propriete "textures" dans le profil. Quand le deguisement tombe, on remet celle que
     * Mojang avait envoyee a la connexion : la retirer sans la remplacer laisserait le joueur en
     * Steve plutot que dans son vrai skin.
     */
    private static void poserSurLeProfil(ServerPlayer player, DisguiseData.Disguise d) {
        var props = player.getGameProfile().getProperties();
        String[] vrai = vraiSkin(player);
        props.removeAll("textures");
        if (d != null && d.aUnSkin()) {
            props.put("textures", new Property("textures", d.skinValue,
                    d.skinSignature.isEmpty() ? null : d.skinSignature));
        } else if (!vrai[0].isEmpty()) {
            props.put("textures", new Property("textures", vrai[0],
                    vrai[1].isEmpty() ? null : vrai[1]));
        }
    }

    /**
     * Le skin d'origine, mis de cote a la premiere connexion. Il ne peut pas etre relu du profil
     * une fois le deguisement pose, puisque le deguisement l'a justement remplace.
     */
    private static final java.util.Map<UUID, String[]> SKINS_ORIGINE = new java.util.HashMap<>();

    private static String[] vraiSkin(ServerPlayer player) {
        return SKINS_ORIGINE.getOrDefault(player.getUUID(), new String[] {"", ""});
    }

    /**
     * Demande aux autres clients d'oublier ce joueur puis de le redecouvrir. Le modele d'un joueur
     * deja affiche ne change pas de texture tout seul : il faut que l'entite disparaisse et
     * reapparaisse pour que le nouveau profil soit lu.
     */
    private static void rafraichirVisage(ServerPlayer player) {
        MinecraftServer server = player.server;
        // Le modele est reconstruit a partir de rien : il faut lui rendre sa pose, son equipement et
        // l'orientation de sa tete, sinon il reapparait nu, droit et le regard au nord.
        var metadonnees = player.getEntityData().getNonDefaultValues();
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,
                net.minecraft.world.item.ItemStack>> equipement = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot emplacement
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            equipement.add(com.mojang.datafixers.util.Pair.of(emplacement,
                    player.getItemBySlot(emplacement).copy()));
        }
        byte tete = (byte) Math.floor(player.getYHeadRot() * 256.0F / 360.0F);

        for (ServerPlayer autre : server.getPlayerList().getPlayers()) {
            if (autre == player) {
                continue; // son propre modele est deja charge : il le verra a sa prochaine connexion
            }
            autre.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
            autre.connection.send(
                    ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
            // L'entite n'est renvoyee qu'a ceux qui la voient deja : l'envoyer a un joueur a l'autre
            // bout du monde poserait chez lui une entite hors de ses chunks charges.
            if (autre.level() != player.level() || autre.distanceToSqr(player) >= 96 * 96) {
                continue;
            }
            autre.connection.send(new ClientboundRemoveEntitiesPacket(player.getId()));
            autre.connection.send(new ClientboundAddEntityPacket(player, 0, player.blockPosition()));
            if (!metadonnees.isEmpty()) {
                autre.connection.send(new net.minecraft.network.protocol.game
                        .ClientboundSetEntityDataPacket(player.getId(), metadonnees));
            }
            autre.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundSetEquipmentPacket(player.getId(), equipement));
            autre.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundRotateHeadPacket(player, tete));
            // L'entite est reconstruite a neuf : elle a perdu le lien qui l'assoit dans sa barque ou
            // sur son cheval. Sans ce rappel, le cavalier reste plante a cote de sa monture.
            if (player.getVehicle() != null) {
                autre.connection.send(new net.minecraft.network.protocol.game
                        .ClientboundSetPassengersPacket(player.getVehicle()));
            }
        }
    }

    // ------------------------------------------------------------------ Connexion

    /**
     * A la connexion : on met de cote le vrai skin, puis on repose le deguisement. Le profil est
     * reconstruit par Mojang a chaque connexion, le deguisement tomberait sans cela.
     */
    public static void onLogin(ServerPlayer player) {
        String[] textures = com.utopia.entity.NpcSkins.capture(player);
        SKINS_ORIGINE.put(player.getUUID(), textures);

        DisguiseData.Disguise d = DisguiseData.get(player.server).get(player.getUUID());
        if (d == null) {
            return;
        }
        if (d.aUnSkin()) {
            poserSurLeProfil(player, d);
        }
        if (d.aUnNom()) {
            player.refreshTabListName();
        }
    }

    /**
     * Une fois le joueur en jeu : on renvoie les noms d'emprunt a tout le monde. L'arrivant doit
     * apprendre ceux des autres, et les autres le sien.
     */
    public static void onJoined(ServerPlayer player) {
        diffuserNoms(player.server);
    }

    public static void onLogout(ServerPlayer player) {
        SKINS_ORIGINE.remove(player.getUUID());
    }

    /** Le nom d'emprunt a afficher, ou nul si ce joueur n'en porte pas. */
    public static Component nomDemprunt(ServerPlayer player) {
        DisguiseData.Disguise d = DisguiseData.get(player.server).get(player.getUUID());
        return d != null && d.aUnNom() ? Component.literal(d.nom) : null;
    }
}
