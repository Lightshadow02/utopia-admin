package com.utopia.data;

import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Le porteur de /dieux : une seule personne a la fois, designee depuis la console du serveur et
 * nulle part ailleurs.
 *
 * <p>Ce n'est pas un rang d'administration de plus. Il sert a preparer des evenements a l'insu des
 * autres operateurs : la commande qui le decerne leur est donc fermee, et /dieux n'apparait meme
 * pas dans leur completion - Brigadier ne transmet a chaque client que les commandes dont il
 * remplit la condition.
 */
public final class DieuData extends SavedData {

    private static final String ID = "utopia_dieu";

    public static final SavedData.Factory<DieuData> FACTORY =
            new SavedData.Factory<>(DieuData::new, DieuData::load, null);

    private UUID dieu;
    private String nom = "";

    public DieuData() {
    }

    public static DieuData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean isDieu(UUID id) {
        return dieu != null && dieu.equals(id);
    }

    public UUID dieu() {
        return dieu;
    }

    public String nom() {
        return nom;
    }

    /** Designe le porteur. Un seul a la fois : le precedent perd tout par ce seul appel. */
    public void setDieu(UUID id, String nom) {
        this.dieu = id;
        this.nom = nom == null ? "" : nom;
        setDirty();
    }

    public void clear() {
        this.dieu = null;
        this.nom = "";
        setDirty();
    }

    public static DieuData load(CompoundTag tag, HolderLookup.Provider registries) {
        DieuData data = new DieuData();
        String brut = tag.getString("dieu");
        if (!brut.isEmpty()) {
            try {
                data.dieu = UUID.fromString(brut);
            } catch (IllegalArgumentException ignored) {
                data.dieu = null; // uuid corrompu : personne ne porte le titre
            }
        }
        data.nom = tag.getString("nom");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("dieu", dieu == null ? "" : dieu.toString());
        tag.putString("nom", nom);
        return tag;
    }
}
