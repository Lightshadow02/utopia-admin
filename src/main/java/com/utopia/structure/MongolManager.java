package com.utopia.structure;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.utopia.UtopiaMod;
import com.utopia.daily.DailyCalendar;
import com.utopia.daily.DailyManager;
import com.utopia.data.MongolData;
import com.utopia.economy.EconomyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Marchand mongol : il rachete aux joueurs une liste d'items <b>programmee a l'avance</b> (meme
 * systeme que le Daily : un calendrier date -&gt; items), a raison d'<b>1 Utopiece par item</b>, dans la
 * limite d'un <b>quota quotidien global au serveur</b> remis a zero chaque jour a minuit.
 */
public final class MongolManager {

    /** Nombre maximal d'items rachetes par jour, toutes ventes et tous joueurs confondus. */
    public static final int DAILY_QUOTA = 1000;
    /** Prix paye par item. */
    public static final int UNIT_PRICE = 1;

    private static DailyCalendar calendar;

    private MongolManager() {
    }

    // ------------------------------------------------------------------ Programme (calendrier)

    public static Path calendarPath() {
        return FMLPaths.CONFIGDIR.get().resolve(UtopiaMod.MODID).resolve("mongol_calendar.json");
    }

    public static synchronized DailyCalendar loadCalendar() {
        calendar = DailyCalendar.load(calendarPath());
        return calendar;
    }

    /** Calendrier des items acceptes (meme format que le Daily : date ISO -&gt; specs "modid:item qte"). */
    public static DailyCalendar calendar() {
        DailyCalendar c = calendar;
        return c == null ? loadCalendar() : c;
    }

    /** Specs des items acceptes a une date donnee. */
    public static List<String> acceptedSpecs(LocalDate date) {
        return calendar().getReward(date);
    }

    /** Items acceptes aujourd'hui, sous forme de piles modeles (quantite 1). */
    public static List<ItemStack> acceptedToday() {
        List<ItemStack> out = new ArrayList<>();
        for (String spec : acceptedSpecs(LocalDate.now())) {
            ItemStack stack = DailyManager.specToStack(spec);
            if (!stack.isEmpty()) {
                out.add(stack.copyWithCount(1));
            }
        }
        return out;
    }

    /** Le marchand accepte-t-il cet objet aujourd'hui ? (comparaison objet + composants) */
    public static boolean accepts(ItemStack stack) {
        for (ItemStack model : acceptedToday()) {
            if (ItemStack.isSameItemSameComponents(model, stack)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Quota global

    /** Items deja rachetes aujourd'hui (apres recalage du jour). */
    public static int soldToday(MinecraftServer server) {
        MongolData data = MongolData.get(server);
        data.rollOver(LocalDate.now().toEpochDay());
        return data.sold();
    }

    /** Items que le marchand peut encore racheter aujourd'hui. */
    public static int remaining(MinecraftServer server) {
        return Math.max(0, DAILY_QUOTA - soldToday(server));
    }

    /**
     * A appeler periodiquement : remet le quota a zero au passage de minuit (heure du serveur) et
     * previent les joueurs que le marchand a de nouveau de la place.
     */
    public static void tick(MinecraftServer server) {
        MongolData data = MongolData.get(server);
        // On n'annonce la reouverture que si le quota avait reellement ete atteint la veille : pas de
        // message au tout premier demarrage, ni les jours ou le marchand n'a jamais ete rempli.
        boolean wasFull = data.initialized() && data.announced();
        if (data.rollOver(LocalDate.now().toEpochDay()) && wasFull) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Le marchand mongol a vide ses reserves : il rachete de nouveau "
                                    + DAILY_QUOTA + " items aujourd'hui !")
                            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)), false);
        }
    }

    // ------------------------------------------------------------------ Vente

    public enum SellResult { OK, NOT_ACCEPTED, QUOTA_FULL, NONE_OWNED, INVALID }

    /** Resultat detaille d'une vente : ce qui a ete pris et ce qui a ete paye. */
    public record Sale(SellResult result, int sold, long paid) {
    }

    /**
     * Vend jusqu'a {@code qty} exemplaires de {@code model} au marchand. La quantite reellement prise
     * est bornee par ce que le joueur possede et par le quota restant.
     */
    public static Sale sell(ServerPlayer player, ItemStack model, int qty) {
        MinecraftServer server = player.server;
        if (model.isEmpty() || qty <= 0) {
            return new Sale(SellResult.INVALID, 0, 0);
        }
        if (!accepts(model)) {
            return new Sale(SellResult.NOT_ACCEPTED, 0, 0);
        }
        int left = remaining(server);
        if (left <= 0) {
            return new Sale(SellResult.QUOTA_FULL, 0, 0);
        }
        int owned = count(player, model);
        if (owned <= 0) {
            return new Sale(SellResult.NONE_OWNED, 0, 0);
        }
        int take = Math.min(Math.min(qty, owned), left);
        int removed = remove(player, model, take);
        if (removed <= 0) {
            return new Sale(SellResult.NONE_OWNED, 0, 0);
        }
        long paid = (long) removed * UNIT_PRICE;
        EconomyManager.add(server, player.getUUID(), paid);
        MongolData.get(server).addSold(removed);
        announceIfFull(server);
        return new Sale(SellResult.OK, removed, paid);
    }

    /** Diffuse (une seule fois par jour) le message annoncant que les reserves sont pleines. */
    private static void announceIfFull(MinecraftServer server) {
        MongolData data = MongolData.get(server);
        if (data.sold() < DAILY_QUOTA || data.announced()) {
            return;
        }
        data.setAnnounced(true);
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("Le marchand mongol a rempli ses reserves pour aujourd'hui ! "
                                + "Son quota est atteint : il n'acceptera plus aucun item avant minuit.")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)), false);
    }

    // ------------------------------------------------------------------ Inventaire

    /** Nombre d'exemplaires de {@code model} (meme objet + memes composants) dans l'inventaire. */
    public static int count(ServerPlayer player, ItemStack model) {
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
    private static int remove(ServerPlayer player, ItemStack model, int qty) {
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
}
