package com.utopia.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menu admin d'economie : liste des joueurs, donner / retirer 1, 10, 100, 1000. */
public final class EconomyMenus {

    private static final int[] AMOUNTS = { 1, 10, 100, 1000 };

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
                ServerPlayer::closeContainer);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    /** Panneau d'un joueur : donner / retirer des montants. */
    public static void openPlayerEco(ServerPlayer admin, UUID targetId) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(5,
                Icons.label("Solde : " + nameOf(server, targetId), ChatFormatting.DARK_AQUA));

        gui.set(4, head(server, targetId, Icons.label(nameOf(server, targetId), ChatFormatting.WHITE),
                List.of(Icons.lore("Solde : " + EconomyManager.format(EconomyManager.getBalance(server, targetId)), ChatFormatting.GOLD))));

        int[] giveSlots = { 10, 11, 12, 13 };
        int[] takeSlots = { 19, 20, 21, 22 };
        for (int i = 0; i < AMOUNTS.length; i++) {
            int amount = AMOUNTS[i];
            gui.button(giveSlots[i], Icons.icon(Items.EMERALD,
                    Icons.label("+ " + amount, ChatFormatting.GREEN),
                    List.of(Icons.lore("Donner " + EconomyManager.format(amount), ChatFormatting.GRAY))),
                    sp -> {
                        EconomyManager.add(server, targetId, amount);
                        notifyTarget(server, targetId);
                        openPlayerEco(sp, targetId);
                    });
            gui.button(takeSlots[i], Icons.icon(Items.REDSTONE,
                    Icons.label("- " + amount, ChatFormatting.RED),
                    List.of(Icons.lore("Retirer " + EconomyManager.format(amount), ChatFormatting.GRAY))),
                    sp -> {
                        long bal = EconomyManager.getBalance(server, targetId);
                        EconomyManager.setBalance(server, targetId, Math.max(0L, bal - amount));
                        notifyTarget(server, targetId);
                        openPlayerEco(sp, targetId);
                    });
        }

        gui.set(15, Icons.icon(Items.PAPER, Icons.label("Donner (vert) / Retirer (rouge)", ChatFormatting.AQUA),
                List.of(Icons.lore("Montants : 1, 10, 100, 1000", ChatFormatting.GRAY))));
        gui.button(40, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                EconomyMenus::openAdminMenu);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    private static void notifyTarget(MinecraftServer server, UUID targetId) {
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target != null) {
            target.sendSystemMessage(Messages.info("Votre solde est desormais de "
                    + EconomyManager.format(EconomyManager.getBalance(server, targetId)) + "."));
        }
    }

    // ============================================================ Menu joueur (/balance menu)

    /** Menu joueur : solde, payer un joueur, retirer, deposer. */
    public static void openPlayerMenu(ServerPlayer player) {
        MinecraftServer server = player.server;
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Banque", ChatFormatting.DARK_AQUA));

        gui.set(4, Icons.icon(Items.GOLD_INGOT, Icons.label("Votre solde", ChatFormatting.GOLD),
                List.of(Icons.lore(EconomyManager.format(EconomyManager.getBalance(server, player.getUUID())), ChatFormatting.GREEN))));

        gui.button(10, Icons.icon(Items.PLAYER_HEAD, Icons.label("Payer un joueur", ChatFormatting.YELLOW),
                List.of(Icons.lore("Envoyer de l'argent a un joueur en ligne", ChatFormatting.GRAY))),
                EconomyMenus::openPayPicker);
        gui.button(12, Icons.icon(Items.EMERALD, Icons.label("Retirer en pieces", ChatFormatting.GREEN),
                List.of(Icons.lore("Sortir des pieces dans l'inventaire", ChatFormatting.GRAY))),
                sp -> openWithdrawMenu(sp, 10));
        gui.button(14, Icons.icon(Items.HOPPER, Icons.label("Deposer mes pieces", ChatFormatting.AQUA),
                List.of(Icons.lore("Met toutes les pieces de l'inventaire en banque", ChatFormatting.GRAY))),
                sp -> {
                    int count = EconomyManager.countCoins(sp);
                    if (count <= 0) {
                        sp.sendSystemMessage(Messages.warn("Vous n'avez aucune piece a deposer."));
                    } else {
                        int taken = EconomyManager.takeCoins(sp, count);
                        EconomyManager.add(server, sp.getUUID(), taken);
                        sp.sendSystemMessage(Messages.success("Depose " + EconomyManager.format(taken) + "."));
                    }
                    openPlayerMenu(sp);
                });
        gui.fillEmpty();
        Menus.open(player, gui);
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
                    sp -> openPayAmount(sp, id, 10));
        }
        if (slot == 0) {
            gui.set(22, Icons.icon(Items.BARRIER, Icons.label("Aucun autre joueur en ligne", ChatFormatting.RED), List.of()));
        }
        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                EconomyMenus::openPlayerMenu);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void openPayAmount(ServerPlayer player, UUID targetId, long amount) {
        MinecraftServer server = player.server;
        long shown = Math.max(1, amount);
        UtopiaGui gui = new UtopiaGui(3,
                Icons.label("Payer " + nameOf(server, targetId) + " : " + EconomyManager.format(shown), ChatFormatting.GOLD));
        amountButtons(gui, shown, v -> openPayAmount(player, targetId, v));
        gui.button(22, Icons.icon(Items.LIME_DYE, Icons.label("Envoyer " + EconomyManager.format(shown), ChatFormatting.GREEN), List.of()),
                sp -> {
                    if (sp.getUUID().equals(targetId)) {
                        sp.sendSystemMessage(Messages.error("Vous ne pouvez pas vous payer."));
                    } else if (EconomyManager.transfer(server, sp.getUUID(), targetId, shown)) {
                        sp.sendSystemMessage(Messages.success("Envoye " + EconomyManager.format(shown) + " a " + nameOf(server, targetId) + "."));
                        ServerPlayer t = server.getPlayerList().getPlayer(targetId);
                        if (t != null) {
                            t.sendSystemMessage(Messages.success("Vous avez recu " + EconomyManager.format(shown)
                                    + " de " + sp.getGameProfile().getName() + "."));
                        }
                    } else {
                        sp.sendSystemMessage(Messages.error("Solde insuffisant."));
                    }
                    openPlayerMenu(sp);
                });
        gui.button(18, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                EconomyMenus::openPayPicker);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    private static void openWithdrawMenu(ServerPlayer player, long amount) {
        MinecraftServer server = player.server;
        long shown = Math.max(1, amount);
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Retirer : " + EconomyManager.format(shown), ChatFormatting.GOLD));
        gui.set(4, Icons.icon(Items.GOLD_INGOT, Icons.label("Solde : " + EconomyManager.format(EconomyManager.getBalance(server, player.getUUID())), ChatFormatting.GREEN),
                List.of(Icons.lore("Place dispo : " + EconomyManager.freeSpaceForCoins(player) + " pieces", ChatFormatting.GRAY))));
        amountButtons(gui, shown, v -> openWithdrawMenu(player, v));
        gui.button(22, Icons.icon(Items.EMERALD, Icons.label("Retirer " + EconomyManager.format(shown), ChatFormatting.GREEN), List.of()),
                sp -> {
                    int space = EconomyManager.freeSpaceForCoins(sp);
                    long bal = EconomyManager.getBalance(server, sp.getUUID());
                    int give = (int) Math.min(shown, Math.min(space, bal));
                    if (give <= 0) {
                        sp.sendSystemMessage(Messages.error(space <= 0 ? "Inventaire plein." : "Solde insuffisant."));
                    } else {
                        EconomyManager.remove(server, sp.getUUID(), give);
                        EconomyManager.giveCoins(sp, give);
                        sp.sendSystemMessage(Messages.success("Retire " + EconomyManager.format(give)
                                + (give < shown ? " (limite a la place dispo)" : "") + "."));
                    }
                    openPlayerMenu(sp);
                });
        gui.button(18, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                EconomyMenus::openPlayerMenu);
        gui.fillEmpty();
        Menus.open(player, gui);
    }

    /** Boutons -1000/-100/-10/-1 (slots 9-12) et +1/+10/+100/+1000 (slots 14-17) + valeur au 13. */
    private static void amountButtons(UtopiaGui gui, long shown, java.util.function.LongConsumer onChange) {
        int[] minus = { -1000, -100, -10, -1 };
        int[] minusSlots = { 9, 10, 11, 12 };
        int[] plus = { 1, 10, 100, 1000 };
        int[] plusSlots = { 14, 15, 16, 17 };
        for (int i = 0; i < 4; i++) {
            int delta = minus[i];
            gui.button(minusSlots[i], Icons.icon(Items.REDSTONE, Icons.label("" + delta, ChatFormatting.RED), List.of()),
                    sp -> onChange.accept(Math.max(1, shown + delta)));
            int deltaP = plus[i];
            gui.button(plusSlots[i], Icons.icon(Items.EMERALD, Icons.label("+" + deltaP, ChatFormatting.GREEN), List.of()),
                    sp -> onChange.accept(shown + deltaP));
        }
        gui.set(13, Icons.icon(Items.GOLD_INGOT, Icons.label(EconomyManager.format(shown), ChatFormatting.GOLD), List.of()));
    }
}
