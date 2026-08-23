package com.utopia.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Quota quotidien du marchand mongol. Le quota est <b>global au serveur</b> : toutes les ventes de
 * tous les joueurs s'additionnent dans le meme compteur, remis a zero chaque jour a minuit.
 */
public final class MongolData extends SavedData {

    private static final String ID = "utopia_mongol";

    public static final SavedData.Factory<MongolData> FACTORY =
            new SavedData.Factory<>(MongolData::new, MongolData::load, null);

    /** Jour (epoch day) auquel se rapporte le compteur. */
    private long day;
    /** Items pris sur la RESERVE GLOBALE aujourd'hui (uniquement les depassements de quota personnel). */
    private int sold;
    /** Le message "reserves pleines" a-t-il deja ete diffuse aujourd'hui ? */
    private boolean announced;
    /** Items vendus aujourd'hui par joueur (toutes ventes confondues, quota personnel inclus). */
    private final java.util.Map<java.util.UUID, Integer> personal = new java.util.HashMap<>();

    public MongolData() {
    }

    public static MongolData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Faux tant qu'aucun jour n'a encore ete enregistre (toute premiere utilisation). */
    public boolean initialized() {
        return day != 0L;
    }

    /** Recale le compteur si on a change de jour ; renvoie vrai si une remise a zero a eu lieu. */
    public boolean rollOver(long today) {
        if (day == today) {
            return false;
        }
        day = today;
        sold = 0;
        announced = false;
        personal.clear(); // chaque joueur retrouve sa place quotidienne
        setDirty();
        return true;
    }

    /** Items vendus aujourd'hui par ce joueur (quota personnel + depassements). */
    public int personalSold(java.util.UUID playerId) {
        return personal.getOrDefault(playerId, 0);
    }

    public void addPersonal(java.util.UUID playerId, int count) {
        personal.merge(playerId, count, Integer::sum);
        setDirty();
    }

    public int sold() {
        return sold;
    }

    public void addSold(int count) {
        sold = Math.max(0, sold + count);
        setDirty();
    }

    public boolean announced() {
        return announced;
    }

    public void setAnnounced(boolean value) {
        announced = value;
        setDirty();
    }

    // -------- Serialisation --------

    public static MongolData load(CompoundTag tag, HolderLookup.Provider registries) {
        MongolData data = new MongolData();
        data.day = tag.getLong("day");
        data.sold = tag.getInt("sold");
        data.announced = tag.getBoolean("announced");
        net.minecraft.nbt.ListTag list = tag.getList("personal", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                data.personal.put(java.util.UUID.fromString(e.getString("uuid")), e.getInt("count"));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("day", day);
        tag.putInt("sold", sold);
        tag.putBoolean("announced", announced);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (java.util.Map.Entry<java.util.UUID, Integer> e : personal.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("uuid", e.getKey().toString());
            t.putInt("count", e.getValue());
            list.add(t);
        }
        tag.put("personal", list);
        return tag;
    }
}
