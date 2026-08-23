package com.utopia.structure;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.utopia.daily.DailyManager;
import com.utopia.daily.DailyMenus;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.MenuS2CPayload;
import com.utopia.net.OpenDailyPayload;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Menus du marchand mongol : cote joueur, la liste des items rachetes aujourd'hui avec le quota
 * restant ; cote admin, le calendrier qui programme ces items a l'avance (meme systeme que le Daily).
 */
public final class MongolMenus {

    private static final int PAGE_SIZE = 12;

    private MongolMenus() {
    }

    // ==============================================================================================
    //  Cote joueur : vendre au marchand
    // ==============================================================================================

    public static void openSell(ServerPlayer player, String structName) {
        openSell(player, structName, 0);
    }

    public static void openSell(ServerPlayer player, String structName, int page) {
        com.utopia.data.StructureData.Struct st =
                com.utopia.data.StructureData.get(player.server).get(structName);
        String merchantName = (st == null || st.npcName == null) ? "Marchand" : st.npcName;
        int remaining = MongolManager.remaining(player.server);
        List<ItemStack> accepted = MongolManager.acceptedToday();

        Component title = Component.literal(merchantName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        List<Component> stats = new ArrayList<>();
        stats.add(Component.literal("Il rachete 1 item = " + MongolManager.UNIT_PRICE + " Utopiece")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
        stats.add(Component.literal("Quota restant aujourd'hui : " + remaining
                        + " / " + MongolManager.DAILY_QUOTA + " items")
                .withStyle(s -> s.withColor(remaining > 0 ? ChatFormatting.GREEN : ChatFormatting.RED)
                        .withItalic(false)));
        if (accepted.isEmpty()) {
            stats.add(Component.literal("Il ne cherche rien de particulier aujourd'hui.")
                    .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
        }

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        for (ItemStack model : accepted) {
            ItemStack copy = model.copy();
            int owned = MongolManager.count(player, copy);
            entries.add(new OwoMenuServer.HubEntry(copy,
                    Icons.label(copy.getHoverName().getString(),
                            owned > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY),
                    Icons.lore(owned > 0 ? "Tu en as " + owned + " - clic pour vendre" : "Tu n'en as aucun",
                            owned > 0 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY),
                    sp -> promptSell(sp, structName, copy)));
        }

        OwoMenuServer.openHubPaged(player, title, stats, entries, page, PAGE_SIZE,
                (sp, p) -> openSell(sp, structName, p), sp -> ShopMenus.openShop(sp, structName));
    }

    private static void promptSell(ServerPlayer player, String structName, ItemStack model) {
        int remaining = MongolManager.remaining(player.server);
        if (remaining <= 0) {
            player.sendSystemMessage(Messages.warn(
                    "Le marchand a rempli ses reserves pour aujourd'hui : reviens apres minuit."));
            openSell(player, structName);
            return;
        }
        int owned = MongolManager.count(player, model);
        if (owned <= 0) {
            player.sendSystemMessage(Messages.warn("Tu n'as pas cet objet."));
            openSell(player, structName);
            return;
        }
        int max = Math.min(owned, remaining);
        String label = model.getHoverName().getString();
        Menus.promptAmount(player, Icons.label("Vendre : " + label, ChatFormatting.GOLD),
                List.of(Icons.lore("Le marchand paie " + MongolManager.UNIT_PRICE + " Utopiece par item",
                                ChatFormatting.GRAY),
                        Icons.lore("Tu en possedes " + owned + " - quota restant " + remaining,
                                ChatFormatting.DARK_GRAY)),
                Icons.label("Vendre", ChatFormatting.GREEN), max, 1, max,
                qty -> {
                    MongolManager.Sale sale = MongolManager.sell(player, model, (int) qty);
                    switch (sale.result()) {
                        case QUOTA_FULL -> player.sendSystemMessage(Messages.warn(
                                "Quota atteint : le marchand n'achete plus rien avant minuit."));
                        case NOT_ACCEPTED -> player.sendSystemMessage(Messages.warn(
                                "Le marchand ne veut plus de cet objet aujourd'hui."));
                        case NONE_OWNED -> player.sendSystemMessage(Messages.warn("Tu n'as pas cet objet."));
                        case INVALID -> player.sendSystemMessage(Messages.warn("Vente impossible."));
                        default -> player.sendSystemMessage(Messages.success("Vendu : " + sale.sold() + "x "
                                + label + " pour " + sale.paid() + " Utopieces."));
                    }
                    openSell(player, structName);
                });
    }

    // ==============================================================================================
    //  Cote admin : programme des items acceptes (meme calendrier que le Daily)
    // ==============================================================================================

    public static void openCalendar(ServerPlayer admin, String structName) {
        openCalendar(admin, structName, YearMonth.now());
    }

    /** Calendrier mensuel : un clic sur un jour edite les items rachetes ce jour-la. */
    public static void openCalendar(ServerPlayer admin, String structName, YearMonth ym) {
        LocalDate today = LocalDate.now();
        int firstWeekday = ym.atDay(1).getDayOfWeek().getValue() - 1; // 0 = lundi
        int daysInMonth = ym.lengthOfMonth();

        // Les jours occupent les ids 1..31 ; la navigation doit rester < 54 (UtopiaGui : 6 rangees).
        int prevId = 50;
        int nextId = 51;
        int backId = 52;
        UtopiaGui gui = new UtopiaGui(6, Component.literal("Marchand mongol"));

        List<OpenDailyPayload.Day> days = new ArrayList<>(daysInMonth);
        int planned = 0;
        for (int dn = 1; dn <= daysInMonth; dn++) {
            LocalDate date = ym.atDay(dn);
            boolean past = date.isBefore(today);
            int state;
            List<ItemStack> items = new ArrayList<>();
            int actionId;
            if (past) {
                state = OpenDailyPayload.OTHER;
                actionId = -1;
            } else {
                List<String> specs = MongolManager.acceptedSpecs(date);
                boolean has = !specs.isEmpty();
                if (has) {
                    planned++;
                    items = stacks(specs);
                }
                state = has ? OpenDailyPayload.CLAIMED : OpenDailyPayload.FUTURE;
                actionId = dn;
                final LocalDate d = date;
                gui.button(dn, ItemStack.EMPTY, sp -> openDayEditor(sp, structName, d, ym));
            }
            days.add(new OpenDailyPayload.Day(dn, state, items, actionId));
        }
        gui.button(prevId, ItemStack.EMPTY, sp -> openCalendar(sp, structName, ym.minusMonths(1)));
        gui.button(nextId, ItemStack.EMPTY, sp -> openCalendar(sp, structName, ym.plusMonths(1)));
        gui.button(backId, ItemStack.EMPTY, sp -> StructureMenus.openShopAdmin(sp, structName));

        Component title = Component.literal("Marchand mongol - " + monthName(ym) + " " + ym.getYear())
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        Component plannedLine = Component.literal(planned + " jour(s) programme(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false));
        List<Component> help = List.of(
                Component.literal("Clic sur un jour : choisir les items rachetes ce jour-la")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                Component.literal("1 item = " + MongolManager.UNIT_PRICE + " Utopiece | quota "
                                + MongolManager.DAILY_QUOTA + " items/jour (serveur)")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        OwoMenuServer.openScreen(admin, gui, sid -> MenuS2CPayload.of(new OpenDailyPayload(
                sid, title, plannedLine, firstWeekday, daysInMonth, days,
                List.of(new ItemStack(Items.EMERALD)), help, prevId, nextId, -1, backId, false)));
    }

    /** Editeur des items d'un jour : reprend l'editeur du Daily (depose / clic pour retirer). */
    private static void openDayEditor(ServerPlayer admin, String structName, LocalDate date, YearMonth backTo) {
        DailyMenus.openItemsEditor(admin,
                Icons.label("Items rachetes le " + date, ChatFormatting.GOLD),
                MongolManager.acceptedSpecs(date),
                specs -> {
                    MongolManager.calendar().setReward(date, specs);
                    admin.sendSystemMessage(Messages.success(
                            specs.size() + " item(s) programme(s) pour le " + date + "."));
                },
                sp -> openCalendar(sp, structName, backTo));
    }

    private static List<ItemStack> stacks(List<? extends String> specs) {
        List<ItemStack> out = new ArrayList<>();
        for (String spec : specs) {
            ItemStack stack = DailyManager.specToStack(spec);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    private static String monthName(YearMonth ym) {
        String month = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
        return month.isEmpty() ? month : Character.toUpperCase(month.charAt(0)) + month.substring(1);
    }
}
