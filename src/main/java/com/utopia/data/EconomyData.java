package com.utopia.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Donnees persistantes de l'economie : solde bancaire (en pieces) par joueur (UUID).
 * Stocke dans l'overworld ({@code data/utopia_economy.dat}).
 */
public final class EconomyData extends SavedData {
    private static final String ID = "utopia_economy";

    public static final SavedData.Factory<EconomyData> FACTORY =
            new SavedData.Factory<>(EconomyData::new, EconomyData::load, null);

    private final Map<UUID, Long> balances = new HashMap<>();

    public EconomyData() {
    }

    public static EconomyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Solde du joueur, ou {@code defaultValue} s'il n'a pas de compte. */
    public long getBalance(UUID playerId, long defaultValue) {
        return balances.getOrDefault(playerId, defaultValue);
    }

    public boolean hasAccount(UUID playerId) {
        return balances.containsKey(playerId);
    }

    /** Supprime le compte (nettoyage des comptes fantomes crees par erreur). */
    public void removeAccount(UUID playerId) {
        if (balances.remove(playerId) != null) {
            setDirty();
        }
    }

    /** Copie des UUID ayant un compte. */
    public java.util.Set<UUID> accounts() {
        return new java.util.HashSet<>(balances.keySet());
    }

    /** Definit le solde (borne a >= 0) et marque les donnees comme modifiees. */
    public void setBalance(UUID playerId, long amount) {
        balances.put(playerId, Math.max(0L, amount));
        setDirty();
    }

    /** Les {@code limit} plus gros soldes, du plus riche au moins riche. */
    public java.util.List<Map.Entry<UUID, Long>> top(int limit) {
        java.util.List<Map.Entry<UUID, Long>> list = new java.util.ArrayList<>(balances.entrySet());
        // Le compte de la mairie n'apparait pas dans le classement.
        list.removeIf(e -> e.getKey().equals(MarketData.MAIRIE_UUID));
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        ListTag list = tag.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag account = list.getCompound(i);
            try {
                data.balances.put(UUID.fromString(account.getString("uuid")), account.getLong("balance"));
            } catch (IllegalArgumentException ignored) {
                // compte corrompu : ignore
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            CompoundTag account = new CompoundTag();
            account.putString("uuid", e.getKey().toString());
            account.putLong("balance", e.getValue());
            list.add(account);
        }
        tag.put("accounts", list);
        return tag;
    }
}
