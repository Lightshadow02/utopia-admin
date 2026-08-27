package com.utopia.transit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.utopia.data.TransitData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Logique des Capitaines Transit : dialogues, verification des points d'arrivee et embarquement des
 * joueurs.
 *
 * <p>Le vocabulaire visible par les joueurs parle de traversee et d'embarquement : le mot
 * "teleportation" n'apparait jamais.
 */
public final class TransitManager {

    /** Rayon d'apparition d'un capitaine autour de sa position enregistree (synchronisation). */
    private static final double SPAWN_EPSILON = 0.05;

    /** Phrases du capitaine qui emmene vers le continent. */
    public static final List<String> LINES_ALLER = List.of(
            "Ahoy ! Tu veux rejoindre le continent de ressources ? Choisis ta direction et monte a bord. "
                    + "La traversee sera rapide... mon transit aussi.",
            "Nord, Est, Sud ou Ouest ? Choisis ton cap et depeche-toi : mon estomac vient deja de sonner le depart.",
            "Besoin de ressources ? Je peux t'emmener aux quatre coins du continent. Accroche-toi bien, "
                    + "aujourd'hui tout va tres vite chez moi.",
            "Le bateau est pret, l'equipage aussi... mon ventre, beaucoup moins. Choisis ta destination avant "
                    + "que la situation ne nous echappe.",
            "Bienvenue a bord du navire le plus rapide d'Utopia ! Sa vitesse n'a absolument rien a voir avec "
                    + "mes problemes de transit. Enfin... presque.",
            "Choisis une direction et je t'y emmene ! Si tu entends des bruits etranges pendant le trajet, "
                    + "dis-toi simplement que ca vient du moteur.",
            "Tu veux aller recolter quelques ressources ? Monte vite, j'ai le sentiment que cette traversee "
                    + "ne pourra pas attendre tres longtemps.",
            "Quatre directions, quatre destinations et un capitaine au transit douteux. Choisis bien ton voyage, "
                    + "moi je ne garantis que la rapidite.");

    /** Phrases du capitaine qui ramene sur Utopia. */
    public static final List<String> LINES_RETOUR = List.of(
            "Alors, tu as trouve tout ce qu'il te fallait ? Je peux te ramener sur Utopia. La traversee sera "
                    + "rapide : mon transit ne s'est toujours pas calme depuis l'aller.",
            "Tes poches sont pleines et tu veux rentrer ? Monte a bord, mon estomac vient justement de lancer "
                    + "le compte a rebours.",
            "Deja de retour ? Aucun probleme, je te ramene sur Utopia. Evite seulement de t'installer trop pres "
                    + "de la cabine du capitaine.",
            "Pret a rentrer sur Utopia ? Depeche-toi de monter : pour des raisons medicales, ce navire risque "
                    + "de repartir plus tot que prevu.");

    /** Derniere phrase entendue par joueur et par mode : evite de la repeter deux fois de suite. */
    private static final Map<UUID, Integer> LAST_ALLER = new HashMap<>();
    private static final Map<UUID, Integer> LAST_RETOUR = new HashMap<>();
    private static final Random RNG = new Random();

    private TransitManager() {
    }

    // ------------------------------------------------------------------ Dialogues

    /** Choisit une phrase du mode demande, differente de la precedente entendue par ce joueur. */
    public static String nextLine(ServerPlayer player, TransitData.Mode mode) {
        List<String> lines = mode == TransitData.Mode.RETOUR ? LINES_RETOUR : LINES_ALLER;
        Map<UUID, Integer> last = mode == TransitData.Mode.RETOUR ? LAST_RETOUR : LAST_ALLER;
        if (lines.isEmpty()) {
            return "";
        }
        if (lines.size() == 1) {
            return lines.get(0);
        }
        Integer previous = last.get(player.getUUID());
        int index;
        do {
            index = RNG.nextInt(lines.size());
        } while (previous != null && index == previous);
        last.put(player.getUUID(), index);
        return lines.get(index);
    }

    // ------------------------------------------------------------------ Embarquement

    public enum BoardResult { OK, NOT_CONFIGURED, DISABLED, UNSAFE, NO_WORLD }

    /**
     * Verifie qu'un point d'arrivee est praticable : le monde existe, il y a deux blocs libres pour
     * tenir debout, un sol solide juste dessous, et ni liquide ni vide.
     */
    public static BoardResult check(MinecraftServer server, TransitData.Point point) {
        if (point == null || !point.isSet()) {
            return BoardResult.NOT_CONFIGURED;
        }
        if (!point.enabled) {
            return BoardResult.DISABLED;
        }
        ServerLevel level = resolveLevel(server, point.dim);
        if (level == null) {
            return BoardResult.NO_WORLD;
        }
        return isSafe(level, point) ? BoardResult.OK : BoardResult.UNSAFE;
    }

