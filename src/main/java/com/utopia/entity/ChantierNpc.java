package com.utopia.entity;

import com.utopia.chantier.ChantierMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * PNJ d'un chantier communautaire : un clic droit ouvre la presentation du chantier, ses objectifs et
 * leur progression. Un op accroupi mains vides ouvre la configuration.
 */
public class ChantierNpc extends AbstractNpc {

    public ChantierNpc(EntityType<? extends ChantierNpc> type, Level level) {
        super(type, level);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (sp.isShiftKeyDown() && sp.hasPermissions(2) && sp.getMainHandItem().isEmpty()) {
            ChantierMenus.openAdminChantier(sp, ownerKey());
        } else {
            ChantierMenus.openChantier(sp, ownerKey());
        }
        return InteractionResult.CONSUME;
    }
}
