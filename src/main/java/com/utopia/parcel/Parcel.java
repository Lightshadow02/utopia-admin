package com.utopia.parcel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

/**
 * Une parcelle : un terrain a forme libre dans une dimension, defini par des polygones traces
 * (et/ou des boites rectangulaires), avec proprietaire, prix, etat "en vente" et membres autorises.
 */
public final class Parcel {

    /** Categorie d'une parcelle (couleur du contour + item exige a l'achat). */
    public enum Type {
        HABITATION,
        COMMERCE;

        public static Type fromName(String name) {
            for (Type t : values()) {
                if (t.name().equalsIgnoreCase(name)) {
                    return t;
                }
            }
            return HABITATION;
        }

        public String label() {
            return this == COMMERCE ? "Commerce" : "Habitation";
        }
    }

    /** Permissions accordables sur une parcelle. */
    public enum Flag {
        BUILD,       // casser / poser des blocs
        CONTAINERS,  // coffres, barils, hoppers, shulkers...
        DOORS,       // portes, trappes, portillons, boutons, leviers, plaques
        MACHINES,    // fours, enclumes, tables, redstone...
        CREATE;      // blocs du mod Create (namespace create:)

        public int bit() {
            return 1 << ordinal();
        }

        public static int mask(EnumSet<Flag> flags) {
            int m = 0;
            for (Flag f : flags) {
                m |= f.bit();
            }
            return m;
        }

        public static EnumSet<Flag> fromMask(int mask) {
            EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
            for (Flag f : values()) {
                if ((mask & f.bit()) != 0) {
                    set.add(f);
                }
            }
            return set;
        }
    }