    /**
     * Un point est sur si le joueur peut s'y tenir debout : deux blocs traversables, aucun liquide,
     * et un appui solide juste en dessous (pas de vide, pas de chute immediate).
     */
    private static boolean isSafe(ServerLevel level, TransitData.Point point) {
        BlockPos feet = BlockPos.containing(point.x, point.y, point.z);
        if (!level.isLoaded(feet)) {
            return false; // zone non chargee : on ne peut rien garantir
        }
        BlockPos head = feet.above();
        BlockPos ground = feet.below();
        if (blocksPlayer(level, feet) || blocksPlayer(level, head)) {
            return false; // apparaitrait dans un bloc
        }
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) {
            return false; // sous l'eau (ou dans la lave)
        }
        BlockState below = level.getBlockState(ground);
        // Un sol solide OU un liquide porteur juste sous les pieds (pont de navire, quai...) suffit ;
        // sinon le joueur tomberait des son arrivee.
        return !below.getCollisionShape(level, ground).isEmpty() || !level.getFluidState(ground).isEmpty();
    }

    private static boolean blocksPlayer(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Fait embarquer le joueur vers le point donne. Ne touche ni a l'inventaire, ni a l'experience, ni
     * a la vie, ni a la faim, ni aux effets : seul le lieu change.
     */
    public static BoardResult board(ServerPlayer player, TransitData.Point point) {
        BoardResult check = check(player.server, point);
        if (check != BoardResult.OK) {
            return check;
        }
        ServerLevel level = resolveLevel(player.server, point.dim);
        if (level == null) {
            return BoardResult.NO_WORLD;
        }
        player.teleportTo(level, point.x, point.y, point.z, point.yaw, point.pitch);
        return BoardResult.OK;
    }

    /** Message explicatif quand une traversee ne peut pas avoir lieu (sans jamais parler de teleportation). */
    public static String reason(BoardResult result) {
        return switch (result) {
            case NOT_CONFIGURED -> "Cette destination n'a pas encore de quai d'arrivee : la traversee est impossible.";
            case DISABLED -> "Cette destination est momentanement fermee a la navigation.";
            case UNSAFE -> "Le quai d'arrivee n'est pas praticable en ce moment : la traversee est annulee.";
            case NO_WORLD -> "Le monde de destination est introuvable : la traversee est annulee.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ Synchronisation des PNJ

    /**
     * A appeler periodiquement : fait apparaitre les capitaines actifs a leur emplacement, met a jour
     * leur apparence, et retire ceux qui ne doivent plus etre la. Les PNJ n'etant pas sauvegardes,
     * c'est aussi ce qui les recree apres un redemarrage, sans jamais les dupliquer.
     */
    public static void syncNpcs(MinecraftServer server) {
        TransitData data = TransitData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<String, com.utopia.entity.TransitNpc> present = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.TransitNpc npc) {
                    if (present.putIfAbsent(npc.ownerKey(), npc) != null) {
                        npc.discard(); // doublon eventuel
                    }
                }
            }
            for (TransitData.Captain captain : data.captains()) {
                ServerLevel target = captain.isPlaced() ? resolveLevel(server, captain.dim) : null;
                com.utopia.entity.TransitNpc npc = present.remove(captain.id);
                boolean wanted = captain.enabled && target == level;
                if (!wanted) {
                    if (npc != null) {
                        npc.discard();
                    }
                    continue;
                }
                BlockPos pos = BlockPos.containing(captain.x, captain.y, captain.z);
                if (!level.isLoaded(pos)) {
                    continue; // on retentera quand la zone sera chargee
                }
                if (npc == null || npc.isRemoved()) {
                    npc = new com.utopia.entity.TransitNpc(
                            com.utopia.entity.UtopiaEntities.TRANSIT_NPC.get(), level);
                    npc.setOwnerKey(captain.id);
                    npc.moveTo(captain.x, captain.y, captain.z, captain.restYaw, 0.0f);
                    npc.setRestYaw(captain.restYaw);
                    npc.applyLook(captain.name, captain.skinValue, captain.skinSignature, true);
                    level.addFreshEntity(npc);
                } else {
                    npc.setRestYaw(captain.restYaw);
                    npc.applyLook(captain.name, captain.skinValue, captain.skinSignature, true);
                    if (npc.distanceToSqr(captain.x, captain.y, captain.z) > SPAWN_EPSILON) {
                        npc.moveTo(captain.x, captain.y, captain.z, npc.getYRot(), 0.0f);
                    }
                }
            }
            present.values().forEach(Entity::discard); // capitaines supprimes
        }
    }

    static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
