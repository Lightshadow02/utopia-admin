package com.utopia.casino;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.utopia.data.CasinoData;
import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.gui.Icons;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Les machines a capsules : on glisse une Utopiece, il en sort une tete de joueur du serveur ou une
 * carte a collectionner.
 *
 * <p>Un doublon est distribue quand meme, et annonce comme tel. Le retenir priverait les joueurs de
 * la seule chose qu'on puisse faire d'une carte en double : l'echanger.
 */
public final class GachaManager {

    private static final Random RANDOM = new Random();

    /** Ce qui sort d'une capsule : de quoi ecrire le message et tenir la collection a jour. */
    public record Prize(ItemStack stack, Component name, boolean duplicate) {
    }

    private GachaManager() {
    }

    /** Encaisse le tirage et remet la capsule. */
    public static void draw(ServerPlayer player, CasinoData.Machine machine) {
        if (com.utopia.economy.FreezeManager.blocked(player)) {
            return;
        }
        if (machine.cost > 0 && !EconomyManager.payCombined(player, machine.cost)) {
            player.sendSystemMessage(Messages.error("Il te faut " + machine.cost
                    + " Utopiece(s) pour tourner la manivelle."));
            return;
        }
        if (machine.cost > 0 && com.utopia.Config.CASINO_REVENUE_TO_MAIRIE.get()) {
            EconomyManager.add(player.server, MarketData.MAIRIE_UUID, machine.cost);
        }
        Prize prize = roll(player, machine.content);
        if (prize == null) {
            // Rien a distribuer : on rend la mise plutot que de garder l'argent sans contrepartie.
            if (machine.cost > 0) {
                if (com.utopia.Config.CASINO_REVENUE_TO_MAIRIE.get()) {
                    EconomyManager.remove(player.server, MarketData.MAIRIE_UUID, machine.cost);
                }
                EconomyManager.add(player.server, player.getUUID(), machine.cost);
            }
            player.sendSystemMessage(Messages.warn(
                    "Cette machine est vide pour l'instant. Ta mise t'est rendue."));
            return;
        }
        ItemHandlerHelper.giveItemToPlayer(player, prize.stack());
        player.sendSystemMessage(Component.literal("[Capsule] ")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true))
                .append(prize.name().copy())
                .append(Component.literal(prize.duplicate() ? " (doublon, a echanger !)" : " - premiere !")
                        .withStyle(s -> s.withColor(prize.duplicate()
                                ? ChatFormatting.GRAY : ChatFormatting.GREEN).withBold(false))));
    }

    /** Tire une capsule sans rien encaisser. Nul si la machine n'a rien a donner. */
    public static Prize roll(ServerPlayer player, CasinoData.Content content) {
        CasinoData data = CasinoData.get(player.server);
        boolean tetes = content != CasinoData.Content.CARTES && !data.knownPlayers().isEmpty();
        boolean cartes = content != CasinoData.Content.TETES && !GachaCards.ALL.isEmpty();
        if (!tetes && !cartes) {
            return null;
        }
        boolean tireUneTete = tetes && (!cartes || RANDOM.nextInt(100) < 35);
        return tireUneTete ? rollHead(player, data) : rollCard(player, data);
    }

    private static Prize rollHead(ServerPlayer player, CasinoData data) {
        List<Map.Entry<UUID, String>> pool = new ArrayList<>(data.knownPlayers().entrySet());
        Map.Entry<UUID, String> pick = pool.get(RANDOM.nextInt(pool.size()));
        boolean premiere = data.collectHead(player.getUUID(), pick.getKey());

        com.mojang.authlib.GameProfile profile =
                new com.mojang.authlib.GameProfile(pick.getKey(), pick.getValue());
        Component name = Component.literal("Tete de " + pick.getValue())
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false));
        ItemStack stack = Icons.playerHead(profile, name, List.of(
                Icons.lore("Collection : les visages d'Utopia", ChatFormatting.DARK_GRAY)));
        return new Prize(stack, name, !premiere);
    }

    private static Prize rollCard(ServerPlayer player, CasinoData data) {
        // Tirage pondere par la rarete : on additionne les poids, on tire dans le total, et on
        // avance jusqu'a franchir le tirage. Une legendaire pese vingt fois moins qu'une commune.
        int total = 0;
        for (GachaCards.Card card : GachaCards.ALL) {
            total += data.rarity(card.id()).weight;
        }
        if (total <= 0) {
            return null;
        }
        int tirage = RANDOM.nextInt(total);
        GachaCards.Card chosen = GachaCards.ALL.get(GachaCards.ALL.size() - 1);
        for (GachaCards.Card card : GachaCards.ALL) {
            tirage -= data.rarity(card.id()).weight;
            if (tirage < 0) {
                chosen = card;
                break;
            }
        }
        GachaCards.Rarity rarity = data.rarity(chosen.id());
        boolean premiere = data.collectCard(player.getUUID(), chosen.id());

        Component name = Component.literal("\"" + chosen.text() + "\"")
                .withStyle(s -> s.withColor(rarity.color).withItalic(false));
        ItemStack stack = Icons.icon(cardItem(rarity), 1, name, List.of(
                Icons.lore(rarity.label, rarity.color),
                Icons.lore("Carte a collectionner - Utopia", ChatFormatting.DARK_GRAY)));
        // Un cachet invisible : deux cartes differentes ne doivent pas s'empiler en une seule pile.
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new net.minecraft.world.item.component.CustomModelData(
                        Math.abs(chosen.id().hashCode() % 1_000_000)));
        return new Prize(stack, name, !premiere);
    }

    /** Le support de la carte : plus la carte est rare, plus le papier est noble. */
    private static net.minecraft.world.item.Item cardItem(GachaCards.Rarity rarity) {
        return switch (rarity) {
            case LEGENDAIRE -> Items.ENCHANTED_BOOK;
            case EPIQUE -> Items.WRITTEN_BOOK;
            case RARE -> Items.BOOK;
            default -> Items.PAPER;
        };
    }
}
