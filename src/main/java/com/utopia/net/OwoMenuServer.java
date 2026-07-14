package com.utopia.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import com.utopia.gui.UtopiaGui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Cote serveur : tient le menu owo actif de chaque joueur (menu a icones, saisie de montant, ou
 * saisie de texte), envoie l'ouverture/fermeture au client et execute les actions/valeurs recues.
 */
public final class OwoMenuServer {

    private OwoMenuServer() {
    }

    private record AmountPrompt(long min, long max, LongConsumer onConfirm) {
    }

    private record TextPrompt(Consumer<String> onConfirm) {
    }

    /** Une session active : menu a icones ({@code gui}), saisie de montant ({@code amount}) ou texte ({@code text}). */
    private record Session(int id, UtopiaGui gui, AmountPrompt amount, TextPrompt text) {
    }

    /** Une entree de l'ecran d'accueil (hub) : icone + libelle + sous-libelle + action au clic. */
    public record HubEntry(ItemStack icon, Component label, Component sublabel, Consumer<ServerPlayer> action) {
    }

    /** Une ligne d'un panneau de reglages : libelle + valeur + (libelle de bouton + action, optionnels). */
    public record PanelRow(Component label, Component value, Component buttonLabel, Consumer<ServerPlayer> action) {
    }

    /** Un bouton d'action du pied de page d'un panneau. */
    public record PanelAction(Component label, Consumer<ServerPlayer> action) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /**
     * Nombre d'actions adressables : les clics sont portes par un {@link UtopiaGui} de 6 rangees max,
     * soit 54 slots. Un id au-dela est ignore en silence par le GUI -> bouton mort. Les menus doivent
     * donc rester sous cette limite (sinon : paginer).
     */
    public static final int MAX_ACTION_SLOTS = 54;

    /** Tronque une liste d'entrees a {@code max} en journalisant le surplus (evite les boutons morts). */
    private static <T> List<T> clampEntries(List<T> entries, int max, Component title) {
        if (entries.size() <= max) {
            return entries;
        }
        com.utopia.UtopiaMod.LOGGER.warn(
                "[Utopia] Menu \"{}\" : {} entrees pour {} slots d'action disponibles ; le surplus est masque "
                        + "(pense a paginer ce menu).", title.getString(), entries.size(), max);
        return entries.subList(0, max);
    }

