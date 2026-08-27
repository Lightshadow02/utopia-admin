package com.utopia.chantier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.Config;
import com.utopia.data.ChantierData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Logique des chantiers communautaires : depot des ressources, progression commune, passage automatique
 * en "Ressources reunies", annonce globale unique, PNJ et hologramme du Top 3.
 *
 * <p>Les Utopieces sont traitees comme n'importe quel autre item : elles sont prises dans l'inventaire
 * du joueur, jamais sur son solde bancaire.
 */
public final class ChantierManager {

    private static final String HOLO_TAG = "utopiaChantierHolo";
    private static final double LINE_GAP = 0.28;
    /** Hauteur du premier texte au-dessus de la tete du PNJ. */
    private static final double HOLO_BASE = 2.35;
    private static final double SPAWN_EPSILON = 0.05;

    private ChantierManager() {
    }

    // ------------------------------------------------------------------ Utopiece

    /** L'item servant d'Utopiece sur le serveur (configure, par defaut celui du mod utopiamods). */
    public static Item coinItem() {
        return com.utopia.economy.EconomyManager.coinItem();
    }

    /**
     * Correspondance d'un item avec l'objectif : on compare l'<b>identifiant de l'item</b>.
     *
     * <p>C'est volontaire : une Utopiece retiree a la banque porte un marqueur interne qu'une Utopiece
     * obtenue autrement n'a pas ; exiger des composants identiques ferait refuser la moitie des
     * pieces des joueurs. Les variantes restent bien distinctes (chene et bouleau sont deux items).
     */
    public static boolean matches(ItemStack model, ItemStack stack) {
        return !model.isEmpty() && !stack.isEmpty() && stack.is(model.getItem());
    }

    // ------------------------------------------------------------------ Depot

    public enum DepositResult { OK, CLOSED, ALREADY_DONE, NONE_OWNED, INVALID }

    /** Resultat d'un depot : ce qui a ete reellement pris. */
    public record Deposit(DepositResult result, int amount, boolean goalCompleted, boolean chantierCompleted) {
    }

