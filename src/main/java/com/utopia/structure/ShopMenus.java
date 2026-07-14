package com.utopia.structure;

import java.util.ArrayList;
import java.util.List;

import com.utopia.data.StructureData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Boutique d'un marchand de structure : achat et revente en Utopieces, stock illimite (boutique
 * serveur). La liste des articles et les prix sont geres par les admins.
 */
public final class ShopMenus {

    private static final int PAGE_SIZE = 12;

    private ShopMenus() {
    }

    // ------------------------------------------------------------------ Cote joueur

    public static void openShop(ServerPlayer player, String structName) {
        openShop(player, structName, 0);
    }

    public static void openShop(ServerPlayer player, String structName, int page) {
        StructureData.Struct st = StructureData.get(player.server).get(structName);
        if (st == null || !st.npcEnabled) {
            player.sendSystemMessage(Messages.warn("Cette boutique n'est pas disponible."));
            return;
        }
        long balance = EconomyManager.getBalance(player.server, player.getUUID());
        Component title = Component.literal(st.npcName == null ? "Marchand" : st.npcName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Ton solde : ").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                        .append(Component.literal(balance + " Utopieces")
                                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false))));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (int i = 0; i < st.trades.size(); i++) {
            final int idx = i;
            StructureData.Trade t = st.trades.get(i);
            String prices = (t.canBuy() ? "Achat " + t.buyPrice() : "")
                    + (t.canBuy() && t.canSell() ? " | " : "")
                    + (t.canSell() ? "Revente " + t.sellPrice() : "");
            entries.add(new OwoMenuServer.HubEntry(t.stack().copy(),
                    Icons.label(t.stack().getHoverName().getString(), ChatFormatting.AQUA),
                    Icons.lore(prices.isEmpty() ? "Indisponible" : prices, ChatFormatting.GRAY),
                    sp -> openTrade(sp, structName, idx)));
        }
        if (entries.isEmpty()) {
            stats = List.of(Component.literal("Aucun article en vente pour le moment.")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openShop(sp, structName, p), null);
    }

    /** Fiche d'un article : acheter ou revendre (le prix est unitaire). */
    private static void openTrade(ServerPlayer player, String structName, int index) {
        StructureData.Struct st = StructureData.get(player.server).get(structName);
        if (st == null || index < 0 || index >= st.trades.size()) {
            openShop(player, structName);
            return;
        }
        StructureData.Trade t = st.trades.get(index);
        long balance = EconomyManager.getBalance(player.server, player.getUUID());
        int owned = countIn(player, t.stack());

        Component title = Component.literal(t.stack().getHoverName().getString())
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
        List<Component> stats = List.of(
                Component.literal("Ton solde : " + balance + " Utopieces")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)),
                Component.literal("Tu en possedes : " + owned)
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        if (t.canBuy()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.EMERALD),
                    Icons.label("Acheter", ChatFormatting.GREEN),
                    Icons.lore(t.buyPrice() + " Utopieces l'unite", ChatFormatting.GRAY),
                    sp -> promptBuy(sp, structName, index)));
        }
        if (t.canSell()) {
            entries.add(new OwoMenuServer.HubEntry(new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT),
                    Icons.label("Revendre", ChatFormatting.GOLD),
                    Icons.lore(t.sellPrice() + " Utopieces l'unite", ChatFormatting.GRAY),
                    sp -> promptSell(sp, structName, index)));
        }

        OwoMenuServer.openHub(player, title, stats, entries,
                sp -> openTrade(sp, structName, index), sp -> openShop(sp, structName));
    }

    private static void promptBuy(ServerPlayer player, String structName, int index) {
        StructureData.Struct st = StructureData.get(player.server).get(structName);
        if (st == null || index >= st.trades.size()) {
            return;
        }
        StructureData.Trade t = st.trades.get(index);
        long unit = t.buyPrice();
        long balance = EconomyManager.getBalance(player.server, player.getUUID());
        long max = unit <= 0 ? 1024 : Math.max(1, Math.min(1024, balance / unit));
        if (unit > 0 && balance < unit) {
            player.sendSystemMessage(Messages.error("Solde insuffisant (" + unit + " Utopieces l'unite)."));
            openTrade(player, structName, index);
            return;
        }
        Menus.promptAmount(player, Icons.label("Quantite a acheter", ChatFormatting.GOLD),
                List.of(Icons.lore("Prix unitaire : " + unit + " Utopieces", ChatFormatting.GRAY),
                        Icons.lore("Tu peux en prendre jusqu'a " + max, ChatFormatting.DARK_GRAY)),
                Icons.label("Acheter", ChatFormatting.GREEN), 1, 1, max,
                qty -> {
                    long total = unit * qty;
                    if (!EconomyManager.remove(player.server, player.getUUID(), total)) {
                        player.sendSystemMessage(Messages.error("Solde insuffisant."));
                        openTrade(player, structName, index);
                        return;
                    }
                    give(player, t.stack(), (int) qty);
                    player.sendSystemMessage(Messages.success("Achat : " + qty + "x "
                            + t.stack().getHoverName().getString() + " pour " + total + " Utopieces."));
                    openTrade(player, structName, index);
                });
    }

    private static void promptSell(ServerPlayer player, String structName, int index) {
        StructureData.Struct st = StructureData.get(player.server).get(structName);
        if (st == null || index >= st.trades.size()) {
            return;
        }
        StructureData.Trade t = st.trades.get(index);
        long unit = t.sellPrice();
        int owned = countIn(player, t.stack());
        if (owned <= 0) {
            player.sendSystemMessage(Messages.warn("Tu n'as pas cet objet."));
            openTrade(player, structName, index);
            return;
        }
        Menus.promptAmount(player, Icons.label("Quantite a revendre", ChatFormatting.GOLD),
                List.of(Icons.lore("Le marchand paie " + unit + " Utopieces l'unite", ChatFormatting.GRAY),
                        Icons.lore("Tu en possedes " + owned, ChatFormatting.DARK_GRAY)),
                Icons.label("Revendre", ChatFormatting.GOLD), owned, 1, owned,
                qty -> {
                    int removed = removeFrom(player, t.stack(), (int) qty);
                    if (removed <= 0) {
                        player.sendSystemMessage(Messages.warn("Objet introuvable dans ton inventaire."));
                        openTrade(player, structName, index);
                        return;
                    }
                    long total = unit * removed;
                    EconomyManager.add(player.server, player.getUUID(), total);
                    player.sendSystemMessage(Messages.success("Revente : " + removed + "x "
                            + t.stack().getHoverName().getString() + " pour " + total + " Utopieces."));
                    openTrade(player, structName, index);
                });
    }

    // ------------------------------------------------------------------ Inventaire

    /** Nombre d'exemplaires de {@code model} (meme objet + memes composants) dans l'inventaire. */
    public static int countIn(ServerPlayer player, ItemStack model) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, model)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Retire jusqu'a {@code qty} exemplaires ; renvoie le nombre reellement retire. */
    private static int removeFrom(ServerPlayer player, ItemStack model, int qty) {
        Inventory inv = player.getInventory();
        int remaining = qty;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, model)) {
                int take = Math.min(remaining, s.getCount());
                inv.removeItem(i, take);
                remaining -= take;
            }
        }
        if (remaining != qty) {
            inv.setChanged();
        }
        return qty - remaining;
    }

    /** Donne {@code qty} exemplaires en respectant la taille de pile (surplus au sol). */
    private static void give(ServerPlayer player, ItemStack model, int qty) {
        int max = Math.max(1, model.getMaxStackSize());
        int remaining = qty;
        while (remaining > 0) {
            int chunk = Math.min(remaining, max);
            ItemHandlerHelper.giveItemToPlayer(player, model.copyWithCount(chunk));
            remaining -= chunk;
        }
    }
}
