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
}