    /** Nombre d'exemplaires de cet objectif dans l'inventaire du joueur. */
    public static int count(ServerPlayer player, ChantierData.Goal goal) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (matches(goal.model, s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /**
     * Depose jusqu'a {@code qty} items sur un objectif. La quantite est bornee par ce que le joueur
     * possede et par ce qu'il reste a fournir : le surplus reste dans l'inventaire.
     */
    public static Deposit deposit(ServerPlayer player, ChantierData.Chantier chantier,
                                  ChantierData.Goal goal, int qty) {
        if (qty <= 0 || goal.model.isEmpty()) {
            return new Deposit(DepositResult.INVALID, 0, false, false);
        }
        if (!chantier.acceptsDeposits()) {
            return new Deposit(DepositResult.CLOSED, 0, false, false);
        }
        if (goal.done()) {
            return new Deposit(DepositResult.ALREADY_DONE, 0, false, false);
        }
        int owned = available(player, goal);
        if (owned <= 0) {
            return new Deposit(DepositResult.NONE_OWNED, 0, false, false);
        }
        int take = Math.min(Math.min(qty, owned), goal.remaining());
        int removed = collect(player, goal, take);
        if (removed <= 0) {
            return new Deposit(DepositResult.NONE_OWNED, 0, false, false);
        }
        goal.current += removed;
        // Les ressources sont consommees par la construction, mais les Utopieces donnees ne sortent pas
        // de l'economie : elles rejoignent la caisse de la mairie, qui finance la suite du chantier.
        if (isCoinGoal(goal)) {
            com.utopia.economy.EconomyManager.add(player.server,
                    com.utopia.data.MarketData.MAIRIE_UUID, removed);
        }
        chantier.addContribution(player.getUUID(), player.getGameProfile().getName(),
                goal.display, removed);
        ChantierData.get(player.server).setDirty();

        boolean goalDone = goal.done();
        boolean allDone = false;
        if (chantier.allDone() && chantier.state == ChantierData.State.COLLECTE) {
            chantier.state = ChantierData.State.REUNIES;
            allDone = true;
            announce(player.server, chantier);
        }
        return new Deposit(DepositResult.OK, removed, goalDone, allDone);
    }

    /** Retire jusqu'a {@code qty} exemplaires ; renvoie le nombre reellement retire. */
    /**
     * Ce que le joueur peut donner : ce qu'il porte, et pour un objectif en Utopieces, son solde en
     * banque par-dessus. On ne fait pas le tour de la ville pour retirer des pieces avant de
     * contribuer : le PNJ du chantier sait encaisser un virement.
     */
    public static int available(ServerPlayer player, ChantierData.Goal goal) {
        int carried = count(player, goal);
        if (!isCoinGoal(goal)) {
            return carried;
        }
        long balance = com.utopia.economy.EconomyManager.getBalance(player.server, player.getUUID());
        return (int) Math.min(Integer.MAX_VALUE, (long) carried + Math.max(0, balance));
    }

    /** Prend d'abord ce que le joueur porte, puis complete sur son compte s'il s'agit d'Utopieces. */
    private static int collect(ServerPlayer player, ChantierData.Goal goal, int qty) {
        int removed = remove(player, goal, qty);
        if (removed >= qty || !isCoinGoal(goal)) {
            return removed;
        }
        int rest = qty - removed;
        if (com.utopia.economy.EconomyManager.remove(player.server, player.getUUID(), rest)) {
            removed += rest;
        }
        return removed;
    }

    private static int remove(ServerPlayer player, ChantierData.Goal goal, int qty) {
        Inventory inv = player.getInventory();
        int remaining = qty;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (matches(goal.model, s)) {
                int take = Math.min(remaining, s.getCount());
                inv.removeItem(i, take);
                remaining -= take;
            }
        }
        if (remaining != qty) {
            inv.setChanged();
        }
        return qty - remaining;
    }

    /** Diffuse l'annonce globale, une seule fois pour la vie du chantier. */
    private static void announce(MinecraftServer server, ChantierData.Chantier chantier) {
        if (chantier.announced) {
            return;
        }
        chantier.announced = true;
        ChantierData.get(server).setDirty();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("Toutes les ressources necessaires au chantier " + chantier.name
                                + " ont ete reunies par les Utopiens ! Bravo a tous ! "
                                + "La mairie vous annoncera prochainement la suite du chantier.")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)), false);
    }

    // ------------------------------------------------------------------ PNJ et hologramme

    /**
     * A appeler periodiquement : place les PNJ de chantier, met a jour leur apparence, et redessine
     * l'hologramme du Top 3. Les PNJ n'etant pas sauvegardes, c'est aussi ce qui les recree apres un
     * redemarrage, sans jamais les dupliquer.
     */
    public static void sync(MinecraftServer server) {
        ChantierData data = ChantierData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, com.utopia.entity.ChantierNpc> npcs = new HashMap<>();
            Map<String, List<ArmorStand>> holos = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.ChantierNpc npc) {
                    if (npcs.putIfAbsent(npc.ownerKey(), npc) != null) {
                        npc.discard();
                    }
                } else if (e instanceof ArmorStand stand) {
                    String key = stand.getPersistentData().getString(HOLO_TAG);
                    if (!key.isEmpty()) {
                        holos.computeIfAbsent(key, k -> new ArrayList<>()).add(stand);
                    }
                }
            }
            for (ChantierData.Chantier chantier : data.all()) {
                ServerLevel target = chantier.isPlaced() ? resolveLevel(server, chantier.dim) : null;
                com.utopia.entity.ChantierNpc npc = npcs.remove(chantier.id);
                List<ArmorStand> lines = holos.remove(chantier.id);
                boolean wanted = chantier.npcEnabled && target == level;
                if (!wanted) {
                    if (npc != null) {
                        npc.discard();
                    }
                    if (lines != null) {
                        lines.forEach(Entity::discard);
                    }
                    continue;
                }
                BlockPos pos = BlockPos.containing(chantier.x, chantier.y, chantier.z);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                if (npc == null || npc.isRemoved()) {
                    npc = new com.utopia.entity.ChantierNpc(
                            com.utopia.entity.UtopiaEntities.CHANTIER_NPC.get(), level);
                    npc.setOwnerKey(chantier.id);
                    npc.moveTo(chantier.x, chantier.y, chantier.z, chantier.restYaw, 0.0f);
                    npc.setRestYaw(chantier.restYaw);
                    npc.applyLook(chantier.npcName, chantier.npcSkinValue, chantier.npcSkinSignature, true);
                    level.addFreshEntity(npc);
                } else {
                    npc.setRestYaw(chantier.restYaw);
                    npc.applyLook(chantier.npcName, chantier.npcSkinValue, chantier.npcSkinSignature, true);
                    if (npc.distanceToSqr(chantier.x, chantier.y, chantier.z) > SPAWN_EPSILON) {
                        npc.moveTo(chantier.x, chantier.y, chantier.z, npc.getYRot(), 0.0f);
                    }
                }
                syncHologram(level, chantier, lines);
            }
            npcs.values().forEach(Entity::discard);
            holos.values().forEach(list -> list.forEach(Entity::discard));
        }
    }

    /** Hologramme du Top 3 au-dessus du PNJ, recree seulement quand son contenu change. */
    private static void syncHologram(ServerLevel level, ChantierData.Chantier chantier, List<ArmorStand> existing) {
        List<Component> lines = chantier.hologram ? topLines(chantier) : List.of();
        if (lines.isEmpty()) {
            if (existing != null) {
                existing.forEach(Entity::discard);
            }
            return;
        }
        double cx = chantier.x;
        double cz = chantier.z;
        double topY = chantier.y + HOLO_BASE + (lines.size() - 1) * LINE_GAP;
        if (existing != null && existing.size() == lines.size()) {
            existing.sort(java.util.Comparator.comparingInt(s -> s.getPersistentData().getInt("line")));
            for (int i = 0; i < lines.size(); i++) {
                ArmorStand stand = existing.get(i);
                stand.setCustomName(lines.get(i));
                stand.setCustomNameVisible(true);
                stand.teleportTo(cx, topY - i * LINE_GAP, cz);
            }
            return;
        }
        if (existing != null) {
            existing.forEach(Entity::discard);
        }
        for (int i = 0; i < lines.size(); i++) {
            spawnLine(level, chantier.id, i, cx, topY - i * LINE_GAP, cz, lines.get(i));
        }
    }

    /** Les lignes du Top 3 (en-tete + jusqu'a trois contributeurs). */
    private static List<Component> topLines(ChantierData.Chantier chantier) {
        List<Map.Entry<UUID, Integer>> ranking = chantier.ranking();
        if (ranking.isEmpty()) {
            return List.of();
        }
        String[] medals = { "1er", "2e", "3e" };
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Top des contributeurs")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Map.Entry<UUID, Integer> e = ranking.get(i);
            String medal = medals[i];
            lines.add(Component.literal(medal + " " + chantier.nameOf(e.getKey()) + " - " + e.getValue() + " items")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)));
        }
        return lines;
    }

    private static void spawnLine(ServerLevel level, String key, int index,
                                  double x, double y, double z, Component text) {
        ArmorStand stand = new ArmorStand(level, x, y, z);
        CompoundTag tag = stand.saveWithoutId(new CompoundTag());
        tag.putBoolean("Marker", true);
        stand.load(tag);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setNoBasePlate(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.getPersistentData().putString(HOLO_TAG, key);
        stand.getPersistentData().putInt("line", index);
        stand.setPos(x, y, z);
        level.addFreshEntity(stand);
    }

    /** Retire le PNJ et l'hologramme d'un chantier (suppression manuelle par un admin). */
    public static void removeEntities(MinecraftServer server, String chantierId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.ChantierNpc npc && chantierId.equals(npc.ownerKey())) {
                    npc.discard();
                } else if (e instanceof ArmorStand stand
                        && chantierId.equals(stand.getPersistentData().getString(HOLO_TAG))) {
                    stand.discard();
                }
            }
        }
    }

    // ------------------------------------------------------------------ Divers

    /** Identifiant lisible d'un item, pour le registre. */
    public static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "?" : id.toString();
    }

    /** L'objectif porte-t-il sur l'Utopiece ? (mis en avant dans l'interface) */
    public static boolean isCoinGoal(ChantierData.Goal goal) {
        return !goal.model.isEmpty() && goal.model.is(coinItem());
    }

    /** Nom de la monnaie tel que configure, pour les libelles. */
    public static String coinName() {
        return Config.ECO_CURRENCY_NAME.get();
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
