package com.utopia.clearlag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.utopia.UtopiaMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Systeme de nettoyage des objets au sol. A chaque passage (toutes les
 * {@code scanIntervalSeconds}), chaque objet droppe est supprime lorsque sa duree de vie
 * configuree est ecoulee — duree comptee <b>par objet</b> depuis qu'il a ete laisse au sol.
 *
 * <p>L'instant de depot ("born") de chaque objet suivi est stocke dans son NBT persistant
 * ({@link Entity#getPersistentData()}), qui survit aux rechargements de chunk et aux redemarrages.
 * L'expiration est recalculee a chaque scan ({@code born + dureeConfiguree}), de sorte qu'un
 * changement de configuration s'applique aussi aux objets deja au sol.
 *
 * <ul>
 *   <li>Objets <b>proteges</b> (liste {@code neverDespawn}, duree &le; 0, ou objet renomme si
 *       {@code protectNamedItems}) : {@link ItemEntity#setUnlimitedLifetime()} -> ne disparaissent jamais.</li>
 *   <li>Duree &gt; 5 min : on gele l'objet pour que vanilla ne le supprime pas a 6000 ticks ;
 *       le mod gere seul le retrait via le NBT persistant.</li>
 *   <li>Duree &le; 5 min : meme suivi par NBT ; vanilla sert de filet de securite (despawn a 6000).</li>
 * </ul>
 */
public final class ClearLagManager {

    /** Age (en ticks) auquel un objet disparait en vanilla. */
    private static final int VANILLA_LIFETIME = 6000;
    /** Valeur sentinelle d'age signifiant "duree de vie illimitee" (vanilla n'incremente plus l'age). */
    private static final int FROZEN_AGE = -32768;
    /** Cle NBT (dans les donnees persistantes de l'objet) stockant l'instant de depot en temps de jeu. */
    private static final String BORN_KEY = "utopia_clearlag_born";

    /** Configuration courante (rechargeable). */
    private static volatile ClearLagConfig config = new ClearLagConfig();

    private ClearLagManager() {
    }

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(UtopiaMod.MODID).resolve("clearlag.json");
    }

    /** (Re)charge la configuration depuis le disque. Ne touche pas aux minuteurs (stockes par objet). */
    public static synchronized ClearLagConfig reload() {
        config = ClearLagConfig.load(configPath());
        return config;
    }

    public static ClearLagConfig getConfig() {
        return config;
    }

    /** Appele a chaque tick serveur ; declenche un scan selon l'intervalle configure. */
    public static void tick(MinecraftServer server) {
        ClearLagConfig cfg = config;
        if (!cfg.enabled) {
            return;
        }
        if (server.getTickCount() % cfg.scanIntervalTicks() != 0) {
            return;
        }
        int removed = scanAll(server, cfg, false);
        if (removed > 0 && cfg.broadcastOnClear) {
            broadcast(server, cfg, removed);
        }
    }

    /** Suppression immediate de tous les objets au sol non proteges (commande /clearlag now). */
    public static int clearNow(MinecraftServer server) {
        return scanAll(server, config, true);
    }

    /** Compte les objets actuellement au sol (toutes dimensions). */
    public static int countGroundItems(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ItemEntity) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int scanAll(MinecraftServer server, ClearLagConfig cfg, boolean force) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            long gameTime = level.getGameTime();
            // On copie d'abord la liste pour eviter toute modification concurrente pendant les discard().
            List<ItemEntity> items = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ItemEntity item && item.isAlive()) {
                    items.add(item);
                }
            }
            for (ItemEntity item : items) {
                if (processItem(cfg, item, gameTime, force)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Traite un objet ; renvoie vrai s'il a ete supprime. */
    private static boolean processItem(ClearLagConfig cfg, ItemEntity item, long gameTime, boolean force) {
        boolean named = cfg.protectNamedItems && item.hasCustomName();
        int ticks = named ? ClearLagConfig.PROTECTED : cfg.lifetimeTicksForItem(item.getItem().getItem());

        if (ticks == ClearLagConfig.PROTECTED) {
            // Objet protege : ne jamais supprimer. On gele son age pour bloquer le despawn vanilla
            // et on retire tout suivi precedent.
            if (item.getAge() != FROZEN_AGE) {
                item.setUnlimitedLifetime();
            }
            CompoundTag data = item.getPersistentData();
            if (data.contains(BORN_KEY)) {
                data.remove(BORN_KEY);
            }
            return false;
        }

        if (force) {
            item.discard();
            return true;
        }

        // Pour les longues durees, on empeche vanilla de supprimer l'objet a 6000 ticks.
        if (ticks > VANILLA_LIFETIME && item.getAge() != FROZEN_AGE) {
            item.setUnlimitedLifetime();
        }

        // Instant de depot : lu depuis le NBT persistant, ou reconstruit puis memorise.
        CompoundTag data = item.getPersistentData();
        long born;
        if (data.contains(BORN_KEY)) {
            born = data.getLong(BORN_KEY);
        } else {
            int age = item.getAge();
            long effectiveAge = age < 0 ? 0L : age; // age gele/negatif -> considere comme neuf
            born = gameTime - effectiveAge;
            data.putLong(BORN_KEY, born);
        }

        if (gameTime >= born + ticks) {
            item.discard();
            return true;
        }
        return false;
    }

    private static void broadcast(MinecraftServer server, ClearLagConfig cfg, int count) {
        String msg = translateColors(cfg.broadcastMessage.replace("%count%", String.valueOf(count)));
        if (!msg.isBlank()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        }
    }

    /** Convertit les codes couleur de type "&a" en codes Minecraft "§a". */
    public static String translateColors(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("(?i)&([0-9a-fk-or])", "§$1");
    }

    static {
        UtopiaMod.LOGGER.debug("[Utopia] ClearLagManager charge.");
    }
}
