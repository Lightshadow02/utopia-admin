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

    /** Une structure : sa zone, ses deux etats et son mode de bascule. */
    public static final class Struct {
        public final String name;
        public String dim;
        public BlockPos min;   // coin minimal de la zone
        public Vec3i size;     // dimensions (x, y, z)
        public CompoundTag stateA;  // schematique de l'etat 1 (null = pas encore capture)
        public CompoundTag stateB;  // schematique de l'etat 2
        public boolean auto;        // true = bascule automatique jour/nuit
        public int current = 1;     // etat actuellement pose (1 ou 2)
        public Anim anim = Anim.RANDOM; // style d'animation de la bascule

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

        public boolean hasState(int slot) {
            return (slot == 2 ? stateB : stateA) != null;
        }

        public CompoundTag state(int slot) {
            return slot == 2 ? stateB : stateA;
        }

        public void setState(int slot, CompoundTag tag) {
            if (slot == 2) {
                stateB = tag;
            } else {
                stateA = tag;
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
            if (s.contains("stateA", Tag.TAG_COMPOUND)) {
                st.stateA = s.getCompound("stateA");
            }
            if (s.contains("stateB", Tag.TAG_COMPOUND)) {
                st.stateB = s.getCompound("stateB");
            }
            st.auto = s.getBoolean("auto");
            st.current = s.getInt("current") == 2 ? 2 : 1;
            try {
                st.anim = Anim.valueOf(s.getString("anim"));
            } catch (IllegalArgumentException ignored) {
                st.anim = Anim.RANDOM;
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
            if (st.stateA != null) {
                s.put("stateA", st.stateA);
            }
            if (st.stateB != null) {
                s.put("stateB", st.stateB);
            }
            s.putBoolean("auto", st.auto);
            s.putInt("current", st.current);
            s.putString("anim", st.anim.name());
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
