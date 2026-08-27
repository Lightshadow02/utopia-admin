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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes des chantiers communautaires : les objectifs, la progression commune a tout le
 * serveur, le registre des contributions et la configuration du PNJ associe.
 *
 * <p>La progression, le registre et le classement sont volontairement independants du PNJ : renommer,
 * deplacer ou rehabiller celui-ci ne fait donc jamais perdre une contribution.
 */
public final class ChantierData extends SavedData {

    private static final String ID = "utopia_chantiers";
    /** Nombre maximal de contributions detaillees conservees par chantier (les totaux, eux, sont exacts). */
    private static final int LOG_MAX = 2000;

    public static final SavedData.Factory<ChantierData> FACTORY =
            new SavedData.Factory<>(ChantierData::new, ChantierData::load, null);

    /** Les trois etats d'un chantier. */
    public enum State {
        COLLECTE("Collecte en cours"),
        REUNIES("Ressources reunies"),
        TERMINE("Chantier termine");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Un objectif : un item demande en une certaine quantite. */
    public static final class Goal {
        public ItemStack model;      // exemplaire de reference (quantite 1)
        public String display;       // nom affiche
        public int required;
        public int current;
        public boolean highlight;    // affiche en grand et en tete (Utopieces)

        public Goal(ItemStack model, String display, int required, boolean highlight) {
            this.model = model;
            this.display = display;
            this.required = Math.max(1, required);
            this.highlight = highlight;
        }

        public boolean done() {
            return current >= required;
        }

        public int remaining() {
            return Math.max(0, required - current);
        }

        public float ratio() {
            return required <= 0 ? 1f : Math.min(1f, (float) current / required);
        }

        public int percent() {
            return Math.round(ratio() * 100f);
        }
    }

    /** Une ligne du registre : qui a donne quoi, quand. */
    public record Contribution(UUID player, String playerName, String item, int amount, long millis) {
    }

    /** Un chantier et son PNJ. */
    public static final class Chantier {
        public final String id;
        public String name;
        public String presentation = "";
        public State state = State.COLLECTE;
        public boolean announced;           // l'annonce globale a-t-elle deja ete diffusee ?
        public final List<Goal> goals = new ArrayList<>();
        public final List<Contribution> log = new ArrayList<>();
        public final Map<UUID, Integer> totals = new LinkedHashMap<>();  // classement : items donnes
        public final Map<UUID, String> names = new LinkedHashMap<>();    // dernier pseudo connu

        // PNJ
        public String npcName = "Chef de chantier";
        public String npcSkinValue = "";
        public String npcSkinSignature = "";
        public String dim = "";
        public double x;
        public double y;
        public double z;
        public float restYaw;
        public boolean npcEnabled = true;
        public boolean hologram = true;

