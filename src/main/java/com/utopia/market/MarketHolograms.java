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
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Hologrammes du marche : chaque offre est materialisee par un petit objet flottant (ItemDisplay, face
 * au joueur) surmonte de son texte (quantite + nom + prix + temps restant), pose sur un EMPLACEMENT
 * defini par un op (un bloc casse = un emplacement). Sans emplacement defini, un emplacement par defaut
 * est utilise au-dessus du bloc du stand. Quand il y a plus d'offres que d'emplacements, l'affichage
 * defile (page suivante toutes les ~5 s) pour toutes les montrer. L'en-tete (proprietaire) reste
 * au-dessus du bloc du stand. Synchronise toutes les ~2 s.
 */
public final class MarketHolograms {

    private static final String HOLO_STALL = "utopiaMarketStall";
    private static final String HOLO_KIND = "utopiaMarketKind"; // "text" ou "item"
    private static final String HOLO_LINE = "utopiaMarketLine";
    private static final String HOLO_SIG = "utopiaMarketSig";

    private static final float ITEM_SCALE = 0.5f;     // objet reduit
    private static final int PAGE_TICKS = 100;          // ~5 s par page d'offres

    private MarketHolograms() {
    }

    public static void sync(MinecraftServer server) {
        int tick = server.getTickCount();
        MarketData data = MarketData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            String dimStr = level.dimension().location().toString();

            Map<String, List<Entity>> existing = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                String k = e.getPersistentData().getString(HOLO_STALL);
                if (!k.isEmpty() && (e instanceof ArmorStand || e instanceof Display.ItemDisplay)) {
                    existing.computeIfAbsent(k, key -> new ArrayList<>()).add(e);
                }
            }

            Set<String> wanted = new HashSet<>();
            for (MarketData.Stall stall : data.stalls()) {
                if (!stall.dim.equals(dimStr) || !level.isLoaded(stall.pos())) {
                    continue;
                }
                String key = stallKeyOf(stall);
                wanted.add(key);
                Layout layout = layout(stall, tick);
                List<Entity> ents = existing.get(key);
                if (needsRebuild(ents, layout)) {
                    if (ents != null) {
                        ents.forEach(Entity::discard);
                    }
                    spawn(level, key, layout);
                } else {
                    refresh(ents, layout);
                }
            }

