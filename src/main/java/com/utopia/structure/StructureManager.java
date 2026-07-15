package com.utopia.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import com.utopia.data.StructureData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Moteur des structures a etats : selection de la zone (apercu en particules rouges), capture d'un
 * etat sous forme de schematique vanilla, pose d'un etat, et bascule automatique jour / nuit.
 *
 * <p>La capture/pose s'appuie sur {@link StructureTemplate} (le moteur du bloc de structure) : la
 * palette et les block entities sont gerees par le jeu.
 */
public final class StructureManager {

    /**
     * Volume maximal d'une zone. Les grandes structures ne sont pas refusees : elles mettent
     * simplement <b>plus longtemps</b> a s'animer (le debit par tick est plafonne), ce qui evite les
     * a-coups. Seule la <b>capture</b> reste synchrone : sur une zone enorme, le serveur marque une
     * pause d'une seconde ou deux au moment du "Capturer".
     */
    public static final long MAX_VOLUME = 5_000_000L;

    /**
     * Drapeaux de pose des blocs. On met a jour les clients SANS notifier les voisins
     * ({@code UPDATE_KNOWN_SHAPE}) et en supprimant les drops ({@code UPDATE_SUPPRESS_DROPS}).
     *
     * <p>Avec {@code UPDATE_ALL}, les voisins etaient notifies : tout ce qui a besoin d'un support
     * (echelle, banniere, lutrin et son livre...) se decrochait et tombait au sol en items. Ici, tout
     * vient du schematique : aucun bloc ne doit rien lacher.
     */
    private static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** Joueurs en train de definir une zone : coin 1 (clic gauche) / coin 2 (clic droit). */
    private static final Map<UUID, BlockPos[]> CORNERS = new ConcurrentHashMap<>();

