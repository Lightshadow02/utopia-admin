package com.utopia.menu;

import java.util.List;

import com.utopia.Config;
import com.utopia.daily.DailyMenus;
import com.utopia.economy.EconomyManager;
import com.utopia.economy.EconomyMenus;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.parcel.ParcelMenus;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * Menu central ({@code /menu}) : point d'entree de toutes les actions joueur (parcelles, boutique,
 * banque, teleportation vers un joueur, retour au spawn, quetes...). Chaque sous-menu peut revenir ici.
 */
public final class MainMenu {

    private MainMenu() {
    }

    public static void open(ServerPlayer player) {
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Menu", ChatFormatting.GOLD));

        gui.button(10, Icons.icon(Items.GRASS_BLOCK, Icons.label("Mes parcelles", ChatFormatting.GREEN),
                List.of(Icons.lore("Gerer / vendre tes parcelles", ChatFormatting.GRAY))),
                sp -> ParcelMenus.openMyParcels(sp, 0));
        gui.button(11, Icons.icon(Items.EMERALD, Icons.label("Boutique de parcelles", ChatFormatting.GREEN),
                List.of(Icons.lore("Acheter une parcelle en vente", ChatFormatting.GRAY))),
                sp -> ParcelMenus.openShop(sp));
        gui.button(12, Icons.icon(EconomyManager.coinItem(), Icons.label("Banque", ChatFormatting.GOLD),
                List.of(Icons.lore("Solde, payer, retirer, deposer", ChatFormatting.GRAY))),
                sp -> EconomyMenus.openPlayerMenu(sp));
        gui.button(13, Icons.icon(Items.CHEST, Icons.label("Recompense quotidienne", ChatFormatting.GOLD),
                List.of(Icons.lore("Reclame ta recompense du jour", ChatFormatting.GRAY))),
                sp -> DailyMenus.openPlayerMenu(sp));
        gui.button(14, Icons.icon(Items.ENDER_PEARL, Icons.label("Se teleporter a un joueur", ChatFormatting.LIGHT_PURPLE),
                List.of(Icons.lore("Envoyer une demande de TP (/tpa)", ChatFormatting.GRAY))),
                MainMenu::openTpaPicker);
        gui.button(15, Icons.icon(Items.COMPASS, Icons.label("Retour au spawn", ChatFormatting.AQUA),
                List.of(Icons.lore("Te teleporte au spawn du serveur", ChatFormatting.GRAY))),
                sp -> {
                    Menus.close(sp);
                    runAs(sp, "spawn");
                });
        gui.button(16, Icons.icon(Items.WRITTEN_BOOK, Icons.label("Quetes", ChatFormatting.YELLOW),
                List.of(Icons.lore("Ouvre le livre de quetes", ChatFormatting.GRAY))),
                sp -> {
                    String cmd = Config.MENU_QUEST_COMMAND.get();
                    if (cmd == null || cmd.isBlank()) {
                        sp.sendSystemMessage(Messages.warn("Bouton Quetes non configure (config menu.questCommand)."));
                        return;
                    }
                    Menus.close(sp);
                    runAs(sp, cmd);
                });

        gui.button(22, Icons.icon(Items.BARRIER, Icons.label("Fermer", ChatFormatting.RED), List.of()),
                com.utopia.gui.Menus::close);
        gui.fillEmpty();
        Menus.open(player, gui);
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
