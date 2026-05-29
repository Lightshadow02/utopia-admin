package com.utopia.economy;

import java.util.List;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.UtopiaMod;
import com.utopia.data.EconomyData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Logique de l'economie : soldes bancaires et conversion solde &lt;-&gt; pieces physiques. */
public final class EconomyManager {

    /** Marqueur NBT identifiant une piece frappee par la banque (evite de compter d'autres items). */
    private static final String COIN_MARKER = "UtopiaCoin";

    private EconomyManager() {
    }

    // -------- Soldes --------

    public static long getBalance(MinecraftServer server, UUID playerId) {
        return EconomyData.get(server).getBalance(playerId, Config.ECO_STARTING_BALANCE.get());
    }

    public static void setBalance(MinecraftServer server, UUID playerId, long amount) {
        EconomyData.get(server).setBalance(playerId, amount);
    }

    public static void add(MinecraftServer server, UUID playerId, long amount) {
        setBalance(server, playerId, getBalance(server, playerId) + amount);
    }

    /** Retire {@code amount} si le solde est suffisant ; renvoie faux sinon. */
    public static boolean remove(MinecraftServer server, UUID playerId, long amount) {
        long balance = getBalance(server, playerId);
        if (balance < amount) {
            return false;
        }
        setBalance(server, playerId, balance - amount);
        return true;
    }

    /** Transfere {@code amount} de {@code from} vers {@code to} ; faux si solde insuffisant. */
    public static boolean transfer(MinecraftServer server, UUID from, UUID to, long amount) {
        if (!remove(server, from, amount)) {
            return false;
        }
        add(server, to, amount);
        return true;
    }

    public static String currencyName() {
        return Config.ECO_CURRENCY_NAME.get();
    }

    /** Formate un montant : "1234 pieces". */
    public static String format(long amount) {
        return amount + " " + currencyName();
    }

    // -------- Pieces physiques --------

    /** Item de base utilise comme piece (configurable), avec repli sur la pepite d'or. */
    public static Item coinItem() {
        ResourceLocation id = ResourceLocation.tryParse(Config.ECO_COIN_ITEM.get());
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id);
        }
        return Items.GOLD_NUGGET;
    }

    /** Frappe une pile de {@code count} pieces (marquees), nommees et avec une description. */
    public static ItemStack mintCoins(int count) {
        ItemStack stack = new ItemStack(coinItem(), Math.max(1, count));
        CompoundTag marker = new CompoundTag();
        marker.putBoolean(COIN_MARKER, true);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, marker);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Utopiece").withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Monnaie du serveur").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
                Component.literal("Clic droit ou /deposit pour deposer").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
        return stack;
    }

    /** Une pile est-elle une piece frappee par la banque ? */
    public static boolean isCoin(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag marker = new CompoundTag();
        marker.putBoolean(COIN_MARKER, true);
        return data.matchedBy(marker);
    }

    /** Donne {@code amount} pieces au joueur (en respectant la taille de pile max ; surplus au sol). */
    public static void giveCoins(ServerPlayer player, int amount) {
        int maxStack = new ItemStack(coinItem()).getMaxStackSize();
        int remaining = amount;
        while (remaining > 0) {
            int chunk = Math.min(remaining, maxStack);
            ItemHandlerHelper.giveItemToPlayer(player, mintCoins(chunk));
            remaining -= chunk;
        }
    }

    /** Nombre de pieces qui tiennent encore dans l'inventaire (slots vides + piles de pieces non pleines). */
    public static int freeSpaceForCoins(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int maxStack = new ItemStack(coinItem()).getMaxStackSize();
        long free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                free += maxStack;
            } else if (isCoin(s)) {
                free += Math.max(0, maxStack - s.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, free);
    }

    /** Compte les pieces frappees dans l'inventaire du joueur. */
    public static int countCoins(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isCoin(s)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** Retire jusqu'a {@code amount} pieces de l'inventaire ; renvoie le nombre reellement retire. */
    public static int takeCoins(ServerPlayer player, int amount) {
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (isCoin(s)) {
                int take = Math.min(remaining, s.getCount());
                inv.removeItem(i, take);
                remaining -= take;
            }
        }
        if (remaining != amount) {
            inv.setChanged();
        }
        return amount - remaining;
    }

    static {
        UtopiaMod.LOGGER.debug("[Utopia] EconomyManager charge.");
    }
}
