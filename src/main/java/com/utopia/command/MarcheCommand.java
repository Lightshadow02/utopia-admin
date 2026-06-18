package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.data.MarketData;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commande /marche : reglages du marche public. Pour l'instant, /marche couleur &lt;preset&gt; change la
 * couleur de l'en-tete "Stand de X" des hologrammes (12 presets). Accessible aux op et au maire.
 */
public final class MarcheCommand {

    private MarcheCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("marche")
                .requires(MarcheCommand::canUse)
                .then(Commands.literal("couleur")
                        .executes(MarcheCommand::show)
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(MarketData.HEADER_COLOR_PRESETS, b))
                                .executes(MarcheCommand::setColor))));
    }

    /** Op (niveau 2) ou maire designe. */
    private static boolean canUse(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p
                && MarketData.get(p.server).isMaire(p.getUUID());
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        MarketData data = MarketData.get(ctx.getSource().getServer());
        ctx.getSource().sendSystemMessage(Messages.info("Couleur actuelle : " + data.headerColor()
                + ". Presets : " + String.join(", ", MarketData.HEADER_COLOR_PRESETS)));
        return Command.SINGLE_SUCCESS;
    }

    private static int setColor(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String preset = StringArgumentType.getString(ctx, "preset");
        MarketData data = MarketData.get(ctx.getSource().getServer());
        if (data.setHeaderColor(preset)) {
            ctx.getSource().sendSystemMessage(Messages.success("Couleur des en-tetes de stand : " + preset
                    + " (appliquee d'ici ~2 s)."));
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSystemMessage(Messages.warn("Preset inconnu. Choix : "
                + String.join(", ", MarketData.HEADER_COLOR_PRESETS)));
        return 0;
    }
}
