package com.utopia.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    private AdminMenu() {
    }

    public static void open(ServerPlayer player) {
        Component title = Component.literal("ADMINISTRATION")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true));
        List<Component> stats = List.of(Component.literal("Outils reserves aux operateurs")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GRASS_BLOCK),
                Icons.label("Parcelles", ChatFormatting.GREEN),
                Icons.lore("Gerer toutes les parcelles", ChatFormatting.GRAY),
                ParcelMenus::openAdminAll));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLD_INGOT),
                Icons.label("Economie", ChatFormatting.GOLD),
                Icons.lore("Soldes des joueurs en ligne", ChatFormatting.GRAY),
                EconomyMenus::openAdminMenu));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                Icons.label("Recompenses (daily)", ChatFormatting.GOLD),
                Icons.lore("Calendrier et recompenses", ChatFormatting.GRAY),
                DailyMenus::openAdminMenu));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WHITE_BED),
                Icons.label("Auberge / chambres", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Chambres + configuration (outil, bloc d'acces)", ChatFormatting.GRAY),
                AdminMenu::openAubergeAdmin));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Aubergistes", ChatFormatting.AQUA),
                Icons.lore("Designer qui peut ouvrir /auberge", ChatFormatting.GRAY),
                AdminMenu::openAubergistePicker));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.EMERALD_BLOCK),
                Icons.label("Marche : definir un stand", ChatFormatting.GREEN),
                Icons.lore("Active le mode, puis CASSE le bloc qui sera le stand", ChatFormatting.GRAY),
                sp -> {
                    MarketManager.startStallSelect(sp.getUUID());
                    sp.sendSystemMessage(Messages.info("Mode actif : casse le bloc qui servira de stand de marche."));
                    Menus.close(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST_MINECART),
                Icons.label("Recuperation marche", ChatFormatting.GOLD),
                Icons.lore("Objets expires en attente de restitution", ChatFormatting.GRAY),
                MarketMenus::openRecoveryAdmin));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.GOLDEN_HELMET),
                Icons.label("Maire", ChatFormatting.GOLD),
                Icons.lore("Designer qui accede a /maire (compte de la mairie)", ChatFormatting.GRAY),
                AdminMenu::openMairePicker));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.ENDER_CHEST),
                Icons.label("Inventaires", ChatFormatting.LIGHT_PURPLE),
                Icons.lore("Basculer entre l'inventaire 1 et 2 (garder sa survie avant le creatif)", ChatFormatting.GRAY),
                AdminMenu::openInventorySwitch));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPASS),
                Icons.label("Warps", ChatFormatting.AQUA),
                Icons.lore("Points de teleportation admin (/setwarp pour en creer)", ChatFormatting.GRAY),
                AdminMenu::openWarps));

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::open, null);
    }

    /** Liste des warps admin : clic = teleportation. Creation via /setwarp <nom>. */
    public static void openWarps(ServerPlayer player) {
        com.utopia.data.WarpData data = com.utopia.data.WarpData.get(player.server);
        List<String> names = data.names();

        Component title = Component.literal("Warps admin")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
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

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::openWarps, AdminMenu::open);
    }

    /** Bascule entre les deux inventaires sauvegardes (Inventaire 1 / Inventaire 2). */
    public static void openInventorySwitch(ServerPlayer player) {
        int active = com.utopia.data.InventoryData.get(player.server).getActive(player.getUUID());

        Component title = Component.literal("Inventaires")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true));
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
        MinecraftServer server = player.server;
        MarketData data = MarketData.get(server);

        Component title = Component.literal("Maire")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
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

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::openMairePicker, AdminMenu::open);
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

        Component title = Component.literal("Auberge - configuration")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        OwoMenuServer.openHub(admin, title, List.of(), entries, AdminMenu::openAubergeAdmin, AdminMenu::open);
    }

    /** Selecteur des joueurs en ligne : bascule le statut d'aubergiste. */
    public static void openAubergistePicker(ServerPlayer player) {
        MinecraftServer server = player.server;
        RoomData data = RoomData.get(server);

        Component title = Component.literal("Aubergistes")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
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

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::openAubergistePicker, AdminMenu::open);
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
