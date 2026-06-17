package com.utopia.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.utopia.data.MarketData;
import com.utopia.economy.EconomyManager;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Hologrammes au-dessus des stands de marche : pour chaque offre, un objet flottant qui tourne
 * (entite item sans gravite) surmonte de son texte (quantite + prix + temps restant), plus un en-tete
 * avec le proprietaire. Empile a la verticale (proposition "1 objet par offre") mais borne a ~3 blocs
 * de hauteur : l'espacement se resserre quand il y a beaucoup d'offres. Synchronise toutes les ~2 s.
 */
public final class MarketHolograms {

    private static final String HOLO_STALL = "utopiaMarketStall";
    private static final String HOLO_KIND = "utopiaMarketKind"; // "text" ou "item"
    private static final String HOLO_LINE = "utopiaMarketLine";
    private static final String HOLO_SIG = "utopiaMarketSig";
    private static final double MAX_HEIGHT = 3.0;
    private static final double MIN_UNIT = 0.45;
    private static final double MAX_UNIT = 0.85;

    private MarketHolograms() {
    }

    public static void sync(MinecraftServer server) {
        MarketData data = MarketData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dim = level.dimension().location();
            String dimStr = dim.toString();

            Map<String, List<Entity>> existing = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                String key = e.getPersistentData().getString(HOLO_STALL);
                if (!key.isEmpty() && (e instanceof ArmorStand || e instanceof ItemEntity)) {
                    existing.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
                }
            }

            Set<String> wanted = new HashSet<>();
            for (MarketData.Stall stall : data.stalls()) {
                if (!stall.dim.equals(dimStr)) {
                    continue;
                }
                BlockPos pos = stall.pos();
                if (!level.isLoaded(pos)) {
                    continue;
                }
                String key = stall.dim + ";" + pos.getX() + ";" + pos.getY() + ";" + pos.getZ();
                wanted.add(key);
                String sig = signature(stall);
                Layout layout = layout(stall);
                List<Entity> ents = existing.get(key);

                if (needsRebuild(ents, sig, layout)) {
                    if (ents != null) {
                        ents.forEach(Entity::discard);
                    }
                    spawn(level, key, sig, pos, layout);
                } else {
                    refresh(ents, pos, layout);
                }
            }

