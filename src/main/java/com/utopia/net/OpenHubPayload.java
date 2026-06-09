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
 * S2C : ouvre l'ecran d'accueil "riche" (hub) cote client. Contient le titre, des lignes de stats
 * pre-formatees (solde, parcelles, rang...) et une liste de gros boutons (icone + libelle + sous-libelle).
 * Chaque bouton porte un {@code id} : un clic renvoie un {@link MenuClickPayload} avec ce {@code id}.
 *
 * <p>Transporte via {@link MenuS2CPayload} (variante {@code OPEN_HUB}) ; pas de canal reseau dedie.
 */
public record OpenHubPayload(int sessionId, Component title, List<Component> stats,
                             List<Button> buttons, int refreshId, int backId) implements CustomPacketPayload {

    /** Un bouton du hub : {@code id} (renvoye au clic), icone, libelle et sous-libelle. */
    public record Button(int id, ItemStack icon, Component label, Component sublabel) {
    }

    public static final Type<OpenHubPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_hub"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> COMPONENTS =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenHubPayload> STREAM_CODEC =
            StreamCodec.of(OpenHubPayload::encode, OpenHubPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenHubPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        COMPONENTS.encode(buf, p.stats);
        buf.writeVarInt(p.buttons.size());
        for (Button b : p.buttons) {
            buf.writeVarInt(b.id());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, b.icon());
            ComponentSerialization.STREAM_CODEC.encode(buf, b.label());
            ComponentSerialization.STREAM_CODEC.encode(buf, b.sublabel());
        }
        buf.writeVarInt(p.refreshId);
        buf.writeVarInt(p.backId);
    }

    private static OpenHubPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        List<Component> stats = COMPONENTS.decode(buf);
        int n = buf.readVarInt();
        List<Button> buttons = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = buf.readVarInt();
            ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            Component label = ComponentSerialization.STREAM_CODEC.decode(buf);
            Component sublabel = ComponentSerialization.STREAM_CODEC.decode(buf);
            buttons.add(new Button(id, icon, label, sublabel));
        }
        int refreshId = buf.readVarInt();
        int backId = buf.readVarInt();
        return new OpenHubPayload(sessionId, title, stats, buttons, refreshId, backId);
    }

    @Override
    public Type<OpenHubPayload> type() {
        return TYPE;
    }
}
