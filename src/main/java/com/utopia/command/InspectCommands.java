package com.utopia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.utopia.Config;
import com.utopia.inspect.InspectMenus;
import com.utopia.inspect.OfflinePlayerData;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /invsee} et {@code /ecsee} : les affaires de quelqu'un, connecte ou non.
 *
 * <p>Les memes ecrans que la rubrique "Voir un inventaire" de {@code /admin}, mais accessibles d'un
 * mot : sur un litige, on a deja le pseudo sous les yeux, derouler une liste de trois cents joueurs
 * pour le retrouver n'a pas de sens.
 *
 * <p>Le pseudo s'ecrit en clair et non sous forme de cible de commande : une cible ne designe que
 * les joueurs presents, et ce sont surtout les absents qu'on vient regarder.
 */
public final class InspectCommands {

    private InspectCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
                .requires(src -> src.hasPermission(2) && Config.ADMIN_INSPECT.get())
                .then(Commands.argument("pseudo", StringArgumentType.word())
                        .suggests(InspectCommands::pseudos)
                        .executes(ctx -> ouvrir(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "pseudo"), false))));

        dispatcher.register(Commands.literal("ecsee")
                .requires(src -> src.hasPermission(2) && Config.ADMIN_INSPECT.get())
                .then(Commands.argument("pseudo", StringArgumentType.word())
                        .suggests(InspectCommands::pseudos)
                        .executes(ctx -> ouvrir(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "pseudo"), true))));
    }

    /** Completion : les connectes d'abord, puis tous ceux qui ont une sauvegarde ici. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            pseudos(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        java.util.LinkedHashSet<String> noms =
                new java.util.LinkedHashSet<>(ctx.getSource().getOnlinePlayerNames());
        for (OfflinePlayerData.Connu connu : OfflinePlayerData.joueursConnus(ctx.getSource().getServer())) {
            if (connu.nomConnu()) {
                noms.add(connu.nom());
            }
        }
        return SharedSuggestionProvider.suggest(noms, builder);
    }

    private static int ouvrir(ServerPlayer admin, String pseudo, boolean coffreDeLEnd) {
        OfflinePlayerData.Connu cible = InspectMenus.resoudre(admin.server, pseudo);
        if (cible == null) {
            admin.sendSystemMessage(Messages.error("Aucune sauvegarde pour \"" + pseudo
                    + "\". Ce joueur n'est jamais venu sur ce serveur."));
            return 0;
        }
        if (coffreDeLEnd) {
            InspectMenus.openEnderchest(admin, cible.id(), cible.nom(), 0);
        } else {
            InspectMenus.openInventaire(admin, cible.id(), cible.nom(), 0);
        }
        return Command.SINGLE_SUCCESS;
    }
}