        public Chantier(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean isPlaced() {
            return !dim.isEmpty();
        }

        /** Tous les objectifs sont-ils atteints ? (un chantier sans objectif n'est jamais "reuni") */
        public boolean allDone() {
            if (goals.isEmpty()) {
                return false;
            }
            for (Goal g : goals) {
                if (!g.done()) {
                    return false;
                }
            }
            return true;
        }

        /** Les depots ne sont acceptes que pendant la collecte. */
        public boolean acceptsDeposits() {
            return state == State.COLLECTE;
        }

        public void addContribution(UUID player, String playerName, String item, int amount) {
            log.add(new Contribution(player, playerName, item, amount, System.currentTimeMillis()));
            while (log.size() > LOG_MAX) {
                log.remove(0);
            }
            totals.merge(player, amount, Integer::sum);
            names.put(player, playerName);
        }

        /** Classement decroissant des contributeurs (UUID, total d'items). */
        public List<Map.Entry<UUID, Integer>> ranking() {
            List<Map.Entry<UUID, Integer>> list = new ArrayList<>(totals.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            return list;
        }

        public String nameOf(UUID player) {
            return names.getOrDefault(player, "?");
        }
    }

    private final Map<String, Chantier> chantiers = new LinkedHashMap<>();

    public ChantierData() {
    }

    public static ChantierData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public Collection<Chantier> all() {
        return chantiers.values();
    }

    public Chantier get(String id) {
        return chantiers.get(key(id));
    }

    public boolean exists(String name) {
        return chantiers.containsKey(key(name));
    }

    public Chantier create(String name) {
        Chantier c = new Chantier(key(name), name.trim());
        chantiers.put(c.id, c);
        setDirty();
        return c;
    }

    public boolean remove(String id) {
        if (chantiers.remove(key(id)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // -------- Serialisation --------

    public static ChantierData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChantierData data = new ChantierData();
        ListTag list = tag.getList("chantiers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            String id = c.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            Chantier ch = new Chantier(id, c.getString("name"));
            ch.presentation = c.getString("presentation");
            try {
                ch.state = State.valueOf(c.getString("state"));
            } catch (IllegalArgumentException ignored) {
                ch.state = State.COLLECTE;
            }
            ch.announced = c.getBoolean("announced");
            ch.npcName = c.contains("npcName") ? c.getString("npcName") : "Chef de chantier";
            ch.npcSkinValue = c.getString("npcSkin");
            ch.npcSkinSignature = c.getString("npcSkinSig");
            ch.dim = c.getString("dim");
            ch.x = c.getDouble("x");
            ch.y = c.getDouble("y");
            ch.z = c.getDouble("z");
            ch.restYaw = c.getFloat("restYaw");
            ch.npcEnabled = !c.contains("npcEnabled") || c.getBoolean("npcEnabled");
            ch.hologram = !c.contains("hologram") || c.getBoolean("hologram");

            ListTag goals = c.getList("goals", Tag.TAG_COMPOUND);
            for (int g = 0; g < goals.size(); g++) {
                CompoundTag gt = goals.getCompound(g);
                ItemStack model = ItemStack.parse(registries, gt.getCompound("item")).orElse(ItemStack.EMPTY);
                if (model.isEmpty()) {
                    continue;
                }
                Goal goal = new Goal(model, gt.getString("display"), gt.getInt("required"),
                        gt.getBoolean("highlight"));
                goal.current = gt.getInt("current");
                ch.goals.add(goal);
            }
            ListTag log = c.getList("log", Tag.TAG_COMPOUND);
            for (int l = 0; l < log.size(); l++) {
                CompoundTag lt = log.getCompound(l);
                try {
                    ch.log.add(new Contribution(UUID.fromString(lt.getString("uuid")), lt.getString("name"),
                            lt.getString("item"), lt.getInt("amount"), lt.getLong("t")));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu
                }
            }
            ListTag totals = c.getList("totals", Tag.TAG_COMPOUND);
            for (int t = 0; t < totals.size(); t++) {
                CompoundTag tt = totals.getCompound(t);
                try {
                    UUID player = UUID.fromString(tt.getString("uuid"));
                    ch.totals.put(player, tt.getInt("total"));
                    ch.names.put(player, tt.getString("name"));
                } catch (IllegalArgumentException ignored) {
                    // uuid corrompu
                }
            }
            data.chantiers.put(id, ch);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Chantier ch : chantiers.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", ch.id);
            c.putString("name", ch.name);
            c.putString("presentation", ch.presentation == null ? "" : ch.presentation);
            c.putString("state", ch.state.name());
            c.putBoolean("announced", ch.announced);
            c.putString("npcName", ch.npcName == null ? "" : ch.npcName);
            c.putString("npcSkin", ch.npcSkinValue == null ? "" : ch.npcSkinValue);
            c.putString("npcSkinSig", ch.npcSkinSignature == null ? "" : ch.npcSkinSignature);
            c.putString("dim", ch.dim == null ? "" : ch.dim);
            c.putDouble("x", ch.x);
            c.putDouble("y", ch.y);
            c.putDouble("z", ch.z);
            c.putFloat("restYaw", ch.restYaw);
            c.putBoolean("npcEnabled", ch.npcEnabled);
            c.putBoolean("hologram", ch.hologram);

            ListTag goals = new ListTag();
            for (Goal g : ch.goals) {
                CompoundTag gt = new CompoundTag();
                gt.put("item", g.model.save(registries));
                gt.putString("display", g.display == null ? "" : g.display);
                gt.putInt("required", g.required);
                gt.putInt("current", g.current);
                gt.putBoolean("highlight", g.highlight);
                goals.add(gt);
            }
            c.put("goals", goals);

            ListTag log = new ListTag();
            for (Contribution con : ch.log) {
                CompoundTag lt = new CompoundTag();
                lt.putString("uuid", con.player().toString());
                lt.putString("name", con.playerName());
                lt.putString("item", con.item());
                lt.putInt("amount", con.amount());
                lt.putLong("t", con.millis());
                log.add(lt);
            }
            c.put("log", log);

            ListTag totals = new ListTag();
            for (Map.Entry<UUID, Integer> e : ch.totals.entrySet()) {
                CompoundTag tt = new CompoundTag();
                tt.putString("uuid", e.getKey().toString());
                tt.putInt("total", e.getValue());
                tt.putString("name", ch.nameOf(e.getKey()));
                totals.add(tt);
            }
            c.put("totals", totals);
            list.add(c);
        }
        tag.put("chantiers", list);
        return tag;
    }
}