            for (Map.Entry<String, List<Entity>> entry : existing.entrySet()) {
                if (!wanted.contains(entry.getKey())) {
                    entry.getValue().forEach(Entity::discard);
                }
            }
        }
    }

    private static boolean needsRebuild(List<Entity> ents, Layout layout) {
        if (ents == null || ents.isEmpty()) {
            return true;
        }
        if (!layout.sig.equals(ents.get(0).getPersistentData().getString(HOLO_SIG))) {
            return true;
        }
        long items = ents.stream().filter(e -> e instanceof Display.ItemDisplay).count();
        return items != layout.items.size();
    }

    private static void refresh(List<Entity> ents, Layout layout) {
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
                    e.teleportTo(t.x, t.y, t.z);
                }
            }
        }
    }

    private static void spawn(ServerLevel level, String key, Layout layout) {
        for (TextLine t : layout.texts) {
            ArmorStand stand = new ArmorStand(level, t.x, t.y, t.z);
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
            stand.getPersistentData().putString(HOLO_SIG, layout.sig);
            stand.setPos(t.x, t.y, t.z);
            level.addFreshEntity(stand);
        }
        for (ItemSpot spot : layout.items) {
            Display.ItemDisplay disp = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
            disp.setPos(spot.x, spot.y, spot.z);
            CompoundTag tag = disp.saveWithoutId(new CompoundTag());
            tag.put("item", spot.stack.save(level.registryAccess()));
            tag.putString("item_display", "fixed");
            tag.putString("billboard", "center"); // l'objet fait toujours face au joueur
            tag.put("transformation", scaleTransform(ITEM_SCALE));
            disp.load(tag);
            disp.getPersistentData().putString(HOLO_STALL, key);
            disp.getPersistentData().putString(HOLO_KIND, "item");
            disp.getPersistentData().putInt(HOLO_LINE, spot.idx);
            disp.getPersistentData().putString(HOLO_SIG, layout.sig);
            disp.setPos(spot.x, spot.y, spot.z);
            level.addFreshEntity(disp);
        }
    }

    /** Tag de transformation Display : mise a l'echelle uniforme (rotation identite). */
    private static CompoundTag scaleTransform(float scale) {
        CompoundTag tr = new CompoundTag();
        tr.put("translation", floats(0f, 0f, 0f));
        tr.put("left_rotation", floats(0f, 0f, 0f, 1f));
        tr.put("scale", floats(scale, scale, scale));
        tr.put("right_rotation", floats(0f, 0f, 0f, 1f));
        return tr;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    /** Calcule la disposition (objets + textes + signature) selon les emplacements et la page courante. */
    private static Layout layout(MarketData.Stall stall, int tick) {
        Layout l = new Layout();
        StringBuilder sig = new StringBuilder();
        double sbx = stall.x + 0.5;
        double sbz = stall.z + 0.5;
        boolean hasSpots = !stall.displaySpots.isEmpty();

        if (stall.owner == null) {
            sig.append("FREE");
            l.texts.add(new TextLine(0, sbx, stall.y + 1.55, sbz,
                    Component.literal("Stand libre").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true))));
            l.texts.add(new TextLine(1, sbx, stall.y + 1.25, sbz,
                    Component.literal("Clic droit pour vendre").withStyle(s -> s.withColor(ChatFormatting.GRAY))));
            l.sig = sig.toString();
            return l;
        }

        sig.append("OWN|").append(stall.ownerName);
        double headerY = stall.y + (hasSpots ? 1.5 : 1.95);
        l.texts.add(new TextLine(0, sbx, headerY, sbz, ownerHeader(stall)));

        int n = stall.offers.size();
        if (n == 0) {
            sig.append("|empty");
            l.texts.add(new TextLine(1, sbx, stall.y + 1.2, sbz,
                    Component.literal("(aucune offre)").withStyle(s -> s.withColor(ChatFormatting.GRAY))));
            l.sig = sig.toString();
            return l;
        }

        List<BlockPos> spots = hasSpots ? stall.displaySpots : List.of(stall.pos());
        int slots = spots.size();
        int pageCount = (n + slots - 1) / slots;
        int page = pageCount <= 1 ? 0 : (tick / PAGE_TICKS) % pageCount;
        sig.append("|p").append(page).append('/').append(pageCount);

        long now = System.currentTimeMillis();
        for (int j = 0; j < slots; j++) {
            BlockPos sp = spots.get(j);
            double cx = sp.getX() + 0.5;
            double cz = sp.getZ() + 0.5;
            int offerIndex = page * slots + j;
            sig.append('|').append(sp.asLong()).append(':');
            if (offerIndex >= n) {
                sig.append('-');
                continue;
            }
            MarketData.Offer o = stall.offers.get(offerIndex);
            sig.append(BuiltInRegistries.ITEM.getKey(o.stack.getItem())).append('x').append(o.stack.getCount());
            l.items.add(new ItemSpot(j, cx, sp.getY() + 1.0, cz, o.stack.copyWithCount(1)));
            String time = Messages.formatDuration(Math.max(0, o.expiryMillis - now) / 1000);
            Component text = Component.literal(o.stack.getCount() + "x ").withStyle(ChatFormatting.WHITE)
                    .append(o.stack.getHoverName().copy().withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)))
                    .append(Component.literal("  -  " + EconomyManager.format(o.price))
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)))
                    .append(Component.literal("  -  " + time)
                            .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
            l.texts.add(new TextLine(j + 1, cx, sp.getY() + 1.4, cz, text));
        }
        l.sig = sig.toString();
        return l;
    }

    private static Component ownerHeader(MarketData.Stall stall) {
        return Component.literal("Stand de " + stall.ownerName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
    }

    private static String stallKeyOf(MarketData.Stall stall) {
        return stall.dim + ";" + stall.x + ";" + stall.y + ";" + stall.z;
    }

    private static final class Layout {
        final List<TextLine> texts = new ArrayList<>();
        final List<ItemSpot> items = new ArrayList<>();
        String sig = "";
    }

    private static final class TextLine {
        final int idx;
        final double x;
        final double y;
        final double z;
        final Component text;

        TextLine(int idx, double x, double y, double z, Component text) {
            this.idx = idx;
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
        }
    }

    private static final class ItemSpot {
        final int idx;
        final double x;
        final double y;
        final double z;
        final ItemStack stack;

        ItemSpot(int idx, double x, double y, double z, ItemStack stack) {
            this.idx = idx;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stack = stack;
        }
    }
}
