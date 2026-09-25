package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les tables de jeu du casino : un bloc, un jeu, des places, et parfois un croupier.
 *
 * <p>Les mises en cours sont enregistrees ici, et c'est le point important. L'argent est retire au
 * moment ou l'on mise, bien avant que la main se termine : sans trace sur le disque, une coupure
 * du serveur entre les deux garderait la mise et ne rendrait jamais rien. Ce qui est note ici est
 * rembourse au demarrage suivant.
 */
public final class TableData extends SavedData {

    private static final String ID = "utopia_tables";

    public static final SavedData.Factory<TableData> FACTORY =
            new SavedData.Factory<>(TableData::new, TableData::load, null);

    /** Une table posee dans le monde. */
    public static final class Table {
        public final String dim;
        public final int x;
        public final int y;
        public final int z;
        public com.utopia.table.Jeu jeu;
        /** Nom affiche ; vide = le nom du jeu. */
        public String label = "";
        public int places = 4;
        public long miseMin = 1;
        public long miseMax = 500;
        /** Croupier attitre, ou nul : la table tourne alors toute seule pour le compte de la maison. */
        public UUID croupier;
        public String croupierNom = "";
        /** Part des mises reversee au croupier, en pourcentage. */
        public int commission = 5;

        public Table(String dim, int x, int y, int z, com.utopia.table.Jeu jeu) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.jeu = jeu;
        }

        public String key() {
            return dim + "@" + x + "," + y + "," + z;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public String nom() {
            return label == null || label.isBlank() ? jeu.label : label;
        }

        public boolean aUnCroupier() {
            return croupier != null;
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    /** Ce qui a ete retire a quelqu'un sans etre encore resolu. */
    private final Map<UUID, Long> misesEnCours = new LinkedHashMap<>();

    public TableData() {
    }

    public static TableData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // ------------------------------------------------------------------ Les tables

    public Collection<Table> tables() {
        return tables.values();
    }

    public Table tableAt(ResourceLocation dim, BlockPos pos) {
        return tables.get(dim + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    public Table byKey(String key) {
        return tables.get(key);
    }

    public Table add(ResourceLocation dim, BlockPos pos, com.utopia.table.Jeu jeu) {
        Table t = new Table(dim.toString(), pos.getX(), pos.getY(), pos.getZ(), jeu);
        t.places = Math.min(4, jeu.placesMax);
        tables.put(t.key(), t);
        setDirty();
        return t;
    }

    public boolean remove(String key) {
        boolean parti = tables.remove(key) != null;
        if (parti) {
            setDirty();
        }
        return parti;
    }

    public int countFor(com.utopia.table.Jeu jeu) {
        int n = 0;
        for (Table t : tables.values()) {
            if (t.jeu == jeu) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ Les mises en cours

    /** Note qu'on vient de retirer {@code montant} a quelqu'un pour une main pas encore jouee. */
    public void engager(UUID joueur, long montant) {
        if (montant <= 0) {
            return;
        }
        misesEnCours.merge(joueur, montant, Long::sum);
        setDirty();
    }

    /** La main est jouee : la mise n'est plus en l'air, qu'elle ait ete perdue ou rendue. */
    public void liberer(UUID joueur, long montant) {
        if (montant <= 0) {
            return;
        }
        Long reste = misesEnCours.computeIfPresent(joueur, (k, v) -> v - montant <= 0 ? null : v - montant);
        if (reste == null) {
            misesEnCours.remove(joueur);
        }
        setDirty();
    }

    public Map<UUID, Long> misesEnCours() {
        return java.util.Collections.unmodifiableMap(misesEnCours);
    }

    /**
     * Rend tout ce qui etait en l'air et vide la liste. Appele au demarrage : ce qui s'y trouve
     * encore est forcement une main que l'arret du serveur a interrompue.
     */
    public List<Map.Entry<UUID, Long>> reprendreTout() {
        List<Map.Entry<UUID, Long>> dues = new ArrayList<>(misesEnCours.entrySet());
        if (!dues.isEmpty()) {
            misesEnCours.clear();
            setDirty();
        }
        return dues;
    }

    // ------------------------------------------------------------------ Serialisation

    public static TableData load(CompoundTag tag, HolderLookup.Provider registries) {
        TableData data = new TableData();
        ListTag liste = tag.getList("tables", Tag.TAG_COMPOUND);
        for (int i = 0; i < liste.size(); i++) {
            CompoundTag c = liste.getCompound(i);
            com.utopia.table.Jeu jeu = com.utopia.table.Jeu.parNom(c.getString("jeu"));
            if (jeu == null) {
                continue; // jeu disparu d'une version a l'autre : la table avec lui
            }
            Table t = new Table(c.getString("dim"), c.getInt("x"), c.getInt("y"), c.getInt("z"), jeu);
            t.label = c.getString("label");
            t.places = Math.max(1, Math.min(jeu.placesMax, c.getInt("places")));
            t.miseMin = Math.max(1, c.getLong("miseMin"));
            t.miseMax = Math.max(t.miseMin, c.getLong("miseMax"));
            t.commission = Math.max(0, Math.min(20, c.getInt("commission")));
            if (c.contains("croupier")) {
                try {
                    t.croupier = UUID.fromString(c.getString("croupier"));
                    t.croupierNom = c.getString("croupierNom");
                } catch (IllegalArgumentException ignored) {
                    t.croupier = null; // identifiant abime : la table repasse a la maison
                }
            }
            data.tables.put(t.key(), t);
        }
        ListTag mises = tag.getList("misesEnCours", Tag.TAG_COMPOUND);
        for (int i = 0; i < mises.size(); i++) {
            CompoundTag c = mises.getCompound(i);
            try {
                data.misesEnCours.put(UUID.fromString(c.getString("uuid")), c.getLong("montant"));
            } catch (IllegalArgumentException ignored) {
                // identifiant abime : impossible de savoir a qui rendre
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag liste = new ListTag();
        for (Table t : tables.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("dim", t.dim);
            c.putInt("x", t.x);
            c.putInt("y", t.y);
            c.putInt("z", t.z);
            c.putString("jeu", t.jeu.name());
            c.putString("label", t.label == null ? "" : t.label);
            c.putInt("places", t.places);
            c.putLong("miseMin", t.miseMin);
            c.putLong("miseMax", t.miseMax);
            c.putInt("commission", t.commission);
            if (t.croupier != null) {
                c.putString("croupier", t.croupier.toString());
                c.putString("croupierNom", t.croupierNom == null ? "" : t.croupierNom);
            }
            liste.add(c);
        }
        tag.put("tables", liste);
        ListTag mises = new ListTag();
        for (Map.Entry<UUID, Long> e : misesEnCours.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", e.getKey().toString());
            c.putLong("montant", e.getValue());
            mises.add(c);
        }
        tag.put("misesEnCours", mises);
        return tag;
    }
}
