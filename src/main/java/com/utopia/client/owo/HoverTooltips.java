package com.utopia.client.owo;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

/**
 * Gere des bulles d'info qui n'apparaissent qu'apres un court survol (delai), au lieu d'etre
 * affichees en ligne. Un ecran owo enregistre ses composants via {@link #register}, vide la liste
 * dans {@code build()} via {@link #clear()}, et appelle {@link #update()} a chaque frame (render).
 */
public final class HoverTooltips {

    /** Delai de survol (ms) avant l'apparition de la bulle. */
    private static final long DELAY_MS = 2000;

    private record Entry(io.wispforest.owo.ui.core.Component comp, List<Component> text, long[] start) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public void clear() {
        entries.clear();
    }

    public void register(io.wispforest.owo.ui.core.Component comp, Component text) {
        if (text != null && !text.getString().isEmpty()) {
            register(comp, List.of(text));
        }
    }

    /** Associe une bulle d'info (plusieurs lignes) a un composant, affichee apres {@link #DELAY_MS} de survol. */
    public void register(io.wispforest.owo.ui.core.Component comp, List<Component> text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final long[] start = {-1};
        comp.mouseEnter().subscribe(() -> start[0] = System.currentTimeMillis());
        comp.mouseLeave().subscribe(() -> {
            start[0] = -1;
            comp.tooltip(List.<Component>of()); // masque la bulle
        });
        entries.add(new Entry(comp, text, start));
    }

    /** A appeler a chaque frame : affiche la bulle des composants survoles depuis assez longtemps. */
    public void update() {
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            if (e.start()[0] >= 0 && now - e.start()[0] >= DELAY_MS) {
                e.comp().tooltip(e.text());
            }
        }
    }
}