    /** Boite rectangulaire (coordonnees normalisees min &le; max). */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public boolean intersects(Box o) {
            return minX <= o.maxX && maxX >= o.minX
                    && minY <= o.maxY && maxY >= o.minY
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /** Polygone (empreinte 2D X/Z) avec une plage de hauteur [minY, maxY]. */
    public record Poly(int[] xs, int[] zs, int minY, int maxY) {
        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && pointInPolygon(x + 0.5, z + 0.5, xs, zs);
        }

        /** Test point-dans-polygone (lancer de rayon), sur les centres de blocs. */
        public static boolean pointInPolygon(double px, double pz, int[] xs, int[] zs) {
            boolean inside = false;
            int n = xs.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = xs[i] + 0.5;
                double zi = zs[i] + 0.5;
                double xj = xs[j] + 0.5;
                double zj = zs[j] + 0.5;
                boolean cross = ((zi > pz) != (zj > pz))
                        && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi);
                if (cross) {
                    inside = !inside;
                }
            }
            return inside;
        }

        /** Boite englobante (pour detection rapide de chevauchement / centre). */
        public Box bounds() {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < xs.length; i++) {
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minZ = Math.min(minZ, zs[i]);
                maxZ = Math.max(maxZ, zs[i]);
            }
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private final String id;
    private String name;
    private final ResourceLocation dimension;
    private final List<Box> boxes = new ArrayList<>();
    private final List<Poly> polys = new ArrayList<>();
    private UUID owner;
    private String ownerName;
    private long price;
    private long lastPaid;
    private boolean forSale;
    private boolean admin;
    private Type type = Type.HABITATION;
    private double holoDx;
    private double holoDy;
    private double holoDz;
    private final Map<UUID, EnumSet<Flag>> members = new HashMap<>();

    public Parcel(String id, String name, ResourceLocation dimension) {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name == null || name.isBlank() ? id : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public void addBox(Box box) {
        boxes.add(box);
    }

    public List<Poly> polys() {
        return polys;
    }

    public void addPoly(Poly poly) {
        polys.add(poly);
    }

    public int regionCount() {
        return boxes.size() + polys.size();
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public boolean isOwned() {
        return owner != null;
    }

    public boolean isOwner(UUID id) {
        return owner != null && owner.equals(id);
    }

    public long price() {
        return price;
    }

    public void setPrice(long price) {
        this.price = Math.max(0, price);
    }

    /** Dernier montant paye par le proprietaire actuel (sert au remboursement 75% et au relist). */
    public long lastPaid() {
        return lastPaid;
    }

    public void setLastPaid(long lastPaid) {
        this.lastPaid = Math.max(0, lastPaid);
    }

    public boolean forSale() {
        return forSale;
    }

    public void setForSale(boolean forSale) {
        this.forSale = forSale;
    }

    /** Parcelle administrative : protegee (anti-grief), hors shop, sans proprietaire, geree par les admins. */
    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public Type type() {
        return type;
    }

    public void setType(Type type) {
        this.type = type == null ? Type.HABITATION : type;
    }

    public double holoDx() {
        return holoDx;
    }

    public double holoDy() {
        return holoDy;
    }

    public double holoDz() {
        return holoDz;
    }

    public void setHoloOffset(double dx, double dy, double dz) {
        this.holoDx = dx;
        this.holoDy = dy;
        this.holoDz = dz;
    }

    /** Centre X/Z de l'empreinte (premiere region), ou nul si aucune region. */
    public double[] centerXZ() {
        if (!boxes.isEmpty()) {
            Box b = boxes.get(0);
            return new double[] { (b.minX() + b.maxX()) / 2.0 + 0.5, (b.minZ() + b.maxZ()) / 2.0 + 0.5 };
        }
        if (!polys.isEmpty()) {
            Box b = polys.get(0).bounds();
            return new double[] { (b.minX() + b.maxX()) / 2.0 + 0.5, (b.minZ() + b.maxZ()) / 2.0 + 0.5 };
        }
        return null;
    }

    public Map<UUID, EnumSet<Flag>> members() {
        return members;
    }

    /** Definit les permissions d'un membre (ensemble vide => retire le membre). */
    public void setMember(UUID player, EnumSet<Flag> flags) {
        if (flags == null || flags.isEmpty()) {
            members.remove(player);
        } else {
            members.put(player, flags);
        }
    }

    public boolean contains(ResourceLocation dim, int x, int y, int z) {
        if (!dimension.equals(dim)) {
            return false;
        }
        for (Box b : boxes) {
            if (b.contains(x, y, z)) {
                return true;
            }
        }
        for (Poly p : polys) {
            if (p.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** Le joueur a-t-il ce droit sur la parcelle ? (proprietaire = tous les droits). */
    public boolean allows(UUID player, Flag flag) {
        if (isOwner(player)) {
            return true;
        }
        EnumSet<Flag> set = members.get(player);
        return set != null && set.contains(flag);
    }

    /** Centre approximatif de la premiere region (pour la teleportation), ou nul. */
    public double[] firstRegionCenter() {
        if (!boxes.isEmpty()) {
            Box b = boxes.get(0);
            return new double[] { (b.minX() + b.maxX()) / 2.0 + 0.5, b.maxY() + 1.0, (b.minZ() + b.maxZ()) / 2.0 + 0.5 };
        }
        if (!polys.isEmpty()) {
            Box b = polys.get(0).bounds();
            return new double[] { (b.minX() + b.maxX()) / 2.0 + 0.5, b.maxY() + 1.0, (b.minZ() + b.maxZ()) / 2.0 + 0.5 };
        }
        return null;
    }

    /** Surface approximative (boites + boites englobantes des polygones) en blocs au sol. */
    public long approxFootprint() {
        long area = 0;
        for (Box b : boxes) {
            area += (long) (b.maxX() - b.minX() + 1) * (b.maxZ() - b.minZ() + 1);
        }
        for (Poly p : polys) {
            Box b = p.bounds();
            area += (long) (b.maxX() - b.minX() + 1) * (b.maxZ() - b.minZ() + 1);
        }
        return area;
    }
}
