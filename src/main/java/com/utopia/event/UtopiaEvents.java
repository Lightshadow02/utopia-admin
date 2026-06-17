package com.utopia.event;

import com.mojang.brigadier.CommandDispatcher;
import com.utopia.UtopiaMod;
import com.utopia.clearlag.ClearLagManager;
import com.utopia.command.ClearLagCommand;
import com.utopia.command.DailyCommand;
import com.utopia.command.EconomyCommands;
import com.utopia.command.ParcelCommands;
import com.utopia.command.RoomCommands;
import com.utopia.command.SpawnCommands;
import com.utopia.data.RoomData;
import com.utopia.room.RoomManager;
import com.utopia.command.TpaCommands;
import com.utopia.Config;
import com.utopia.daily.DailyManager;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.UtopiaMenu;
import com.utopia.parcel.Parcel;
import com.utopia.parcel.ParcelHolograms;
import com.utopia.parcel.ParcelManager;
import com.utopia.teleport.TeleportManager;
import com.utopia.teleport.TpaManager;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Abonnements aux evenements du bus de jeu : enregistrement des commandes et
 * traitement des decomptes (teleportations en attente, expiration des demandes).
 */
// Le bus par defaut de @EventBusSubscriber est GAME (verifie dans NeoForge 21.1),
// ce qui correspond aux evenements ci-dessous (commandes, tick serveur).
@EventBusSubscriber(modid = UtopiaMod.MODID)
public final class UtopiaEvents {

