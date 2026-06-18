package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.utopia.inventory.InventoryManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Commande /inv (alias /inventaire) : bascule entre l'inventaire 1 et l'inventaire 2.
 * Reservee aux operateurs. Pratique pour conserver son inventaire de survie avant de passer en creatif.
 */
public final class InventoryCommand {

    private InventoryCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(Commands.literal("inv")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("1").executes(ctx -> switchTo(ctx, 1)))
                .then(Commands.literal("2").executes(ctx -> switchTo(ctx, 2))));
        dispatcher.register(Commands.literal("inventaire")
                .requires(s -> s.hasPermission(2))
                .redirect(node));
    }

    private static int switchTo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        InventoryManager.switchTo(ctx.getSource().getPlayerOrException(), target);
        return Command.SINGLE_SUCCESS;
    }
}
