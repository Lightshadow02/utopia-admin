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
import net.minecraft.world.item.ItemStack;

/**
 * S2C : ouvre un menu owo cote client. Contient le titre, le nombre de rangees, toutes les icones
 * (slot par slot) et la liste des slots cliquables (pour le retour visuel).
 */
public record OpenMenuPayload(int sessionId, Component title, int rows, List<ItemStack> items,
                              List<Integer> clickable, boolean grid) implements CustomPacketPayload {

    public static final Type<OpenMenuPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(UtopiaMod.MODID, "open_menu"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> ITEMS =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<io.netty.buffer.ByteBuf, List<Integer>> CLICKABLE =
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenuPayload> STREAM_CODEC =
            StreamCodec.of(OpenMenuPayload::encode, OpenMenuPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenMenuPayload p) {
        buf.writeVarInt(p.sessionId);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
        buf.writeVarInt(p.rows);
        ITEMS.encode(buf, p.items);
        CLICKABLE.encode(buf, p.clickable);
        buf.writeBoolean(p.grid);
    }

    private static OpenMenuPayload decode(RegistryFriendlyByteBuf buf) {
        int sessionId = buf.readVarInt();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        int rows = buf.readVarInt();
        List<ItemStack> items = ITEMS.decode(buf);
        List<Integer> clickable = CLICKABLE.decode(buf);
        boolean grid = buf.readBoolean();
        return new OpenMenuPayload(sessionId, title, rows, items, clickable, grid);
    }

    @Override
    public Type<OpenMenuPayload> type() {
        return TYPE;
    }
}
