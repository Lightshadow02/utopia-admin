package com.utopia.room;

import java.util.UUID;

import com.utopia.parcel.Parcel;

import net.minecraft.resources.ResourceLocation;

/**
 * Une chambre d'auberge : une boite 3D (X/Y/Z, donc superposable) geree par les admins (aubergistes),
 * attribuable a un joueur, avec un prix par jour, une duree indicative, et un etat "gele" (freeze).
 * Seul l'occupant (et les admins) peut interagir avec sa chambre ; la chambre est prioritaire sur les
 * parcelles (y compris administratives).
 */
public final class Room {

    private final String id;
    private String name;
    private final ResourceLocation dimension;
    private Parcel.Box box;
    private UUID occupant;
    private String occupantName;
    private long pricePerDay;
    private int days;
    private boolean frozen;

    public Room(String id, String name, ResourceLocation dimension, Parcel.Box box) {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
        this.box = box;
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

    public Parcel.Box box() {
        return box;
    }

    public void setBox(Parcel.Box box) {
        this.box = box;
    }

    public UUID occupant() {
        return occupant;
    }

    public String occupantName() {
        return occupantName;
    }

    public boolean isAssigned() {
        return occupant != null;
    }

    public boolean isOccupant(UUID id) {
        return occupant != null && occupant.equals(id);
    }

    public void setOccupant(UUID occupant, String occupantName) {
        this.occupant = occupant;
        this.occupantName = occupantName;
    }

    public long pricePerDay() {
        return pricePerDay;
    }

    public void setPricePerDay(long pricePerDay) {
        this.pricePerDay = Math.max(0, pricePerDay);
    }

    public int days() {
        return days;
    }

    public void setDays(int days) {
        this.days = Math.max(0, days);
    }

    /** Cout total (prix/jour x jours) avance par l'aubergiste a l'attribution. */
    public long totalCost() {
        return pricePerDay * days;
    }

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean contains(ResourceLocation dim, int x, int y, int z) {
        return box != null && dimension.equals(dim) && box.contains(x, y, z);
    }

    /** Centre de la chambre (pour la teleportation), au niveau du sol de la boite. */
    public double[] center() {
        if (box == null) {
            return null;
        }
        return new double[] {
                (box.minX() + box.maxX()) / 2.0 + 0.5,
                box.minY(),
                (box.minZ() + box.maxZ()) / 2.0 + 0.5 };
    }
}