    private static final DustParticleOptions LINE = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.15F), 1.0F);
    private static final DustParticleOptions VERTEX = new DustParticleOptions(new Vector3f(1.0F, 0.5F, 0.0F), 1.7F);

    private StructureManager() {
    }

    // ------------------------------------------------------------------ Selection de zone

    public static void startSelect(UUID id) {
        CORNERS.put(id, new BlockPos[2]);
    }

    public static boolean isSelecting(UUID id) {
        return CORNERS.containsKey(id);
    }

    public static void clearSelect(UUID id) {
        CORNERS.remove(id);
    }

    /** Definit le coin 1 (clic gauche) ou le coin 2 (clic droit). */
    public static void setCorner(UUID id, BlockPos pos, boolean first) {
        BlockPos[] c = CORNERS.computeIfAbsent(id, k -> new BlockPos[2]);
        c[first ? 0 : 1] = pos.immutable();
    }

    public static BlockPos[] corners(UUID id) {
        return CORNERS.get(id);
    }

    /** Vrai si les deux coins sont poses. */
    public static boolean hasBox(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        return c != null && c[0] != null && c[1] != null;
    }

    /** Coin minimal de la selection, ou null. */
    public static BlockPos min(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        if (c == null || c[0] == null || c[1] == null) {
            return null;
        }
        return new BlockPos(Math.min(c[0].getX(), c[1].getX()),
                Math.min(c[0].getY(), c[1].getY()),
                Math.min(c[0].getZ(), c[1].getZ()));
    }

    /** Dimensions de la selection (inclusives), ou null. */
    public static Vec3i size(UUID id) {
        BlockPos[] c = CORNERS.get(id);
        if (c == null || c[0] == null || c[1] == null) {
            return null;
        }
        return new Vec3i(Math.abs(c[0].getX() - c[1].getX()) + 1,
                Math.abs(c[0].getY() - c[1].getY()) + 1,
                Math.abs(c[0].getZ() - c[1].getZ()) + 1);
    }

    // ------------------------------------------------------------------ Capture / pose

    /** Capture l'etat actuel du monde dans le slot demande (1 ou 2). */
    public static boolean capture(MinecraftServer server, StructureData.Struct struct, int slot) {
        ServerLevel level = resolveLevel(server, struct.dim);
        if (level == null || struct.volume() > MAX_VOLUME) {
            return false;
        }
        StructureTemplate template = new StructureTemplate();
        // - eau ignoree : les blocs d'eau ne sont ni memorises ni reposes, la mer reste intacte ;
        // - sans entites : sinon chaque bascule en respawnerait des copies (doublons).
        template.fillFromWorld(level, struct.min, struct.size, false, Blocks.WATER);
        struct.setState(slot, template.save(new CompoundTag()));
        StructureData.get(server).setDirty();
        return true;
    }

    /**
     * Pose l'etat demande d'un coup (sans animation). Passe par le meme chemin que l'animation
     * (diff + pose bloc par bloc) pour beneficier des memes protections : pas de drop, conteneurs
     * vides avant remplacement, blocs inchanges laisses tranquilles.
     */
    public static boolean apply(MinecraftServer server, StructureData.Struct struct, int slot) {
        CompoundTag tag = struct.state(slot);
        if (tag == null) {
            return false;
        }
        ServerLevel level = resolveLevel(server, struct.dim);
        if (level == null) {
            return false;
        }
        cancelFor(struct);
        List<Change> changes = diff(level, struct, tag);
        for (int i = 0; i < changes.size(); i++) {
            applyChange(level, changes.get(i), i);
        }
        struct.current = slot;
        StructureData.get(server).setDirty();
        return true;
    }

    // ------------------------------------------------------------------ Transition animee (dissolution)

    /**
     * Duree visee d'une transition (~3 s) et debit maximal par tick. Le plafond prime : une grosse
     * structure n'accelere pas, elle s'anime simplement plus longtemps (ex. 300 000 blocs a 120/tick
     * = ~2 min 5 s), ce qui garde le TPS stable au lieu de poser 5 000 blocs d'un coup.
     */
    private static final int ANIM_TICKS = 60;
    private static final int MAX_PER_TICK = 120;

    /** Un bloc a changer : position absolue, etat cible, et NBT eventuel (coffre, panneau...). */
    private record Change(BlockPos pos, BlockState state, CompoundTag nbt) {
    }

    /** Une transition en cours : les changements restants, melanges au hasard. */
    private static final class Transition {
        final ServerLevel level;
        final StructureData.Struct struct;
        final List<Change> changes;
        final int perTick;
        int index;

        Transition(ServerLevel level, StructureData.Struct struct, List<Change> changes, int perTick) {
            this.level = level;
            this.struct = struct;
            this.changes = changes;
            this.perTick = perTick;
        }
    }

    private static final List<Transition> TRANSITIONS = new ArrayList<>(); // thread serveur uniquement

    public static boolean isTransitioning(StructureData.Struct struct) {
        return TRANSITIONS.stream().anyMatch(t -> t.struct == struct);
    }

    private static void cancelFor(StructureData.Struct struct) {
        TRANSITIONS.removeIf(t -> t.struct == struct);
    }

    /**
     * Pose l'etat demande en <b>dissolution aleatoire</b> : seuls les blocs qui changent reellement
     * sont modifies, dans un ordre aleatoire, etales dans le temps, avec particules et son.
     */
    public static boolean applyAnimated(MinecraftServer server, StructureData.Struct struct, int slot) {
        if (struct.anim == StructureData.Anim.INSTANT) {
            return apply(server, struct, slot);
        }
        CompoundTag tag = struct.state(slot);
        if (tag == null) {
            return false;
        }
        ServerLevel level = resolveLevel(server, struct.dim);
        if (level == null) {
            return false;
        }
        List<Change> changes = diff(level, struct, tag);
        cancelFor(struct);
        // L'etat est marque tout de suite : evite que la bascule auto ne relance la transition.
        struct.current = slot;
        StructureData.get(server).setDirty();
        if (changes.isEmpty()) {
            return true; // le monde correspond deja a cet etat
        }
        order(changes, struct);
        int perTick = Math.max(1, Math.min(MAX_PER_TICK, (changes.size() + ANIM_TICKS - 1) / ANIM_TICKS));
        TRANSITIONS.add(new Transition(level, struct, changes, perTick));
        return true;
    }

    /** Trie les blocs a poser selon le style d'animation de la structure. */
    private static void order(List<Change> changes, StructureData.Struct struct) {
        switch (struct.anim) {
            case BOTTOM_UP -> changes.sort(Comparator.comparingInt(c -> c.pos().getY()));
            case TOP_DOWN -> changes.sort(Comparator.comparingInt((Change c) -> c.pos().getY()).reversed());
            case CENTER_OUT -> changes.sort(Comparator.comparingDouble(c -> c.pos().distSqr(center(struct))));
            case OUTSIDE_IN -> changes.sort(Comparator.comparingDouble((Change c) -> c.pos().distSqr(center(struct))).reversed());
            default -> Collections.shuffle(changes); // RANDOM : dissolution
        }
    }

    private static BlockPos center(StructureData.Struct struct) {
        return struct.min.offset(struct.size.getX() / 2, struct.size.getY() / 2, struct.size.getZ() / 2);
    }

    /** A appeler chaque tick : avance les transitions en cours. */
    public static void tickTransitions() {
        if (TRANSITIONS.isEmpty()) {
            return;
        }
        TRANSITIONS.removeIf(t -> {
            int end = Math.min(t.changes.size(), t.index + t.perTick);
            for (; t.index < end; t.index++) {
                applyChange(t.level, t.changes.get(t.index), t.index);
            }
            return t.index >= t.changes.size();
        });
    }

    /** Pose un bloc + effets. Les particules montrent le bloc qui disparait (ou celui qui apparait). */
    private static void applyChange(ServerLevel level, Change c, int index) {
        BlockState old = level.getBlockState(c.pos());
        BlockState fx = old.isAir() ? c.state() : old;
        // Un bloc qui contient des objets les lache dans onRemove quand on le remplace (piedestal et
        // son livre, coffres...) : UPDATE_SUPPRESS_DROPS ne couvre que les drops du bloc lui-meme.
        // On neutralise donc la source : vider l'inventaire s'il expose Container, puis SUPPRIMER le
        // block entity. Sans block entity, onRemove n'a plus rien a lacher, quelle que soit la facon
        // dont le mod stocke son contenu (Container, capability, champ interne...).
        // Sans risque : le contenu est restaure depuis le NBT du schematique.
        BlockEntity oldBe = level.getBlockEntity(c.pos());
        if (oldBe != null) {
            if (oldBe instanceof Container container) {
                container.clearContent();
            }
            level.removeBlockEntity(c.pos());
        }
        level.setBlock(c.pos(), c.state(), PLACE_FLAGS);
        if (c.nbt() != null) {
            BlockEntity be = level.getBlockEntity(c.pos());
            if (be != null) {
                CompoundTag t = c.nbt().copy();
                t.putInt("x", c.pos().getX());
                t.putInt("y", c.pos().getY());
                t.putInt("z", c.pos().getZ());
                be.loadWithComponents(t, level.registryAccess());
                be.setChanged();
            }
        }
        if (!fx.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, fx),
                    c.pos().getX() + 0.5, c.pos().getY() + 0.5, c.pos().getZ() + 0.5,
                    6, 0.25, 0.25, 0.25, 0.0);
            // Son echantillonne : un bloc sur 8, sinon c'est un vacarme.
            if (index % 8 == 0) {
                level.playSound(null, c.pos(), fx.getSoundType().getPlaceSound(),
                        SoundSource.BLOCKS, 0.35f, 0.8f + level.getRandom().nextFloat() * 0.4f);
            }
        }
    }

    /**
     * Le changement est-il autorise par le filtre de la structure ? Sans filtre, tout passe. Avec un
     * filtre, on ne garde que les changements qui <b>concernent</b> un bloc filtre, d'un cote ou de
     * l'autre : ainsi une lanterne allumee -> eteinte (ou lanterne -> air) passe, mais le reste de la
     * zone n'est pas touche.
     */
    private static boolean allowed(StructureData.Struct struct, BlockState old, BlockState target) {
        if (struct.blockFilter.isEmpty()) {
            return true;
        }
        return struct.blockFilter.contains(blockId(old)) || struct.blockFilter.contains(blockId(target));
    }

    /** Identifiant de registre d'un bloc, ex. "minecraft:lantern". */
    public static String blockId(BlockState state) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.toString();
    }

    /**
     * Liste des blocs qui different entre le monde et le schematique. Lit directement le NBT du
     * StructureTemplate (format vanilla : "palette" + "blocks"), ce qui permet de poser dans notre
     * propre ordre au lieu du placement instantane.
     */
    private static List<Change> diff(ServerLevel level, StructureData.Struct struct, CompoundTag tag) {
        HolderGetter<Block> lookup = level.holderLookup(Registries.BLOCK);
        ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty() && tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            if (!palettes.isEmpty()) {
                paletteTag = palettes.getList(0);
            }
        }
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(lookup, paletteTag.getCompound(i)));
        }

        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            int state = b.getInt("state");
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            ListTag p = b.getList("pos", Tag.TAG_INT);
            if (p.size() < 3) {
                continue;
            }
            BlockPos abs = struct.min.offset(p.getInt(0), p.getInt(1), p.getInt(2));
            BlockState target = palette.get(state);
            BlockState old = level.getBlockState(abs);
            if (old.equals(target)) {
                continue; // deja au bon etat : on ne touche pas
            }
            if (!allowed(struct, old, target)) {
                continue; // filtre actif : ce bloc n'est pas concerne par la bascule
            }
            out.add(new Change(abs, target, b.contains("nbt", Tag.TAG_COMPOUND) ? b.getCompound("nbt") : null));
        }
        return out;
    }

    // ------------------------------------------------------------------ Bascule automatique

    /**
     * A appeler periodiquement : pour chaque structure en mode auto, pose l'etat correspondant a
     * l'heure du monde (nuit -> etat 2, jour -> etat 1). Ne fait rien tant que l'etat est deja bon.
     */
    public static void tickAuto(MinecraftServer server) {
        StructureData data = StructureData.get(server);
        for (StructureData.Struct struct : data.all()) {
            // Le mode auto suit le cycle jour/nuit : etat 1 le jour, etat 2 la nuit.
            // Les etats 3 a 5 eventuels restent pilotes a la main.
            if (!struct.auto || !struct.hasState(1) || !struct.hasState(2)) {
                continue;
            }
            ServerLevel level = resolveLevel(server, struct.dim);
            if (level == null || !level.isLoaded(struct.min)) {
                continue;
            }
            int wanted = isNight(level) ? 2 : 1;
            if (struct.current != wanted && !isTransitioning(struct)) {
                applyAnimated(server, struct, wanted); // dissolution aleatoire
            }
        }
    }

    /**
     * A appeler periodiquement : fait apparaitre / disparaitre les marchands selon l'etat courant de
     * leur structure. Les PNJ ne sont pas sauvegardes, ils sont donc recrees ici apres un redemarrage.
     */
    public static void syncShopNpcs(MinecraftServer server) {
        StructureData data = StructureData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            // Recense les marchands presents, par structure (et supprime les doublons).
            Map<String, com.utopia.entity.ShopNpc> present = new java.util.HashMap<>();
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e instanceof com.utopia.entity.ShopNpc npc) {
                    if (present.putIfAbsent(npc.structName(), npc) != null) {
                        npc.discard();
                    }
                }
            }
            for (StructureData.Struct st : data.all()) {
                ServerLevel target = resolveLevel(server, st.dim);
                if (target != level) {
                    continue;
                }
                com.utopia.entity.ShopNpc npc = present.remove(st.name);
                boolean wanted = st.npcEnabled && st.npcPos != null && st.current == st.npcState;
                if (!wanted) {
                    if (npc != null) {
                        npc.discard();
                    }
                    continue;
                }
                if (!level.isLoaded(st.npcPos)) {
                    continue; // zone non chargee : on retentera
                }
                if (npc == null || npc.isRemoved()) {
                    npc = new com.utopia.entity.ShopNpc(com.utopia.entity.UtopiaEntities.SHOP_NPC.get(), level);
                    npc.setStructName(st.name);
                    npc.moveTo(st.npcPos.getX() + 0.5, st.npcPos.getY(), st.npcPos.getZ() + 0.5, 0.0f, 0.0f);
                    npc.applyShop(st);
                    level.addFreshEntity(npc);
                } else {
                    npc.applyShop(st); // idempotent : ne synchronise que si ca change
                }
            }
            // Marchands restants : structures supprimees ou renommees.
            present.values().forEach(net.minecraft.world.entity.Entity::discard);
        }
    }

    // ------------------------------------------------------------------ Skin par URL

    /** Hote autorise par authlib cote client : toute autre URL de skin serait ignoree. */
    private static final String SKIN_HOST = "textures.minecraft.net";
    /** Caracteres acceptes dans une URL de skin (evite toute injection dans le JSON). */
    private static final java.util.regex.Pattern SKIN_URL_OK =
            java.util.regex.Pattern.compile("[A-Za-z0-9:/._-]+");

    /**
     * Construit la valeur de propriete "textures" (JSON encode en base64) a partir d'une URL de skin
     * ou d'un simple hash. Renvoie null si l'entree n'est pas exploitable.
     *
     * <p>Seul {@code textures.minecraft.net} est accepte : c'est le seul domaine que le client
     * autorise (c'est aussi ce que fournissent NameMC, MineSkin, etc.).
     */
    public static String skinValueFromUrl(String input) {
        if (input == null) {
            return null;
        }
        String url = input.trim();
        if (url.isEmpty() || !SKIN_URL_OK.matcher(url).matches()) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + SKIN_HOST + "/texture/" + url; // hash seul
        }
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null || !host.equalsIgnoreCase(SKIN_HOST)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Nuit minecraftienne (approximativement de 13000 a 23000). */
    public static boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    // ------------------------------------------------------------------ Apercu (particules rouges)

    /** Affiche la boite de selection en cours (12 aretes rouges) pour chaque joueur qui selectionne. */
    public static void renderSelections(MinecraftServer server) {
        if (CORNERS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, BlockPos[]> e : CORNERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                continue;
            }
            BlockPos[] c = e.getValue();
            ServerLevel level = player.serverLevel();
            if (c[0] != null && c[1] != null) {
                drawBox(level, player, c[0], c[1]);
            } else if (c[0] != null) {
                vertex(level, player, c[0]);
            } else if (c[1] != null) {
                vertex(level, player, c[1]);
            }
        }
    }

    /** Affiche la zone d'une structure existante (apercu ponctuel). */
    public static void showZone(ServerPlayer player, StructureData.Struct struct) {
        BlockPos max = struct.min.offset(struct.size.getX() - 1, struct.size.getY() - 1, struct.size.getZ() - 1);
        drawBox(player.serverLevel(), player, struct.min, max);
    }

    private static void drawBox(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b) {
        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;
        line(level, player, x0, y0, z0, x0, y1, z0);
        line(level, player, x1, y0, z0, x1, y1, z0);
        line(level, player, x1, y0, z1, x1, y1, z1);
        line(level, player, x0, y0, z1, x0, y1, z1);
        line(level, player, x0, y0, z0, x1, y0, z0);
        line(level, player, x1, y0, z0, x1, y0, z1);
        line(level, player, x1, y0, z1, x0, y0, z1);
        line(level, player, x0, y0, z1, x0, y0, z0);
        line(level, player, x0, y1, z0, x1, y1, z0);
        line(level, player, x1, y1, z0, x1, y1, z1);
        line(level, player, x1, y1, z1, x0, y1, z1);
        line(level, player, x0, y1, z1, x0, y1, z0);
        vertex(level, player, a);
        vertex(level, player, b);
    }

    /** Trace une arete en semant des particules (pas adaptatif, borne pour les grandes zones). */
    private static void line(ServerLevel level, ServerPlayer player,
                             double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.min(64, Math.max(2, len));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            level.sendParticles(player, LINE, true,
                    x0 + dx * t, y0 + dy * t, z0 + dz * t, 1, 0, 0, 0, 0);
        }
    }

    private static void vertex(ServerLevel level, ServerPlayer player, BlockPos pos) {
        level.sendParticles(player, VERTEX, true,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim == null ? "" : dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }
}
