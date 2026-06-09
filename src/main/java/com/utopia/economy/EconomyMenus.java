package com.utopia.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.utopia.data.BalTopData;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menu admin d'economie : liste des joueurs, donner / retirer 1, 10, 100, 1000. */
public final class EconomyMenus {

    private EconomyMenus() {
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        return gp.map(GameProfile::getName).filter(n -> n != null).orElse(id.toString().substring(0, 8));
    }

    private static ItemStack head(MinecraftServer server, UUID id, Component name, List<Component> lore) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Icons.playerHead(online, name, lore);
        }
        Optional<GameProfile> gp = server.getProfileCache().get(id);
        return gp.isPresent() ? Icons.playerHead(gp.get(), name, lore) : Icons.icon(Items.PLAYER_HEAD, name, lore);
    }

    /** Liste des joueurs en ligne. */
    public static void openAdminMenu(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Economie - joueurs", ChatFormatting.DARK_AQUA));

        int slot = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            UUID id = target.getUUID();
            gui.button(slot++, head(server, id, Icons.label(target.getGameProfile().getName(), ChatFormatting.WHITE),
                    List.of(Icons.lore("Solde : " + EconomyManager.format(EconomyManager.getBalance(server, id)), ChatFormatting.GOLD),
                            Icons.lore("Clic pour gerer", ChatFormatting.GRAY))),
                    sp -> openPlayerEco(sp, id));
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun joueur en ligne", ChatFormatting.RED),
                    List.of(Icons.lore("Pour les joueurs hors ligne : /money give|take|set", ChatFormatting.GRAY))));
        }

        gui.button(49, Icons.icon(Items.BARRIER, Icons.label("Fermer", ChatFormatting.RED), List.of()),
                com.utopia.gui.Menus::close);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Panneau d'un joueur (ecran riche) : saisir un montant, puis Ajouter ou Retirer. */
    public static void openPlayerEco(ServerPlayer admin, UUID targetId) {
        MinecraftServer server = admin.server;
        String name = nameOf(server, targetId);
        long balance = EconomyManager.getBalance(server, targetId);

        Component title = Icons.label("Solde : " + name, ChatFormatting.GOLD);
        List<Component> stats = List.of(
                stat("Joueur : ", name, ChatFormatting.AQUA),
                stat("Solde : ", EconomyManager.format(balance) + " $", ChatFormatting.GOLD));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.EMERALD),
                Icons.label("Ajouter", ChatFormatting.GREEN),
                Icons.lore("Saisir le montant a crediter", ChatFormatting.GRAY),
                sp -> Menus.promptAmount(sp, Icons.label("Montant a ajouter", ChatFormatting.GREEN),
                        List.of(Icons.lore("Joueur : " + name, ChatFormatting.GRAY)),
                        Icons.label("Ajouter", ChatFormatting.GREEN), 100, 1, 1_000_000_000L,
                        v -> {
                            EconomyManager.add(sp.server, targetId, v);
                            notifyTarget(sp.server, targetId);
                            openPlayerEco(sp, targetId);
                        })));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.REDSTONE),
                Icons.label("Retirer", ChatFormatting.RED),
                Icons.lore("Saisir le montant a retirer", ChatFormatting.GRAY),
                sp -> Menus.promptAmount(sp, Icons.label("Montant a retirer", ChatFormatting.RED),
                        List.of(Icons.lore("Joueur : " + name, ChatFormatting.GRAY)),
                        Icons.label("Retirer", ChatFormatting.RED), 100, 1, 1_000_000_000L,
                        v -> {
                            long bal = EconomyManager.getBalance(sp.server, targetId);
                            EconomyManager.setBalance(sp.server, targetId, Math.max(0L, bal - v));
                            notifyTarget(sp.server, targetId);
                            openPlayerEco(sp, targetId);
                        })));

        OwoMenuServer.openHub(admin, title, stats, entries,
                sp -> openPlayerEco(sp, targetId), EconomyMenus::openAdminMenu);
    }

    private static void notifyTarget(MinecraftServer server, UUID targetId) {
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target != null) {
            target.sendSystemMessage(Messages.info("Votre solde est desormais de "
                    + EconomyManager.format(EconomyManager.getBalance(server, targetId)) + "."));
        }
    }

    // ============================================================ Menu joueur (/balance menu)

    /** Menu joueur "riche" (ecran owo) : solde, deposer, retirer, payer. */
    public static void openPlayerMenu(ServerPlayer player) {
        MinecraftServer server = player.server;
        long balance = EconomyManager.getBalance(server, player.getUUID());
        int coins = EconomyManager.countCoins(player);

        Component title = Icons.label("Banque", ChatFormatting.GOLD);
        List<Component> stats = List.of(
                stat("Solde en banque : ", EconomyManager.format(balance) + " $", ChatFormatting.GOLD),
                stat("Pieces en main : ", Integer.toString(coins), ChatFormatting.AQUA));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.HOPPER),
                Icons.label("Deposer mes pieces", ChatFormatting.AQUA),
                Icons.lore("Met toutes les pieces en banque", ChatFormatting.GRAY),
                sp -> {
                    int count = EconomyManager.countCoins(sp);
                    if (count <= 0) {
                        sp.sendSystemMessage(Messages.warn("Vous n'avez aucune piece a deposer."));
                    } else {
                        int taken = EconomyManager.takeCoins(sp, count);
                        EconomyManager.add(sp.server, sp.getUUID(), taken);
                        sp.sendSystemMessage(Messages.success("Depose " + EconomyManager.format(taken) + "."));
                    }
                    openPlayerMenu(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(EconomyManager.coinItem()),
                Icons.label("Retirer en pieces", ChatFormatting.GREEN),
                Icons.lore("Sortir des pieces dans l'inventaire", ChatFormatting.GRAY),
                EconomyMenus::openWithdrawMenu));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Payer un joueur", ChatFormatting.YELLOW),
                Icons.lore("Envoyer de l'argent a un joueur en ligne", ChatFormatting.GRAY),
                EconomyMenus::openPayPicker));

        OwoMenuServer.openHub(player, title, stats, entries,
                EconomyMenus::openPlayerMenu, com.utopia.menu.MainMenu::open);
    }

    /** Ligne de stat "label: valeur" (label gris, valeur coloree). */
    private static Component stat(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(value).withStyle(s -> s.withColor(valueColor).withItalic(false)));
    }

    private static void openPayPicker(ServerPlayer player) {
        MinecraftServer server = player.server;
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Payer qui ?", ChatFormatting.DARK_AQUA));
        int slot = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (slot > 44) {
                break;
            }
            if (target.getUUID().equals(player.getUUID())) {
                continue;
            }
            UUID id = target.getUUID();
            gui.button(slot++, Icons.playerHead(target, Icons.label(target.getGameProfile().getName(), ChatFormatting.WHITE),
                    List.of(Icons.lore("Clic pour choisir le montant", ChatFormatting.GRAY))),
                    sp -> openPayAmount(sp, id));
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun autre joueur en ligne", ChatFormatting.RED), List.of()));
        }
        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                EconomyMenus::openPlayerMenu);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void openPayAmount(ServerPlayer player, UUID targetId) {
        MinecraftServer server = player.server;
        long balance = EconomyManager.getBalance(server, player.getUUID());
        if (balance <= 0) {
            player.sendSystemMessage(Messages.warn("Vous n'avez rien a envoyer."));
            openPlayerMenu(player);
            return;
        }
        List<Component> info = List.of(
                Icons.lore("Destinataire : " + nameOf(server, targetId), ChatFormatting.GRAY),
                Icons.lore("Votre solde : " + EconomyManager.format(balance), ChatFormatting.GREEN));
        Menus.promptAmount(player,
                Icons.label("Payer " + nameOf(server, targetId), ChatFormatting.GOLD),
                info, Icons.label("Envoyer", ChatFormatting.GREEN),
                Math.min(10, balance), 1, balance,
                amount -> {
                    if (player.getUUID().equals(targetId)) {
                        player.sendSystemMessage(Messages.error("Vous ne pouvez pas vous payer."));
                    } else if (EconomyManager.transfer(server, player.getUUID(), targetId, amount)) {
                        player.sendSystemMessage(Messages.success("Envoye " + EconomyManager.format(amount) + " a " + nameOf(server, targetId) + "."));
                        ServerPlayer t = server.getPlayerList().getPlayer(targetId);
                        if (t != null) {
                            t.sendSystemMessage(Messages.success("Vous avez recu " + EconomyManager.format(amount)
                                    + " de " + player.getGameProfile().getName() + "."));
                        }
                    } else {
                        player.sendSystemMessage(Messages.error("Solde insuffisant."));
                    }
                    openPlayerMenu(player);
                });
    }

    private static void openWithdrawMenu(ServerPlayer player) {
        MinecraftServer server = player.server;
        long balance = EconomyManager.getBalance(server, player.getUUID());
        int space = EconomyManager.freeSpaceForCoins(player);
        long max = Math.min(balance, space);
        if (max <= 0) {
            player.sendSystemMessage(Messages.error(space <= 0 ? "Inventaire plein." : "Solde insuffisant."));
            openPlayerMenu(player);
            return;
        }
        List<Component> info = List.of(
                Icons.lore("Solde : " + EconomyManager.format(balance), ChatFormatting.GREEN),
                Icons.lore("Place dispo : " + space + " pieces", ChatFormatting.GRAY));
        Menus.promptAmount(player,
                Icons.label("Retirer en pieces", ChatFormatting.GOLD),
                info, Icons.label("Retirer", ChatFormatting.GREEN),
                Math.min(10, max), 1, max,
                amount -> {
                    int space2 = EconomyManager.freeSpaceForCoins(player);
                    long bal = EconomyManager.getBalance(server, player.getUUID());
                    int give = (int) Math.min(amount, Math.min(space2, bal));
                    if (give <= 0) {
                        player.sendSystemMessage(Messages.error(space2 <= 0 ? "Inventaire plein." : "Solde insuffisant."));
                    } else {
                        EconomyManager.remove(server, player.getUUID(), give);
                        EconomyManager.giveCoins(player, give);
                        player.sendSystemMessage(Messages.success("Retire " + EconomyManager.format(give)
                                + (give < amount ? " (limite a la place dispo)" : "") + "."));
                    }
                    openPlayerMenu(player);
                });
    }

    // ============================================================ Hologramme BalTop (admin)

    /** Menu admin : place / deplace (boussole) / supprime l'hologramme du classement des soldes. */
    public static void openBalTopHoloMove(ServerPlayer admin) {
        BalTopData data = BalTopData.get(admin.server);
        UtopiaGui gui = new UtopiaGui(4, Icons.label("Hologramme BalTop", ChatFormatting.GOLD)).gridLayout(true);

        if (data.enabled()) {
            gui.set(12, Icons.icon(Items.ARMOR_STAND,
                    Icons.label(String.format("X %.1f Y %.1f Z %.1f", data.x(), data.y(), data.z()), ChatFormatting.AQUA),
                    List.of(Icons.lore("Top 10 des plus riches", ChatFormatting.DARK_GRAY))));
            bhBtn(gui, 3, "↑ Nord", Items.ARROW, 0, 0, -1);
            bhBtn(gui, 21, "↓ Sud", Items.ARROW, 0, 0, 1);
            bhBtn(gui, 11, "← Ouest", Items.ARROW, -1, 0, 0);
            bhBtn(gui, 13, "→ Est", Items.ARROW, 1, 0, 0);
            bhBtn(gui, 5, "↑ Monter", Items.SPECTRAL_ARROW, 0, 0.5, 0);
            bhBtn(gui, 23, "↓ Descendre", Items.SPECTRAL_ARROW, 0, -0.5, 0);
        } else {
            gui.set(12, Icons.icon(Items.BARRIER, Icons.label("Hologramme non place", ChatFormatting.RED),
                    List.of(Icons.lore("Clique 'Placer ici'", ChatFormatting.GRAY))));
        }

        gui.button(29, Icons.icon(Items.ENDER_PEARL, Icons.label("Placer ici", ChatFormatting.GREEN),
                List.of(Icons.lore("Place l'hologramme a ta position", ChatFormatting.GRAY))),
                sp -> {
                    BalTopHologram.setHere(sp);
                    openBalTopHoloMove(sp);
                });
        gui.button(31, Icons.icon(Items.LAVA_BUCKET, Icons.label("Supprimer", ChatFormatting.RED), List.of()),
                sp -> {
                    BalTopHologram.remove(sp.server);
                    sp.sendSystemMessage(Messages.success("Hologramme BalTop retire."));
                    openBalTopHoloMove(sp);
                });
        gui.button(33, Icons.icon(Items.OAK_DOOR, Icons.label("Fermer", ChatFormatting.YELLOW), List.of()),
                com.utopia.gui.Menus::close);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void bhBtn(UtopiaGui gui, int slot, String label, net.minecraft.world.level.ItemLike icon,
                              double dx, double dy, double dz) {
        gui.button(slot, Icons.icon(icon, Icons.label(label, ChatFormatting.YELLOW),
                List.of(Icons.lore(String.format("%+.1f %+.1f %+.1f (X Y Z)", dx, dy, dz), ChatFormatting.DARK_GRAY))),
                sp -> {
                    BalTopData.get(sp.server).move(dx, dy, dz);
                    openBalTopHoloMove(sp);
                });
    }
}
