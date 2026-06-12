package com.utopia.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.utopia.daily.DailyMenus;
import com.utopia.data.RoomData;
import com.utopia.economy.EconomyMenus;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
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

        OwoMenuServer.openHub(player, title, stats, entries, AdminMenu::open, null);
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
