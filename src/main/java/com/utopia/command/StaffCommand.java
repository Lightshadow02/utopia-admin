package com.utopia.command;

import java.util.Collection;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.data.InventoryData;
import com.utopia.inventory.InventoryManager;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /staff : bascule "mode staff" (inventaire + gamemode + op serveur), reservee aux joueurs
 * de la liste staff (et aux op). Gestion de la liste : /staff add &lt;joueur&gt; et /staff take &lt;joueur&gt;
 * (op uniquement). Fusionne avec le systeme d'inventaires de /inv (slot 1 = survie, slot 2 = creatif).
 */
public final class StaffCommand {

    private StaffCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("staff")
                .requires(StaffCommand::canToggle)
                .executes(StaffCommand::toggle)
                .then(Commands.literal("add")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueur", GameProfileArgument.gameProfile())
                                .executes(ctx -> setStaff(ctx, true))))
                .then(Commands.literal("take")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueur", GameProfileArgument.gameProfile())
                                .executes(ctx -> setStaff(ctx, false)))));
    }

    /** Vrai si la source est op (niveau 2) ou un membre de la liste staff. */
    private static boolean canToggle(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p
                && InventoryData.get(p.server).isStaff(p.getUUID());
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        InventoryManager.staffToggle(ctx.getSource().getPlayerOrException());
        return Command.SINGLE_SUCCESS;
    }

    private static int setStaff(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "joueur");
        InventoryData data = InventoryData.get(server);
        int count = 0;
        for (GameProfile profile : profiles) {
            data.setStaff(profile.getId(), add);
            count++;
            ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
            if (online != null) {
                server.getCommands().sendCommands(online); // /staff apparait/disparait pour lui
                online.sendSystemMessage(add
                        ? Messages.success("Tu fais maintenant partie du staff : /staff est disponible.")
                        : Messages.warn("Tu ne fais plus partie du staff."));
            }
            ctx.getSource().sendSuccess(() -> Messages.success(
                    (add ? "Ajoute au staff : " : "Retire du staff : ") + profile.getName()), true);
        }
        return count;
    }
}
