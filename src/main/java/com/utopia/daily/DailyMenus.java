package com.utopia.daily;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import com.utopia.Config;
import com.utopia.gui.Icons;
import com.utopia.gui.Menus;
import com.utopia.gui.UtopiaGui;
import com.utopia.net.MenuS2CPayload;
import com.utopia.net.OpenDailyPayload;
import com.utopia.net.OwoMenuServer;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Construit et ouvre les interfaces (GUI coffre) du systeme de recompenses quotidiennes (calendrier). */
public final class DailyMenus {

    private DailyMenus() {
    }

    // =============================================================================================
    // Menu joueur : /daily -> calendrier du mois courant
    // =============================================================================================

    public static void openPlayerMenu(ServerPlayer player) {
        openPlayerCalendarRich(player, YearMonth.from(DailyManager.today()));
    }

    /**
     * Calendrier joueur "riche" (ecran owo dedie) : grille mensuelle coloree par etat + streak +
     * prochaine recompense. Les actions (reclamer, mois precedent/suivant, retour au menu) passent
     * par {@link OwoMenuServer#openScreen}.
     */
    public static void openPlayerCalendarRich(ServerPlayer player, YearMonth ym) {
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        LocalDate today = DailyManager.today();
        long todayEpoch = today.toEpochDay();

        int firstWeekday = ym.atDay(1).getDayOfWeek().getValue() - 1; // 0 = lundi
        int daysInMonth = ym.lengthOfMonth();

        List<OpenDailyPayload.Day> days = new ArrayList<>(daysInMonth);
        for (int dn = 1; dn <= daysInMonth; dn++) {
            LocalDate date = ym.atDay(dn);
            long epoch = date.toEpochDay();
            boolean claimed = DailyManager.hasClaimed(server, id, epoch);
            int state;
            ItemStack reward = ItemStack.EMPTY;
            if (date.isEqual(today)) {
                state = claimed ? OpenDailyPayload.TODAY_DONE : OpenDailyPayload.CLAIMABLE;
                reward = firstReward(date);
            } else if (date.isAfter(today)) {
                state = OpenDailyPayload.FUTURE;
                reward = firstReward(date);
            } else if (claimed) {
                state = OpenDailyPayload.CLAIMED;
            } else if (epoch >= todayEpoch - 70) {
                state = OpenDailyPayload.MISSED;
            } else {
                state = OpenDailyPayload.OTHER;
            }
            // Seul le jour reclamable est cliquable (id = claimId = 0).
            int actionId = state == OpenDailyPayload.CLAIMABLE ? 0 : -1;
            days.add(new OpenDailyPayload.Day(dn, state, reward, actionId));
        }

        int streak = DailyManager.currentStreak(server, id);
        boolean available = DailyManager.isAvailable(server, id);
        Component streakLine = Component.literal("Serie : " + streak + " jour" + (streak > 1 ? "s" : ""))
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false));

        // Prochaine recompense (aujourd'hui si dispo, sinon demain).
        LocalDate nextDay = available ? today : today.plusDays(1);
        List<Component> nextLore = new ArrayList<>();
        nextLore.add(Component.literal(available
                ? "Recompense du jour : disponible !"
                : "Prochaine dans " + Messages.formatDuration(DailyManager.secondsUntilTomorrow()))
                .withStyle(s -> s.withColor(available ? ChatFormatting.GREEN : ChatFormatting.YELLOW).withItalic(false)));
        ItemStack nextIcon = new ItemStack(Items.CHEST);
        boolean firstSpec = true;
        for (String spec : DailyManager.rewardSpecsFor(nextDay)) {
            ItemStack st = DailyManager.specToStack(spec);
            if (!st.isEmpty()) {
                if (firstSpec) {
                    nextIcon = st.copy();
                    firstSpec = false;
                }
                nextLore.add(Component.literal(" - " + st.getCount() + "x " + st.getHoverName().getString())
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)));
            }
        }

        Component title = Component.literal("Recompenses - " + monthName(ym) + " " + ym.getYear())
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));

        // Actions (portees par un UtopiaGui : clic id -> action).
        int claimId = 0;
        int prevId = 1;
        int nextId = 2;
        int backId = 3;
        UtopiaGui gui = new UtopiaGui(1, title);
        gui.button(claimId, ItemStack.EMPTY, sp -> {
            DailyManager.claim(sp);
            openPlayerCalendarRich(sp, YearMonth.from(DailyManager.today()));
        });
        gui.button(prevId, ItemStack.EMPTY, sp -> openPlayerCalendarRich(sp, ym.minusMonths(1)));
        gui.button(nextId, ItemStack.EMPTY, sp -> openPlayerCalendarRich(sp, ym.plusMonths(1)));
        gui.button(backId, ItemStack.EMPTY, com.utopia.menu.MainMenu::open);

        final ItemStack nextIconF = nextIcon;
        OwoMenuServer.openScreen(player, gui, sid -> MenuS2CPayload.of(new OpenDailyPayload(
                sid, title, streakLine, firstWeekday, daysInMonth, days,
                nextIconF, nextLore, prevId, nextId, claimId, backId, available)));
    }

    /** Premiere recompense (non vide) prevue pour {@code date}, ou un stack vide. */
    private static ItemStack firstReward(LocalDate date) {
        for (String spec : DailyManager.rewardSpecsFor(date)) {
            ItemStack st = DailyManager.specToStack(spec);
            if (!st.isEmpty()) {
                return st;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Nom du mois en francais, premiere lettre en majuscule. */
    private static String monthName(YearMonth ym) {
        String m = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
        return m.isEmpty() ? m : Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    public static void openPlayerCalendar(ServerPlayer player, YearMonth ym) {
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        LocalDate today = DailyManager.today();
        long todayDay = today.toEpochDay();

        UtopiaGui gui = buildCalendarBase(monthTitle("Daily", ym), ym,
                (g, slot, date) -> {
                    int d = date.getDayOfMonth();
                    long epoch = date.toEpochDay();
                    boolean claimed = DailyManager.hasClaimed(server, id, epoch);

                    if (date.isEqual(today)) {
                        if (claimed) {
                            g.set(slot, Icons.icon(Items.LIME_STAINED_GLASS_PANE, d,
                                    Icons.label("Aujourd'hui (jour " + d + ")", ChatFormatting.GREEN),
                                    List.of(Icons.lore("Deja recupere - reviens demain !", ChatFormatting.GRAY))));
                        } else {
                            List<Component> lore = new ArrayList<>();
                            lore.add(Icons.lore("Clique pour recuperer !", ChatFormatting.GREEN));
                            for (String spec : DailyManager.rewardSpecsFor(date)) {
                                ItemStack st = DailyManager.specToStack(spec);
                                if (!st.isEmpty()) {
                                    lore.add(Icons.lore(" - " + st.getCount() + "x " + st.getHoverName().getString(), ChatFormatting.AQUA));
                                }
                            }
                            g.button(slot, Icons.icon(Items.CHEST, d,
                                    Icons.label("Aujourd'hui (jour " + d + ")", ChatFormatting.YELLOW), lore),
                                    sp -> {
                                        DailyManager.claim(sp);
                                        openPlayerCalendar(sp, ym);
                                    });
                        }
                    } else if (date.isAfter(today)) {
                        g.set(slot, Icons.icon(Items.SHULKER_BOX, d,
                                Icons.label("Jour " + d, ChatFormatting.LIGHT_PURPLE),
                                List.of(Icons.lore("A venir - reviens le " + date, ChatFormatting.GRAY))));
                    } else { // passe
                        if (claimed) {
                            g.set(slot, Icons.icon(Items.LIME_STAINED_GLASS_PANE, d,
                                    Icons.label("Jour " + d, ChatFormatting.GREEN),
                                    List.of(Icons.lore("Recupere", ChatFormatting.GRAY))));
                        } else if (epoch >= todayDay - 70) {
                            g.set(slot, Icons.icon(Items.BARRIER, d,
                                    Icons.label("Jour " + d, ChatFormatting.RED),
                                    List.of(Icons.lore("Manque", ChatFormatting.GRAY))));
                        } else {
                            g.set(slot, Icons.icon(Items.GRAY_STAINED_GLASS_PANE, d,
                                    Icons.label("Jour " + d, ChatFormatting.DARK_GRAY), List.of()));
                        }
                    }
                },
                sp -> openPlayerCalendar(sp, ym.minusMonths(1)),
                sp -> openPlayerCalendar(sp, ym.plusMonths(1)),
                com.utopia.gui.Menus::close);

        // Resume de l'etat (slot 45).
        int streak = DailyManager.currentStreak(server, id);
        boolean available = DailyManager.isAvailable(server, id);
        gui.set(45, Icons.icon(Items.CLOCK, Icons.label("Votre progression", ChatFormatting.AQUA), List.of(
                Icons.lore("Serie : " + streak + " jour(s)", ChatFormatting.GOLD),
                available ? Icons.lore("Recompense du jour : disponible !", ChatFormatting.GREEN)
                        : Icons.lore("Prochaine dans " + Messages.formatDuration(DailyManager.secondsUntilTomorrow()), ChatFormatting.YELLOW))));

        // Item du prochain daily a recuperer (aujourd'hui si dispo, sinon demain).
        LocalDate nextDay = available ? today : today.plusDays(1);
        List<Component> nextLore = new ArrayList<>();
        nextLore.add(Icons.lore(available ? "A recuperer aujourd'hui (" + nextDay + ")" : "Demain (" + nextDay + ")",
                ChatFormatting.GRAY));
        ItemStack nextItem = new ItemStack(Items.CHEST);
        for (String spec : DailyManager.rewardSpecsFor(nextDay)) {
            ItemStack st = DailyManager.specToStack(spec);
            if (!st.isEmpty()) {
                if (nextItem.is(Items.CHEST)) {
                    nextItem = st.copy(); // 1er item comme icone
                }
                nextLore.add(Icons.lore(" - " + st.getCount() + "x " + st.getHoverName().getString(), ChatFormatting.AQUA));
            }
        }
        if (nextLore.size() == 1) {
            nextLore.add(Icons.lore("(aucune recompense prevue)", ChatFormatting.DARK_GRAY));
        }
        gui.set(53, Icons.icon(nextItem.getItem(), Math.max(1, nextItem.getCount()),
                Icons.label("Prochaine recompense", ChatFormatting.GREEN), nextLore));

        gui.button(44, Icons.icon(Items.ARROW, Icons.label("Retour au menu", ChatFormatting.YELLOW), List.of()),
                com.utopia.menu.MainMenu::open);

        Menus.open(player, gui);
    }

    // =============================================================================================
    // Menu admin principal : /daily admin
    // =============================================================================================

    public static void openAdminMenu(ServerPlayer admin) {
        Component title = Component.literal("Daily - Administration")
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true));
        List<Component> stats = List.of(Component.literal("Planification des recompenses & gestion des joueurs")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        List<OwoMenuServer.HubEntry> entries = new ArrayList<>();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.CHEST),
                Icons.label("Calendrier des recompenses", ChatFormatting.YELLOW),
                Icons.lore("Planifier par date (1-2 mois a l'avance)", ChatFormatting.GRAY),
                sp -> openAdminCalendarRich(sp, YearMonth.from(DailyManager.today()))));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.WRITABLE_BOOK),
                Icons.label("Recompense par defaut", ChatFormatting.YELLOW),
                Icons.lore("Donnee les jours sans planning", ChatFormatting.GRAY),
                DailyMenus::openBaseRewardEditor));
        boolean defOn = Config.DAILY_DEFAULT_ENABLED.get();
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(defOn ? Items.LIME_DYE : Items.GRAY_DYE),
                Icons.label("Defaut : " + (defOn ? "ACTIVE" : "DESACTIVE"),
                        defOn ? ChatFormatting.GREEN : ChatFormatting.RED),
                Icons.lore(defOn ? "Jours non planifies = recompense par defaut" : "Seuls les jours planifies donnent",
                        ChatFormatting.GRAY),
                sp -> {
                    Config.DAILY_DEFAULT_ENABLED.set(!Config.DAILY_DEFAULT_ENABLED.get());
                    Config.DAILY_DEFAULT_ENABLED.save();
                    openAdminMenu(sp);
                }));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.PLAYER_HEAD),
                Icons.label("Gestion des joueurs", ChatFormatting.YELLOW),
                Icons.lore("Series, reset, forcer une recompense", ChatFormatting.GRAY),
                DailyMenus::openPlayerList));
        entries.add(new OwoMenuServer.HubEntry(new ItemStack(Items.COMPARATOR),
                Icons.label("Parametres actuels", ChatFormatting.YELLOW),
                Icons.lore("Afficher la config dans le chat", ChatFormatting.GRAY),
                DailyMenus::showSettings));

        OwoMenuServer.openHub(admin, title, stats, entries,
                DailyMenus::openAdminMenu, com.utopia.menu.AdminMenu::open);
    }

    private static void showSettings(ServerPlayer admin) {
        admin.sendSystemMessage(Messages.info("Parametres daily :"));
        admin.sendSystemMessage(Messages.info(" - 1 recompense par jour calendaire (modele calendrier)."));
        admin.sendSystemMessage(Messages.info(" - serie : " + (Config.DAILY_STREAK_ENABLED.get() ? "activee" : "desactivee")
                + " | un jour manque reinitialise la serie."));
        admin.sendSystemMessage(Messages.info(" - recompense par defaut : " + Config.DAILY_ITEMS.get().size()
                + " item(s) | commandes : " + Config.DAILY_COMMANDS.get().size()
                + " | paliers : " + Config.DAILY_STREAK_MILESTONES.get().size()));
        admin.sendSystemMessage(Messages.info("(commandes/paliers : config/utopia-common.toml | calendrier : config/utopia/daily_calendar.json)"));
    }

    // =============================================================================================
    // Calendrier admin : navigation par mois, clic sur un jour -> editeur de la date
    // =============================================================================================

    public static void openAdminCalendar(ServerPlayer admin, YearMonth ym) {
        DailyCalendar cal = DailyManager.calendar();
        LocalDate today = DailyManager.today();

        UtopiaGui gui = buildCalendarBase(monthTitle("Calendrier", ym), ym,
                (g, slot, date) -> {
                    int d = date.getDayOfMonth();
                    int planned = cal.rewardSize(date);
                    List<Component> lore = new ArrayList<>();
                    lore.add(Icons.lore("Date : " + date, ChatFormatting.DARK_GRAY));
                    lore.add(Icons.lore("Recompense planifiee : " + planned + " item(s)",
                            planned > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));

                    if (date.isBefore(today)) {
                        lore.add(Icons.lore("Passe (non modifiable)", ChatFormatting.DARK_GRAY));
                        g.set(slot, Icons.icon(Items.GRAY_STAINED_GLASS_PANE, d,
                                Icons.label("Jour " + d, ChatFormatting.DARK_GRAY), lore));
                    } else {
                        boolean isToday = date.isEqual(today);
                        lore.add(Icons.lore("Clic : editer la recompense", ChatFormatting.YELLOW));
                        g.button(slot, Icons.icon(isToday ? Items.CHEST : Items.SHULKER_BOX, d,
                                Icons.label("Jour " + d + (isToday ? " (aujourd'hui)" : ""),
                                        isToday ? ChatFormatting.YELLOW : ChatFormatting.LIGHT_PURPLE), lore),
                                sp -> openDayEditor(sp, date, ym));
                    }
                },
                sp -> openAdminCalendar(sp, ym.minusMonths(1)),
                sp -> openAdminCalendar(sp, ym.plusMonths(1)),
                DailyMenus::openAdminMenu);

        gui.set(45, Icons.icon(Items.PAPER, Icons.label("Aide", ChatFormatting.AQUA), List.of(
                Icons.lore("Coffre = aujourd'hui, Shulker = a venir.", ChatFormatting.GRAY),
                Icons.lore("Clique un jour pour definir sa recompense.", ChatFormatting.GRAY),
                Icons.lore("Fleches = changer de mois.", ChatFormatting.GRAY))));

        Menus.open(admin, gui);
    }

    private static void openDayEditor(ServerPlayer admin, LocalDate date, YearMonth backTo) {
        openItemsEditor(admin,
                Icons.label("Recompense du " + date, ChatFormatting.DARK_AQUA),
                DailyManager.calendar().getReward(date),
                specs -> {
                    DailyManager.calendar().setReward(date, specs);
                    admin.sendSystemMessage(Messages.success(specs.size() + " item(s) planifie(s) pour le " + date + "."));
                },
                sp -> openAdminCalendar(sp, backTo));
    }

    // =============================================================================================
    // Calendrier admin "riche" (meme ecran que le calendrier joueur) : clic sur un jour = editer
    // =============================================================================================

    public static void openAdminCalendarRich(ServerPlayer admin, YearMonth ym) {
        DailyCalendar cal = DailyManager.calendar();
        LocalDate today = DailyManager.today();

        int firstWeekday = ym.atDay(1).getDayOfWeek().getValue() - 1; // 0 = lundi
        int daysInMonth = ym.lengthOfMonth();

        // Les jours occupent les ids 1..31 ; la nav doit rester < 54 (UtopiaGui plafonne a 6 rangees).
        int prevId = 50;
        int nextId = 51;
        int backId = 52;
        UtopiaGui gui = new UtopiaGui(6, Component.literal("Calendrier"));

        List<OpenDailyPayload.Day> days = new ArrayList<>(daysInMonth);
        int planned = 0;
        for (int dn = 1; dn <= daysInMonth; dn++) {
            LocalDate date = ym.atDay(dn);
            boolean isPast = date.isBefore(today);
            int state;
            ItemStack reward = ItemStack.EMPTY;
            int actionId;
            if (isPast) {
                state = OpenDailyPayload.OTHER;
                actionId = -1;
            } else {
                boolean has = cal.rewardSize(date) > 0;
                if (has) {
                    planned++;
                }
                state = has ? OpenDailyPayload.CLAIMED : OpenDailyPayload.FUTURE;
                reward = has ? firstSpecStack(cal.getReward(date)) : ItemStack.EMPTY;
                actionId = dn; // clic -> editer ce jour
                final LocalDate dd = date;
                gui.button(dn, ItemStack.EMPTY, sp -> openDayEditorRich(sp, dd, ym));
            }
            days.add(new OpenDailyPayload.Day(dn, state, reward, actionId));
        }
        gui.button(prevId, ItemStack.EMPTY, sp -> openAdminCalendarRich(sp, ym.minusMonths(1)));
        gui.button(nextId, ItemStack.EMPTY, sp -> openAdminCalendarRich(sp, ym.plusMonths(1)));
        gui.button(backId, ItemStack.EMPTY, DailyMenus::openAdminMenu);

        Component title = Component.literal("Calendrier - " + monthName(ym) + " " + ym.getYear())
                .withStyle(s -> s.withColor(ChatFormatting.DARK_AQUA).withBold(true));
        Component plannedLine = Component.literal(planned + " jour(s) planifie(s)")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false));
        List<Component> help = List.of(
                Component.literal("Clic sur un jour : editer sa recompense")
                        .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)),
                Component.literal("Vert = planifie | violet = vide | gris = passe")
                        .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));

        OwoMenuServer.openScreen(admin, gui, sid -> MenuS2CPayload.of(new OpenDailyPayload(
                sid, title, plannedLine, firstWeekday, daysInMonth, days,
                new ItemStack(Items.WRITABLE_BOOK), help, prevId, nextId, -1, backId, false)));
    }

    private static void openDayEditorRich(ServerPlayer admin, LocalDate date, YearMonth backTo) {
        openItemsEditor(admin,
                Icons.label("Recompense du " + date, ChatFormatting.DARK_AQUA),
                DailyManager.calendar().getReward(date),
                specs -> {
                    DailyManager.calendar().setReward(date, specs);
                    admin.sendSystemMessage(Messages.success(specs.size() + " item(s) planifie(s) pour le " + date + "."));
                },
                sp -> openAdminCalendarRich(sp, backTo));
    }

    /** Premiere recompense (non vide) d'une liste de specs, ou un stack vide. */
    private static ItemStack firstSpecStack(List<? extends String> specs) {
        for (String spec : specs) {
            ItemStack st = DailyManager.specToStack(spec);
            if (!st.isEmpty()) {
                return st;
            }
        }
        return ItemStack.EMPTY;
    }

    // =============================================================================================
    // Editeur d'items generique (recompense de base ou d'une date)
    // =============================================================================================

    private static final int EDIT_FROM = 9;
    private static final int EDIT_TO = 44;

    private static void openBaseRewardEditor(ServerPlayer admin) {
        List<String> current = new ArrayList<>(Config.DAILY_ITEMS.get());
        openItemsEditor(admin,
                Icons.label("Recompense par defaut", ChatFormatting.DARK_AQUA),
                current,
                specs -> {
                    Config.DAILY_ITEMS.set(specs);
                    Config.DAILY_ITEMS.save();
                    admin.sendSystemMessage(Messages.success(specs.size() + " item(s) enregistre(s) comme recompense par defaut."));
                },
                DailyMenus::openAdminMenu);
    }

    private static void openItemsEditor(ServerPlayer admin, Component title, List<? extends String> currentSpecs,
                                        Consumer<List<String>> onSave, Consumer<ServerPlayer> reopen) {
        UtopiaGui gui = new UtopiaGui(6, title);

        // Rangee 0 : recompense actuelle (lecture seule).
        int slot = 0;
        for (String spec : currentSpecs) {
            if (slot > 8) {
                break;
            }
            ItemStack st = DailyManager.specToStack(spec);
            if (!st.isEmpty()) {
                gui.set(slot++, Icons.icon(st.getItem(), st.getCount(),
                        st.getHoverName().copy().withStyle(s -> s.withItalic(false)),
                        List.of(Icons.lore("Actuellement defini (lecture seule)", ChatFormatting.DARK_GRAY))));
            }
        }
        for (int i = slot; i <= 8; i++) {
            gui.set(i, Icons.filler());
        }

        // Zone editable.
        for (int i = EDIT_FROM; i <= EDIT_TO; i++) {
            gui.editableSlot(i);
        }

        gui.set(45, Icons.icon(Items.PAPER, Icons.label("Mode d'emploi", ChatFormatting.AQUA), List.of(
                Icons.lore("Placez les items a donner, puis Sauvegarder.", ChatFormatting.GRAY),
                Icons.lore("La zone definit la TOTALITE de la recompense.", ChatFormatting.GRAY),
                Icons.lore("Vos items vous sont rendus a la fermeture.", ChatFormatting.GREEN))));

        gui.button(48, Icons.icon(Items.LIME_DYE, Icons.label("SAUVEGARDER", ChatFormatting.GREEN), List.of()),
                sp -> {
                    onSave.accept(readEditable(gui));
                    returnEditorItems(sp, gui);
                    gui.markFinalized();
                    reopen.accept(sp);
                });

        gui.button(50, Icons.icon(Items.RED_DYE, Icons.label("ANNULER", ChatFormatting.RED),
                List.of(Icons.lore("Ferme sans sauvegarder (items rendus)", ChatFormatting.GRAY))),
                sp -> {
                    returnEditorItems(sp, gui);
                    gui.markFinalized();
                    reopen.accept(sp);
                });

        for (int i = 45; i <= 53; i++) {
            if (i != 45 && i != 48 && i != 50 && gui.container().getItem(i).isEmpty()) {
                gui.set(i, Icons.filler());
            }
        }

        gui.onClose(sp -> returnEditorItems(sp, gui));
        Menus.open(admin, gui);
    }

    private static List<String> readEditable(UtopiaGui gui) {
        List<String> specs = new ArrayList<>();
        for (int i = EDIT_FROM; i <= EDIT_TO; i++) {
            ItemStack stack = gui.container().getItem(i);
            if (!stack.isEmpty()) {
                specs.add(DailyManager.stackToSpec(stack));
            }
        }
        return specs;
    }

    private static void returnEditorItems(ServerPlayer admin, UtopiaGui gui) {
        for (int i = EDIT_FROM; i <= EDIT_TO; i++) {
            ItemStack stack = gui.container().getItem(i);
            if (!stack.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(admin, stack.copy());
                gui.container().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    // =============================================================================================
    // Gestion des joueurs
    // =============================================================================================

    public static void openPlayerList(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        UtopiaGui gui = new UtopiaGui(6, Icons.label("Gestion des joueurs", ChatFormatting.DARK_AQUA));

        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        int slot = 0;
        for (ServerPlayer target : online) {
            if (slot > 44) {
                break;
            }
            UUID tid = target.getUUID();
            int streak = DailyManager.currentStreak(server, tid);
            boolean available = DailyManager.isAvailable(server, tid);
            List<Component> lore = List.of(
                    Icons.lore("Serie : " + streak + " jour(s)", ChatFormatting.GOLD),
                    Icons.lore("Recompense : " + (available ? "disponible" : "deja prise aujourd'hui"),
                            available ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                    Icons.lore("Cliquez pour gerer", ChatFormatting.DARK_GRAY));
            gui.button(slot++,
                    Icons.playerHead(target, Icons.label(target.getGameProfile().getName(), ChatFormatting.WHITE), lore),
                    sp -> openPlayerPanel(sp, tid));
        }
        if (online.size() > 45) {
            admin.sendSystemMessage(Messages.warn("Plus de 45 joueurs en ligne : seuls les 45 premiers sont affiches."));
        }

        gui.button(49, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                DailyMenus::openAdminMenu);
        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    public static void openPlayerPanel(ServerPlayer admin, UUID targetId) {
        MinecraftServer server = admin.server;
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            admin.sendSystemMessage(Messages.error("Ce joueur n'est plus connecte."));
            openPlayerList(admin);
            return;
        }

        int streak = DailyManager.currentStreak(server, targetId);
        boolean available = DailyManager.isAvailable(server, targetId);

        UtopiaGui gui = new UtopiaGui(3,
                Icons.label("Joueur : " + target.getGameProfile().getName(), ChatFormatting.DARK_AQUA));

        gui.set(4, Icons.playerHead(target,
                Icons.label(target.getGameProfile().getName(), ChatFormatting.WHITE),
                List.of(
                        Icons.lore("Serie : " + streak + " jour(s)", ChatFormatting.GOLD),
                        Icons.lore("Recompense : " + (available ? "disponible"
                                : "prise (prochaine dans " + Messages.formatDuration(DailyManager.secondsUntilTomorrow()) + ")"),
                                available ? ChatFormatting.GREEN : ChatFormatting.YELLOW))));

        gui.button(10, Icons.icon(Items.EMERALD, Icons.label("Forcer la recompense", ChatFormatting.GREEN),
                List.of(Icons.lore("Donne la recompense du jour (meme si deja prise)", ChatFormatting.GRAY))),
                sp -> {
                    ServerPlayer t = server.getPlayerList().getPlayer(targetId);
                    if (t != null) {
                        DailyManager.adminForceClaim(t);
                        sp.sendSystemMessage(Messages.success("Recompense donnee a " + t.getGameProfile().getName() + "."));
                    }
                    openPlayerPanel(sp, targetId);
                });

        gui.button(12, Icons.icon(Items.BARRIER, Icons.label("Reinitialiser", ChatFormatting.RED),
                List.of(Icons.lore("Remet serie, reclamation et historique a zero", ChatFormatting.GRAY))),
                sp -> {
                    DailyManager.reset(server, targetId);
                    sp.sendSystemMessage(Messages.success("Joueur reinitialise."));
                    openPlayerPanel(sp, targetId);
                });

        gui.button(14, Icons.icon(Items.LIME_DYE, Icons.label("Serie +1", ChatFormatting.GREEN), List.of()),
                sp -> {
                    DailyManager.adjustStreak(server, targetId, 1);
                    openPlayerPanel(sp, targetId);
                });

        gui.button(15, Icons.icon(Items.RED_DYE, Icons.label("Serie -1", ChatFormatting.RED), List.of()),
                sp -> {
                    DailyManager.adjustStreak(server, targetId, -1);
                    openPlayerPanel(sp, targetId);
                });

        gui.button(16, Icons.icon(Items.ARROW, Icons.label("Retour", ChatFormatting.YELLOW), List.of()),
                DailyMenus::openPlayerList);

        gui.fillEmpty();
        Menus.open(admin, gui);
    }

    // =============================================================================================
    // Construction commune d'un calendrier mensuel (grille 7 colonnes, navigation)
    // =============================================================================================

    /** Decore une case-jour du calendrier. */
    private interface DayDecorator {
        void decorate(UtopiaGui gui, int slot, LocalDate date);
    }

    private static UtopiaGui buildCalendarBase(Component title, YearMonth ym, DayDecorator decorator,
                                               Consumer<ServerPlayer> prev, Consumer<ServerPlayer> next,
                                               Consumer<ServerPlayer> back) {
        UtopiaGui gui = new UtopiaGui(6, title);

        LocalDate first = ym.atDay(1);
        int startWeekday = first.getDayOfWeek().getValue() - 1; // 0 = lundi
        int length = ym.lengthOfMonth();
        for (int d = 1; d <= length; d++) {
            int index = startWeekday + d - 1;
            int week = index / 7;
            int weekday = index % 7;
            if (week > 5) {
                break; // securite (max 6 semaines)
            }
            int slot = week * 9 + weekday + 1; // colonnes 1 a 7
            decorator.decorate(gui, slot, ym.atDay(d));
        }

        gui.button(0, Icons.icon(Items.ARROW, Icons.label("<- Mois precedent", ChatFormatting.YELLOW), List.of()),
                prev::accept);
        gui.button(8, Icons.icon(Items.ARROW, Icons.label("Mois suivant ->", ChatFormatting.YELLOW), List.of()),
                next::accept);
        gui.button(53, Icons.icon(Items.BARRIER, Icons.label("Fermer / Retour", ChatFormatting.RED), List.of()),
                back::accept);

        gui.fillEmpty();
        return gui;
    }

    private static Component monthTitle(String prefix, YearMonth ym) {
        String month = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
        if (!month.isEmpty()) {
            month = Character.toUpperCase(month.charAt(0)) + month.substring(1);
        }
        return Icons.label(prefix + " - " + month + " " + ym.getYear(), ChatFormatting.DARK_AQUA);
    }
}
