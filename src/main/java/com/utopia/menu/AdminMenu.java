package com.utopia.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.daily.DailyMenus;
import com.utopia.data.MarketData;
import com.utopia.data.RoomData;
import com.utopia.economy.EconomyMenus;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.market.MarketManager;
import com.utopia.market.MarketMenus;
import com.utopia.net.OwoMenuServer;
import com.utopia.parcel.ParcelMenus;
import com.utopia.room.RoomManager;
import com.utopia.room.RoomMenus;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hub d'administration ({@code /admin}), reserve aux operateurs (op niveau 2). Centralise les outils
 * d'admin : parcelles, economie, recompenses, auberge, et la designation des aubergistes.
 */
public final class AdminMenu {

    /** Entrees par page dans les selecteurs (joueurs, warps...) : garde les menus sous la limite d'actions. */
    private static final int PICKER_PAGE_SIZE = 12;

    private AdminMenu() {
    }

    public static void open(ServerPlayer player) {
        Component title = Icons.screenTitle("Administration", ChatFormatting.RED);
        List<Component> stats = List.of(Component.literal("Outils reserves aux operateurs")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (Config.ADMIN_PARCELS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GRASS_BLOCK),
                    Icons.label("Parcelles", ChatFormatting.GREEN),
                    Icons.lore("Gerer toutes les parcelles", ChatFormatting.GRAY),
                    ParcelMenus::openAdminAll));
        }
        if (Config.ADMIN_ECONOMY.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_INGOT),
                    Icons.label("Economie", ChatFormatting.GOLD),
                    Icons.lore("Soldes des joueurs en ligne", ChatFormatting.GRAY),
                    EconomyMenus::openAdminMenu));
        }
        if (Config.ADMIN_DAILY.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                    Icons.label("Recompenses (daily)", ChatFormatting.GOLD),
                    Icons.lore("Calendrier et recompenses", ChatFormatting.GRAY),
                    DailyMenus::openAdminMenu));
        }
        if (Config.ADMIN_ROOMS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WHITE_BED),
                    Icons.label("Auberge / chambres", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Chambres + configuration (outil, bloc d'acces)", ChatFormatting.GRAY),
                    AdminMenu::openAubergeAdmin));
        }
        if (Config.ADMIN_INNKEEPERS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                    Icons.label("Aubergistes", ChatFormatting.AQUA),
                    Icons.lore("Designer qui peut ouvrir /auberge", ChatFormatting.GRAY),
                    AdminMenu::openAubergistePicker));
        }
        if (Config.ADMIN_MARKET.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.EMERALD_BLOCK),
                    Icons.label("Marche : definir un stand", ChatFormatting.GREEN),
                    Icons.lore("Active le mode, puis CASSE le bloc qui sera le stand", ChatFormatting.GRAY),
                    sp -> {
                        MarketManager.startStallSelect(sp.getUUID());
                        sp.sendSystemMessage(Messages.info("Mode actif : casse le bloc qui servira de stand de marche."));
                        Menus.close(sp);
                    }));
        }
        if (Config.ADMIN_MARKETRECOVERY.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST_MINECART),
                    Icons.label("Recuperation marche", ChatFormatting.GOLD),
                    Icons.lore("Objets expires en attente de restitution", ChatFormatting.GRAY),
                    MarketMenus::openRecoveryAdmin));
        }
        if (Config.ADMIN_MAIRE.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLDEN_HELMET),
                    Icons.label("Maire", ChatFormatting.GOLD),
                    Icons.lore("Designer qui accede a /maire (compte de la mairie)", ChatFormatting.GRAY),
                    AdminMenu::openMairePicker));
        }
        if (Config.ADMIN_INVENTORIES.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_CHEST),
                    Icons.label("Inventaires", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Basculer entre l'inventaire 1 et 2 (garder sa survie avant le creatif)", ChatFormatting.GRAY),
                    AdminMenu::openInventorySwitch));
        }
        if (Config.ADMIN_WARPS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPASS),
                    Icons.label("Warps", ChatFormatting.AQUA),
                    Icons.lore("Points de teleportation admin (/setwarp pour en creer)", ChatFormatting.GRAY),
                    AdminMenu::openWarps));
        }
        if (Config.ADMIN_ELECTIONS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                    Icons.label("Elections", ChatFormatting.GOLD),
                    Icons.lore("Creer/lancer une election, hologramme des resultats, tests", ChatFormatting.GRAY),
                    com.utopia.election.ElectionMenus::openAdminMenu));
        }
        if (Config.ADMIN_JOBS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_INGOT),
                    Icons.label("Metiers et salaires", ChatFormatting.GOLD),
                    Icons.lore("Metiers, salaires quotidiens, employes, banquiers", ChatFormatting.GRAY),
                    com.utopia.job.JobMenus::open));
        }
        if (Config.ADMIN_WAYSTONES.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_PEARL),
                    Icons.label("Balises de voyage", ChatFormatting.AQUA),
                    Icons.lore("Reseau de deplacement : distribuer, nommer, ouvrir a tous",
                            ChatFormatting.GRAY),
                    com.utopia.waystone.WaystoneMenus::openAdmin));
        }
        if (Config.ADMIN_NPCS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARMOR_STAND),
                    Icons.label("Statues", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("PNJ decoratifs a l'effigie d'un joueur, visage conserve",
                            ChatFormatting.GRAY),
                    com.utopia.npc.NpcMenus::open));
        }
        if (Config.ADMIN_NPCS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ARMOR_STAND),
                    Icons.label("Statues", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Personnages decoratifs a l'effigie d'un joueur", ChatFormatting.GRAY),
                    com.utopia.npc.NpcMenus::open));
        }
        if (Config.ADMIN_HOLOGRAMS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GLOW_ITEM_FRAME),
                    Icons.label("Hologrammes", ChatFormatting.LIGHT_PURPLE),
                    Icons.lore("Panneaux de texte libres : lignes, couleurs, position", ChatFormatting.GRAY),
                    com.utopia.hologram.HologramMenus::open));
        }
        if (Config.ADMIN_BETS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_NUGGET),
                    Icons.label("Paris", ChatFormatting.GOLD),
                    Icons.lore("Registre complet, controle des cagnottes, paris a surveiller",
                            ChatFormatting.GRAY),
                    com.utopia.bet.BetAdminMenus::open));
        }
        if (Config.ADMIN_QUOTES.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                    Icons.label("Devis des joueurs", ChatFormatting.YELLOW),
                    Icons.lore("Historique des devis emis et recus, taxe, validite", ChatFormatting.GRAY),
                    com.utopia.quote.QuoteMenus::openAdmin));
        }
        if (Config.ADMIN_SAVINGS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_NUGGET),
                    Icons.label("Livrets d'epargne", ChatFormatting.GOLD),
                    Icons.lore("Registre des livrets, bareme des taux, suivi quotidien", ChatFormatting.GRAY),
                    com.utopia.savings.SavingsMenus::openRegistry));
        }
        if (Config.ADMIN_CHANTIERS.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.SCAFFOLDING),
                    Icons.label("Chantiers", ChatFormatting.GOLD),
                    Icons.lore("Collectes communautaires, PNJ, objectifs, registre", ChatFormatting.GRAY),
                    com.utopia.chantier.ChantierMenus::openAdmin));
        }
        if (Config.ADMIN_TRANSIT.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.OAK_BOAT),
                    Icons.label("Capitaines Transit", ChatFormatting.AQUA),
                    Icons.lore("Traversees vers le continent, destinations, point de retour", ChatFormatting.GRAY),
                    com.utopia.transit.TransitMenus::openAdmin));
        }
        if (Config.ADMIN_STRUCTURES.get()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.STRUCTURE_BLOCK),
                    Icons.label("Structures", ChatFormatting.AQUA),
                    Icons.lore("Zones a 2 etats (bascule manuelle ou auto jour/nuit)", ChatFormatting.GRAY),
                    sp -> {
                        if (com.utopia.structure.StructureManager.isSelecting(sp.getUUID())) {
                            com.utopia.structure.StructureMenus.openSelection(sp); // selection en cours
                        } else {
                            com.utopia.structure.StructureMenus.openList(sp);
                        }
                    }));
        }

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::open, null);
    }

    /** Liste des warps admin : clic = teleportation. Creation via /setwarp <nom>. */
    public static void openWarps(ServerPlayer player) {
        openWarps(player, 0);
    }

    public static void openWarps(ServerPlayer player, int page) {
        com.utopia.data.WarpData data = com.utopia.data.WarpData.get(player.server);
        List<String> names = data.names();

        Component title = Icons.title("Warps admin", ChatFormatting.AQUA);
        List<Component> stats = List.of(Component.literal(names.isEmpty()
                ? "Aucun warp - /setwarp <nom> pour en creer"
                : names.size() + " warp(s) - /setwarp, /delwarp")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (String name : names) {
            com.utopia.data.WarpData.Warp warp = data.get(name);
            String coords = String.format("%.0f, %.0f, %.0f", warp.x(), warp.y(), warp.z());
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPASS),
                    Icons.label(name, ChatFormatting.WHITE),
                    Icons.lore(coords, ChatFormatting.GRAY),
                    sp -> {
                        com.utopia.command.WarpCommands.teleport(sp, warp);
                        sp.sendSystemMessage(Messages.success("Teleporte au warp " + name + "."));
                        Menus.close(sp);
                    }));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PICKER_PAGE_SIZE,
                AdminMenu::openWarps, AdminMenu::open);
    }

    /** Bascule entre les deux inventaires sauvegardes (Inventaire 1 / Inventaire 2). */
    public static void openInventorySwitch(ServerPlayer player) {
        int active = com.utopia.data.InventoryData.get(player.server).getActive(player.getUUID());

        Component title = Icons.title("Inventaires", ChatFormatting.LIGHT_PURPLE);
        List<Component> stats = List.of(
                Component.literal("Actif : Inventaire " + active)
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Basculer sauvegarde l'inventaire courant et charge l'autre.")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (int slot = 1; slot <= 2; slot++) {
            final int target = slot;
            boolean isActive = active == slot;
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                    Icons.label("Inventaire " + slot, isActive ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(isActive ? "Actuellement actif" : "Cliquer pour basculer sur cet inventaire",
                            isActive ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    sp -> {
                        com.utopia.inventory.InventoryManager.switchTo(sp, target);
                        openInventorySwitch(sp);
                    }));
        }

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::openInventorySwitch, AdminMenu::open);
    }

    /** Selecteur des joueurs en ligne : bascule le statut de maire (acces a /maire). */
    public static void openMairePicker(ServerPlayer player) {
        openMairePicker(player, 0);
    }

    public static void openMairePicker(ServerPlayer player, int page) {
        MinecraftServer server = player.server;
        MarketData data = MarketData.get(server);

        Component title = Icons.title("Maire", ChatFormatting.GOLD);
        List<Component> stats = List.of(Component.literal(data.maires().size() + " maire(s) designe(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            String tname = target.getGameProfile().getName();
            boolean isMaire = data.isMaire(tid);
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, isMaire ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    Icons.lore(isMaire ? "Maire : OUI (clic pour retirer)" : "Maire : non (clic pour nommer)",
                            isMaire ? ChatFormatting.GOLD : ChatFormatting.GRAY),
                    sp -> toggleMaire(sp, tid, tname)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PICKER_PAGE_SIZE,
                AdminMenu::openMairePicker, AdminMenu::open);
    }

    private static void toggleMaire(ServerPlayer admin, UUID targetId, String targetName) {
        MinecraftServer server = admin.server;
        boolean now = MarketData.get(server).toggleMaire(targetId);
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target != null) {
            server.getCommands().sendCommands(target); // rafraichit l'arbre (/maire apparait/disparait)
            target.sendSystemMessage(now
                    ? Messages.success("Vous etes nomme MAIRE : la commande /maire est disponible.")
                    : Messages.warn("Vous n'etes plus maire."));
        }
        admin.sendSystemMessage(now ? Messages.success(targetName + " est nomme maire.")
                : Messages.info(targetName + " n'est plus maire."));
        openMairePicker(admin);
    }

    /** Sous-menu auberge (op) : gerer les chambres + outils de configuration (outil chambre, bloc d'acces). */
    public static void openAubergeAdmin(ServerPlayer admin) {
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WHITE_BED),
                Icons.label("Gerer les chambres", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Liste et gestion des chambres", ChatFormatting.GRAY),
                RoomMenus::openAuberge));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(RoomManager.wandItem()),
                Icons.label("Recevoir l'outil chambre", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Trace une chambre, puis /room create <id>", ChatFormatting.GRAY),
                sp -> {
                    sp.getInventory().add(new ItemStack(RoomManager.wandItem()));
                    sp.sendSystemMessage(Messages.success("Outil chambre recu."));
                    Menus.close(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.LODESTONE),
                Icons.label("Definir le bloc d'acces", ChatFormatting.AQUA),
                Icons.lore("Active le mode, puis CASSE le bloc voulu (clic droit dessus = auberge)", ChatFormatting.GRAY),
                sp -> {
                    RoomManager.startAubergeBlockSelect(sp.getUUID());
                    sp.sendSystemMessage(Messages.info("Mode actif : casse le bloc qui servira d'acces a l'auberge."));
                    Menus.close(sp);
                }));

        Component title = Icons.title("Auberge - configuration", ChatFormatting.GOLD);
        OwoMenuServer.openHub(admin, title, List.of(), entries, AdminMenu::openAubergeAdmin, AdminMenu::open);
    }

    /** Selecteur des joueurs en ligne : bascule le statut d'aubergiste. */
    public static void openAubergistePicker(ServerPlayer player) {
        openAubergistePicker(player, 0);
    }

    public static void openAubergistePicker(ServerPlayer player, int page) {
        MinecraftServer server = player.server;
        RoomData data = RoomData.get(server);

        Component title = Icons.title("Aubergistes", ChatFormatting.AQUA);
        List<Component> stats = List.of(Component.literal(data.aubergistes().size() + " aubergiste(s) designe(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            UUID tid = target.getUUID();
            String tname = target.getGameProfile().getName();
            boolean isAub = data.isAubergiste(tid);
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(tname, ChatFormatting.WHITE), List.of()),
                    Icons.label(tname, isAub ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    Icons.lore(isAub ? "Aubergiste : OUI (clic pour retirer)" : "Aubergiste : non (clic pour ajouter)",
                            isAub ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    sp -> toggle(sp, tid, tname)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PICKER_PAGE_SIZE,
                AdminMenu::openAubergistePicker, AdminMenu::open);
    }

    private static void toggle(ServerPlayer admin, UUID targetId, String targetName) {
        MinecraftServer server = admin.server;
        boolean now = RoomData.get(server).toggleAubergiste(targetId);
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target != null) {
            server.getCommands().sendCommands(target); // rafraichit l'arbre de commandes (/auberge apparait/disparait)
            target.sendSystemMessage(now
                    ? Messages.success("Vous etes desormais aubergiste : la commande /auberge est disponible.")
                    : Messages.warn("Vous n'etes plus aubergiste."));
        }
        admin.sendSystemMessage(now ? Messages.success(targetName + " est maintenant aubergiste.")
                : Messages.info(targetName + " n'est plus aubergiste."));
        openAubergistePicker(admin);
    }
}
