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
        openPlayerCalendar(player, YearMonth.from(DailyManager.today()));
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
                ServerPlayer::closeContainer);

        // Resume de l'etat (slot 45).
        int streak = DailyManager.currentStreak(server, id);
        boolean available = DailyManager.isAvailable(server, id);
        gui.set(45, Icons.icon(Items.CLOCK, Icons.label("Votre progression", ChatFormatting.AQUA), List.of(
                Icons.lore("Serie : " + streak + " jour(s)", ChatFormatting.GOLD),
                available ? Icons.lore("Recompense du jour : disponible !", ChatFormatting.GREEN)
                        : Icons.lore("Prochaine dans " + Messages.formatDuration(DailyManager.secondsUntilTomorrow()), ChatFormatting.YELLOW))));

        Menus.open(player, gui);
    }

    // =============================================================================================
    // Menu admin principal : /daily admin
    // =============================================================================================

    public static void openAdminMenu(ServerPlayer admin) {
        UtopiaGui gui = new UtopiaGui(3, Icons.label("Daily - Administration", ChatFormatting.DARK_AQUA));

        gui.button(10, Icons.icon(Items.CHEST,
                Icons.label("Calendrier des recompenses", ChatFormatting.YELLOW),
                List.of(Icons.lore("Planifier les recompenses par date", ChatFormatting.GRAY),
                        Icons.lore("(1 a 2 mois a l'avance)", ChatFormatting.DARK_GRAY))),
                sp -> openAdminCalendar(sp, YearMonth.from(DailyManager.today())));

        gui.button(12, Icons.icon(Items.WRITABLE_BOOK,
                Icons.label("Recompense par defaut", ChatFormatting.YELLOW),
                List.of(Icons.lore("Donnee les jours SANS planning", ChatFormatting.GRAY))),
                DailyMenus::openBaseRewardEditor);

        gui.button(14, Icons.icon(Items.PLAYER_HEAD,
                Icons.label("Gestion des joueurs", ChatFormatting.YELLOW),
                List.of(Icons.lore("Series, reset, forcer une recompense", ChatFormatting.GRAY))),
                DailyMenus::openPlayerList);

        gui.button(16, Icons.icon(Items.COMPARATOR,
                Icons.label("Parametres actuels", ChatFormatting.YELLOW),
                List.of(Icons.lore("Afficher la configuration dans le chat", ChatFormatting.GRAY))),
                DailyMenus::showSettings);

        gui.fillEmpty();
        Menus.open(admin, gui);
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
