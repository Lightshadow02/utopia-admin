package com.utopia.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * PNJ purement decoratif : une statue vivante a l'effigie d'un joueur. Il ne fait rien au clic,
 * c'est le but — il habite un lieu sans rien reclamer.
 */
public class DecorNpc extends AbstractNpc {

    public DecorNpc(EntityType<? extends DecorNpc> type, Level level) {
        super(type, level);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
}
