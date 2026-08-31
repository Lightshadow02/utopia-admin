package com.utopia.block;

import com.utopia.UtopiaMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Blocs du mod. Aucun n'a de recette : ils se distribuent depuis l'administration. */
public final class UtopiaBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, UtopiaMod.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, UtopiaMod.MODID);

    public static final DeferredHolder<Block, WaystoneBlock> WAYSTONE = BLOCKS.register("waystone",
            () -> new WaystoneBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0f, 12.0f)   // solide sans etre increvable : une erreur reste rattrapable
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 7)  // les runes eclairent leur pied
                    .noOcclusion()));

    public static final DeferredHolder<Item, Item> WAYSTONE_ITEM = ITEMS.register("waystone",
            () -> new BlockItem(WAYSTONE.get(), new Item.Properties()));

    private UtopiaBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
