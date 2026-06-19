package com.utopia.command;

import java.util.Collection;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.data.EconomyData;
import com.utopia.economy.EconomyManager;
import com.utopia.economy.EconomyMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Commandes d'economie : /balance, /pay, /withdraw, /deposit, et /money (admin). */
public final class EconomyCommands {

    private EconomyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /balance [joueur] | /balance menu | /balance top | /balance admin (+ alias /bal)
        dispatcher.register(Commands.literal("balance")
                .executes(EconomyCommands::balanceSelf)
                .then(Commands.literal("menu").executes(EconomyCommands::playerMenu))
                .then(Commands.literal("top").executes(EconomyCommands::top))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2)).executes(EconomyCommands::adminMenu))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(EconomyCommands::balanceOther)));
        dispatcher.register(Commands.literal("bal")
                .executes(EconomyCommands::balanceSelf)
                .then(Commands.literal("menu").executes(EconomyCommands::playerMenu))
                .then(Commands.literal("top").executes(EconomyCommands::top))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2)).executes(EconomyCommands::adminMenu))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(EconomyCommands::balanceOther)));
        dispatcher.register(Commands.literal("baltop").executes(EconomyCommands::top)
                .then(Commands.literal("holo").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("here").executes(EconomyCommands::baltopHoloHere))
                        .then(Commands.literal("move").executes(EconomyCommands::baltopHoloMove))
                        .then(Commands.literal("remove").executes(EconomyCommands::baltopHoloRemove))));

        // /pay <joueur> <montant>
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(EconomyCommands::pay))));

        // /withdraw <montant>
        dispatcher.register(Commands.literal("withdraw")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(EconomyCommands::withdraw)));

        // /deposit [montant]
        dispatcher.register(Commands.literal("deposit")
                .executes(ctx -> deposit(ctx, -1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> deposit(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));

        // /money give|take|set <joueur> <montant> (admin)
        dispatcher.register(Commands.literal("money")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("give")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> adminOp(ctx, Op.GIVE)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> adminOp(ctx, Op.TAKE)))))
                .then(Commands.literal("set")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> adminOp(ctx, Op.SET)))))
                .then(Commands.literal("purge")
                        .executes(EconomyCommands::purge)));
    }

    /** Vrai si la cible est connue du serveur : en ligne, deja un compte, ou un fichier de donnees joueur. */
    private static boolean knownTarget(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id) != null
                || EconomyData.get(server).hasAccount(id)
                || playerDataExists(server, id);
    }

    /** Le joueur a-t-il deja rejoint le serveur (fichier playerdata/&lt;uuid&gt;.dat present) ? */
    private static boolean playerDataExists(MinecraftServer server, UUID id) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                .resolve(id + ".dat").toFile().exists();
    }

    /** Supprime les comptes "fantomes" : ni en ligne, ni de fichier playerdata (crees par un pseudo errone). */
    private static int purge(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        EconomyData data = EconomyData.get(server);
        int removed = 0;
        for (UUID id : data.accounts()) {
            if (id.equals(com.utopia.data.MarketData.MAIRIE_UUID)) {
                continue;
            }
            if (server.getPlayerList().getPlayer(id) == null && !playerDataExists(server, id)) {
                data.removeAccount(id);
                removed++;
            }
        }
        final int n = removed;
        ctx.getSource().sendSuccess(() -> Messages.success(n + " compte(s) fantome(s) supprime(s) du classement."), true);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        return profiles.isEmpty() ? null : profiles.iterator().next();
    }

    private static int adminMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        EconomyMenus.openAdminMenu(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int playerMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        EconomyMenus.openPlayerMenu(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int top(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        var list = com.utopia.data.EconomyData.get(server).top(10);
        ctx.getSource().sendSuccess(() -> Messages.success("Classement des soldes (top " + list.size() + ") :"), false);
        int rank = 1;
        for (var e : list) {
            final int r = rank++;
            ServerPlayer online = server.getPlayerList().getPlayer(e.getKey());
            String name = e.getKey().equals(com.utopia.data.MarketData.MAIRIE_UUID)
                    ? com.utopia.data.MarketData.MAIRIE_NAME
                    : online != null ? online.getGameProfile().getName()
                    : server.getProfileCache().get(e.getKey()).map(com.mojang.authlib.GameProfile::getName)
                            .orElse(e.getKey().toString().substring(0, 8));
            ctx.getSource().sendSuccess(() -> Messages.info(" #" + r + " " + name + " : " + EconomyManager.format(e.getValue())), false);
        }
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Messages.info(" (aucun compte)"), false);
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int baltopHoloHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        com.utopia.economy.BalTopHologram.setHere(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int baltopHoloMove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        EconomyMenus.openBalTopHoloMove(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int baltopHoloRemove(CommandContext<CommandSourceStack> ctx) {
        com.utopia.economy.BalTopHologram.remove(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Messages.success("Hologramme BalTop retire."), false);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int balanceSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long bal = EconomyManager.getBalance(player.server, player.getUUID());
        player.sendSystemMessage(Messages.success("Votre solde : " + EconomyManager.format(bal)));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        GameProfile gp = profile(ctx);
        if (gp == null) {
            ctx.getSource().sendFailure(Messages.error("Joueur introuvable."));
            return 0;
        }
        long bal = EconomyManager.getBalance(server, gp.getId());
        ctx.getSource().sendSuccess(() -> Messages.success("Solde de " + gp.getName() + " : "
                + EconomyManager.format(bal)), false);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        MinecraftServer server = sender.server;
        GameProfile gp = profile(ctx);
        if (gp == null) {
            sender.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        if (gp.getId().equals(sender.getUUID())) {
            sender.sendSystemMessage(Messages.error("Vous ne pouvez pas vous payer vous-meme."));
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        if (!EconomyManager.transfer(server, sender.getUUID(), gp.getId(), amount)) {
            sender.sendSystemMessage(Messages.error("Solde insuffisant."));
            return 0;
        }
        sender.sendSystemMessage(Messages.success("Vous avez envoye " + EconomyManager.format(amount)
                + " a " + gp.getName() + "."));
        ServerPlayer target = server.getPlayerList().getPlayer(gp.getId());
        if (target != null) {
            target.sendSystemMessage(Messages.success("Vous avez recu " + EconomyManager.format(amount)
                    + " de " + sender.getGameProfile().getName() + "."));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int requested = IntegerArgumentType.getInteger(ctx, "amount");
        int space = EconomyManager.freeSpaceForCoins(player);
        if (space <= 0) {
            player.sendSystemMessage(Messages.error("Votre inventaire est plein."));
            return 0;
        }
        long balance = EconomyManager.getBalance(player.server, player.getUUID());
        int amount = (int) Math.min(requested, Math.min(space, balance));
        if (amount <= 0) {
            player.sendSystemMessage(Messages.error("Solde insuffisant."));
            return 0;
        }
        EconomyManager.remove(player.server, player.getUUID(), amount);
        EconomyManager.giveCoins(player, amount);
        String suffix = amount < requested ? " (limite a la place dispo)" : "";
        player.sendSystemMessage(Messages.success("Vous avez retire " + EconomyManager.format(amount) + suffix
                + ". Nouveau solde : " + EconomyManager.format(EconomyManager.getBalance(player.server, player.getUUID())) + "."));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int deposit(CommandContext<CommandSourceStack> ctx, int requested) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int available = EconomyManager.countCoins(player);
        if (available <= 0) {
            player.sendSystemMessage(Messages.warn("Vous n'avez aucune piece a deposer."));
            return 0;
        }
        int toDeposit = requested < 0 ? available : Math.min(requested, available);
        int taken = EconomyManager.takeCoins(player, toDeposit);
        EconomyManager.add(player.server, player.getUUID(), taken);
        player.sendSystemMessage(Messages.success("Vous avez depose " + EconomyManager.format(taken)
                + ". Nouveau solde : " + EconomyManager.format(EconomyManager.getBalance(player.server, player.getUUID())) + "."));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private enum Op {
        GIVE, TAKE, SET
    }

    private static int adminOp(CommandContext<CommandSourceStack> ctx, Op op) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        GameProfile gp = profile(ctx);
        if (gp == null) {
            ctx.getSource().sendFailure(Messages.error("Joueur introuvable."));
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        UUID id = gp.getId();
        // Anti-compte-fantome : on refuse un pseudo qui n'a jamais rejoint le serveur (evite de creer
        // un compte aleatoire via un nom errone). Les joueurs connus / deja en banque restent gerables.
        if (!knownTarget(server, id)) {
            ctx.getSource().sendFailure(Messages.error("Joueur inconnu : \"" + gp.getName()
                    + "\" n'a jamais rejoint le serveur (aucun compte cree)."));
            return 0;
        }
        long before = EconomyManager.getBalance(server, id);
        String verb;
        switch (op) {
            case GIVE -> {
                EconomyManager.add(server, id, amount);
                verb = "credite de " + EconomyManager.format(amount);
            }
            case TAKE -> {
                EconomyManager.setBalance(server, id, Math.max(0L, before - amount));
                verb = "debite de " + EconomyManager.format(amount);
            }
            default -> {
                EconomyManager.setBalance(server, id, amount);
                verb = "defini a " + EconomyManager.format(amount);
            }
        }
        long after = EconomyManager.getBalance(server, id);
        ctx.getSource().sendSuccess(() -> Messages.success("Compte de " + gp.getName() + " " + verb
                + " (solde : " + EconomyManager.format(after) + ")."), true);
        ServerPlayer target = server.getPlayerList().getPlayer(id);
        if (target != null) {
            target.sendSystemMessage(Messages.info("Votre solde est desormais de " + EconomyManager.format(after) + "."));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }
}
