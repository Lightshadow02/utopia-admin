package com.utopia.command;

import java.util.Collection;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.data.RoomData;
import com.utopia.economy.EconomyManager;
import com.utopia.parcel.Parcel;
import com.utopia.room.Room;
import com.utopia.room.RoomManager;
import com.utopia.room.RoomMenus;
import com.utopia.util.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Commandes /room (alias /auberge) : gestion des chambres d'auberge par les admins (op niveau 2). */
public final class RoomCommands {

    private RoomCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("room").requires(s -> s.hasPermission(2))
                .executes(RoomCommands::menu)
                .then(Commands.literal("menu").executes(RoomCommands::menu))
                .then(Commands.literal("wand").executes(RoomCommands::wand))
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(RoomCommands::create)))
                .then(Commands.literal("assign")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                        .then(Commands.argument("pricePerDay", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("days", IntegerArgumentType.integer(1))
                                                        .executes(RoomCommands::assign))))))
                .then(Commands.literal("free")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(RoomCommands::free)))
                .then(Commands.literal("freeze")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> setFrozen(ctx, true))))
                .then(Commands.literal("unfreeze")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> setFrozen(ctx, false))))
                .then(Commands.literal("setprice")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("pricePerDay", IntegerArgumentType.integer(0)).executes(RoomCommands::setprice))))
                .then(Commands.literal("setdays")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("days", IntegerArgumentType.integer(0)).executes(RoomCommands::setdays))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(RoomCommands::delete)))
                .then(Commands.literal("list").executes(RoomCommands::list));

        dispatcher.register(root);

        // /auberge : ouvre le menu de gestion des chambres. Accessible aux op ET aux aubergistes
        // designes par un op (via /admin -> Aubergistes). Les sous-commandes /room restent op-only.
        dispatcher.register(Commands.literal("auberge")
                .requires(RoomCommands::canOpenAuberge)
                .executes(RoomCommands::menu));
    }

    /** Vrai si la source est op (niveau 2) ou un aubergiste designe. */
    private static boolean canOpenAuberge(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer p
                && RoomData.get(p.server).isAubergiste(p.getUUID());
    }

    private static Room byId(ServerPlayer player, CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        Room r = RoomData.get(player.server).get(id);
        if (r == null) {
            player.sendSystemMessage(Messages.error("Chambre introuvable : '" + id + "'."));
        }
        return r;
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        return profiles.isEmpty() ? null : profiles.iterator().next();
    }

    private static int menu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        RoomMenus.openAuberge(ctx.getSource().getPlayerOrException());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int wand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.getInventory().add(new ItemStack(RoomManager.wandItem()));
        player.sendSystemMessage(Messages.success("Outil chambre recu. Clic GAUCHE = coin 1, clic DROIT = coin 2 (le Y compte), puis /room create <id>."));
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        RoomData data = RoomData.get(player.server);
        if (data.exists(id)) {
            player.sendSystemMessage(Messages.error("Une chambre '" + id + "' existe deja."));
            return 0;
        }
        Parcel.Box box = RoomManager.buildBox(player);
        if (box == null) {
            player.sendSystemMessage(Messages.error("Selectionnez 2 coins avec l'outil chambre (clic gauche puis clic droit)."));
            return 0;
        }
        Room room = new Room(id, id, player.serverLevel().dimension().location(), box);
        data.put(room);
        RoomManager.clearCorners(player.getUUID());
        player.sendSystemMessage(Messages.success("Chambre '" + id + "' creee (" + box.volume() + " blocs, Y " + box.minY() + " a " + box.maxY() + ")."));
        return 1;
    }

    private static int assign(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        GameProfile target = profile(ctx);
        if (target == null) {
            player.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        int pricePerDay = IntegerArgumentType.getInteger(ctx, "pricePerDay");
        int days = IntegerArgumentType.getInteger(ctx, "days");
        long cost = (long) pricePerDay * days;
        if (!EconomyManager.payCombined(player, cost)) {
            player.sendSystemMessage(Messages.error("Fonds insuffisants pour avancer " + EconomyManager.format(cost)
                    + " (pieces + solde)."));
            return 0;
        }
        r.setOccupant(target.getId(), target.getName());
        r.setPricePerDay(pricePerDay);
        r.setDays(days);
        r.setFrozen(false);
        RoomData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Chambre '" + r.id() + "' attribuee a " + target.getName()
                + " (avance " + EconomyManager.format(cost) + " ; " + EconomyManager.format(pricePerDay) + "/jour x " + days + ")."));
        ServerPlayer online = player.server.getPlayerList().getPlayer(target.getId());
        if (online != null) {
            online.sendSystemMessage(Messages.info("La chambre " + r.name() + " vous a ete attribuee."));
        }
        return 1;
    }

    private static int free(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        r.setOccupant(null, null);
        r.setFrozen(false);
        RoomData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Chambre '" + r.id() + "' liberee."));
        return 1;
    }

    private static int setFrozen(CommandContext<CommandSourceStack> ctx, boolean frozen) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        r.setFrozen(frozen);
        RoomData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Chambre '" + r.id() + "' " + (frozen ? "GELEE (acces coupe)." : "degelee (acces rendu).")));
        ServerPlayer online = r.occupant() != null ? player.server.getPlayerList().getPlayer(r.occupant()) : null;
        if (online != null) {
            online.sendSystemMessage(frozen ? Messages.warn("Votre chambre " + r.name() + " a ete gelee.")
                    : Messages.success("Votre chambre " + r.name() + " est de nouveau accessible."));
        }
        return 1;
    }

    private static int setprice(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        r.setPricePerDay(IntegerArgumentType.getInteger(ctx, "pricePerDay"));
        RoomData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Prix/jour de '" + r.id() + "' : " + EconomyManager.format(r.pricePerDay()) + "."));
        return 1;
    }

    private static int setdays(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        r.setDays(IntegerArgumentType.getInteger(ctx, "days"));
        RoomData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Duree de '" + r.id() + "' : " + r.days() + " jours."));
        return 1;
    }


    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Room r = byId(player, ctx);
        if (r == null) {
            return 0;
        }
        RoomData.get(player.server).remove(r.id());
        player.sendSystemMessage(Messages.success("Chambre '" + r.id() + "' supprimee."));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var rooms = RoomData.get(player.server).all();
        if (rooms.isEmpty()) {
            player.sendSystemMessage(Messages.info("Aucune chambre."));
            return 1;
        }
        player.sendSystemMessage(Messages.success("Chambres (" + rooms.size() + ") :"));
        for (Room r : rooms) {
            player.sendSystemMessage(Messages.info(" - " + r.id() + " : "
                    + (r.isAssigned() ? r.occupantName() : "libre")
                    + (r.frozen() ? " [GELEE]" : "")
                    + " | " + EconomyManager.format(r.pricePerDay()) + "/jour x " + r.days()));
        }
        return 1;
    }
}