    /** Ouvre (ou remplace) le menu a icones du joueur. */
    public static void open(ServerPlayer player, UtopiaGui gui) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, gui, null, null));

        int size = gui.size();
        List<ItemStack> items = new ArrayList<>(size);
        List<Integer> clickable = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(gui.container().getItem(i));
            if (gui.action(i) != null || gui.rightAction(i) != null) {
                clickable.add(i);
            }
        }
        PacketDistributor.sendToPlayer(player,
                MenuS2CPayload.of(new OpenMenuPayload(id, gui.title(), gui.rows(), items, clickable,
                        gui.gridLayout(), gui.iconOnly())));
    }

    /**
     * Ouvre l'ecran d'accueil "riche" (hub) : un en-tete, des lignes de stats deja formatees et une
     * liste de gros boutons. {@code onRefresh} est declenche par le bouton "Rafraichir" (typiquement
     * pour rouvrir le hub avec des donnees a jour). Les actions reutilisent le canal de menu existant.
     */
    /**
     * Ouvre un hub <b>pagine</b> : n'affiche qu'une tranche des entrees et ajoute des boutons
     * "Page precedente / suivante" si necessaire. A utiliser des que la liste peut etre longue
     * (joueurs en ligne, warps, parcelles...), pour ne jamais depasser {@link #MAX_ACTION_SLOTS}.
     *
     * @param reopen rouvre le menu a une page donnee (recoit le joueur et l'index de page)
     */
    public static void openHubPaged(ServerPlayer player, Component title, List<Component> stats,
                                    List<HubEntry> all, int page, int pageSize,
                                    java.util.function.BiConsumer<ServerPlayer, Integer> reopen,
                                    Consumer<ServerPlayer> onBack) {
        int totalPages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        final int cur = Math.max(0, Math.min(page, totalPages - 1));
        int from = cur * pageSize;
        int to = Math.min(all.size(), from + pageSize);

        List<HubEntry> shown = new ArrayList<>(all.subList(from, to));
        Component shownTitle = title;
        if (totalPages > 1) {
            final int pages = totalPages;
            shown.add(new HubEntry(new ItemStack(net.minecraft.world.item.Items.ARROW),
                    Component.literal("< Page precedente")
                            .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.YELLOW).withItalic(false)),
                    Component.literal("Page " + (cur + 1) + "/" + pages)
                            .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GRAY).withItalic(false)),
                    sp -> reopen.accept(sp, (cur - 1 + pages) % pages)));
            shown.add(new HubEntry(new ItemStack(net.minecraft.world.item.Items.ARROW),
                    Component.literal("Page suivante >")
                            .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.YELLOW).withItalic(false)),
                    Component.literal("Page " + (cur + 1) + "/" + pages)
                            .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GRAY).withItalic(false)),
                    sp -> reopen.accept(sp, (cur + 1) % pages)));
            shownTitle = title.copy().append(Component.literal(" (" + (cur + 1) + "/" + pages + ")")
                    .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GRAY).withBold(false)));
        }
        openHub(player, shownTitle, stats, shown, sp -> reopen.accept(sp, cur), onBack);
    }

    public static void openHub(ServerPlayer player, Component title, List<Component> stats,
                               List<HubEntry> entries, Consumer<ServerPlayer> onRefresh,
                               Consumer<ServerPlayer> onBack) {
        int id = COUNTER.incrementAndGet();
        // Garde-fou : les actions sont portees par un UtopiaGui de 54 slots max. Au-dela, les ids
        // seraient ignores en silence -> boutons morts (y compris Rafraichir/Retour). On tronque.
        entries = clampEntries(entries, MAX_ACTION_SLOTS - 2, title);
        int n = entries.size();
        int refreshId = n;                              // slot d'action du bouton "Rafraichir"
        int backId = onBack != null ? n + 1 : -1;       // slot d'action du bouton "Retour" (optionnel)
        int maxSlot = onBack != null ? backId : refreshId;
        int rows = Math.max(1, (maxSlot + 9) / 9);

        UtopiaGui gui = new UtopiaGui(rows, title);
        List<OpenHubPayload.Button> buttons = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            HubEntry e = entries.get(i);
            gui.button(i, e.icon(), e.action());
            buttons.add(new OpenHubPayload.Button(i, e.icon(), e.label(), e.sublabel()));
        }
        gui.button(refreshId, ItemStack.EMPTY, onRefresh != null ? onRefresh : sp -> { });
        if (onBack != null) {
            gui.button(backId, ItemStack.EMPTY, onBack);
        }

        SESSIONS.put(player.getUUID(), new Session(id, gui, null, null));
        PacketDistributor.sendToPlayer(player,
                MenuS2CPayload.of(new OpenHubPayload(id, title, stats, buttons, refreshId, backId)));
    }

    /**
     * Ouvre un ecran "riche" dont les actions sont portees par {@code actionGui} (clic sur un id ->
     * {@code actionGui.action(id)}) et dont l'affichage est decrit par {@code payloadFactory} (recoit
     * l'id de session a inclure dans le paquet). Utilise pour les ecrans owo dedies (daily, etc.).
     */
    public static void openScreen(ServerPlayer player, UtopiaGui actionGui,
                                  java.util.function.IntFunction<MenuS2CPayload> payloadFactory) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, actionGui, null, null));
        PacketDistributor.sendToPlayer(player, payloadFactory.apply(id));
    }

    /**
     * Ouvre un ecran "panneau de reglages" : une liste de lignes (libelle + valeur + bouton optionnel)
     * et un pied de page d'actions, avec retour + rafraichir. Tous les ids restent &lt; 54 (taille max
     * du conteneur d'actions). Reutilise le canal de menu existant.
     */
    public static void openPanel(ServerPlayer player, Component title, List<PanelRow> rows,
                                 List<PanelAction> footer, Consumer<ServerPlayer> onRefresh,
                                 Consumer<ServerPlayer> onBack) {
        openPanel(player, title, rows, footer, false, null, null, onRefresh, onBack);
    }

    /** Variante : {@code inlineFooter} = boutons d'action sur la meme rangee que Retour/Rafraichir/Fermer. */
    public static void openPanel(ServerPlayer player, Component title, List<PanelRow> rows,
                                 List<PanelAction> footer, boolean inlineFooter,
                                 Consumer<ServerPlayer> onRefresh, Consumer<ServerPlayer> onBack) {
        openPanel(player, title, rows, footer, inlineFooter, null, null, onRefresh, onBack);
    }

    /**
     * Variante complete : {@code onPrev}/{@code onNext} (optionnels) ajoutent des fleches de navigation
     * de part et d'autre du titre dans l'en-tete (ex: &lt; Prec. | Parcelle 01 (1/2) | Suiv. &gt;).
     */
    public static void openPanel(ServerPlayer player, Component title, List<PanelRow> rows,
                                 List<PanelAction> footer, boolean inlineFooter,
                                 Consumer<ServerPlayer> onPrev, Consumer<ServerPlayer> onNext,
                                 Consumer<ServerPlayer> onRefresh, Consumer<ServerPlayer> onBack) {
        int id = COUNTER.incrementAndGet();
        UtopiaGui gui = new UtopiaGui(6, title); // 54 slots d'actions

        // Garde-fou : lignes + pied de page + Rafraichir/Retour/Prec./Suiv. doivent tenir dans 54 slots.
        int reserved = footer.size() + (onRefresh != null ? 1 : 0) + (onBack != null ? 1 : 0)
                + (onPrev != null ? 1 : 0) + (onNext != null ? 1 : 0);
        rows = clampEntries(rows, Math.max(1, MAX_ACTION_SLOTS - reserved), title);

        List<OpenPanelPayload.Row> netRows = new ArrayList<>(rows.size());
        int idCounter = 0;
        for (PanelRow r : rows) {
            int buttonId = -1;
            if (r.action() != null) {
                buttonId = idCounter++;
                gui.button(buttonId, ItemStack.EMPTY, r.action());
            }
            netRows.add(new OpenPanelPayload.Row(r.label(), r.value(), buttonId,
                    r.buttonLabel() == null ? Component.empty() : r.buttonLabel()));
        }
        List<OpenPanelPayload.Action> netFooter = new ArrayList<>(footer.size());
        for (PanelAction a : footer) {
            int aid = idCounter++;
            gui.button(aid, ItemStack.EMPTY, a.action());
            netFooter.add(new OpenPanelPayload.Action(aid, a.label()));
        }
        int refreshId = -1;
        if (onRefresh != null) {
            refreshId = idCounter++;
            gui.button(refreshId, ItemStack.EMPTY, onRefresh);
        }
        int backId = -1;
        if (onBack != null) {
            backId = idCounter++;
            gui.button(backId, ItemStack.EMPTY, onBack);
        }
        int prevId = -1;
        if (onPrev != null) {
            prevId = idCounter++;
            gui.button(prevId, ItemStack.EMPTY, onPrev);
        }
        int navNextId = -1;
        if (onNext != null) {
            navNextId = idCounter++;
            gui.button(navNextId, ItemStack.EMPTY, onNext);
        }

        SESSIONS.put(player.getUUID(), new Session(id, gui, null, null));
        PacketDistributor.sendToPlayer(player, MenuS2CPayload.of(new OpenPanelPayload(
                id, title, netRows, netFooter, refreshId, backId, inlineFooter, prevId, navNextId)));
    }

    /** Ouvre un ecran de saisie de montant ; {@code onConfirm} recoit la valeur (deja bornee). */
    public static void openAmount(ServerPlayer player, Component title, List<Component> info, Component confirmLabel,
                                  long defaultValue, long min, long max, LongConsumer onConfirm) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, null, new AmountPrompt(min, max, onConfirm), null));
        PacketDistributor.sendToPlayer(player,
                MenuS2CPayload.of(new OpenAmountPayload(id, title, info, confirmLabel, defaultValue, min, max)));
    }

    /** Ouvre un ecran de saisie de texte ; {@code onConfirm} recoit la chaine saisie. */
    public static void openText(ServerPlayer player, Component title, List<Component> info, Component confirmLabel,
                                String defaultText, int maxLength, Consumer<String> onConfirm) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, null, null, new TextPrompt(onConfirm)));
        PacketDistributor.sendToPlayer(player,
                MenuS2CPayload.of(new OpenTextPayload(id, title, info, confirmLabel, defaultText, maxLength)));
    }

    /** Ferme l'ecran owo cote client + declenche le rappel de fermeture eventuel. */
    public static void close(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s != null) {
            if (s.gui() != null) {
                s.gui().fireClose(player);
            }
            PacketDistributor.sendToPlayer(player, MenuS2CPayload.of(new CloseMenuPayload(s.id())));
        }
    }

    /** Nettoyage a la deconnexion (sans paquet). */
    public static void clear(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s != null && s.gui() != null) {
            s.gui().fireClose(player);
        }
    }

    /** Traite un clic (ou une fermeture si slot &lt; 0) recu du client. */
    public static void handleClick(MenuClickPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            Session s = SESSIONS.get(sp.getUUID());
            if (s == null || s.id() != payload.sessionId()) {
                return; // session obsolete : on ignore
            }
            if (payload.slot() < 0) { // fermeture demandee par le client (Echap / Annuler)
                SESSIONS.remove(sp.getUUID());
                if (s.gui() != null) {
                    s.gui().fireClose(sp);
                }
                return;
            }
            if (s.gui() == null) {
                return; // session de saisie : pas de clic d'icone
            }
            Consumer<ServerPlayer> action = payload.button() == 1
                    ? s.gui().rightAction(payload.slot())
                    : s.gui().action(payload.slot());
            if (action == null && payload.button() == 1) {
                action = s.gui().action(payload.slot()); // repli clic droit -> action principale
            }
            if (action != null) {
                action.accept(sp);
            }
        });
    }

    /** Traite un montant saisi recu du client. */
    public static void handleAmount(AmountResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            Session s = SESSIONS.get(sp.getUUID());
            if (s == null || s.id() != payload.sessionId() || s.amount() == null) {
                return;
            }
            AmountPrompt prompt = s.amount();
            long value = Math.max(prompt.min(), Math.min(prompt.max(), payload.value()));
            SESSIONS.remove(sp.getUUID()); // consomme avant l'action (qui peut ouvrir un autre menu)
            prompt.onConfirm().accept(value);
        });
    }

    /** Traite un texte saisi recu du client. */
    public static void handleText(TextResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            Session s = SESSIONS.get(sp.getUUID());
            if (s == null || s.id() != payload.sessionId() || s.text() == null) {
                return;
            }
            TextPrompt prompt = s.text();
            SESSIONS.remove(sp.getUUID());
            prompt.onConfirm().accept(payload.value());
        });
    }
}
