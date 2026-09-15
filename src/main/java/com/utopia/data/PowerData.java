package com.utopia.data;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Qui detient quelle capacite speciale. Le registre est tenu a part du joueur : une capacite se
 * donne et se reprend depuis /dieux, elle ne doit pas dependre d'un objet qu'on peut perdre, jeter
 * ou se faire voler.
 */
public final class PowerData extends SavedData {

    private static final String ID = "utopia_pouvoirs";

    public static final SavedData.Factory<PowerData> FACTORY =
            new SavedData.Factory<>(PowerData::new, PowerData::load, null);

    private final Map<UUID, EnumSet<com.utopia.power.Power>> porteurs = new LinkedHashMap<>();
    /** Pseudos retenus, pour lister les porteurs absents sans interroger le cache de profils. */
    private final Map<UUID, String> noms = new LinkedHashMap<>();
    /**
     * Capacites accordees a quelqu'un d'absent, dont l'objet reste a remettre. Sans cette liste, il
     * faudrait redonner l'objet manquant a chaque connexion pour couvrir le cas - et un objet rendu
     * a chaque connexion se duplique a volonte : il suffit de le poser dans un coffre et de revenir.
     */
    private final Map<UUID, EnumSet<com.utopia.power.Power>> aRemettre = new LinkedHashMap<>();

    public PowerData() {
    }

    public static PowerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean a(UUID joueur, com.utopia.power.Power pouvoir) {
        EnumSet<com.utopia.power.Power> set = porteurs.get(joueur);
        return set != null && set.contains(pouvoir);
    }

    public Set<com.utopia.power.Power> de(UUID joueur) {
        EnumSet<com.utopia.power.Power> set = porteurs.get(joueur);
        return set == null ? Set.of() : java.util.Collections.unmodifiableSet(set);
    }

    public Map<UUID, String> noms() {
        return java.util.Collections.unmodifiableMap(noms);
    }

    public Set<UUID> tousLesPorteurs() {
        return java.util.Collections.unmodifiableSet(porteurs.keySet());
    }

    /** Les capacites accordees a cette personne dont l'objet n'a pas encore pu lui etre remis. */
    public Set<com.utopia.power.Power> aRemettre(UUID joueur) {
        EnumSet<com.utopia.power.Power> set = aRemettre.get(joueur);
        return set == null ? Set.of() : java.util.Collections.unmodifiableSet(set);
    }

    /** Note qu'un objet reste du a quelqu'un d'absent. */
    public void noterARemettre(UUID joueur, com.utopia.power.Power pouvoir) {
        aRemettre.computeIfAbsent(joueur, k -> EnumSet.noneOf(com.utopia.power.Power.class))
                .add(pouvoir);
        setDirty();
    }

    /** L'objet a ete remis, ou la capacite reprise : il n'est plus du. */
    public void oublierARemettre(UUID joueur, com.utopia.power.Power pouvoir) {
        EnumSet<com.utopia.power.Power> set = aRemettre.get(joueur);
        if (set == null || !set.remove(pouvoir)) {
            return;
        }
        if (set.isEmpty()) {
            aRemettre.remove(joueur);
        }
        setDirty();
    }

    /** Donne ou reprend une capacite ; renvoie vrai si elle vient d'etre accordee. */
    public boolean basculer(UUID joueur, String nom, com.utopia.power.Power pouvoir) {
        EnumSet<com.utopia.power.Power> set =
                porteurs.computeIfAbsent(joueur, k -> EnumSet.noneOf(com.utopia.power.Power.class));
        boolean accorde;
        if (set.contains(pouvoir)) {
            set.remove(pouvoir);
            accorde = false;
        } else {
            set.add(pouvoir);
            accorde = true;
        }
        if (set.isEmpty()) {
            porteurs.remove(joueur);
            noms.remove(joueur);
        } else if (nom != null && !nom.isBlank()) {
            noms.put(joueur, nom);
        }
        if (!accorde) {
            EnumSet<com.utopia.power.Power> du = aRemettre.get(joueur);
            if (du != null && du.remove(pouvoir) && du.isEmpty()) {
                aRemettre.remove(joueur);
            }
        }
        setDirty();
        return accorde;
    }

    public void toutRetirer(UUID joueur) {
        porteurs.remove(joueur);
        noms.remove(joueur);
        aRemettre.remove(joueur);
        setDirty();
    }

    public static PowerData load(CompoundTag tag, HolderLookup.Provider registries) {
        PowerData data = new PowerData();
        ListTag list = tag.getList("porteurs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(c.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                continue; // uuid corrompu : ce porteur est perdu, les autres tiennent
            }
            EnumSet<com.utopia.power.Power> set = EnumSet.noneOf(com.utopia.power.Power.class);
            ListTag pouvoirs = c.getList("pouvoirs", Tag.TAG_STRING);
            for (int j = 0; j < pouvoirs.size(); j++) {
                com.utopia.power.Power p = com.utopia.power.Power.parNom(pouvoirs.getString(j));
                if (p != null) {
                    set.add(p); // un pouvoir disparu d'une version a l'autre est simplement ignore
                }
            }
            if (set.isEmpty()) {
                continue;
            }
            data.porteurs.put(id, set);
            String nom = c.getString("nom");
            if (!nom.isEmpty()) {
                data.noms.put(id, nom);
            }
            EnumSet<com.utopia.power.Power> dus = EnumSet.noneOf(com.utopia.power.Power.class);
            ListTag attente = c.getList("aRemettre", Tag.TAG_STRING);
            for (int j = 0; j < attente.size(); j++) {
                com.utopia.power.Power p = com.utopia.power.Power.parNom(attente.getString(j));
                // Un objet du pour une capacite qui n'est plus la sienne n'a plus lieu d'etre.
                if (p != null && set.contains(p)) {
                    dus.add(p);
                }
            }
            if (!dus.isEmpty()) {
                data.aRemettre.put(id, dus);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, EnumSet<com.utopia.power.Power>> e : porteurs.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", e.getKey().toString());
            c.putString("nom", noms.getOrDefault(e.getKey(), ""));
            ListTag pouvoirs = new ListTag();
            for (com.utopia.power.Power p : e.getValue()) {
                pouvoirs.add(net.minecraft.nbt.StringTag.valueOf(p.name()));
            }
            c.put("pouvoirs", pouvoirs);
            ListTag attente = new ListTag();
            for (com.utopia.power.Power p : aRemettre.getOrDefault(e.getKey(),
                    EnumSet.noneOf(com.utopia.power.Power.class))) {
                attente.add(net.minecraft.nbt.StringTag.valueOf(p.name()));
            }
            c.put("aRemettre", attente);
            list.add(c);
        }
        tag.put("porteurs", list);
        return tag;
    }
}
