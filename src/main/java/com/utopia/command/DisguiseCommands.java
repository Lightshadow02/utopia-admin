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
 * <p>Ouvertes a tout le monde. Sur un serveur de roleplay, changer de nom et de visage fait partie
 * du jeu : le reserver au staff en ferait un privilege au lieu d'un costume.
 *
 * <p>Le selecteur (@p, @r) reste hors de portee des non-operateurs, c'est Brigadier qui s'en charge
 * pour tout le mod : un joueur ordinaire doit ecrire le nom de celui dont il prend le visage.
 */
public final class DisguiseCommands {

    /**
     * Repos entre deux changements, par joueur. Chaque deguisement fait retirer puis reposer le
     * joueur dans la liste de <b>tous</b> les clients, et reapparaitre son entite chez les voisins :
     * enchaine a la macro, cela ferait clignoter l'ecran de tout le serveur. Le pupitre de /dieux n'y
     * est pas soumis - il n'y a qu'une personne derriere, et elle prepare un evenement.
     */
    private static final long REPOS_MS = 3000L;

    private static final java.util.Map<java.util.UUID, Long> DERNIER = new java.util.HashMap<>();

    private DisguiseCommands() {
    }

    /** Vrai si ce joueur doit patienter ; le lui dit au passage. */
    private static boolean tropTot(ServerPlayer player) {
        long maintenant = System.currentTimeMillis();
        Long dernier = DERNIER.get(player.getUUID());
        if (dernier != null && maintenant - dernier < REPOS_MS) {
            player.sendSystemMessage(Messages.warn("Laisse passer un instant avant de changer a nouveau."));
            return true;
        }
        DERNIER.put(player.getUUID(), maintenant);
        if (DERNIER.size() > 128) {
            DERNIER.entrySet().removeIf(e -> maintenant - e.getValue() > REPOS_MS * 20);
        }
        return false;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nick")
                .then(Commands.literal("retirer")
                        .executes(ctx -> retirerNom(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("nom", StringArgumentType.greedyString())
                        .executes(ctx -> poserNom(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "nom")))));

        dispatcher.register(Commands.literal("skin")
                .then(Commands.literal("retirer")
                        .executes(ctx -> retirerSkin(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("modele", EntityArgument.player())
                        .executes(ctx -> poserSkin(ctx.getSource().getPlayerOrException(),
                                EntityArgument.getPlayer(ctx, "modele")))));
    }

    private static int poserNom(ServerPlayer player, String nom) {
        if (tropTot(player)) {
            return 0;
        }
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
        if (tropTot(player)) {
            return 0;
        }
        DisguiseManager.clearNom(player);
        player.sendSystemMessage(Messages.success("Tu reprends ton nom."));
        return Command.SINGLE_SUCCESS;
    }

    private static int poserSkin(ServerPlayer player, ServerPlayer modele) {
        if (tropTot(player)) {
            return 0;
        }
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
        if (tropTot(player)) {
            return 0;
        }
        DisguiseManager.clearSkin(player);
        player.sendSystemMessage(Messages.success("Tu reprends ton visage."));
        return Command.SINGLE_SUCCESS;
    }
}
