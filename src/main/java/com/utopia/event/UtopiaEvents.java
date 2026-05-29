package com.utopia.event;

import com.mojang.brigadier.CommandDispatcher;
import com.utopia.UtopiaMod;
import com.utopia.clearlag.ClearLagManager;
import com.utopia.command.ClearLagCommand;
import com.utopia.command.DailyCommand;
import com.utopia.command.EconomyCommands;
import com.utopia.command.ParcelCommands;
import com.utopia.command.SpawnCommands;
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
        UtopiaMod.LOGGER.info("[Utopia] Commandes enregistrees (tpa, tpahere, tpaccept, tpadeny, spawn, setspawn, daily, clearlag, balance, pay, withdraw, deposit, money, parcel).");
    }

    // ----------------------------------------------------------------------------------- Parcelles

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp && event.getLevel() instanceof ServerLevel level) {
            if (!ParcelManager.isActionAllowed(sp, level, event.getPos(), Parcel.Flag.BUILD)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.error("Vous n'avez pas le droit de modifier cette parcelle."));
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && event.getLevel() instanceof ServerLevel level) {
            if (!ParcelManager.isActionAllowed(sp, level, event.getPos(), Parcel.Flag.BUILD)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Messages.error("Vous n'avez pas le droit de construire ici."));
            }
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
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        // Outil de trace : ajouter un point (clic droit au sol).
        if (ParcelManager.isWand(event.getItemStack()) && ParcelManager.isOp(sp)) {
            int n = ParcelManager.addPoint(sp, pos);
            sp.sendSystemMessage(Messages.success("Point " + n + " : " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            event.setCanceled(true);
            return;
        }
        // Protection des interactions (coffres, portes, machines).
        Parcel.Flag flag = ParcelManager.requiredInteractFlag(level, pos);
        if (flag != null && !ParcelManager.isActionAllowed(sp, level, pos, flag)) {
            event.setCanceled(true);
            sp.sendSystemMessage(Messages.error("Interaction protegee sur cette parcelle."));
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
        event.getAffectedBlocks().removeIf(pos ->
                data.parcelAt(dim, pos.getX(), pos.getY(), pos.getZ()) != null);
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
        // Deposer des pieces en banque par clic droit en les tenant.
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!EconomyManager.isCoin(held)) {
            return;
        }
        int amount = held.getCount();
        EconomyManager.add(sp.server, sp.getUUID(), amount);
        held.setCount(0);
        sp.sendSystemMessage(Messages.success("Depose " + EconomyManager.format(amount)
                + ". Solde : " + EconomyManager.format(EconomyManager.getBalance(sp.server, sp.getUUID())) + "."));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // Cet event est emis AVANT la sauvegarde de l'inventaire : on rend ici les items eventuellement
        // deposes dans l'editeur de recompenses (sinon ils seraient perdus, removed() n'etant pas appele).
        if (event.getEntity() instanceof ServerPlayer sp && sp.containerMenu instanceof UtopiaMenu menu) {
            menu.handleLogout(sp);
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
        }
        // Synchronisation des hologrammes + balayage anti-feu (toutes les ~2s).
        if (t % 40 == 0) {
            ParcelHolograms.syncHolograms(server);
            if (Config.PARCEL_EXTINGUISH_FIRE.get()) {
                ParcelManager.sweepFire(server);
            }
        }
    }
}
