package com.utopia.menu;

import java.util.ArrayList;
import java.util.List;

import com.utopia.Config;
import com.utopia.daily.DailyMenus;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.economy.EconomyMenus;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.OwoMenuServer;
import com.utopia.parcel.ParcelMenus;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Menu central ({@code /menu}) : point d'entree de toutes les actions joueur (parcelles, boutique,
 * banque, teleportation vers un joueur, retour au spawn, quetes...). Chaque sous-menu peut revenir ici.
 */
public final class MainMenu {

    private MainMenu() {
    }

    public static void open(ServerPlayer player) {
        MinecraftServer server = player.server;

        // En-tete + statistiques (deja formatees cote serveur).
        Component title = Component.literal("UTOPIA - " + player.getGameProfile().getName())
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        long balance = EconomyManager.getBalance(server, player.getUUID());
        int coins = EconomyManager.countCoins(player);
        int parcels = ParcelData.get(server).ownedBy(player.getUUID()).size();
        List<Component> stats = List.of(
                stat("Solde en banque : ", EconomyManager.format(balance) + " $", ChatFormatting.GOLD),
                stat("Pieces en main : ", Integer.toString(coins), ChatFormatting.AQUA),
                stat("Parcelles possedees : ", Integer.toString(parcels), ChatFormatting.GREEN));

        // Gros boutons d'acces rapide.
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(entry(Items.GRASS_BLOCK, "Mes parcelles", ChatFormatting.GREEN, "Gerer / vendre",
                sp -> ParcelMenus.openMyParcels(sp, 0)));
        entries.add(entry(Items.EMERALD, "Boutique", ChatFormatting.GREEN, "Acheter une parcelle",
                ParcelMenus::openShop));
        entries.add(entry(EconomyManager.coinItem(), "Banque", ChatFormatting.GOLD, "Solde, payer, retirer",
                EconomyMenus::openPlayerMenu));
        entries.add(entry(Items.CHEST, "Recompense", ChatFormatting.GOLD, "Ta recompense du jour",
                DailyMenus::openPlayerMenu));
        entries.add(entry(Items.ENDER_PEARL, "Se teleporter", ChatFormatting.LIGHT_PURPLE, "Vers un joueur (/tpa)",
                MainMenu::openTpaPicker));
        entries.add(entry(Items.COMPASS, "Retour au spawn", ChatFormatting.AQUA, "Spawn du serveur",
                sp -> {
                    Menus.close(sp);
                    runAs(sp, "spawn");
                }));
        entries.add(entry(Items.WRITTEN_BOOK, "Quetes", ChatFormatting.YELLOW, "Livre de quetes",
                sp -> {
                    String cmd = Config.MENU_QUEST_COMMAND.get();
                    if (cmd == null || cmd.isBlank()) {
                        sp.sendSystemMessage(Messages.warn("Bouton Quetes non configure (config menu.questCommand)."));
                        return;
                    }
                    Menus.close(sp);
                    runAs(sp, cmd);
                }));

        OwoMenuServer.openHub(player, title, stats, entries, MainMenu::open);
    }

    /** Construit une ligne de stat "label: valeur" (label gris, valeur coloree). */
    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }

    /** Construit une entree de hub (icone + libelle colore + sous-libelle gris + action). */
    private static OwoMenuServer.HubEntry entry(net.minecraft.world.level.ItemLike item, String label,
                                                ChatFormatting color, String sublabel, java.util.function.Consumer<ServerPlayer> action) {
        return new OwoMenuServer.HubEntry(new ItemStack(item),
                Icons.label(label, color),
                Icons.lore(sublabel, ChatFormatting.GRAY),
                action);
    }

    /** Selecteur de joueur en ligne -> envoie une demande /tpa. */
    public static void openTpaPicker(ServerPlayer player) {
        MinecraftServer server = player.server;
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Se teleporter a...", ChatFormatting.DARK_AQUA));
        int slot = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            if (target.getUUID().equals(player.getUUID())) {
                continue;
            }
            String name = target.getGameProfile().getName();
            gui.button(slot++, Icons.playerHead(target, Icons.label(name, ChatFormatting.WHITE),
                    List.of(Icons.lore("Envoyer une demande de TP", ChatFormatting.GRAY))),
                    sp -> {
                        Menus.close(sp);
                        runAs(sp, "tpa " + name);
                    });
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun autre joueur en ligne", ChatFormatting.RED), List.of()));
        }
        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour au menu", ChatFormatting.YELLOW), List.of()),
                MainMenu::open);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void runAs(ServerPlayer player, String command) {
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }
}
