package com.utopia.menu;

import java.util.ArrayList;
import java.util.List;

import com.utopia.Config;
import com.utopia.daily.DailyMenus;
import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
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
        Component title = Icons.screenTitle("Utopia - " + player.getGameProfile().getName(),
                ChatFormatting.GOLD);

        long balance = EconomyManager.getBalance(server, player.getUUID());
        int coins = EconomyManager.countCoins(player);
        int parcels = ParcelData.get(server).ownedBy(player.getUUID()).size();
        List<Component> stats = List.of(
                stat("Solde en banque : ", balance + " Utopieces", ChatFormatting.GOLD),
                stat("Pieces en main : ", Integer.toString(coins), ChatFormatting.AQUA),
                stat("Parcelles possedees : ", Integer.toString(parcels), ChatFormatting.GREEN));

        // Gros boutons d'acces rapide.
        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (Config.MENU_PARCELS.get()) {
            entries.add(entry(Items.GRASS_BLOCK, "Mes parcelles", ChatFormatting.GREEN, "Gerer / vendre",
                    sp -> ParcelMenus.openMyParcels(sp, 0)));
        }
        if (Config.MENU_SHOP.get()) {
            entries.add(entry(Items.EMERALD, "Boutique", ChatFormatting.GREEN, "Acheter une parcelle",
                    ParcelMenus::openShop));
        }
        // Banque retiree du /menu : elle s'ouvre desormais par clic droit sur la carte bancaire.
        if (Config.MENU_DAILY.get()) {
            entries.add(entry(Items.CHEST, "Recompense", ChatFormatting.GOLD, "Ta recompense du jour",
                    DailyMenus::openPlayerMenu));
        }
        if (Config.MENU_QUOTES.get()) {
            entries.add(entry(Items.WRITABLE_BOOK, "Mes devis", ChatFormatting.YELLOW, quoteSublabel(server, player),
                    com.utopia.quote.QuoteMenus::openHome));
        }
        if (Config.MENU_BETS.get()) {
            entries.add(entry(Items.GOLD_NUGGET, "Creer un pari", ChatFormatting.GOLD, betSublabel(server, player),
                    com.utopia.bet.BetMenus::openCreate));
        }
        if (Config.MENU_TPA.get()) {
            entries.add(entry(Items.ENDER_PEARL, "Se teleporter", ChatFormatting.LIGHT_PURPLE, "Vers un joueur (/tpa)",
                    MainMenu::openTpaPicker));
        }
        if (Config.MENU_SPAWN.get()) {
            entries.add(entry(Items.COMPASS, "Retour au spawn", ChatFormatting.AQUA, "Spawn du serveur",
                    sp -> {
                        Menus.close(sp);
                        runAs(sp, "spawn");
                    }));
        }
        if (Config.MENU_SERVERS.get()) {
            entries.add(entry(Items.END_PORTAL_FRAME, "Changer de serveur", ChatFormatting.LIGHT_PURPLE,
                    "Rejoindre un autre serveur du reseau", MainMenu::openServers));
        }
        if (Config.MENU_QUESTS.get()) {
            entries.add(entry(Items.WRITTEN_BOOK, "Quetes", ChatFormatting.YELLOW, "Livre de quetes",
                    sp -> {
                        String cmd = Config.MENU_QUEST_COMMAND.get();
                        if (cmd == null || cmd.isBlank()) {
                            sp.sendSystemMessage(Messages.warn("Bouton Quetes non configure (config menu.questCommand)."));
                            return;
                        }
                        cmd = cmd.trim();
                        if (cmd.equals("ftbquests")) {
                            cmd = "ftbquests open_book"; // auto-correction de l'ancienne valeur incomplete
                        }
                        Menus.close(sp);
                        runAsOp(sp, cmd); // permission elevee : le livre s'ouvre meme pour les non-op
                    }));
        }

        OwoMenuServer.openHub(player, title, stats, entries, MainMenu::open, null);
    }

    /**
     * Sous-titre du bouton Pari. On ne liste jamais les paris en cours ici : pour en consulter un, il
     * faut trouver son Bookmaker dans le monde.
     */
    private static String betSublabel(MinecraftServer server, ServerPlayer player) {
        com.utopia.data.BetData.Bet active =
                com.utopia.data.BetData.get(server).activeOf(player.getUUID());
        return active == null ? "Placer un Bookmaker et ouvrir les mises"
                : "Vous avez deja un pari en cours";
    }

    /** Sous-titre du bouton Devis : signale d'emblee ce qui attend une reponse. */
    private static String quoteSublabel(MinecraftServer server, ServerPlayer player) {
        int waiting = com.utopia.data.QuoteData.get(server).awaitingCount(player.getUUID());
        return waiting > 0 ? waiting + " devis en attente de reponse" : "Rediger, envoyer, regler";
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

    /** Entrees par page dans le selecteur de joueurs. */
    private static final int PICKER_PAGE_SIZE = 12;

    /**
     * Serveurs du reseau. Le changement passe par le proxy Velocity : le mod lui envoie une demande
     * de connexion, le client ne voit rien. Le serveur ou l'on se trouve deja est montre mais pas
     * proposable, pour qu'on comprenne ou l'on est plutot que de le voir disparaitre de la liste.
     */
    public static void openServers(ServerPlayer player) {
        String current = Config.SERVERS_CURRENT.get().trim();
        Component title = Icons.title("Changer de serveur", ChatFormatting.LIGHT_PURPLE);

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (String raw : Config.SERVERS_LIST.get()) {
            String[] parts = raw.split("\\|", -1);
            String id = parts[0].trim();
            if (id.isEmpty()) {
                continue;
            }
            String label = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : id;
            String hint = parts.length > 2 ? parts[2].trim() : "";
            boolean here = id.equalsIgnoreCase(current);
            entries.add(new OwoMenuServer.HubEntry(
                    new ItemStack(here ? Items.LIME_DYE : Items.ENDER_PEARL),
                    Icons.label(label, here ? ChatFormatting.GREEN : ChatFormatting.AQUA),
                    Icons.lore(here ? "Vous y etes deja" : (hint.isEmpty() ? "Rejoindre ce serveur" : hint),
                            here ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY),
                    here ? null : sp -> {
                        Menus.close(sp);
                        sp.sendSystemMessage(Messages.info("Connexion a " + label + "..."));
                        com.utopia.api.UtopiaProxyAPI.connectToServer(sp, id);
                    }));
        }

        List<Component> stats = entries.isEmpty()
                ? List.of(Icons.lore("Aucun serveur configure (menu.servers.list).", ChatFormatting.RED))
                : List.of(Icons.lore("Le changement est immediat : votre inventaire vous suit.",
                        ChatFormatting.DARK_GRAY));

        OwoMenuServer.openHub(player, title, stats, entries, MainMenu::openServers, MainMenu::open);
    }

    /** Selecteur (pagine) de joueur en ligne -> envoie une demande /tpa. */
    public static void openTpaPicker(ServerPlayer player) {
        openTpaPicker(player, 0);
    }

    public static void openTpaPicker(ServerPlayer player, int page) {
        MinecraftServer server = player.server;
        Component title = Icons.title("Se teleporter a...", ChatFormatting.DARK_AQUA);

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (target.getUUID().equals(player.getUUID())) {
                continue;
            }
            String name = target.getGameProfile().getName();
            entries.add(new OwoMenuServer.HubEntry(
                    Icons.playerHead(target, Icons.label(name, ChatFormatting.WHITE), List.of()),
                    Icons.label(name, ChatFormatting.WHITE),
                    Icons.lore("Envoyer une demande de TP", ChatFormatting.GRAY),
                    sp -> {
                        Menus.close(sp);
                        runAs(sp, "tpa " + name);
                    }));
        }
        List<Component> stats = entries.isEmpty()
                ? List.of(Icons.lore("Aucun autre joueur en ligne", ChatFormatting.RED))
                : List.of();

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PICKER_PAGE_SIZE,
                MainMenu::openTpaPicker, MainMenu::open);
    }

    private static void runAs(ServerPlayer player, String command) {
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    /** Execute une commande au nom du joueur mais avec permission op (niveau 4). */
    private static void runAsOp(ServerPlayer player, String command) {
        player.server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4), command);
    }
}