    private UtopiaEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        TpaCommands.register(dispatcher);
        SpawnCommands.register(dispatcher);
        DailyCommand.register(dispatcher);
        ClearLagCommand.register(dispatcher);
        EconomyCommands.register(dispatcher);
        ParcelCommands.register(dispatcher);
        RoomCommands.register(dispatcher);
        com.utopia.command.MenuCommand.register(dispatcher);
        com.utopia.command.AdminCommand.register(dispatcher);
        com.utopia.command.MaireCommand.register(dispatcher);
        UtopiaMod.LOGGER.info("[Utopia] Commandes enregistrees (tpa, spawn, daily, clearlag, balance/baltop, pay, withdraw, deposit, money, parcel, room/auberge, menu, admin).");
    }

    // ----------------------------------------------------------------------------------- Parcelles

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp && event.getLevel() instanceof ServerLevel level) {
            ResourceLocation dim = level.dimension().location();
            BlockPos pos = event.getPos();
            // Mode "definir le bloc d'acces auberge" : ce bloc devient l'acces (casse annulee).
            if (RoomManager.isSelectingAubergeBlock(sp.getUUID())) {
                RoomData.get(level.getServer()).addAubergeBlock(dim, pos);
                RoomManager.clearAubergeBlockSelect(sp.getUUID());
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.success("Bloc d'acces a l'auberge defini ! Clic droit dessus pour ouvrir le gestionnaire de chambres."));
                return;
            }
            // Si on casse un bloc d'acces enregistre, on le retire de la liste.
            if (RoomData.get(level.getServer()).isAubergeBlock(dim, pos)) {
                RoomData.get(level.getServer()).removeAubergeBlock(dim, pos);
            }
            // Mode "definir un stand de marche" : ce bloc devient un emplacement de vente.
            if (com.utopia.market.MarketManager.isSelectingStall(sp.getUUID())) {
                com.utopia.data.MarketData.get(level.getServer()).addStall(dim, pos);
                com.utopia.market.MarketManager.clearStallSelect(sp.getUUID());
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.success("Stand de marche cree ! Les joueurs feront clic droit dessus pour vendre/acheter."));
                return;
            }
            // Casser un stand enregistre : on le retire (offres invendues -> recuperation mairie).
            if (com.utopia.data.MarketData.get(level.getServer()).isStall(dim, pos)) {
                com.utopia.market.MarketManager.removeStall(level.getServer(), dim, pos);
            }
            if (!ParcelManager.isActionAllowed(sp, level, pos, Parcel.Flag.BUILD)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.error("Vous n'avez pas le droit de modifier cette parcelle."));
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (!ParcelManager.isActionAllowed(sp, level, pos, Parcel.Flag.BUILD)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.error("Vous n'avez pas le droit de construire ici."));
            }
            return;
        }
        // Pose par une entite non-joueur (projectile de slingshot, dispenser, sable, etc.) :
        // interdite dans une parcelle (protection contre les contournements).
        ResourceLocation dim = level.dimension().location();
        if (ParcelData.get(level.getServer()).parcelAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null
                || RoomData.get(level.getServer()).roomAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        // Outil de trace : annuler le dernier point (clic gauche).
        if (!(event.getEntity() instanceof ServerPlayer sp) || !(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (ParcelManager.isWand(event.getItemStack()) && ParcelManager.isOp(sp)) {
            BlockPos removed = ParcelManager.undoPoint(sp.getUUID());
            sp.sendSystemMessage(removed == null ? Messages.warn("Aucun point a annuler.")
                    : Messages.success("Point annule (reste " + ParcelManager.traceSize(sp.getUUID()) + ")."));
            event.setCanceled(true);
            return;
        }
        // Outil chambre : coin 1 (clic gauche), Y compris.
        if (RoomManager.isWand(event.getItemStack()) && RoomManager.isOp(sp)) {
            RoomManager.setCorner(sp, event.getPos(), true);
            BlockPos c = event.getPos();
            sp.sendSystemMessage(Messages.success("Chambre - coin 1 : " + c.getX() + " " + c.getY() + " " + c.getZ()));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        // Stand de marche : clic droit -> reserver / gerer / acheter.
        com.utopia.data.MarketData.Stall stall =
                com.utopia.data.MarketData.get(level.getServer()).stallAt(level.dimension().location(), pos);
        if (stall != null) {
            com.utopia.market.MarketMenus.openStall(sp, stall);
            event.setCanceled(true);
            return;
        }
        // Bloc d'acces auberge : clic droit -> gestionnaire de chambres (op ou aubergiste designe).
        RoomData roomData = RoomData.get(level.getServer());
        if (roomData.isAubergeBlock(level.dimension().location(), pos)) {
            if (sp.hasPermissions(2) || roomData.isAubergiste(sp.getUUID())) {
                com.utopia.room.RoomMenus.openAuberge(sp);
            } else {
                sp.sendSystemMessage(Messages.warn("Acces reserve aux aubergistes."));
            }
            event.setCanceled(true);
            return;
        }
        // Outil de trace : ajouter un point (clic droit au sol).
        if (ParcelManager.isWand(event.getItemStack()) && ParcelManager.isOp(sp)) {
            int n = ParcelManager.addPoint(sp, pos);
            sp.sendSystemMessage(Messages.success("Point " + n + " : " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            event.setCanceled(true);
            return;
        }
        // Outil chambre : coin 2 (clic droit), Y compris.
        if (RoomManager.isWand(event.getItemStack()) && RoomManager.isOp(sp)) {
            RoomManager.setCorner(sp, pos, false);
            sp.sendSystemMessage(Messages.success("Chambre - coin 2 : " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            event.setCanceled(true);
            return;
        }
        // Protection des interactions (coffres, portes, machines, Create).
        Parcel.Flag flag = ParcelManager.requiredInteractFlag(level, pos);
        boolean inRoom = RoomData.get(sp.server).roomAt(level.dimension().location(), pos.getX(), pos.getY(), pos.getZ()) != null;
        // Portes/trappes/boutons publics : sauf a l'interieur d'une chambre (privee a l'occupant).
        if (flag == Parcel.Flag.DOORS && Config.PARCEL_PUBLIC_DOORS.get() && !inRoom) {
            return;
        }
        if (flag != null && !ParcelManager.isActionAllowed(sp, level, pos, flag)) {
            event.setCanceled(true);
            sp.sendSystemMessage(Messages.error(inRoom
                    ? "Cette chambre ne vous appartient pas (ou est gelee)."
                    : "Interaction protegee sur cette parcelle."));
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        // Empeche de blesser/tuer les entites (villageois, animaux, cadres...) d'une parcelle.
        if (!Config.PARCEL_PROTECT_ENTITIES.get() || !(event.getEntity() instanceof ServerPlayer sp)
                || !(sp.serverLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getTarget().blockPosition();
        if (!ParcelManager.isActionAllowed(sp, level, pos, Parcel.Flag.BUILD)) {
            event.setCanceled(true);
            sp.sendSystemMessage(Messages.error("Vous ne pouvez pas attaquer les entites de cette parcelle."));
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        // Retire les blocs des parcelles de la liste des blocs detruits par l'explosion.
        if (!Config.PARCEL_PROTECT_EXPLOSIONS.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ResourceLocation dim = level.dimension().location();
        ParcelData data = ParcelData.get(level.getServer());
        RoomData rooms = RoomData.get(level.getServer());
        event.getAffectedBlocks().removeIf(pos ->
                data.parcelAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null
                        || rooms.roomAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // Charge les fichiers locaux : clear-lag (JSON) et calendrier des recompenses (JSON).
        ClearLagManager.reload();
        DailyManager.loadCalendar();
        com.utopia.util.Banner.print(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Previent le joueur si une recompense quotidienne est a recuperer.
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (DailyManager.isAvailable(sp.server, sp.getUUID())) {
            MutableComponent open = Component.literal("[/daily]").withStyle(s -> s
                    .withColor(ChatFormatting.GREEN).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/daily"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Ouvrir le menu des recompenses"))));
            sp.sendSystemMessage(Component.empty().append(Messages.PREFIX)
                    .append(Component.literal("Tu as une recompense quotidienne a recuperer ! ").withStyle(ChatFormatting.YELLOW))
                    .append(open));
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // Clic droit avec la carte bancaire en main -> ouvre le menu de banque.
        // (Le depot automatique des pieces au clic droit a ete retire : on passe par la carte ou /deposit.)
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (EconomyManager.isBankCard(event.getItemStack())) {
            com.utopia.economy.EconomyMenus.openPlayerMenu(sp);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // Cet event est emis AVANT la sauvegarde de l'inventaire : on rend ici les items eventuellement
        // deposes dans l'editeur de recompenses (sinon ils seraient perdus, removed() n'etant pas appele).
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (sp.containerMenu instanceof UtopiaMenu menu) {
                menu.handleLogout(sp);
            }
            // Oublie un eventuel menu owo encore ouvert (declenche son rappel de fermeture).
            com.utopia.net.OwoMenuServer.clear(sp);
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        TeleportManager.tick(server);
        TpaManager.tick(server);
        ClearLagManager.tick(server);
        int t = server.getTickCount();
        // Visualisation du trace de parcelle + apercu des delimitations (toutes les ~0.5s).
        if (t % 10 == 0) {
            ParcelManager.renderTraces(server);
            ParcelHolograms.renderPreviews(server);
            ParcelHolograms.renderNearby(server); // contour au sol des parcelles a < 20 blocs
            RoomManager.renderSelections(server);
        }
        // Synchronisation des hologrammes + balayage anti-feu (toutes les ~2s).
        if (t % 40 == 0) {
            ParcelHolograms.syncHolograms(server);
            com.utopia.economy.BalTopHologram.sync(server);
            com.utopia.market.MarketManager.tickExpiry(server);
            com.utopia.market.MarketHolograms.sync(server);
            if (Config.PARCEL_EXTINGUISH_FIRE.get()) {
                ParcelManager.sweepFire(server);
            }
        }
    }
}
