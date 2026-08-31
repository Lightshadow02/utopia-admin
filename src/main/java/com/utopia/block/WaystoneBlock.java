package com.utopia.block;

import com.utopia.data.WaystoneData;
import com.utopia.waystone.WaystoneManager;
import com.utopia.waystone.WaystoneMenus;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Balise de voyage : une stele que l'on touche pour rejoindre celles que l'on a deja trouvees.
 *
 * <p>Elle n'a aucune recette : elle ne s'obtient que des mains de l'administration. Un reseau de
 * deplacement se distribue, il ne se fabrique pas — sans quoi il n'aurait plus de valeur.
 */
public class WaystoneBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<WaystoneBlock> CODEC = simpleCodec(WaystoneBlock::new);

    /** Socle large, stele plus etroite : la forme suit le modele affiche. */
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(2, 0, 2, 14, 3, 14),
            Block.box(4, 3, 4, 12, 16, 12));

    public WaystoneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // La rune regarde celui qui pose la balise.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof ServerPlayer player) {
            WaystoneManager.onPlaced(server, pos, player);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               net.minecraft.world.entity.player.Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        WaystoneManager.onUsed(sp, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            // La balise disparait du reseau en meme temps que du monde : personne ne doit pouvoir
            // viser un point ou il n'y a plus rien.
            WaystoneManager.onBroken(server, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Ouvre le menu d'une balise, pour le bloc comme pour les menus d'administration. */
    public static void open(ServerPlayer player, WaystoneData.Waystone stone) {
        WaystoneMenus.openStone(player, stone.id);
    }
}
