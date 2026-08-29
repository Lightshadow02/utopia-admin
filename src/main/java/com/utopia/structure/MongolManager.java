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

    /**
     * Place quotidienne de chaque joueur : ces items ne touchent pas la reserve du serveur. Chacun
     * peut donc toujours vendre ses 200 premiers items, quoi qu'aient fait les autres.
     */
    public static final int PERSONAL_QUOTA = 200;
    /**
     * Reserve commune : elle n'est entamee que par les <b>depassements</b> de la place quotidienne.
     * Une fois vide, plus personne ne peut vendre au-dela de ses 200 items du jour.
     */
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

    /** Reserve commune restante (elle ne sert qu'aux depassements de place quotidienne). */
    public static int remaining(MinecraftServer server) {
        return Math.max(0, DAILY_QUOTA - soldToday(server));
    }

    /** Items deja vendus aujourd'hui par ce joueur (place quotidienne + depassements). */
    public static int personalSold(ServerPlayer player) {
        MongolData data = MongolData.get(player.server);
        data.rollOver(LocalDate.now().toEpochDay());
        return data.personalSold(player.getUUID());
    }

    /** Place quotidienne restante de ce joueur (sur {@link #PERSONAL_QUOTA}). */
    public static int personalRemaining(ServerPlayer player) {
        return Math.max(0, PERSONAL_QUOTA - personalSold(player));
    }

    /**
     * Nombre d'items que ce joueur peut encore vendre maintenant : sa place quotidienne restante,
     * plus ce qu'il reste dans la reserve commune une fois cette place epuisee.
     */
    public static int sellableFor(ServerPlayer player) {
        return personalRemaining(player) + remaining(player.server);
    }

    /**
     * A appeler periodiquement : remet le quota a zero au passage de minuit (heure du serveur) et
     * previent les joueurs que le marchand a de nouveau de la place.
     */
    /**
     * Journee du marchand. Elle ne suit pas minuit mais l'heure de renouvellement choisie en config :
     * avant cette heure, on est encore dans la journee de la veille. Le fuseau est celui de Paris,
     * comme pour les salaires et les livrets, et non celui de la machine.
     */
    private static long merchantDay() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(com.utopia.job.JobManager.ZONE);
        int resetHour = com.utopia.Config.MERCHANT_RESET_HOUR.get();
        return now.toLocalTime().getHour() < resetHour
                ? now.toLocalDate().minusDays(1).toEpochDay()
                : now.toLocalDate().toEpochDay();
    }

    /** Nom du marchand tel que les joueurs le connaissent ; jamais un nom de code. */
    public static String merchantName(MinecraftServer server) {
        for (com.utopia.data.StructureData.Struct st
                : com.utopia.data.StructureData.get(server).all()) {
            if (st.npcMongol && st.npcName != null && !st.npcName.isBlank()) {
                return st.npcName;
            }
        }
        return "Le marchand";
    }

    public static void tick(MinecraftServer server) {
        MongolData data = MongolData.get(server);
        // On n'annonce la reouverture que si la reserve avait reellement ete epuisee : pas de
        // message au tout premier demarrage, ni les jours ou le marchand n'a jamais ete rempli.
        boolean wasFull = data.initialized() && data.announced();
        if (data.rollOver(merchantDay()) && wasFull) {
            String name = merchantName(server);
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(name + " a de nouveau de la place : venez le voir !")
                            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true))
                            .append(Component.literal("\n" + PERSONAL_QUOTA
                                            + " items du jour pour chacun, et " + DAILY_QUOTA
                                            + " items de reserve commune.")
                                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false))),
                    false);
        }
    }

    // ------------------------------------------------------------------ Vente

    public enum SellResult { OK, NOT_ACCEPTED, QUOTA_FULL, NONE_OWNED, INVALID }

    /**
     * Resultat detaille d'une vente : combien a ete pris, combien a ete paye, et quelle part est
     * sortie de la reserve commune (le reste venant de la place quotidienne du joueur).
     */
    public record Sale(SellResult result, int sold, long paid, int fromReserve) {
    }

    /**
     * Vend jusqu'a {@code qty} exemplaires de {@code model} au marchand. La quantite reellement prise
     * est bornee par ce que le joueur possede, par sa place quotidienne, puis par la reserve commune :
     * les {@link #PERSONAL_QUOTA} premiers items du joueur sont toujours rachetes, et seul le
     * depassement entame la reserve du serveur.
     */
    public static Sale sell(ServerPlayer player, ItemStack model, int qty, String merchantName) {
        MinecraftServer server = player.server;
        if (model.isEmpty() || qty <= 0) {
            return new Sale(SellResult.INVALID, 0, 0, 0);
        }
        if (!accepts(model)) {
            return new Sale(SellResult.NOT_ACCEPTED, 0, 0, 0);
        }
        int owned = count(player, model);
        if (owned <= 0) {
            return new Sale(SellResult.NONE_OWNED, 0, 0, 0);
        }
        int wanted = Math.min(qty, owned);
        int fromPersonal = Math.min(wanted, personalRemaining(player));
        int overflow = wanted - fromPersonal;
        int fromReserve = Math.min(overflow, remaining(server));
        int take = fromPersonal + fromReserve;
        if (take <= 0) {
            // Place quotidienne epuisee ET reserve commune vide : plus rien n'est rachetable.
            return new Sale(SellResult.QUOTA_FULL, 0, 0, 0);
        }
        int removed = remove(player, model, take);
        if (removed <= 0) {
            return new Sale(SellResult.NONE_OWNED, 0, 0, 0);
        }
        // Si l'inventaire a bouge entre-temps, on impute d'abord a la place quotidienne.
        int reserveUsed = Math.max(0, removed - fromPersonal);
        long paid = (long) removed * UNIT_PRICE;
        EconomyManager.add(server, player.getUUID(), paid);
        MongolData data = MongolData.get(server);
        data.addPersonal(player.getUUID(), removed);
        if (reserveUsed > 0) {
            data.addSold(reserveUsed);
            announceIfFull(server, merchantName);
        }
        return new Sale(SellResult.OK, removed, paid, reserveUsed);
    }

    /**
     * Diffuse (une seule fois par jour) le message annoncant que les reserves sont pleines. Le
     * marchand est designe par son nom en jeu : aucun nom de code n'apparait cote joueur.
     */
    private static void announceIfFull(MinecraftServer server, String merchantName) {
        MongolData data = MongolData.get(server);
        if (data.sold() < DAILY_QUOTA || data.announced()) {
            return;
        }
        data.setAnnounced(true);
        String who = (merchantName == null || merchantName.isBlank()) ? "Le marchand" : merchantName;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(who + " a rempli ses reserves pour aujourd'hui ! "
                                + "Impossible de depasser vos " + PERSONAL_QUOTA
                                + " de place quotidienne avant minuit.")
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
