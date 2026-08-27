package com.utopia.entity;

import com.utopia.transit.TransitMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Capitaine Transit : le PNJ qui fait embarquer les joueurs entre Utopia et le continent de
 * ressources. Un clic droit affiche l'une de ses repliques, puis ouvre l'interface d'embarquement
 * correspondant a son mode (quatre caps depuis Utopia, retour simple depuis le continent).
 */
public class TransitNpc extends AbstractNpc {

    public TransitNpc(EntityType<? extends TransitNpc> type, Level level) {
        super(type, level);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        TransitMenus.onInteract(sp, ownerKey());
        return InteractionResult.CONSUME;
    }
}
