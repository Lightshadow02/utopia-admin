package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.utopia.disguise.DisguiseManager;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /nick} et {@code /skin} : le nom et le visage d'emprunt.
 *
 * <p>Deux commandes de deguisement, donc deux commandes d'usurpation : elles restent fermees a qui
 * n'est pas operateur, et le pupitre de /dieux y donne acces sans passer par elles.
 */
public final class DisguiseCommands {

    private DisguiseCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nick")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("retirer")
                        .executes(ctx -> retirerNom(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("nom", StringArgumentType.greedyString())
                        .executes(ctx -> poserNom(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "nom")))));

        dispatcher.register(Commands.literal("skin")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("retirer")
                        .executes(ctx -> retirerSkin(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("modele", EntityArgument.player())
                        .executes(ctx -> poserSkin(ctx.getSource().getPlayerOrException(),
                                EntityArgument.getPlayer(ctx, "modele")))));
    }

    private static int poserNom(ServerPlayer player, String nom) {
        String propre = nom.trim();
        if (propre.isEmpty() || propre.length() > 32) {
            player.sendSystemMessage(Messages.error("Un nom de 1 a 32 caracteres."));
            return 0;
        }
        DisguiseManager.setNom(player, propre);
        player.sendSystemMessage(Messages.success("Tu apparais desormais sous le nom de "
                + propre + "."));
        return Command.SINGLE_SUCCESS;
    }

    private static int retirerNom(ServerPlayer player) {
        DisguiseManager.clearNom(player);
        player.sendSystemMessage(Messages.success("Tu reprends ton nom."));
        return Command.SINGLE_SUCCESS;
    }

    private static int poserSkin(ServerPlayer player, ServerPlayer modele) {
        if (modele.getUUID().equals(player.getUUID())) {
            player.sendSystemMessage(Messages.warn("C'est deja ton visage."));
            return 0;
        }
        DisguiseManager.setSkin(player, modele);
        player.sendSystemMessage(Messages.success("Tu portes le visage de "
                + modele.getGameProfile().getName()
                + ". Les autres le voient tout de suite ; toi, a ta prochaine connexion."));
        return Command.SINGLE_SUCCESS;
    }

    private static int retirerSkin(ServerPlayer player) {
        DisguiseManager.clearSkin(player);
        player.sendSystemMessage(Messages.success("Tu reprends ton visage."));
        return Command.SINGLE_SUCCESS;
    }
}
