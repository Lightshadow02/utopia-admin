package com.utopia.net;

import java.util.ArrayList;
import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C : ouvre un ecran "panneau de reglages" : une liste de lignes (libelle + valeur actuelle + un
 * bouton optionnel a droite), une rangee de boutons d'action en pied de page, et un bouton retour.
 * Transporte via {@link MenuS2CPayload} (variante {@code OPEN_PANEL}).
 *
 * <p>Les clics (boutons de ligne, actions, retour) repassent par {@code menu_c2s} avec l'id porte.
 */
public record OpenPanelPayload(int sessionId, Component title, List<Row> rows, List<Action> footer,
                               int refreshId, int backId) implements CustomPacketPayload {

    /** Une ligne : libelle + valeur, et un bouton optionnel ({@code buttonId < 0} = ligne info seule). */
    public record Row(Component label, Component value, int buttonId, Component buttonLabel) {
    }

    /** Un bouton d'action du pied de page. */
    public record Action(int id, Component label) {
    }

    public static final Type<OpenPanelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPanelPayload> STREAM_CODEC =
            StreamCodec.of(OpenPanelPayload::encode, OpenPanelPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenPanelPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        buf.writeVarInt(p.rows.size());
        for (Row r : p.rows) {
            ComponentSerialization.STREAM_CODEC.encode(buf, r.label());
            ComponentSerialization.STREAM_CODEC.encode(buf, r.value());
            buf.writeVarInt(r.buttonId());
            ComponentSerialization.STREAM_CODEC.encode(buf, r.buttonLabel());
        }
        buf.writeVarInt(p.footer.size());
        for (Action a : p.footer) {
            buf.writeVarInt(a.id());
            ComponentSerialization.STREAM_CODEC.encode(buf, a.label());
        }
        buf.writeVarInt(p.refreshId);
        buf.writeVarInt(p.backId);
    }

    private static OpenPanelPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        int rn = buf.readVarInt();
        List<Row> rows = new ArrayList<>(rn);
        for (int i = 0; i < rn; i++) {
            Component label = ComponentSerialization.STREAM_CODEC.decode(buf);
            Component value = ComponentSerialization.STREAM_CODEC.decode(buf);
            int buttonId = buf.readVarInt();
            Component buttonLabel = ComponentSerialization.STREAM_CODEC.decode(buf);
            rows.add(new Row(label, value, buttonId, buttonLabel));
        }
        int fn = buf.readVarInt();
        List<Action> footer = new ArrayList<>(fn);
        for (int i = 0; i < fn; i++) {
            int id = buf.readVarInt();
            Component label = ComponentSerialization.STREAM_CODEC.decode(buf);
            footer.add(new Action(id, label));
        }
        int refreshId = buf.readVarInt();
        int backId = buf.readVarInt();
        return new OpenPanelPayload(sessionId, title, rows, footer, refreshId, backId);
    }

    @Override
    public Type<OpenPanelPayload> type() {
        return TYPE;
    }
}
