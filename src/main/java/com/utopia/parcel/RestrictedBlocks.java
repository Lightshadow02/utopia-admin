package com.utopia.parcel;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.utopia.Config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Blocs que l'administrateur declare lui-meme comme proteges, dans la liste
 * {@code parcel.restrictedBlocks} du fichier de configuration.
 *
 * <p>Le mod devine tout seul les coffres, les portes ou les machines, mais il ne peut pas deviner
 * les blocs qui donnent acces a un inventaire <b>sans en etre un</b> : un terminal de stockage n'est
 * pas un conteneur pour Minecraft, il ouvre pourtant tous les coffres de la parcelle. Cette liste
 * comble ce trou, et reste ouverte : chaque nouveau mod pose sa ligne sans toucher au code.
 */
public final class RestrictedBlocks {

    /** Permission utilisee quand la ligne n'en precise aucune : le cas de loin le plus courant. */
    private static final Parcel.Flag DEFAULT_FLAG = Parcel.Flag.CONTAINERS;

    private static List<? extends String> parsedFrom;
    private static Map<ResourceLocation, Parcel.Flag> cache = Map.of();

    private RestrictedBlocks() {
    }

    /** Permission exigee pour ce bloc, ou null s'il n'est pas dans la liste. */
    public static Parcel.Flag flagFor(BlockState state) {
        Map<ResourceLocation, Parcel.Flag> map = map();
        if (map.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : map.get(id);
    }

    /** Permission exigee pour ce bloc, ou null. Variante utilisee par les ecrans d'administration. */
    public static Parcel.Flag flagFor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : map().get(id);
    }

    /** Force la relecture de la liste au prochain acces (rechargement manuel du fichier). */
    public static synchronized void invalidate() {
        parsedFrom = null;
        cache = Map.of();
    }

    /**
     * La liste analysee. Elle est relue quand la configuration change d'instance : NeoForge
     * remplace l'objet a chaque rechargement, ce qui suffit a detecter un /reload sans avoir a
     * s'abonner a un evenement.
     */
    private static synchronized Map<ResourceLocation, Parcel.Flag> map() {
        List<? extends String> raw = Config.PARCEL_RESTRICTED_BLOCKS.get();
        if (raw == parsedFrom) {
            return cache;
        }
        Map<ResourceLocation, Parcel.Flag> built = new HashMap<>();
        for (String line : raw) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            ResourceLocation id = ResourceLocation.tryParse(fields[0].trim());
            if (id == null) {
                continue; // deja refuse par le validateur, on ne fait que se proteger
            }
            built.put(id, parseFlag(fields.length >= 2 ? fields[1] : null));
        }
        parsedFrom = raw;
        cache = Map.copyOf(built);
        return cache;
    }

    private static Parcel.Flag parseFlag(String token) {
        if (token == null || token.isBlank()) {
            return DEFAULT_FLAG;
        }
        try {
            return Parcel.Flag.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT_FLAG;
        }
    }
}
