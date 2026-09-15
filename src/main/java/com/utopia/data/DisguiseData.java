package com.utopia.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les deguisements : le nom sous lequel un joueur apparait, et le visage qu'il porte.
 *
 * <p>Les deux sont conserves separement, parce qu'on veut souvent l'un sans l'autre : un nom
 * d'emprunt sur son propre visage, ou le visage d'un autre sous son vrai nom.
 *
 * <p>Le skin est copie et non reference. Le profil d'un joueur est reconstruit a chaque connexion
 * a partir de ce que Mojang renvoie : sans cette copie, le deguisement tomberait a la premiere
 * reconnexion, et il faut de toute facon pouvoir porter le visage de quelqu'un qui n'est pas la.
 */
public final class DisguiseData extends SavedData {

    private static final String ID = "utopia_disguise";

    public static final SavedData.Factory<DisguiseData> FACTORY =
            new SavedData.Factory<>(DisguiseData::new, DisguiseData::load, null);

    /** Un deguisement. Les champs vides signifient "rien de change sur ce point". */
    public static final class Disguise {
        public String nom = "";
        public String skinValue = "";
        public String skinSignature = "";
        /** Nom du joueur dont le visage est emprunte, pour l'afficher au pupitre. */
        public String skinSource = "";

        public boolean aUnNom() {
            return nom != null && !nom.isBlank();
        }

        public boolean aUnSkin() {
            return skinValue != null && !skinValue.isEmpty();
        }

        public boolean vide() {
            return !aUnNom() && !aUnSkin();
        }
    }

    private final Map<UUID, Disguise> deguisements = new LinkedHashMap<>();

    public DisguiseData() {
    }

    public static DisguiseData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Le deguisement de ce joueur, ou nul s'il n'en porte aucun. */
    public Disguise get(UUID player) {
        return deguisements.get(player);
    }

    public Map<UUID, Disguise> all() {
        return java.util.Collections.unmodifiableMap(deguisements);
    }

    /** Le deguisement de ce joueur, cree au besoin. */
    public Disguise getOrCreate(UUID player) {
        return deguisements.computeIfAbsent(player, k -> new Disguise());
    }

    /** Range le deguisement s'il ne change plus rien : une entree vide n'a pas a etre gardee. */
    public void nettoyer(UUID player) {
        Disguise d = deguisements.get(player);
        if (d != null && d.vide()) {
            deguisements.remove(player);
        }
        setDirty();
    }

    public void retirer(UUID player) {
        deguisements.remove(player);
        setDirty();
    }

    public static DisguiseData load(CompoundTag tag, HolderLookup.Provider registries) {
        DisguiseData data = new DisguiseData();
        ListTag list = tag.getList("deguisements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            try {
                Disguise d = new Disguise();
                d.nom = c.getString("nom");
                d.skinValue = c.getString("skinValue");
                d.skinSignature = c.getString("skinSignature");
                d.skinSource = c.getString("skinSource");
                if (!d.vide()) {
                    data.deguisements.put(UUID.fromString(c.getString("uuid")), d);
                }
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu : ce deguisement est perdu, les autres tiennent
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Disguise> e : deguisements.entrySet()) {
            Disguise d = e.getValue();
            if (d.vide()) {
                continue;
            }
            CompoundTag c = new CompoundTag();
            c.putString("uuid", e.getKey().toString());
            c.putString("nom", d.nom == null ? "" : d.nom);
            c.putString("skinValue", d.skinValue == null ? "" : d.skinValue);
            c.putString("skinSignature", d.skinSignature == null ? "" : d.skinSignature);
            c.putString("skinSource", d.skinSource == null ? "" : d.skinSource);
            list.add(c);
        }
        tag.put("deguisements", list);
        return tag;
    }
}
