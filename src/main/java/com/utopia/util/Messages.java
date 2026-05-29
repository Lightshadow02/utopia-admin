package com.utopia.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Petits utilitaires pour construire des messages de chat coherents (prefixe + couleurs). */
public final class Messages {
    /** Prefixe affiche devant chaque message du mod. */
    public static final Component PREFIX = Component.literal("[Utopia] ").withStyle(ChatFormatting.AQUA);

    private Messages() {
    }

    public static MutableComponent info(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    public static MutableComponent success(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.GREEN));
    }

    public static MutableComponent error(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    public static MutableComponent warn(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    /** Met en forme une duree en secondes de maniere lisible (ex: "1h 05m", "45s"). */
    public static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("h ");
            sb.append(String.format("%02dm", minutes));
        } else if (minutes > 0) {
            sb.append(minutes).append("m ");
            sb.append(String.format("%02ds", seconds));
        } else {
            sb.append(seconds).append("s");
        }
        return sb.toString().trim();
    }
}
