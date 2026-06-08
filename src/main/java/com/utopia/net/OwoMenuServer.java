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

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();

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
                new OpenMenuPayload(id, gui.title(), gui.rows(), items, clickable, gui.gridLayout()));
    }

    /** Ouvre un ecran de saisie de montant ; {@code onConfirm} recoit la valeur (deja bornee). */
    public static void openAmount(ServerPlayer player, Component title, List<Component> info, Component confirmLabel,
                                  long defaultValue, long min, long max, LongConsumer onConfirm) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, null, new AmountPrompt(min, max, onConfirm), null));
        PacketDistributor.sendToPlayer(player,
                new OpenAmountPayload(id, title, info, confirmLabel, defaultValue, min, max));
    }

    /** Ouvre un ecran de saisie de texte ; {@code onConfirm} recoit la chaine saisie. */
    public static void openText(ServerPlayer player, Component title, List<Component> info, Component confirmLabel,
                                String defaultText, int maxLength, Consumer<String> onConfirm) {
        int id = COUNTER.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(id, null, null, new TextPrompt(onConfirm)));
        PacketDistributor.sendToPlayer(player,
                new OpenTextPayload(id, title, info, confirmLabel, defaultText, maxLength));
    }

    /** Ferme l'ecran owo cote client + declenche le rappel de fermeture eventuel. */
    public static void close(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s != null) {
            if (s.gui() != null) {
                s.gui().fireClose(player);
            }
            PacketDistributor.sendToPlayer(player, new CloseMenuPayload(s.id()));
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
