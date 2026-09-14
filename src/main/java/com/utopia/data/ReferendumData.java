package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les referendums : une question posee au serveur, et une seule reponse possible, pour ou contre.
 *
 * <p>L'intitule tient sur <b>plusieurs lignes</b> et non sur une seule chaine. Une question de
 * referendum s'ecrit rarement en dix mots ; en la decoupant, on peut la relire, la corriger ligne
 * par ligne, et l'afficher au joueur sans qu'elle deborde de l'ecran.
 */
public final class ReferendumData extends SavedData {

    private static final String ID = "utopia_referendum";

    public static final SavedData.Factory<ReferendumData> FACTORY =
            new SavedData.Factory<>(ReferendumData::new, ReferendumData::load, null);

    /** Lignes d'intitule au maximum : au-dela, l'ecran de vote ne tient plus d'un seul regard. */
    public static final int MAX_LIGNES = 12;
    /** Caracteres par ligne : la saisie libre du mod s'arrete la, autant l'annoncer. */
    public static final int MAX_CARACTERES = 200;

    public enum Etat {
        BROUILLON("Brouillon", "en preparation, invisible des joueurs"),
        OUVERT("Ouvert", "les joueurs peuvent voter"),
        CLOS("Clos", "le depouillement est fige");

        public final String label;
        public final String detail;

        Etat(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    /** Une consultation : son titre court, son intitule long, et les bulletins deposes. */
    public static final class Referendum {
        public final String id;
        public String titre;
        /** L'intitule, une entree par ligne. C'est la place dont l'auteur a besoin. */
        public final List<String> lignes = new ArrayList<>();
        public Etat etat = Etat.BROUILLON;
        public String auteur = "";
        public long ouvertA;
        public long closA;
        /** Vrai = pour, faux = contre. Un joueur n'a qu'une voix. */
        public final Map<UUID, Boolean> votes = new LinkedHashMap<>();
        public final Map<UUID, String> noms = new LinkedHashMap<>();

        public Referendum(String id, String titre) {
            this.id = id;
            this.titre = titre;
        }

        public int pour() {
            int n = 0;
            for (boolean v : votes.values()) {
                if (v) {
                    n++;
                }
            }
            return n;
        }

        public int contre() {
            return votes.size() - pour();
        }

        public boolean aVote(UUID player) {
            return votes.containsKey(player);
        }

        /** Part des voix pour, en pourcentage entier ; 0 quand personne n'a vote. */
        public int pourcentagePour() {
            return votes.isEmpty() ? 0 : Math.round(pour() * 100f / votes.size());
        }
    }

    private final Map<String, Referendum> referendums = new LinkedHashMap<>();

    public ReferendumData() {
    }

    public static ReferendumData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Collection<Referendum> all() {
        return referendums.values();
    }

    public Referendum get(String id) {
        return referendums.get(id);
    }

    /** Les consultations ouvertes, dans l'ordre d'ouverture. */
    public List<Referendum> ouverts() {
        List<Referendum> out = new ArrayList<>();
        for (Referendum r : referendums.values()) {
            if (r.etat == Etat.OUVERT) {
                out.add(r);
            }
        }
        return out;
    }

    /** Les consultations ouvertes ou ce joueur n'a pas encore depose de bulletin. */
    public List<Referendum> aVoterPour(UUID player) {
        List<Referendum> out = new ArrayList<>();
        for (Referendum r : ouverts()) {
            if (!r.aVote(player)) {
                out.add(r);
            }
        }
        return out;
    }

    public Referendum create(String titre, String auteur) {
        String base = slug(titre);
        if (base.isEmpty()) {
            return null;
        }
        String id = base;
        int n = 2;
        while (referendums.containsKey(id)) {
            id = base + "_" + n++;
        }
        Referendum r = new Referendum(id, titre.trim());
        r.auteur = auteur == null ? "" : auteur;
        referendums.put(id, r);
        setDirty();
        return r;
    }

    public boolean remove(String id) {
        boolean removed = referendums.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Identifiant sur : lettres et chiffres sans accent, le reste devient un tiret bas. */
    private static String slug(String titre) {
        if (titre == null) {
            return "";
        }
        String sansAccent = java.text.Normalizer.normalize(titre.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String propre = sansAccent.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return propre.length() > 40 ? propre.substring(0, 40) : propre;
    }

    // -------- Serialisation --------

    public static ReferendumData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReferendumData data = new ReferendumData();
        ListTag list = tag.getList("referendums", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            String id = c.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Referendum r = new Referendum(id, c.getString("titre"));
            r.auteur = c.getString("auteur");
            r.ouvertA = c.getLong("ouvertA");
            r.closA = c.getLong("closA");
            try {
                r.etat = Etat.valueOf(c.getString("etat"));
            } catch (IllegalArgumentException ignored) {
                r.etat = Etat.BROUILLON; // etat disparu d'une version a l'autre
            }
            ListTag lignes = c.getList("lignes", Tag.TAG_STRING);
            for (int j = 0; j < lignes.size(); j++) {
                r.lignes.add(lignes.getString(j));
            }
            ListTag votes = c.getList("votes", Tag.TAG_COMPOUND);
            for (int j = 0; j < votes.size(); j++) {
                CompoundTag v = votes.getCompound(j);
                try {
                    UUID who = UUID.fromString(v.getString("uuid"));
                    r.votes.put(who, v.getBoolean("pour"));
                    String nom = v.getString("nom");
                    if (!nom.isEmpty()) {
                        r.noms.put(who, nom);
                    }
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu : le bulletin est perdu, le depouillement reste lisible
                }
            }
            data.referendums.put(id, r);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Referendum r : referendums.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", r.id);
            c.putString("titre", r.titre == null ? "" : r.titre);
            c.putString("auteur", r.auteur == null ? "" : r.auteur);
            c.putString("etat", r.etat.name());
            c.putLong("ouvertA", r.ouvertA);
            c.putLong("closA", r.closA);
            ListTag lignes = new ListTag();
            for (String ligne : r.lignes) {
                lignes.add(net.minecraft.nbt.StringTag.valueOf(ligne));
            }
            c.put("lignes", lignes);
            ListTag votes = new ListTag();
            for (Map.Entry<UUID, Boolean> e : r.votes.entrySet()) {
                CompoundTag v = new CompoundTag();
                v.putString("uuid", e.getKey().toString());
                v.putBoolean("pour", e.getValue());
                v.putString("nom", r.noms.getOrDefault(e.getKey(), ""));
                votes.add(v);
            }
            c.put("votes", votes);
            list.add(c);
        }
        tag.put("referendums", list);
        return tag;
    }
}
