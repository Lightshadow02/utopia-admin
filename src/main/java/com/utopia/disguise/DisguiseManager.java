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

    /** Le nom sous lequel ce joueur apparait, ou son vrai nom s'il n'est pas deguise. */
    public static String nomAffiche(ServerPlayer player) {
        DisguiseData.Disguise d = DisguiseData.get(player.server).get(player.getUUID());
        return d != null && d.aUnNom() ? d.nom : player.getGameProfile().getName();
    }

    public static void setNom(ServerPlayer player, String nom) {
        DisguiseData data = DisguiseData.get(player.server);
        DisguiseData.Disguise d = data.getOrCreate(player.getUUID());
        d.nom = nom == null ? "" : nom.trim();
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

    public static void onLogout(ServerPlayer player) {
        SKINS_ORIGINE.remove(player.getUUID());
    }

    /** Le nom d'emprunt a afficher, ou nul si ce joueur n'en porte pas. */
    public static Component nomDemprunt(ServerPlayer player) {
        DisguiseData.Disguise d = DisguiseData.get(player.server).get(player.getUUID());
        return d != null && d.aUnNom() ? Component.literal(d.nom) : null;
    }
}
