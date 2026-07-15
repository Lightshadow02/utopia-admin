package com.utopia.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Structures a etats : une zone du monde dont le serveur memorise deux "schematiques" (etat 1 et
 * etat 2) qu'il peut reposer a la demande, manuellement ou automatiquement (jour / nuit).
 *
 * <p>Les schematiques sont des {@code StructureTemplate} vanilla serialises (palette + block
 * entities), donc compacts et fideles.
 */
public final class StructureData extends SavedData {

    private static final String ID = "utopia_structures";

    public static final SavedData.Factory<StructureData> FACTORY =
            new SavedData.Factory<>(StructureData::new, StructureData::load, null);

    /** Style d'animation de la bascule d'etat. */
    public enum Anim {
        RANDOM("Dissolution aleatoire"),
        BOTTOM_UP("Couche par couche (montee)"),
        TOP_DOWN("Couche par couche (descente)"),
        CENTER_OUT("Onde depuis le centre"),
        OUTSIDE_IN("Onde vers le centre"),
        INSTANT("Instantane");

        private final String label;

        Anim(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Style suivant (le menu fait defiler les styles). */
        public Anim next() {
            Anim[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /**
     * Un article du marchand : l'objet, son prix d'achat (le joueur achete) et son prix de revente
     * (le marchand rachete). Un prix negatif desactive le sens correspondant. Stock illimite.
     */
    public record Trade(net.minecraft.world.item.ItemStack stack, long buyPrice, long sellPrice) {

        public boolean canBuy() {
            return buyPrice >= 0;
        }

        public boolean canSell() {
            return sellPrice >= 0;
        }
    }

    /** Bornes du nombre d'etats d'une structure. */
    public static final int MIN_STATES = 2;
    public static final int MAX_STATES = 5;

    /** Comment la structure change d'etat. */
    public enum Mode {
        MANUAL("Manuel"),
        DAY_NIGHT("Auto jour/nuit"),
        SCHEDULE("Horaires precis");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** Une structure : sa zone, ses etats (2 a 5) et son mode de bascule. */
    public static final class Struct {
        public final String name;
        public String dim;
        public BlockPos min;   // coin minimal de la zone
        public Vec3i size;     // dimensions (x, y, z)
        /** Schematiques des etats 1..MAX_STATES (index 0 = etat 1) ; null = pas encore capture. */
        private final CompoundTag[] states = new CompoundTag[MAX_STATES];
        public int stateCount = MIN_STATES; // nombre d'etats utilises (2 a 5)
        public Mode mode = Mode.MANUAL;    // manuel, auto jour/nuit, ou horaires precis
        public int current = 1;            // etat actuellement pose (1..stateCount)
        /** Mode "Horaires" : heure du monde (0..23999) qui declenche chaque etat ; -1 = non defini. */
        private final long[] stateTimes = new long[MAX_STATES];
        public Anim anim = Anim.RANDOM; // style d'animation de la bascule

        /**
         * Filtre optionnel : si non vide, seuls les blocs de cette liste (identifiants de blocs)
         * changent lors d'une bascule ; tout le reste de la zone est laisse intact. Permet par
         * exemple de n'animer que les lanternes (allumees la nuit, eteintes le jour).
         */
        public final List<String> blockFilter = new ArrayList<>();

        // ---- Marchand optionnel, present uniquement dans l'un des deux etats ----
        public boolean npcEnabled;
        public int npcState = 1;            // etat dans lequel le marchand apparait
        public BlockPos npcPos;             // ou il se tient (null = pas encore place)
        public String npcName = "Marchand";
        public String npcSkinValue = "";    // skin (propriete "textures"), vide = Steve
        public String npcSkinSignature = "";
        public final List<Trade> trades = new ArrayList<>();

        public Struct(String name) {
            this.name = name;
        }

        /** Un numero d'etat valide ? (1..MAX_STATES) */
        private static boolean valid(int slot) {
            return slot >= 1 && slot <= MAX_STATES;
        }

        public boolean hasState(int slot) {
            return valid(slot) && states[slot - 1] != null;
        }

        public CompoundTag state(int slot) {
            return valid(slot) ? states[slot - 1] : null;
        }

        public void setState(int slot, CompoundTag tag) {
            if (valid(slot)) {
                states[slot - 1] = tag;
            }
        }

        /** Tous les etats utilises (1..stateCount) sont-ils captures ? */
        public boolean allStatesReady() {
            for (int i = 1; i <= stateCount; i++) {
                if (!hasState(i)) {
                    return false;
                }
            }
            return true;
        }

        /** Etat suivant dans le cycle (revient a 1 apres le dernier). */
        public int nextState() {
            return current >= stateCount ? 1 : current + 1;
        }

        /** Heure de declenchement de l'etat (0..23999), ou -1 si non definie. */
        public long stateTime(int slot) {
            return valid(slot) ? stateTimes[slot - 1] : -1L;
        }

        /** Definit l'heure de declenchement ; une valeur hors [0, 23999] efface l'horaire. */
        public void setStateTime(int slot, long time) {
            if (valid(slot)) {
                stateTimes[slot - 1] = (time < 0 || time > 23999) ? -1L : time;
            }
        }

        /** Au moins un horaire est-il defini parmi les etats utilises ? */
        public boolean hasAnySchedule() {
            for (int i = 1; i <= stateCount; i++) {
                if (stateTime(i) >= 0) {
                    return true;
                }
            }
            return false;
        }

        /** Borne le nombre d'etats et recadre l'etat courant / celui du marchand. */
        public void setStateCount(int count) {
            stateCount = Math.max(MIN_STATES, Math.min(MAX_STATES, count));
            if (current > stateCount) {
                current = 1;
            }
            if (npcState > stateCount) {
                npcState = 1;
            }
        }

        /** Volume de la zone en blocs. */
        public long volume() {
            return size == null ? 0L : (long) size.getX() * size.getY() * size.getZ();
        }
    }

    private final Map<String, Struct> structures = new LinkedHashMap<>();

    public StructureData() {
    }

    public static StructureData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Collection<Struct> all() {
        return structures.values();
    }

    public Struct get(String name) {
        return structures.get(key(name));
    }

    public boolean exists(String name) {
        return structures.containsKey(key(name));
    }

    public void put(Struct struct) {
        structures.put(key(struct.name), struct);
        setDirty();
    }

    public boolean remove(String name) {
        if (structures.remove(key(name)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // -------- Serialisation --------

    public static StructureData load(CompoundTag tag, HolderLookup.Provider registries) {
        StructureData data = new StructureData();
        ListTag list = tag.getList("structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            String name = s.getString("name");
            if (name.isEmpty()) {
                continue;
            }
            Struct st = new Struct(name);
            st.dim = s.getString("dim");
            st.min = new BlockPos(s.getInt("x"), s.getInt("y"), s.getInt("z"));
            st.size = new Vec3i(s.getInt("sx"), s.getInt("sy"), s.getInt("sz"));
            // Ancien format (2 etats) : migre vers les etats 1 et 2.
            if (s.contains("stateA", Tag.TAG_COMPOUND)) {
                st.setState(1, s.getCompound("stateA"));
            }
            if (s.contains("stateB", Tag.TAG_COMPOUND)) {
                st.setState(2, s.getCompound("stateB"));
            }
            for (int slot = 1; slot <= MAX_STATES; slot++) {
                if (s.contains("state" + slot, Tag.TAG_COMPOUND)) {
                    st.setState(slot, s.getCompound("state" + slot));
                }
            }
            st.stateCount = Math.max(MIN_STATES, Math.min(MAX_STATES,
                    s.contains("stateCount") ? s.getInt("stateCount") : MIN_STATES));
            // Mode : nouveau format, avec repli sur l'ancien booleen "auto".
            if (s.contains("mode")) {
                try {
                    st.mode = Mode.valueOf(s.getString("mode"));
                } catch (IllegalArgumentException ignored) {
                    st.mode = Mode.MANUAL;
                }
            } else {
                st.mode = s.getBoolean("auto") ? Mode.DAY_NIGHT : Mode.MANUAL;
            }
            for (int slot = 1; slot <= MAX_STATES; slot++) {
                st.setStateTime(slot, s.contains("time" + slot) ? s.getLong("time" + slot) : -1L);
            }
            st.current = Math.max(1, Math.min(st.stateCount, s.getInt("current")));
            try {
                st.anim = Anim.valueOf(s.getString("anim"));
            } catch (IllegalArgumentException ignored) {
                st.anim = Anim.RANDOM;
            }
            ListTag filter = s.getList("blockFilter", Tag.TAG_STRING);
            for (int j = 0; j < filter.size(); j++) {
                st.blockFilter.add(filter.getString(j));
            }
            // Marchand
            st.npcEnabled = s.getBoolean("npcEnabled");
            st.npcState = s.getInt("npcState") == 2 ? 2 : 1;
            if (s.contains("npcX")) {
                st.npcPos = new BlockPos(s.getInt("npcX"), s.getInt("npcY"), s.getInt("npcZ"));
            }
            if (s.contains("npcName")) {
                st.npcName = s.getString("npcName");
            }
            st.npcSkinValue = s.getString("npcSkinValue");
            st.npcSkinSignature = s.getString("npcSkinSig");
            ListTag trades = s.getList("trades", Tag.TAG_COMPOUND);
            for (int j = 0; j < trades.size(); j++) {
                CompoundTag t = trades.getCompound(j);
                net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack
                        .parse(registries, t.getCompound("stack")).orElse(net.minecraft.world.item.ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    st.trades.add(new Trade(stack, t.getLong("buy"), t.getLong("sell")));
                }
            }
            data.structures.put(key(name), st);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Struct st : structures.values()) {
            CompoundTag s = new CompoundTag();
            s.putString("name", st.name);
            s.putString("dim", st.dim == null ? "" : st.dim);
            s.putInt("x", st.min.getX());
            s.putInt("y", st.min.getY());
            s.putInt("z", st.min.getZ());
            s.putInt("sx", st.size.getX());
            s.putInt("sy", st.size.getY());
            s.putInt("sz", st.size.getZ());
            for (int slot = 1; slot <= MAX_STATES; slot++) {
                CompoundTag state = st.state(slot);
                if (state != null) {
                    s.put("state" + slot, state);
                }
            }
            s.putInt("stateCount", st.stateCount);
            s.putString("mode", st.mode.name());
            for (int slot = 1; slot <= MAX_STATES; slot++) {
                s.putLong("time" + slot, st.stateTime(slot));
            }
            s.putInt("current", st.current);
            s.putString("anim", st.anim.name());
            ListTag filter = new ListTag();
            for (String id : st.blockFilter) {
                filter.add(net.minecraft.nbt.StringTag.valueOf(id));
            }
            s.put("blockFilter", filter);
            // Marchand
            s.putBoolean("npcEnabled", st.npcEnabled);
            s.putInt("npcState", st.npcState);
            if (st.npcPos != null) {
                s.putInt("npcX", st.npcPos.getX());
                s.putInt("npcY", st.npcPos.getY());
                s.putInt("npcZ", st.npcPos.getZ());
            }
            s.putString("npcName", st.npcName == null ? "Marchand" : st.npcName);
            s.putString("npcSkinValue", st.npcSkinValue == null ? "" : st.npcSkinValue);
            s.putString("npcSkinSig", st.npcSkinSignature == null ? "" : st.npcSkinSignature);
            ListTag trades = new ListTag();
            for (Trade t : st.trades) {
                CompoundTag tt = new CompoundTag();
                tt.put("stack", t.stack().save(registries));
                tt.putLong("buy", t.buyPrice());
                tt.putLong("sell", t.sellPrice());
                trades.add(tt);
            }
            s.put("trades", trades);
            list.add(s);
        }
        tag.put("structures", list);
        return tag;
    }

    /** Liste des noms, pour les menus. */
    public List<String> names() {
        return new ArrayList<>(structures.keySet());
    }
}
