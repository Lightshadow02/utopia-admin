package com.utopia.transit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.utopia.data.TransitData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

    public enum BoardResult { OK, NOT_CONFIGURED, DISABLED, UNSAFE, NO_WORLD, MOUNTED, TOO_SOON, BUSY }

    /** Duree de l'embarquement, et delai minimal entre deux traversees (anti double-clic). */
    private static final int BOARDING_TICKS = 60;      // ~3 s
    private static final long COOLDOWN_MS = 5_000L;

    /** Une traversee en cours : le joueur monte a bord, puis part au bout du decompte. */
    private record Boarding(UUID player, TransitData.Point point, String where, int ticksLeft) {
    }

    private static final List<Boarding> BOARDINGS = new java.util.ArrayList<>();
    private static final Map<UUID, Long> LAST_TRIP = new HashMap<>();

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
        // Un quai se trouve presque toujours dans une zone ou personne ne se tient : on la charge
        // avant de juger, sinon la traversee echouerait pour la seule raison que le continent dort.
        level.getChunk(feet.getX() >> 4, feet.getZ() >> 4);
        if (!level.isLoaded(feet)) {
            return false; // chargement impossible : on ne peut rien garantir
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
     * Lance l'embarquement : le navire appareille au bout de quelques secondes. Refuse si le joueur
     * monte une bete ou en tient une en laisse (elle resterait a quai), s'il vient deja de voyager, ou
     * si le quai n'est pas praticable.
     */
    public static BoardResult board(ServerPlayer player, TransitData.Point point, String where) {
        // Les refus bon marche d'abord : verifier le quai charge son chunk, un travail bien trop
        // lourd pour le declencher a chaque clic sur un bouton qu'on va de toute facon refuser.
        if (player.isPassenger() || player.isVehicle() || hasLeashed(player)) {
            return BoardResult.MOUNTED;
        }
        if (isBoarding(player)) {
            return BoardResult.BUSY;
        }
        Long last = LAST_TRIP.get(player.getUUID());
        if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) {
            return BoardResult.TOO_SOON;
        }
        BoardResult check = check(player.server, point);
        if (check != BoardResult.OK) {
            return check;
        }
        BOARDINGS.add(new Boarding(player.getUUID(), point, where, BOARDING_TICKS));
        ServerLevel from = player.serverLevel();
        from.sendParticles(ParticleTypes.SPLASH, player.getX(), player.getY() + 0.2, player.getZ(),
                20, 0.5, 0.1, 0.5, 0.05);
        from.playSound(null, player.blockPosition(), SoundEvents.BOAT_PADDLE_WATER,
                SoundSource.PLAYERS, 0.8f, 0.9f);
        return BoardResult.OK;
    }

    /**
     * Ecume qui monte en tournant autour du joueur pendant que le navire prend le large. L'anneau se
     * resserre et la cadence des rames s'accelere a mesure que le depart approche : on entend qu'on
     * s'en va avant de le voir.
     */
    private static void boardingEffect(ServerPlayer player, int ticksLeft) {
        ServerLevel level = player.serverLevel();
        double progress = 1.0 - ticksLeft / (double) BOARDING_TICKS; // 0 au depart, 1 a l'appareillage
        double phase = (player.tickCount % 20) / 20.0 * (Math.PI * 2.0);
        double radius = 1.15 - 0.65 * progress;
        double height = 0.15 + progress * 1.35;
        for (int i = 0; i < 4; i++) {
            double angle = phase + i * (Math.PI / 2.0);
            level.sendParticles(ParticleTypes.SPLASH,
                    player.getX() + Math.cos(angle) * radius, player.getY() + height,
                    player.getZ() + Math.sin(angle) * radius, 2, 0.05, 0.05, 0.05, 0.0);
        }
        level.sendParticles(ParticleTypes.BUBBLE_POP, player.getX(), player.getY() + 0.1,
                player.getZ(), 3, 0.35, 0.05, 0.35, 0.01);

        // Un coup de rame par demi-seconde sur la fin, contre un par seconde au debut.
        int beat = progress > 0.6 ? 10 : 20;
        if (ticksLeft % beat == 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.BOAT_PADDLE_WATER,
                    SoundSource.PLAYERS, 0.5f, (float) (0.9 + progress * 0.45));
        }
    }

    /** Gerbe d'eau laissee au quai de depart. */
    private static void departureEffect(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.6, z, 60, 0.5, 0.6, 0.5, 0.15);
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, x, y + 0.2, z, 30, 0.4, 0.3, 0.4, 0.05);
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.4, z, 25, 0.4, 0.2, 0.4, 0.03);
        level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.PLAYER_SPLASH_HIGH_SPEED,
                SoundSource.PLAYERS, 0.9f, 0.8f);
    }

    /** Accostage : l'ecume retombe et la coque touche le quai. */
    private static void arrivalEffect(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.5, z, 40, 0.45, 0.4, 0.45, 0.1);
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.3, z, 18, 0.35, 0.15, 0.35, 0.02);
        BlockPos pos = BlockPos.containing(x, y, z);
        level.playSound(null, pos, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.6f, 1.1f);
        level.playSound(null, pos, SoundEvents.BOAT_PADDLE_LAND, SoundSource.PLAYERS, 0.9f, 1.0f);
    }

    public static boolean isBoarding(ServerPlayer player) {
        return BOARDINGS.stream().anyMatch(b -> b.player().equals(player.getUUID()));
    }

    /** Le joueur tient-il une bete en laisse ? (elle serait abandonnee a quai) */
    private static boolean hasLeashed(ServerPlayer player) {
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(12.0);
        for (net.minecraft.world.entity.Mob mob
                : player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box)) {
            if (mob.isLeashed() && mob.getLeashHolder() == player) {
                return true;
            }
        }
        return false;
    }

    /**
     * A appeler chaque tick : fait avancer les embarquements en cours et emmene les joueurs a l'echeance.
     * Le deplacement ne touche ni a l'inventaire, ni a l'experience, ni a la vie, ni a la faim, ni aux effets.
     */
    public static void tickBoardings(MinecraftServer server) {
        if (BOARDINGS.isEmpty()) {
            return;
        }
        List<Boarding> next = new java.util.ArrayList<>(BOARDINGS.size());
        for (Boarding b : BOARDINGS) {
            ServerPlayer player = server.getPlayerList().getPlayer(b.player());
            if (player == null) {
                continue; // deconnecte pendant la manoeuvre : la traversee est abandonnee
            }
            if (b.ticksLeft() > 1) {
                boardingEffect(player, b.ticksLeft());
                next.add(new Boarding(b.player(), b.point(), b.where(), b.ticksLeft() - 1));
                continue;
            }
            // Le quai est reverifie a l'arrivee : il a pu changer pendant la manoeuvre.
            BoardResult result = check(server, b.point());
            ServerLevel level = result == BoardResult.OK ? resolveLevel(server, b.point().dim) : null;
            if (level == null) {
                player.sendSystemMessage(com.utopia.util.Messages.warn(reason(
                        result == BoardResult.OK ? BoardResult.NO_WORLD : result)));
                continue;
            }
            // Le sillage reste au quai de depart : ceux qui regardent voient le navire s'en aller.
            departureEffect(player.serverLevel(), player.getX(), player.getY(), player.getZ());
            player.teleportTo(level, b.point().x, b.point().y, b.point().z, b.point().yaw, b.point().pitch);
            LAST_TRIP.put(b.player(), System.currentTimeMillis());
            arrivalEffect(level, b.point().x, b.point().y, b.point().z);
            player.sendSystemMessage(com.utopia.util.Messages.success(
                    "Vous voila arrive a " + b.where() + ". Bon sejour !"));
        }
        BOARDINGS.clear();
        BOARDINGS.addAll(next);
    }

    /** Message explicatif quand une traversee ne peut pas avoir lieu (sans jamais parler de teleportation). */
    public static String reason(BoardResult result) {
        return switch (result) {
            case NOT_CONFIGURED -> "Cette destination n'a pas encore de quai d'arrivee : la traversee est impossible.";
            case DISABLED -> "Cette destination est momentanement fermee a la navigation.";
            case UNSAFE -> "Le quai d'arrivee n'est pas praticable en ce moment : la traversee est annulee.";
            case NO_WORLD -> "Le monde de destination est introuvable : la traversee est annulee.";
            case MOUNTED -> "Impossible d'embarquer avec une monture ou une bete en laisse : "
                    + "laisse-la a quai avant de monter a bord.";
            case TOO_SOON -> "Le navire vient a peine d'accoster : laisse-lui quelques secondes.";
            case BUSY -> "Tu es deja en train d'embarquer.";
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
