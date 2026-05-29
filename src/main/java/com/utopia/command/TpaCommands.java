package com.utopia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.Config;
import com.utopia.teleport.TpaManager;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** Commandes de teleportation entre joueurs : /tpa, /tpahere, /tpaccept, /tpadeny. */
public final class TpaCommands {

    private TpaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> send(ctx, TpaManager.Type.TPA))));

        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> send(ctx, TpaManager.Type.TPA_HERE))));

        dispatcher.register(Commands.literal("tpaccept")
                .executes(ctx -> accept(ctx, null))
                .then(Commands.argument("from", EntityArgument.player())
                        .executes(ctx -> accept(ctx, EntityArgument.getPlayer(ctx, "from")))));

        dispatcher.register(Commands.literal("tpadeny")
                .executes(ctx -> deny(ctx, null))
                .then(Commands.argument("from", EntityArgument.player())
                        .executes(ctx -> deny(ctx, EntityArgument.getPlayer(ctx, "from")))));
        // Alias usuel.
        dispatcher.register(Commands.literal("tpdeny")
                .executes(ctx -> deny(ctx, null)));
    }

    private static int send(CommandContext<CommandSourceStack> ctx, TpaManager.Type type) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

        TpaManager.SendResult result = TpaManager.send(sender, target, type);
        switch (result) {
            case SELF -> {
                sender.sendSystemMessage(Messages.error("Vous ne pouvez pas vous teleporter a vous-meme."));
                return 0;
            }
            case COOLDOWN -> {
                long remaining = TpaManager.cooldownRemainingSeconds(sender);
                sender.sendSystemMessage(Messages.warn("Patientez encore " + Messages.formatDuration(remaining)
                        + " avant d'envoyer une nouvelle demande."));
                return 0;
            }
            case ALREADY_PENDING -> {
                sender.sendSystemMessage(Messages.warn("Vous avez deja une demande en attente vers "
                        + target.getGameProfile().getName() + "."));
                return 0;
            }
            default -> {
                // OK
            }
        }

        String senderName = sender.getGameProfile().getName();
        int timeout = Config.TPA_REQUEST_TIMEOUT_SECONDS.get();

        if (type == TpaManager.Type.TPA) {
            sender.sendSystemMessage(Messages.success("Demande envoyee a " + target.getGameProfile().getName()
                    + ". Elle expire dans " + timeout + "s."));
            target.sendSystemMessage(Messages.info(senderName + " souhaite se teleporter aupres de vous.")
                    .withStyle(ChatFormatting.WHITE));
        } else {
            sender.sendSystemMessage(Messages.success("Demande envoyee a " + target.getGameProfile().getName()
                    + ". Elle expire dans " + timeout + "s."));
            target.sendSystemMessage(Messages.info(senderName + " souhaite que vous vous teleportiez aupres de lui.")
                    .withStyle(ChatFormatting.WHITE));
        }
        target.sendSystemMessage(buildPrompt(senderName));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** Construit la ligne cliquable [ACCEPTER] [REFUSER]. */
    private static Component buildPrompt(String senderName) {
        MutableComponent accept = Component.literal("[ACCEPTER]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + senderName))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Accepter la demande de " + senderName))));
        MutableComponent deny = Component.literal("[REFUSER]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + senderName))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Refuser la demande de " + senderName))));
        return Component.empty().append(Messages.PREFIX).append(accept)
                .append(Component.literal("  ")).append(deny);
    }

    private static int accept(CommandContext<CommandSourceStack> ctx, ServerPlayer from) throws CommandSyntaxException {
        ServerPlayer target = ctx.getSource().getPlayerOrException();
        if (from == null && !TpaManager.hasIncoming(target.getUUID())) {
            target.sendSystemMessage(Messages.warn("Aucune demande de teleportation en attente."));
            return 0;
        }
        TpaManager.Request req = TpaManager.accept(target, from == null ? null : from.getUUID());
        if (req == null) {
            target.sendSystemMessage(Messages.warn("Aucune demande de teleportation correspondante."));
            return 0;
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int deny(CommandContext<CommandSourceStack> ctx, ServerPlayer from) throws CommandSyntaxException {
        ServerPlayer target = ctx.getSource().getPlayerOrException();
        TpaManager.Request req = TpaManager.deny(target, from == null ? null : from.getUUID());
        if (req == null) {
            target.sendSystemMessage(Messages.warn("Aucune demande de teleportation a refuser."));
            return 0;
        }
        target.sendSystemMessage(Messages.info("Demande refusee."));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }
}
