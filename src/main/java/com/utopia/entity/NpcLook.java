package com.utopia.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Oriente un PNJ vers le joueur le plus proche : il "suit du regard" les gens qui passent, ce qui
 * rend les stands et les marchands beaucoup plus vivants qu'une statue figee.
 */
public final class NpcLook {

    /** Distance au-dela de laquelle le PNJ ne suit plus personne. */
    public static final double RANGE = 8.0;

    private NpcLook() {
    }

    /**
     * Tourne le PNJ vers le joueur le plus proche dans un rayon de {@link #RANGE} blocs.
     * Sans joueur a portee, l'orientation courante est conservee (le PNJ ne "revient" pas au neutre).
     */
    public static void faceNearestPlayer(LivingEntity npc) {
        if (npc.level().isClientSide) {
            return;
        }
        Player target = npc.level().getNearestPlayer(npc, RANGE);
        if (target == null || !target.isAlive()) {
            return;
        }
        double dx = target.getX() - npc.getX();
        double dz = target.getZ() - npc.getZ();
        double dy = target.getEyeY() - npc.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-4) {
            return; // joueur pile au-dessus / en dessous : pas d'orientation utile
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Mth.atan2(dy, flat) * (180.0 / Math.PI)));

        npc.setYRot(yaw);
        npc.setXRot(pitch);
        npc.yBodyRot = yaw;
        npc.yHeadRot = yaw;
        npc.setYHeadRot(yaw);
    }
}
