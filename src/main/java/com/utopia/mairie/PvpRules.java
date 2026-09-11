package com.utopia.mairie;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.MairieData;
import com.utopia.data.ParcelData;
import com.utopia.parcel.ParcelManager;
import com.utopia.util.Messages;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le droit du duel dans les parcelles, tel que le maire l'a fixe.
 *
 * <p>La question est posee a chaque coup porte, y compris a chaque fleche et a chaque tic de poison :
 * c'est un chemin chaud. Quand le PVP est autorise, on repond avant d'avoir ouvert la moindre donnee
 * de parcelle — la recherche de parcelle balaie toutes les zones du serveur.
 */
public final class PvpRules {

    /** Un rappel toutes les deux secondes au plus : un arc tire plus vite que ca. */
    private static final long WARN_COOLDOWN_MS = 2000L;

    private static final Map<UUID, Long> lastWarn = new HashMap<>();

    private PvpRules() {
    }

    /**
     * Ce coup entre joueurs doit-il etre empeche ? La position retenue est celle de la victime :
     * sinon il suffirait de tirer depuis le trottoir pour vider une parcelle de ses occupants.
     */
    public static boolean blocked(ServerPlayer attacker, ServerPlayer victim) {
        if (attacker == null || victim == null || attacker.getUUID().equals(victim.getUUID())) {
            return false;
        }
        if (!com.utopia.Config.MAIRIE_PVP.get()
                || MairieData.get(attacker.server).pvpInParcels()) {
            return false; // autorise (ou module retire) : on ne touche pas aux parcelles
        }
        if (ParcelManager.canBypass(attacker)) {
            return false;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return false;
        }
        net.minecraft.core.BlockPos pos = victim.blockPosition();
        net.minecraft.resources.ResourceLocation dim = level.dimension().location();
        // Les chambres d'auberge priment sur les parcelles partout ailleurs dans le mod, et une
        // chambre n'est pas toujours posee sur une parcelle : sans ce test, la piece qu'un joueur
        // loue serait le seul endroit du bourg ou l'on peut le frapper.
        if (com.utopia.data.RoomData.get(attacker.server)
                .roomAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null) {
            return true;
        }
        return ParcelData.get(attacker.server).parcelAt(dim,
                pos.getX(), pos.getY(), pos.getZ()) != null;
    }

    /**
     * Le joueur a qui imputer ce degat. Un loup apprivoise ou un golem frappe en son nom propre :
     * sans remonter a son maitre, il suffirait de lancer sa meute pour contourner l'interdit.
     */
    public static ServerPlayer attackerOf(net.minecraft.world.damagesource.DamageSource source) {
        if (source == null) {
            return null;
        }
        if (source.getEntity() instanceof ServerPlayer direct) {
            return direct;
        }
        // On s'arrete au maitre d'une bete apprivoisee. Remonter plus loin, jusqu'a celui qui a
        // enerve un golem ou un zombie, accuserait un tiers : "le dernier qui a frappe ce mob" est
        // souvent le camarade qui combattait a cote, et son coup n'a rien a voir avec la victime.
        if (source.getEntity() instanceof net.minecraft.world.entity.OwnableEntity pet
                && pet.getOwner() instanceof ServerPlayer owner) {
            return owner;
        }
        return null;
    }

    /**
     * Previent l'attaquant, sans repeter. Le meme coup passe par deux evenements (le coup porte puis
     * le degat recu) et un arc en tire plusieurs par seconde : sans ce delai, le chat deborde.
     */
    public static void warn(ServerPlayer attacker) {
        long now = System.currentTimeMillis();
        Long previous = lastWarn.get(attacker.getUUID());
        if (previous != null && now - previous < WARN_COOLDOWN_MS) {
            return;
        }
        lastWarn.put(attacker.getUUID(), now);
        // La carte des rappels ne doit pas grandir indefiniment sur un serveur qui tourne des mois.
        if (lastWarn.size() > 256) {
            lastWarn.entrySet().removeIf(e -> now - e.getValue() > WARN_COOLDOWN_MS * 10);
        }
        attacker.sendSystemMessage(Messages.error(
                "La mairie interdit le PVP dans les parcelles."));
    }
}
