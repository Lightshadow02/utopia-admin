package com.utopia.entity;

import com.utopia.bet.BetMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Bookmaker d'un pari : un clic droit ouvre le pari qu'il tient. C'est le seul moyen de consulter ou
 * de rejoindre un pari — il faut se rendre sur place, aupres du PNJ.
 */
public class BookmakerNpc extends AbstractNpc {

    public BookmakerNpc(EntityType<? extends BookmakerNpc> type, Level level) {
        super(type, level);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        BetMenus.openBookmaker(sp, ownerKey());
        return InteractionResult.CONSUME;
    }
}
