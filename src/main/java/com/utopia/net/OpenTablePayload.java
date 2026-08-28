package com.utopia.net;

import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C : ouvre un ecran <b>tableau</b> cote client. Chaque ligne porte autant de cellules qu'il y a
 * de colonnes, et la ligne entiere est cliquable — il n'y a pas de bouton par ligne.
 *
 * <p>Les largeurs et les alignements sont decides par le serveur : c'est lui qui connait la nature
 * de chaque colonne (un prix se lit cale a droite, un nom cale a gauche).
 */
public record OpenTablePayload(int sessionId, Component title, List<Component> stats,
                               List<Control> controls, List<Component> headers,
                               List<Integer> widths, List<Integer> aligns,
                               List<Row> rows, List<Action> footer,
                               int refreshId, int backId, int prevId, int nextId)
        implements CustomPacketPayload {

    /** Une ligne : ses cellules, et l'action declenchee par un clic n'importe ou dessus (-1 = inerte). */
    public record Row(List<Component> cells, int actionId) {
    }

    /**
     * Une ligne de commande placee au-dessus du tableau : libelle, etat courant, et un bouton. C'est
     * la que vivent les criteres de recherche, hors de la grille de donnees.
     */
    public record Control(Component label, Component value, Component buttonLabel, int actionId) {
    }

    /** Un bouton de pied de page. */
    public record Action(int id, Component label) {
    }

    public static final Type<OpenTablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_table"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> LINES =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTablePayload> STREAM_CODEC =
            StreamCodec.of(OpenTablePayload::encode, OpenTablePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenTablePayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        LINES.encode(buf, p.stats);
        buf.writeVarInt(p.controls.size());
        for (Control c : p.controls) {
            ComponentSerialization.STREAM_CODEC.encode(buf, c.label());
            ComponentSerialization.STREAM_CODEC.encode(buf, c.value());
            ComponentSerialization.STREAM_CODEC.encode(buf, c.buttonLabel());
            buf.writeVarInt(c.actionId());
        }
        LINES.encode(buf, p.headers);
        buf.writeVarInt(p.widths.size());
        for (int w : p.widths) {
            buf.writeVarInt(w);
        }
        buf.writeVarInt(p.aligns.size());
        for (int a : p.aligns) {
            buf.writeVarInt(a);
        }
        buf.writeVarInt(p.rows.size());
        for (Row row : p.rows) {
            LINES.encode(buf, row.cells());
            buf.writeVarInt(row.actionId());
        }
        buf.writeVarInt(p.footer.size());
        for (Action a : p.footer) {
            buf.writeVarInt(a.id());
            ComponentSerialization.STREAM_CODEC.encode(buf, a.label());
        }
        buf.writeVarInt(p.refreshId);
        buf.writeVarInt(p.backId);
        buf.writeVarInt(p.prevId);
        buf.writeVarInt(p.nextId);
    }

    private static OpenTablePayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        List<Component> stats = LINES.decode(buf);
        int cn = buf.readVarInt();
        List<Control> controls = new java.util.ArrayList<>(cn);
        for (int i = 0; i < cn; i++) {
            controls.add(new Control(ComponentSerialization.STREAM_CODEC.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt()));
        }
        List<Component> headers = LINES.decode(buf);
        int wn = buf.readVarInt();
        List<Integer> widths = new java.util.ArrayList<>(wn);
        for (int i = 0; i < wn; i++) {
            widths.add(buf.readVarInt());
        }
        int an = buf.readVarInt();
        List<Integer> aligns = new java.util.ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            aligns.add(buf.readVarInt());
        }
        int rn = buf.readVarInt();
        List<Row> rows = new java.util.ArrayList<>(rn);
        for (int i = 0; i < rn; i++) {
            rows.add(new Row(LINES.decode(buf), buf.readVarInt()));
        }
        int fn = buf.readVarInt();
        List<Action> footer = new java.util.ArrayList<>(fn);
        for (int i = 0; i < fn; i++) {
            footer.add(new Action(buf.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buf)));
        }
        return new OpenTablePayload(sessionId, title, stats, controls, headers, widths, aligns, rows, footer,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<OpenTablePayload> type() {
        return TYPE;
    }
}
