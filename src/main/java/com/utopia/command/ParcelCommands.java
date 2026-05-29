package com.utopia.command;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.parcel.Parcel;
import com.utopia.parcel.ParcelManager;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Commandes du systeme de parcelles : admin (definition) et joueur (achat, droits). */
public final class ParcelCommands {

    private ParcelCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("parcel")
                // ---- Joueur ----
                .then(Commands.literal("info").executes(ParcelCommands::info))
                .then(Commands.literal("list").executes(ParcelCommands::list))
                .then(Commands.literal("buy").executes(ParcelCommands::buy))
                .then(Commands.literal("sell")
                        .then(Commands.argument("price", IntegerArgumentType.integer(0)).executes(ParcelCommands::sell)))
                .then(Commands.literal("unsell").executes(ParcelCommands::unsell))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ParcelCommands::transfer)))
                .then(Commands.literal("trust")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .then(Commands.argument("flags", StringArgumentType.greedyString()).executes(ParcelCommands::trust))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ParcelCommands::untrust)))
                .then(Commands.literal("trustlist").executes(ParcelCommands::trustlist))
                // ---- Admin ----
                .then(Commands.literal("wand").requires(s -> s.hasPermission(2)).executes(ParcelCommands::wand))
                .then(Commands.literal("trace").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("clear").executes(ParcelCommands::traceClear))
                        .then(Commands.literal("undo").executes(ParcelCommands::traceUndo)))
                .then(Commands.literal("create").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> create(ctx, 0, false))
                                .then(Commands.argument("price", IntegerArgumentType.integer(0))
                                        .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "price"), true)))))
                .then(Commands.literal("addregion").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ParcelCommands::addregion)))
                .then(Commands.literal("setprice").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("price", IntegerArgumentType.integer(0)).executes(ParcelCommands::setprice))))
                .then(Commands.literal("setsale").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("value", BoolArgumentType.bool()).executes(ParcelCommands::setsale))))
                .then(Commands.literal("setowner").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(ParcelCommands::setowner))))
                .then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ParcelCommands::delete)))
                .then(Commands.literal("tp").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ParcelCommands::tp)));

        com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
        dispatcher.register(Commands.literal("parcelle").redirect(node));
    }

    // ----------------------------------------------------------------------------------- helpers

    private static Parcel parcelAt(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        return ParcelData.get(player.server).parcelAt(player.serverLevel().dimension().location(),
                pos.getX(), pos.getY(), pos.getZ());
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        return profiles.isEmpty() ? null : profiles.iterator().next();
    }

    private static EnumSet<Parcel.Flag> parseFlags(String raw) {
        EnumSet<Parcel.Flag> set = EnumSet.noneOf(Parcel.Flag.class);
        for (String tok : raw.toLowerCase(Locale.ROOT).split("[ ,]+")) {
            switch (tok) {
                case "all", "tout" -> {
                    return EnumSet.allOf(Parcel.Flag.class);
                }
                case "build", "construire" -> set.add(Parcel.Flag.BUILD);
                case "containers", "coffres" -> set.add(Parcel.Flag.CONTAINERS);
                case "doors", "portes" -> set.add(Parcel.Flag.DOORS);
                case "machines" -> set.add(Parcel.Flag.MACHINES);
                default -> { }
            }
        }
        return set;
    }

    // ----------------------------------------------------------------------------------- joueur

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = parcelAt(player);
        if (p == null) {
            player.sendSystemMessage(Messages.info("Zone libre (hors parcelle)."));
            return 1;
        }
        player.sendSystemMessage(Messages.success("Parcelle " + p.name() + " [" + p.id() + "]"));
        player.sendSystemMessage(Messages.info(" - Proprietaire : " + (p.isOwned() ? p.ownerName() : "aucun (serveur)")));
        player.sendSystemMessage(Messages.info(" - En vente : " + (p.forSale() ? "oui (" + EconomyManager.format(p.price()) + ")" : "non")));
        player.sendSystemMessage(Messages.info(" - Regions : " + p.regionCount() + " | surface ~ " + p.approxFootprint() + " blocs"));
        EnumSet<Parcel.Flag> mine = p.isOwner(player.getUUID()) ? EnumSet.allOf(Parcel.Flag.class)
                : p.members().getOrDefault(player.getUUID(), EnumSet.noneOf(Parcel.Flag.class));
        player.sendSystemMessage(Messages.info(" - Vos droits : " + (p.isOwner(player.getUUID()) ? "proprietaire" : mine)));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<Parcel> owned = ParcelData.get(player.server).ownedBy(player.getUUID());
        if (owned.isEmpty()) {
            player.sendSystemMessage(Messages.info("Vous ne possedez aucune parcelle."));
            return 1;
        }
        player.sendSystemMessage(Messages.success("Vos parcelles (" + owned.size() + ") :"));
        for (Parcel p : owned) {
            player.sendSystemMessage(Messages.info(" - " + p.name() + " [" + p.id() + "]"
                    + (p.forSale() ? " (en vente : " + EconomyManager.format(p.price()) + ")" : "")));
        }
        return 1;
    }

    private static int buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.server;
        Parcel p = parcelAt(player);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Vous n'etes sur aucune parcelle."));
            return 0;
        }
        if (!p.forSale()) {
            player.sendSystemMessage(Messages.error("Cette parcelle n'est pas en vente."));
            return 0;
        }
        if (p.isOwner(player.getUUID())) {
            player.sendSystemMessage(Messages.error("Vous possedez deja cette parcelle."));
            return 0;
        }
        long price = p.price();
        if (!EconomyManager.remove(server, player.getUUID(), price)) {
            player.sendSystemMessage(Messages.error("Solde insuffisant (prix : " + EconomyManager.format(price) + ")."));
            return 0;
        }
        UUID previousOwner = p.owner();
        if (previousOwner != null) {
            EconomyManager.add(server, previousOwner, price);
            ServerPlayer seller = server.getPlayerList().getPlayer(previousOwner);
            if (seller != null) {
                seller.sendSystemMessage(Messages.success(player.getGameProfile().getName()
                        + " a achete votre parcelle " + p.name() + " pour " + EconomyManager.format(price) + "."));
            }
        }
        p.members().clear();
        p.setOwner(player.getUUID(), player.getGameProfile().getName());
        p.setForSale(false);
        ParcelData.get(server).setDirty();
        player.sendSystemMessage(Messages.success("Vous avez achete la parcelle " + p.name()
                + " pour " + EconomyManager.format(price) + " !"));
        return 1;
    }

    private static int sell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = requireOwned(player);
        if (p == null) {
            return 0;
        }
        int price = IntegerArgumentType.getInteger(ctx, "price");
        p.setPrice(price);
        p.setForSale(true);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Parcelle " + p.name() + " mise en vente pour "
                + EconomyManager.format(price) + "."));
        return 1;
    }

    private static int unsell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = requireOwned(player);
        if (p == null) {
            return 0;
        }
        p.setForSale(false);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Parcelle " + p.name() + " retiree de la vente."));
        return 1;
    }

    private static int transfer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = requireOwned(player);
        if (p == null) {
            return 0;
        }
        GameProfile gp = profile(ctx);
        if (gp == null) {
            player.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        p.members().clear();
        p.setOwner(gp.getId(), gp.getName());
        p.setForSale(false);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Parcelle " + p.name() + " transferee a " + gp.getName() + "."));
        ServerPlayer target = player.server.getPlayerList().getPlayer(gp.getId());
        if (target != null) {
            target.sendSystemMessage(Messages.success("Vous avez recu la parcelle " + p.name() + "."));
        }
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = requireOwned(player);
        if (p == null) {
            return 0;
        }
        GameProfile gp = profile(ctx);
        if (gp == null) {
            player.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        EnumSet<Parcel.Flag> flags = parseFlags(StringArgumentType.getString(ctx, "flags"));
        if (flags.isEmpty()) {
            player.sendSystemMessage(Messages.error("Droits invalides. Utilisez : build, containers, doors, machines, ou all."));
            return 0;
        }
        p.setMember(gp.getId(), flags);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success(gp.getName() + " a maintenant : " + flags + " sur " + p.name() + "."));
        return 1;
    }

    private static int untrust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = requireOwned(player);
        if (p == null) {
            return 0;
        }
        GameProfile gp = profile(ctx);
        if (gp == null) {
            player.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        p.setMember(gp.getId(), EnumSet.noneOf(Parcel.Flag.class));
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success(gp.getName() + " n'a plus de droits sur " + p.name() + "."));
        return 1;
    }

    private static int trustlist(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = parcelAt(player);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Vous n'etes sur aucune parcelle."));
            return 0;
        }
        player.sendSystemMessage(Messages.success("Membres de " + p.name() + " :"));
        if (p.members().isEmpty()) {
            player.sendSystemMessage(Messages.info(" (aucun)"));
        }
        p.members().forEach((uuid, flags) ->
                player.sendSystemMessage(Messages.info(" - " + uuid + " : " + flags)));
        return 1;
    }

    /** Renvoie la parcelle sous le joueur s'il en est proprietaire (ou op), sinon nul + message. */
    private static Parcel requireOwned(ServerPlayer player) {
        Parcel p = parcelAt(player);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Vous n'etes sur aucune parcelle."));
            return null;
        }
        if (!p.isOwner(player.getUUID()) && !ParcelManager.canBypass(player)) {
            player.sendSystemMessage(Messages.error("Vous n'etes pas proprietaire de cette parcelle."));
            return null;
        }
        return p;
    }

    // ----------------------------------------------------------------------------------- admin

    private static int wand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(ParcelManager.wandItem()));
        player.sendSystemMessage(Messages.success("Outil de parcelle recu."));
        player.sendSystemMessage(Messages.info("Clic droit au sol = ajouter un point | Clic gauche = annuler le dernier point."));
        player.sendSystemMessage(Messages.info("Tracez le contour (>= 3 points) puis /parcel create <id> [prix]."));
        return 1;
    }

    private static int traceClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ParcelManager.clearTrace(player.getUUID());
        player.sendSystemMessage(Messages.success("Trace effacee."));
        return 1;
    }

    private static int traceUndo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos removed = ParcelManager.undoPoint(player.getUUID());
        player.sendSystemMessage(removed == null ? Messages.warn("Aucun point a annuler.")
                : Messages.success("Dernier point annule (reste " + ParcelManager.traceSize(player.getUUID()) + ")."));
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, int price, boolean forSale) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        ParcelData data = ParcelData.get(player.server);
        if (data.exists(id)) {
            player.sendSystemMessage(Messages.error("Une parcelle '" + id + "' existe deja."));
            return 0;
        }
        Parcel.Poly poly = ParcelManager.buildPoly(player);
        if (poly == null) {
            player.sendSystemMessage(Messages.error("Tracez d'abord au moins 3 points avec l'outil (clic droit au sol)."));
            return 0;
        }
        ResourceLocation dim = player.serverLevel().dimension().location();
        warnOverlap(player, data, dim, poly.bounds(), id);
        Parcel parcel = new Parcel(id, id, dim);
        parcel.addPoly(poly);
        parcel.setPrice(price);
        parcel.setForSale(forSale);
        data.put(parcel);
        ParcelManager.clearTrace(player.getUUID());
        player.sendSystemMessage(Messages.success("Parcelle '" + id + "' creee (" + poly.xs().length + " sommets, ~"
                + parcel.approxFootprint() + " blocs au sol"
                + (forSale ? ", en vente " + EconomyManager.format(price) : "") + ")."));
        return 1;
    }

    private static int addregion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = byId(player, ctx);
        if (p == null) {
            return 0;
        }
        ResourceLocation dim = player.serverLevel().dimension().location();
        if (!p.dimension().equals(dim)) {
            player.sendSystemMessage(Messages.error("Vous devez etre dans la meme dimension que la parcelle."));
            return 0;
        }
        Parcel.Poly poly = ParcelManager.buildPoly(player);
        if (poly == null) {
            player.sendSystemMessage(Messages.error("Tracez d'abord au moins 3 points avec l'outil."));
            return 0;
        }
        warnOverlap(player, ParcelData.get(player.server), dim, poly.bounds(), p.id());
        p.addPoly(poly);
        ParcelData.get(player.server).setDirty();
        ParcelManager.clearTrace(player.getUUID());
        player.sendSystemMessage(Messages.success("Region ajoutee a '" + p.id() + "' (total : " + p.regionCount() + " regions)."));
        return 1;
    }

    private static int setprice(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = byId(player, ctx);
        if (p == null) {
            return 0;
        }
        p.setPrice(IntegerArgumentType.getInteger(ctx, "price"));
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Prix de '" + p.id() + "' : " + EconomyManager.format(p.price()) + "."));
        return 1;
    }

    private static int setsale(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = byId(player, ctx);
        if (p == null) {
            return 0;
        }
        boolean value = BoolArgumentType.getBool(ctx, "value");
        p.setForSale(value);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Parcelle '" + p.id() + "' en vente : " + value + "."));
        return 1;
    }

    private static int setowner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = byId(player, ctx);
        if (p == null) {
            return 0;
        }
        GameProfile gp = profile(ctx);
        if (gp == null) {
            player.sendSystemMessage(Messages.error("Joueur introuvable."));
            return 0;
        }
        p.members().clear();
        p.setOwner(gp.getId(), gp.getName());
        p.setForSale(false);
        ParcelData.get(player.server).setDirty();
        player.sendSystemMessage(Messages.success("Proprietaire de '" + p.id() + "' : " + gp.getName() + "."));
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        ParcelData data = ParcelData.get(player.server);
        if (!data.exists(id)) {
            player.sendSystemMessage(Messages.error("Parcelle '" + id + "' introuvable."));
            return 0;
        }
        data.remove(id);
        player.sendSystemMessage(Messages.success("Parcelle '" + id + "' supprimee."));
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Parcel p = byId(player, ctx);
        if (p == null) {
            return 0;
        }
        double[] center = p.firstRegionCenter();
        if (center == null) {
            player.sendSystemMessage(Messages.error("Cette parcelle n'a aucune region."));
            return 0;
        }
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, p.dimension()));
        if (level == null) {
            level = player.serverLevel();
        }
        player.teleportTo(level, center[0], center[1], center[2], player.getYRot(), player.getXRot());
        player.sendSystemMessage(Messages.success("Teleporte a la parcelle '" + p.id() + "'."));
        return 1;
    }

    private static Parcel byId(ServerPlayer player, CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        Parcel p = ParcelData.get(player.server).get(id);
        if (p == null) {
            player.sendSystemMessage(Messages.error("Parcelle '" + id + "' introuvable."));
        }
        return p;
    }

    private static void warnOverlap(ServerPlayer player, ParcelData data, ResourceLocation dim, Parcel.Box box, String ignoreId) {
        List<Parcel> overlap = data.overlapping(dim, box, ignoreId);
        if (!overlap.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Parcel o : overlap) {
                sb.append(o.id()).append(' ');
            }
            player.sendSystemMessage(Messages.warn("Attention : chevauche " + sb.toString().trim() + ".")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
