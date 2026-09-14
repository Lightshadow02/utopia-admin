package com.utopia.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.utopia.data.DieuData;
import com.utopia.dieu.DieuMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /dieux} et sa commande de designation.
 *
 * <p>Deux verrous differents. {@code /dieuset} exige de n'avoir <b>aucune entite</b> a la source et
 * le niveau 4 : cela ne laisse passer que la console du serveur et RCON, ni un joueur operateur
 * (qui a une entite) ni un bloc de commande (qui plafonne au niveau 2). {@code /dieux} n'est
 * ouverte qu'au porteur du titre, et comme Brigadier ne transmet a chaque client que les commandes
 * dont il remplit la condition, elle n'existe meme pas dans la completion des autres.
 */
public final class DieuCommand {

    private DieuCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dieux")
                .requires(DieuCommand::estLeDieu)
                .executes(ctx -> {
                    DieuMenus.open(ctx.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }));

        dispatcher.register(Commands.literal("dieuset")
                .requires(DieuCommand::estLaConsole)
                .then(Commands.literal("retirer")
                        .executes(ctx -> retirer(ctx.getSource())))
                .then(Commands.argument("joueur", GameProfileArgument.gameProfile())
                        .executes(ctx -> designer(ctx.getSource(),
                                GameProfileArgument.getGameProfiles(ctx, "joueur")))));
    }

    /** Seul le porteur voit et ouvre /dieux. */
    private static boolean estLeDieu(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p
                && DieuData.get(p.server).isDieu(p.getUUID());
    }

    /**
     * Console (ou RCON) uniquement. Un operateur en jeu porte une entite, un bloc de commande ne
     * depasse pas le niveau 2 : ni l'un ni l'autre ne passe.
     */
    private static boolean estLaConsole(CommandSourceStack source) {
        return source.getEntity() == null && source.hasPermission(4);
    }

    private static int designer(CommandSourceStack source, java.util.Collection<GameProfile> profils) {
        if (profils.size() != 1) {
            source.sendFailure(Messages.error("Un seul joueur a la fois."));
            return 0;
        }
        GameProfile gp = profils.iterator().next();
        MinecraftServer server = source.getServer();
        DieuData data = DieuData.get(server);
        java.util.UUID ancien = data.dieu();
        data.setDieu(gp.getId(), gp.getName());

        // La completion des commandes est envoyee une fois par connexion : sans ce renvoi, le
        // nouveau porteur ne verrait /dieux qu'a sa prochaine reconnexion, et l'ancien continuerait
        // de la voir jusqu'a la sienne.
        rafraichir(server, gp.getId());
        rafraichir(server, ancien);

        source.sendSuccess(() -> Component.literal("[Utopia] " + gp.getName()
                + " porte desormais /dieux."), false);
        ServerPlayer cible = server.getPlayerList().getPlayer(gp.getId());
        if (cible != null) {
            cible.sendSystemMessage(Messages.success("Tu portes desormais /dieux."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int retirer(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        DieuData data = DieuData.get(server);
        java.util.UUID ancien = data.dieu();
        if (ancien == null) {
            source.sendFailure(Messages.warn("Personne ne porte /dieux."));
            return 0;
        }
        String nom = data.nom();
        data.clear();
        rafraichir(server, ancien);
        source.sendSuccess(() -> Component.literal("[Utopia] " + nom + " ne porte plus /dieux."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void rafraichir(MinecraftServer server, java.util.UUID id) {
        if (id == null) {
            return;
        }
        ServerPlayer joueur = server.getPlayerList().getPlayer(id);
        if (joueur != null) {
            server.getCommands().sendCommands(joueur);
        }
    }
}
