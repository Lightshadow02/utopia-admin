package com.utopia.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Oriente un PNJ vers le joueur le plus proche : il "suit du regard" les gens qui passent, ce qui
 * rend les stands, marchands, capitaines et PNJ de chantier beaucoup plus vivants qu'une statue figee.
 *
 * <p>La rotation est lissee : le PNJ tourne progressivement au lieu de se braquer d'un coup, ce qui
 * evite l'effet saccade quand un joueur passe vite ou que la cible change.
 */
public final class NpcLook {

    /** Distance au-dela de laquelle le PNJ ne suit plus personne. */
    public static final double RANGE = 8.0;
    /** Rotation maximale par tick (degres) : au-dela, le mouvement parait mecanique. */
    private static final float MAX_STEP = 12.0f;

    private NpcLook() {
    }

    /**
     * Tourne le PNJ vers le joueur le plus proche dans un rayon de {@link #RANGE} blocs. Sans joueur
     * a portee, l'orientation courante est conservee.
     */
    public static void faceNearestPlayer(LivingEntity npc) {
        faceNearestPlayer(npc, null);
    }

    /**
     * Comme {@link #faceNearestPlayer(LivingEntity)}, mais revient a une orientation de repos quand
     * plus personne n'est a portee.
     *
     * @param restYaw orientation de repos en degres, ou {@code null} pour garder l'orientation courante
     */
    public static void faceNearestPlayer(LivingEntity npc, Float restYaw) {
        if (npc.level().isClientSide) {
            return;
        }
        Player target = npc.level().getNearestPlayer(npc, RANGE);
        if (target == null || !target.isAlive()) {
            if (restYaw != null) {
                turnTowards(npc, restYaw, 0.0f);
            }
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
        turnTowards(npc, yaw, pitch);
    }

    /** Rapproche progressivement l'orientation du PNJ de la cible (rotation lissee). */
    private static void turnTowards(LivingEntity npc, float targetYaw, float targetPitch) {
        float yaw = approach(npc.getYRot(), targetYaw);
        float pitch = approach(npc.getXRot(), targetPitch);
        npc.setYRot(yaw);
        npc.setXRot(pitch);
        npc.yBodyRot = yaw;
        npc.yHeadRot = yaw;
        npc.setYHeadRot(yaw);
    }

    /** Avance d'au plus {@link #MAX_STEP} degres vers l'angle vise, par le chemin le plus court. */
    private static float approach(float current, float target) {
        float delta = Mth.wrapDegrees(target - current);
        return current + Mth.clamp(delta, -MAX_STEP, MAX_STEP);
    }
}
