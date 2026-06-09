package com.utopia.net;

import java.util.ArrayList;
import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * S2C : ouvre le calendrier de recompenses quotidiennes "riche" cote client. Contient le titre, la
 * ligne de serie (streak), la disposition du mois et une case par jour (numero + etat + icone de
 * recompense). Transporte via {@link MenuS2CPayload} (variante {@code OPEN_DAILY}).
 *
 * <p>Les actions (reclamer, mois precedent/suivant, retour) reutilisent le canal {@code menu_c2s} :
 * un clic renvoie un {@link MenuClickPayload} avec l'id correspondant.
 */
public record OpenDailyPayload(int sessionId, Component title, Component streak,
                               int firstWeekday, int daysInMonth, List<Day> days,
                               ItemStack nextIcon, List<Component> nextLore,
                               int prevId, int nextId, int claimId, int backId,
                               boolean canClaim) implements CustomPacketPayload {

    /** Etats possibles d'une case de jour. */
    public static final int OTHER = 0;       // passe lointain / hors-jeu (gris fonce)
    public static final int CLAIMED = 1;     // recupere (vert)
    public static final int CLAIMABLE = 2;   // aujourd'hui, a recuperer (accent)
    public static final int TODAY_DONE = 3;  // aujourd'hui, deja recupere (vert accentue)
    public static final int MISSED = 4;      // manque (rouge)
    public static final int FUTURE = 5;      // a venir (violet)

    /** Une case de jour : numero, etat, et icone de recompense (peut etre vide). */
    public record Day(int day, int state, ItemStack reward) {
    }

    public static final Type<OpenDailyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_daily"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> COMPONENTS =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDailyPayload> STREAM_CODEC =
            StreamCodec.of(OpenDailyPayload::encode, OpenDailyPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenDailyPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.streak);
        buf.writeVarInt(p.firstWeekday);
        buf.writeVarInt(p.daysInMonth);
        buf.writeVarInt(p.days.size());
        for (Day d : p.days) {
            buf.writeVarInt(d.day());
            buf.writeByte(d.state());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, d.reward());
        }
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.nextIcon);
        COMPONENTS.encode(buf, p.nextLore);
        buf.writeVarInt(p.prevId);
        buf.writeVarInt(p.nextId);
        buf.writeVarInt(p.claimId);
        buf.writeVarInt(p.backId);
        buf.writeBoolean(p.canClaim);
    }

    private static OpenDailyPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        Component streak = ComponentSerialization.STREAM_CODEC.decode(buf);
        int firstWeekday = buf.readVarInt();
        int daysInMonth = buf.readVarInt();
        int n = buf.readVarInt();
        List<Day> days = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int day = buf.readVarInt();
            int state = buf.readByte();
            ItemStack reward = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            days.add(new Day(day, state, reward));
        }
        ItemStack nextIcon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        List<Component> nextLore = COMPONENTS.decode(buf);
        int prevId = buf.readVarInt();
        int nextId = buf.readVarInt();
        int claimId = buf.readVarInt();
        int backId = buf.readVarInt();
        boolean canClaim = buf.readBoolean();
        return new OpenDailyPayload(sessionId, title, streak, firstWeekday, daysInMonth, days,
                nextIcon, nextLore, prevId, nextId, claimId, backId, canClaim);
    }

    @Override
    public Type<OpenDailyPayload> type() {
        return TYPE;
    }
}