            for (Map.Entry<String, List<Entity>> entry : existing.entrySet()) {
                if (!wanted.contains(entry.getKey())) {
                    entry.getValue().forEach(Entity::discard);
                }
            }
        }
    }

    /** Reconstruction si rien n'existe, si la structure (offres/objets) a change, ou si un objet a disparu. */
    private static boolean needsRebuild(List<Entity> ents, String sig, Layout layout) {
        if (ents == null || ents.isEmpty()) {
            return true;
        }
        if (!sig.equals(ents.get(0).getPersistentData().getString(HOLO_SIG))) {
            return true;
        }
        long items = ents.stream().filter(e -> e instanceof ItemEntity).count();
        return items != layout.items.size();
    }

    private static void refresh(List<Entity> ents, BlockPos pos, Layout layout) {
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        Map<Integer, TextLine> byIdx = new HashMap<>();
        for (TextLine t : layout.texts) {
            byIdx.put(t.idx, t);
        }
        for (Entity e : ents) {
            if (e instanceof ArmorStand && "text".equals(e.getPersistentData().getString(HOLO_KIND))) {
                TextLine t = byIdx.get(e.getPersistentData().getInt(HOLO_LINE));
                if (t != null) {
                    e.setCustomName(t.text);
                    e.setCustomNameVisible(true);
                    e.teleportTo(cx, t.y, cz);
                }
            }
            // Les objets flottants gardent leur position (animation de rotation/flottement cote client).
        }
    }

    private static void spawn(ServerLevel level, String key, String sig, BlockPos pos, Layout layout) {
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (TextLine t : layout.texts) {
            ArmorStand stand = new ArmorStand(level, cx, t.y, cz);
            CompoundTag tag = stand.saveWithoutId(new CompoundTag());
            tag.putBoolean("Marker", true);
            stand.load(tag);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setNoBasePlate(true);
            stand.setCustomName(t.text);
            stand.setCustomNameVisible(true);
            stand.getPersistentData().putString(HOLO_STALL, key);
            stand.getPersistentData().putString(HOLO_KIND, "text");
            stand.getPersistentData().putInt(HOLO_LINE, t.idx);
            stand.getPersistentData().putString(HOLO_SIG, sig);
            stand.setPos(cx, t.y, cz);
            level.addFreshEntity(stand);
        }
        for (ItemSpot spot : layout.items) {
            ItemEntity ie = new ItemEntity(level, cx, spot.y, cz, spot.stack);
            ie.setNoGravity(true);
            ie.setDeltaMovement(Vec3.ZERO);
            ie.setNeverPickUp();
            ie.setUnlimitedLifetime();
            ie.setSilent(true);
            ie.getPersistentData().putString(HOLO_STALL, key);
            ie.getPersistentData().putString(HOLO_KIND, "item");
            ie.getPersistentData().putInt(HOLO_LINE, spot.idx);
            ie.getPersistentData().putString(HOLO_SIG, sig);
            ie.setPos(cx, spot.y, cz);
            level.addFreshEntity(ie);
        }
    }

    /** Signature structurelle : libre/occupe + nb d'offres + (objet x quantite) de chaque offre. */
    private static String signature(MarketData.Stall stall) {
        StringBuilder sb = new StringBuilder(stall.owner == null ? "FREE" : "OWN");
        sb.append('|').append(stall.offers.size());
        for (MarketData.Offer o : stall.offers) {
            sb.append('|').append(BuiltInRegistries.ITEM.getKey(o.stack.getItem()))
                    .append('x').append(o.stack.getCount());
        }
        return sb.toString();
    }

    /** Calcule la disposition (objets + lignes de texte) bornee a {@link #MAX_HEIGHT} blocs. */
    private static Layout layout(MarketData.Stall stall) {
        Layout l = new Layout();
        double baseY = stall.pos().getY() + 1.0;

        if (stall.owner == null) {
            l.texts.add(new TextLine(0, baseY + 0.55,
                    Component.literal("Stand libre").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true))));
            l.texts.add(new TextLine(1, baseY + 0.25,
                    Component.literal("Clic droit pour vendre").withStyle(s -> s.withColor(ChatFormatting.GRAY))));
            return l;
        }

        List<MarketData.Offer> offers = stall.offers;
        int n = offers.size();
        if (n == 0) {
            l.texts.add(new TextLine(0, baseY + 0.30,
                    Component.literal("(aucune offre)").withStyle(s -> s.withColor(ChatFormatting.GRAY))));
            l.texts.add(new TextLine(1, baseY + 0.60, ownerHeader(stall)));
            return l;
        }

        double unit = Math.max(MIN_UNIT, Math.min(MAX_UNIT, MAX_HEIGHT / n));
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            MarketData.Offer o = offers.get(i);
            double gy = baseY + i * unit;
            l.items.add(new ItemSpot(i, gy + 0.12, o.stack.copyWithCount(1)));
            String time = Messages.formatDuration(Math.max(0, o.expiryMillis - now) / 1000);
            Component text = Component.literal(o.stack.getCount() + "x ").withStyle(ChatFormatting.WHITE)
                    .append(o.stack.getHoverName().copy().withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)))
                    .append(Component.literal("  -  " + EconomyManager.format(o.price))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)))
                    .append(Component.literal("  -  " + time)
                            .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
            l.texts.add(new TextLine(i, gy + unit * 0.55, text));
        }
        l.texts.add(new TextLine(n, baseY + n * unit + 0.15, ownerHeader(stall)));
        return l;
    }

    private static Component ownerHeader(MarketData.Stall stall) {
        return Component.literal("Stand de " + stall.ownerName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
    }

    private static final class Layout {
        final List<TextLine> texts = new ArrayList<>();
        final List<ItemSpot> items = new ArrayList<>();
    }

    private static final class TextLine {
        final int idx;
        final double y;
        final Component text;

        TextLine(int idx, double y, Component text) {
            this.idx = idx;
            this.y = y;
            this.text = text;
        }
    }

    private static final class ItemSpot {
        final int idx;
        final double y;
        final ItemStack stack;

        ItemSpot(int idx, double y, ItemStack stack) {
            this.idx = idx;
            this.y = y;
            this.stack = stack;
        }
    }
}
