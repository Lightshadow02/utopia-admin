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
     * peut donc toujours vendre sa part du jour, quoi qu'aient fait les autres.
     */
    public static int personalQuota() {
        return com.utopia.Config.MERCHANT_PERSONAL_QUOTA.get();
    }

    /**
     * Reserve commune : elle n'est entamee que par les <b>depassements</b> de la place quotidienne.
     * Une fois vide, plus personne ne peut vendre au-dela de sa place du jour.
     */
    public static int dailyQuota() {
        return com.utopia.Config.MERCHANT_DAILY_QUOTA.get();
    }

    /** Prix paye par item. */
    public static int unitPrice() {
        return com.utopia.Config.MERCHANT_UNIT_PRICE.get();
    }

    /** Heure reelle (fuseau de Paris) du renouvellement, telle qu'on l'ecrit aux joueurs : "4h". */
    public static String resetLabel() {
        return com.utopia.Config.MERCHANT_RESET_HOUR.get() + "h";
    }

    private static DailyCalendar calendar;

    /**
     * Vrai quand le changement d'etal du jour n'a pas encore pu etre pose : sa zone n'etait pas
     * chargee a l'heure dite. On retente a chaque tick plutot que de sauter le jour.
     */
    private static boolean rotationPending;

    private MongolManager() {
    }

    // ------------------------------------------------------------------ Programme (calendrier)

    public static Path calendarPath() {
        return FMLPaths.CONFIGDIR.get().resolve(UtopiaMod.MODID).resolve("mongol_calendar.json");
    }

    public static synchronized DailyCalendar loadCalendar() {
        calendar = DailyCalendar.load(calendarPath());
        // Appele au demarrage : un changement d'etal reste en attente d'une autre partie n'a plus
        // lieu d'etre ici, c'est la journee du nouveau monde qui decide.
        rotationPending = false;
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

    /**
     * Items acceptes aujourd'hui, sous forme de piles modeles (quantite 1). La journee est celle du
     * marchand, pas celle du calendrier : la liste change a la meme heure que ses quotas, sinon on
     * lui apporterait a minuit des items qu'il ne peut plus payer.
     */
    public static List<ItemStack> acceptedToday() {
        List<ItemStack> out = new ArrayList<>();
        for (String spec : acceptedSpecs(LocalDate.ofEpochDay(merchantDay()))) {
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
        sync(server);
        return MongolData.get(server).sold();
    }

    /** Reserve commune restante (elle ne sert qu'aux depassements de place quotidienne). */
    public static int remaining(MinecraftServer server) {
        return Math.max(0, dailyQuota() - soldToday(server));
    }

    /** Items deja vendus aujourd'hui par ce joueur (place quotidienne + depassements). */
    public static int personalSold(ServerPlayer player) {
        sync(player.server);
        return MongolData.get(player.server).personalSold(player.getUUID());
    }

    /** Place quotidienne restante de ce joueur (sur {@link #personalQuota()}). */
    public static int personalRemaining(ServerPlayer player) {
        return Math.max(0, personalQuota() - personalSold(player));
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
        sync(server);
        if (rotationPending) {
            rotationPending = !rotateStructure(server);
        }
    }

    /**
     * Recale la journee du marchand si l'heure de renouvellement est passee, et joue une seule fois
     * ce qui l'accompagne. <b>Toutes</b> les lectures de quota passent par ici : si seul le tick
     * remettait les compteurs a zero, un simple coup d'oeil au menu consommerait le changement de
     * jour avant lui, et ni l'etal ni l'annonce ne suivraient.
     */
    private static void sync(MinecraftServer server) {
        MongolData data = MongolData.get(server);
        // On n'annonce la reouverture que si la reserve avait reellement ete epuisee : pas de
        // message au tout premier demarrage, ni les jours ou le marchand n'a jamais ete rempli.
        boolean wasFull = data.initialized() && data.announced();
        boolean firstDay = !data.initialized();
        if (!data.rollOver(merchantDay())) {
            return;
        }
        // Le marchand ne bouge pas : il retrouve simplement sa place, et son etal change de blocs.
        if (!firstDay) {
            rotationPending = !rotateStructure(server);
        }
        if (wasFull && com.utopia.Config.MERCHANT_ANNOUNCE.get()) {
            String name = merchantName(server);
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(name + " a de nouveau de la place : venez le voir !")
                            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true))
                            .append(Component.literal("\n" + personalQuota()
                                            + " items du jour pour chacun, et " + dailyQuota()
                                            + " items de reserve commune.")
                                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false))),
                    false);
        }
    }

    /**
     * Fait passer l'etal du marchand a son etat suivant, une fois par jour a l'heure choisie. Le PNJ
     * reste ou il est : on deplace aussi son etat d'apparition, sinon il disparaitrait avec l'ancien
     * decor. Les structures en bascule automatique sont laissees tranquilles, leur mode (jour/nuit
     * ou horaires du jeu) reprendrait la main dans la seconde.
     */
    private static boolean rotateStructure(MinecraftServer server) {
        if (!com.utopia.Config.MERCHANT_ROTATE_STRUCTURE.get()) {
            return true;
        }
        com.utopia.data.StructureData data = com.utopia.data.StructureData.get(server);
        boolean changed = false;
        boolean done = true;
        for (com.utopia.data.StructureData.Struct st : data.all()) {
            if (!st.npcMongol || st.mode != com.utopia.data.StructureData.Mode.MANUAL) {
                continue;
            }
            int next = nextState(st);
            if (next == st.current) {
                continue; // un seul etat capture : rien a faire tourner
            }
            net.minecraft.server.level.ServerLevel level =
                    StructureManager.resolveLevel(server, st.dim);
            if (level == null || !level.isLoaded(st.min)) {
                done = false; // zone non chargee : on retentera au prochain tick
                continue;
            }
            boolean npcFollows = st.npcEnabled && st.npcState == st.current;
            if (!StructureManager.applyAnimated(server, st, next)) {
                done = false;
                continue;
            }
            if (npcFollows) {
                st.npcState = next;
            }
            changed = true;
        }
        if (changed) {
            data.setDirty();
        }
        return done;
    }

    /** Etat capture suivant, en tournant en boucle sur les etats utilises de la structure. */
    private static int nextState(com.utopia.data.StructureData.Struct st) {
        for (int step = 1; step <= st.stateCount; step++) {
            int slot = ((st.current - 1 + step) % st.stateCount) + 1;
            if (st.hasState(slot)) {
                return slot;
            }
        }
        return st.current;
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
     * les premiers items du joueur (sa place quotidienne) sont toujours rachetes, et seul le
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
        long paid = (long) removed * unitPrice();
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
        if (data.sold() < dailyQuota() || data.announced()) {
            return;
        }
        // Le drapeau est pose meme sans annonce : il sert aussi a savoir, au renouvellement,
        // que la reserve avait bien ete epuisee.
        data.setAnnounced(true);
        if (!com.utopia.Config.MERCHANT_ANNOUNCE.get()) {
            return;
        }
        String who = (merchantName == null || merchantName.isBlank()) ? "Le marchand" : merchantName;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(who + " a rempli ses reserves pour aujourd'hui ! "
                                + "Impossible de depasser vos " + personalQuota()
                                + " de place quotidienne avant " + resetLabel() + ".")
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
