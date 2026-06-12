package com.utopia.parcel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.utopia.data.ParcelData;
import com.utopia.economy.EconomyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

/**
 * Visuels des parcelles : hologrammes "A vendre" (armor stands invisibles) au-dessus des parcelles
 * en vente, et apercu temporaire (30 s) des delimitations en particules.
 */
public final class ParcelHolograms {

    private static final String HOLO_PARCEL = "utopiaHoloParcel";
    private static final String HOLO_LINE = "utopiaHoloLine";
    private static final int LINES = 5;
    private static final double LINE_GAP = 0.28;

    private static final DustParticleOptions ORANGE = new DustParticleOptions(new Vector3f(1.0F, 0.55F, 0.0F), 1.0F);
    private static final DustParticleOptions YELLOW = new DustParticleOptions(new Vector3f(1.0F, 0.95F, 0.1F), 1.3F);
    // Couleurs de contour selon le type de parcelle.
    private static final DustParticleOptions BLUE = new DustParticleOptions(new Vector3f(0.15F, 0.45F, 1.0F), 1.0F);
    private static final DustParticleOptions BLUE_BRIGHT = new DustParticleOptions(new Vector3f(0.5F, 0.75F, 1.0F), 1.3F);
    private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.15F), 1.0F);

    /** Apercu de delimitation en cours pour un joueur. */
    private record Preview(String parcelId, ResourceLocation dim, long expiryTick) {
    }

    private static final Map<UUID, Preview> PREVIEWS = new ConcurrentHashMap<>();

    private ParcelHolograms() {
    }

    // ------------------------------------------------------------------ Apercu (delimitations)

    /** Demarre un apercu de 30 secondes des delimitations de la parcelle pour ce joueur. */
    public static void startPreview(ServerPlayer player, Parcel parcel) {
        PREVIEWS.put(player.getUUID(), new Preview(parcel.id(), parcel.dimension(),
                player.server.getTickCount() + 600L));
        player.sendSystemMessage(com.utopia.util.Messages.info("Delimitations de " + parcel.name()
                + " affichees pendant 30 s."));
    }

    public static void renderPreviews(MinecraftServer server) {
        if (PREVIEWS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Preview>> it = PREVIEWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Preview> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Preview pv = e.getValue();
            if (player == null || now > pv.expiryTick()) {
                it.remove();
                continue;
            }
            if (!player.serverLevel().dimension().location().equals(pv.dim())) {
                continue;
            }
            Parcel parcel = ParcelData.get(server).get(pv.parcelId());
            if (parcel == null) {
                it.remove();
                continue;
            }
            drawOutline(player.serverLevel(), player, parcel);
        }
    }

    /** Distance (blocs) en deca de laquelle le contour d'une parcelle s'affiche automatiquement au joueur. */
    private static final double NEAR_DIST = 20.0;

    /**
     * Affiche automatiquement le contour (au sol) des parcelles proches de chaque joueur (&lt; 20 blocs).
     * A appeler periodiquement depuis le tick serveur.
     */
    public static void renderNearby(MinecraftServer server) {
        ParcelData data = ParcelData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            ResourceLocation dim = level.dimension().location();
            double px = player.getX();
            double pz = player.getZ();
            for (Parcel parcel : data.all()) {
                // Uniquement les parcelles EN VENTE : une fois achetee, plus d'affichage auto.
                if (!parcel.forSale() || !parcel.dimension().equals(dim)) {
                    continue;
                }
                if (isNear(parcel, px, pz)) {
                    drawOutline(level, player, parcel);
                }
            }
        }
    }

    /** Vrai si le point (px, pz) est a moins de {@link #NEAR_DIST} blocs d'une boite/sommet de la parcelle. */
    private static boolean isNear(Parcel parcel, double px, double pz) {
        double maxSq = NEAR_DIST * NEAR_DIST;
        for (Parcel.Box b : parcel.boxes()) {
            double nx = Math.max(b.minX(), Math.min(px, b.maxX() + 1.0));
            double nz = Math.max(b.minZ(), Math.min(pz, b.maxZ() + 1.0));
            double dx = px - nx;
            double dz = pz - nz;
            if (dx * dx + dz * dz <= maxSq) {
                return true;
            }
        }
        for (Parcel.Poly poly : parcel.polys()) {
            for (int i = 0; i < poly.xs().length; i++) {
                double dx = px - (poly.xs()[i] + 0.5);
                double dz = pz - (poly.zs()[i] + 0.5);
                if (dx * dx + dz * dz <= maxSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void drawOutline(ServerLevel level, ServerPlayer player, Parcel parcel) {
        // Bleu = Habitation, Jaune = Commerce, Rouge = zone Admin.
        DustParticleOptions edgeColor;
        DustParticleOptions cornerColor;
        if (parcel.isAdmin()) {
            edgeColor = RED;
            cornerColor = RED;
        } else if (parcel.type() == Parcel.Type.COMMERCE) {
            edgeColor = YELLOW;
            cornerColor = ORANGE;
        } else {
            edgeColor = BLUE;
            cornerColor = BLUE_BRIGHT;
        }
        for (Parcel.Box b : parcel.boxes()) {
            double x0 = b.minX();
            double x1 = b.maxX() + 1.0;
            double z0 = b.minZ();
            double z1 = b.maxZ() + 1.0;
            edge(level, player, x0, z0, x1, z0, edgeColor);
            edge(level, player, x1, z0, x1, z1, edgeColor);
            edge(level, player, x1, z1, x0, z1, edgeColor);
            edge(level, player, x0, z1, x0, z0, edgeColor);
            corner(level, player, x0, z0, cornerColor);
            corner(level, player, x1, z0, cornerColor);
            corner(level, player, x1, z1, cornerColor);
            corner(level, player, x0, z1, cornerColor);
        }
        for (Parcel.Poly poly : parcel.polys()) {
            int n = poly.xs().length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                edge(level, player, poly.xs()[i] + 0.5, poly.zs()[i] + 0.5, poly.xs()[j] + 0.5, poly.zs()[j] + 0.5, edgeColor);
                corner(level, player, poly.xs()[i] + 0.5, poly.zs()[i] + 0.5, cornerColor);
            }
        }
    }

    private static void edge(ServerLevel level, ServerPlayer player, double x1, double z1, double x2, double z2, DustParticleOptions color) {
        double dist = Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1));
        int steps = Math.min(400, Math.max(1, (int) (dist / 0.5)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = x1 + (x2 - x1) * t;
            double z = z1 + (z2 - z1) * t;
            double y = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z)) + 0.3;
            level.sendParticles(player, color, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void corner(ServerLevel level, ServerPlayer player, double x, double z, DustParticleOptions color) {
        double y = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z)) + 0.5;
        level.sendParticles(player, color, true, x, y, z, 6, 0.02, 0.4, 0.02, 0.0);
    }

    // ------------------------------------------------------------------ Hologrammes "A vendre"

    public static void syncHolograms(MinecraftServer server) {
        ParcelData data = ParcelData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dim = level.dimension().location();

            // Recense les hologrammes existants par parcelle (dans les chunks charges).
            Map<String, List<ArmorStand>> existing = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand as && as.getPersistentData().contains(HOLO_PARCEL)) {
                    existing.computeIfAbsent(as.getPersistentData().getString(HOLO_PARCEL), k -> new ArrayList<>()).add(as);
                }
            }

            Set<String> wanted = new HashSet<>();
            for (Parcel p : data.all()) {
                if (!p.forSale() || !p.dimension().equals(dim)) {
                    continue;
                }
                double[] cxz = p.centerXZ();
                if (cxz == null) {
                    continue;
                }
                int bx = (int) Math.floor(cxz[0] + p.holoDx());
                int bz = (int) Math.floor(cxz[1] + p.holoDz());
                if (!level.isLoaded(new net.minecraft.core.BlockPos(bx, level.getMinBuildHeight() + 1, bz))) {
                    continue; // chunk non charge : on synchronisera quand il le sera
                }
                wanted.add(p.id());
                double x = cxz[0] + p.holoDx();
                double z = cxz[1] + p.holoDz();
                double topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) + 2.4 + p.holoDy();
                List<Component> lines = holoLines(p);

                List<ArmorStand> stands = existing.get(p.id());
                if (stands == null || stands.size() != LINES) {
                    if (stands != null) {
                        stands.forEach(Entity::discard);
                    }
                    spawnStands(level, p.id(), x, topY, z, lines);
                } else {
                    stands.sort(Comparator.comparingInt(s -> s.getPersistentData().getInt(HOLO_LINE)));
                    for (int i = 0; i < LINES; i++) {
                        ArmorStand s = stands.get(i);
                        s.teleportTo(x, topY - i * LINE_GAP, z);
                        s.setCustomName(lines.get(i));
                        s.setCustomNameVisible(true);
                    }
                }
            }

            // Supprime les hologrammes des parcelles qui ne sont plus en vente.
            for (Map.Entry<String, List<ArmorStand>> entry : existing.entrySet()) {
                if (!wanted.contains(entry.getKey())) {
                    entry.getValue().forEach(Entity::discard);
                }
            }
        }
    }

    private static List<Component> holoLines(Parcel p) {
        String seller = p.isOwned() ? "Vendeur : " + p.ownerName() : "Vendeur : Mairie";
        return List.of(
                Component.literal("ID: " + p.id()).withStyle(ChatFormatting.YELLOW),
                Component.literal("A VENDRE").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)),
                Component.literal("[" + p.type().label() + "]")
                        .withStyle(p.type() == Parcel.Type.COMMERCE ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                Component.literal(seller).withStyle(p.isOwned() ? ChatFormatting.AQUA : ChatFormatting.GRAY),
                Component.literal(EconomyManager.format(p.price())).withStyle(ChatFormatting.GOLD));
    }

    private static void spawnStands(ServerLevel level, String parcelId, double x, double topY, double z, List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            double y = topY - i * LINE_GAP;
            ArmorStand stand = new ArmorStand(level, x, y, z);
            // Marker (setter prive) via NBT.
            CompoundTag tag = stand.saveWithoutId(new CompoundTag());
            tag.putBoolean("Marker", true);
            stand.load(tag);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setNoBasePlate(true);
            stand.setCustomName(lines.get(i));
            stand.setCustomNameVisible(true);
            stand.getPersistentData().putString(HOLO_PARCEL, parcelId);
            stand.getPersistentData().putInt(HOLO_LINE, i);
            stand.setPos(x, y, z);
            level.addFreshEntity(stand);
        }
    }
}
