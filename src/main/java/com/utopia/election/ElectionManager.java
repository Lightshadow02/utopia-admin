package com.utopia.election;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import it.unimi.dsi.fastutil.ints.IntList;

import com.utopia.data.ElectionData;
import com.utopia.data.ElectionData.Election;
import com.utopia.data.ElectionData.Status;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

/**
 * Logique du systeme d'elections : creation/configuration, lancement, votes (un par joueur, modifiable),
 * cloture (auto ou forcee), ceremonie des resultats (hologramme + chat + feux d'artifice) et mode test.
 */
public final class ElectionManager {

    public static final int DEFAULT_DURATION_MIN = 1440; // 24 h
    public static final int MIN_DURATION_MIN = 1;

    // Feux d'artifice (cf. cahier des charges).
    private static final int FW_COUNT = 8;
    private static final double FW_SPREAD = 10.0;
    private static final int FW_DELAY_TICKS = 8; // ~0,4 s
    private static final int[] FW_COLORS = { 0xFFD700, 0xFFFFFF, 0xFFFF00, 0x66FF33 };

    private static final String HOLO_TAG = "utopiaElectionHolo";
    private static final String HOLO_TEST = "utopiaElectionHoloTest";
    private static final double LINE_GAP = 0.30;
    private static final int BAR_LEN = 20;

    /** Duree de l'animation de depouillement (les barres se remplissent) avant la revelation. */
    private static final int REVEAL_TICKS = 400; // 20 s

    public static final String[] FAKE_CANDIDATES = { "Alice", "Bob", "Charlie" };

    // Ordonnanceur de feux d'artifice.
    private static ServerLevel fwLevel;
    private static double fwX;
    private static double fwY;
    private static double fwZ;
    private static int fwRemaining;
    private static int fwTimer;
    private static final Random RNG = new Random();

    // Animation de depouillement en cours.
    private static boolean revealing;
    private static int revealTicks;
    private static MinecraftServer revealServer;
    private static String revealName;
    private static boolean revealTest;
    private static List<Scored> revealFinal;
    private static ArmorStand revealTitleStand;
    private static List<ArmorStand> revealCandidateStands;

    private ElectionManager() {
    }

    // ============================================================
    //  Cycle de vie
    // ============================================================

    public static Election create(MinecraftServer server, String name, int durationMinutes) {
        Election el = new Election(name, Math.max(MIN_DURATION_MIN, durationMinutes));
        ElectionData.get(server).setCurrent(el);
        removeHolograms(server, false); // nettoie un eventuel ancien hologramme
        return el;
    }

    public static boolean addCandidate(MinecraftServer server, String candidate) {
        Election el = ElectionData.get(server).current();
        if (el == null || el.status != Status.SETUP || candidate.isBlank()
                || el.candidates.stream().anyMatch(c -> c.equalsIgnoreCase(candidate))) {
            return false;
        }
        el.candidates.add(candidate.trim());
        ElectionData.get(server).setDirty();
        return true;
    }

    public static void removeCandidate(MinecraftServer server, String candidate) {
        Election el = ElectionData.get(server).current();
        if (el != null && el.status == Status.SETUP) {
            el.candidates.removeIf(c -> c.equalsIgnoreCase(candidate));
            ElectionData.get(server).setDirty();
        }
    }

    public enum StartResult { OK, NO_ELECTION, NOT_SETUP, NOT_ENOUGH_CANDIDATES }

    public static StartResult start(MinecraftServer server) {
        Election el = ElectionData.get(server).current();
        if (el == null) {
            return StartResult.NO_ELECTION;
        }
        if (el.status != Status.SETUP) {
            return StartResult.NOT_SETUP;
        }
        if (el.candidates.size() < 2) {
            return StartResult.NOT_ENOUGH_CANDIDATES;
        }
        el.status = Status.OPEN;
        el.startMillis = System.currentTimeMillis();
        el.endMillis = el.startMillis + (long) el.durationMinutes * 60_000L;
        ElectionData.get(server).setDirty();
        broadcast(server, Component.literal("=================================")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD)));
        broadcast(server, Component.literal("Election ouverte : " + el.name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        broadcast(server, Component.literal("Votez avec /vote (modifiable jusqu'a la cloture). Duree : "
                + Messages.formatDuration((long) el.durationMinutes * 60L))
                .withStyle(s -> s.withColor(ChatFormatting.YELLOW)));
        broadcast(server, Component.literal("=================================")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD)));
        return StartResult.OK;
    }

    public static void cancel(MinecraftServer server) {
        Election el = ElectionData.get(server).current();
        if (el == null || el.status != Status.OPEN) {
            return;
        }
        el.status = Status.CANCELLED;
        ElectionData.get(server).setDirty();
        removeHolograms(server, false);
        broadcast(server, Component.literal("L'election \"" + el.name + "\" a ete ANNULEE (aucun resultat).")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)));
    }

    /** Cloture normale : passe en CLOSED et lance la ceremonie. */
    public static void close(MinecraftServer server) {
        Election el = ElectionData.get(server).current();
        if (el == null || el.status != Status.OPEN) {
            return;
        }
        el.status = Status.CLOSED;
        el.endMillis = System.currentTimeMillis();
        ElectionData.get(server).setDirty();
        ceremony(server, el);
    }

    // ============================================================
    //  Votes
    // ============================================================

    public enum VoteResult { OK_NEW, OK_CHANGED, NO_ELECTION, NOT_OPEN, INVALID }

    public static VoteResult vote(ServerPlayer player, String candidate) {
        Election el = ElectionData.get(player.server).current();
        if (el == null) {
            return VoteResult.NO_ELECTION;
        }
        if (el.status != Status.OPEN) {
            return VoteResult.NOT_OPEN;
        }
        String match = el.candidates.stream().filter(c -> c.equalsIgnoreCase(candidate)).findFirst().orElse(null);
        if (match == null) {
            return VoteResult.INVALID;
        }
        boolean changed = el.votes.containsKey(player.getUUID());
        el.votes.put(player.getUUID(), match);
        ElectionData.get(player.server).setDirty();
        return changed ? VoteResult.OK_CHANGED : VoteResult.OK_NEW;
    }

    // ============================================================
    //  Scores
    // ============================================================

    /** Une ligne de resultat : nom, nombre de voix, pourcentage. */
    public record Scored(String name, int votes, int percent) {
    }

    /** Scores tries du plus BAS au plus HAUT (gagnant en dernier). */
    public static List<Scored> scores(Election el) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String c : el.candidates) {
            counts.put(c, 0);
        }
        for (String voted : el.votes.values()) {
            counts.merge(voted, 1, Integer::sum);
        }
        int total = el.votes.size();
        List<Scored> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int pct = total == 0 ? 0 : Math.round(e.getValue() * 100f / total);
            list.add(new Scored(e.getKey(), e.getValue(), pct));
        }
        list.sort(Comparator.comparingInt(Scored::votes));
        return list;
    }

    public static int totalVotes(Election el) {
        return el.votes.size();
    }

    // ============================================================
    //  Ceremonie
    // ============================================================

    private static void ceremony(MinecraftServer server, Election el) {
        startReveal(server, el.name, scores(el), false);
    }

    /**
     * Lance l'animation de depouillement (~20 s) : les barres se remplissent progressivement, puis la
     * revelation finale (gagnant en or) declenche l'annonce chat et les feux d'artifice. Si l'hologramme
     * n'est pas configure, on passe directement a l'annonce chat + feux.
     */
    private static void startReveal(MinecraftServer server, String name, List<Scored> finalScored, boolean test) {
        ElectionData data = ElectionData.get(server);
        ServerLevel level = data.holoConfigured() ? resolveLevel(server, data.holoDim()) : null;
        if (level == null) {
            announceChat(server, name, finalScored, test);
            startFireworks(server);
            return;
        }
        removeHolograms(server, false); // repart d'un hologramme propre

        double cx = data.holoX();
        double cz = data.holoZ();
        int total = finalScored.stream().mapToInt(Scored::votes).sum();
        int nLines = (test ? 1 : 0) + 2 + finalScored.size();
        double topY = data.holoY() + (nLines - 1) * LINE_GAP;
        int idx = 0;
        if (test) {
            spawnLine(level, cx, topY - idx++ * LINE_GAP, cz,
                    Component.literal("[TEST]").withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)), true);
        }
        revealTitleStand = spawnLine(level, cx, topY - idx++ * LINE_GAP, cz, titleAnim(name, 0f), test);
        spawnLine(level, cx, topY - idx++ * LINE_GAP, cz,
                Component.literal(total + " votant(s)").withStyle(s -> s.withColor(ChatFormatting.GRAY)), test);
        revealCandidateStands = new ArrayList<>();
        for (Scored sc : finalScored) {
            revealCandidateStands.add(spawnLine(level, cx, topY - idx++ * LINE_GAP, cz,
                    candidateBar(sc.name(), 0, false), test));
        }

        revealServer = server;
        revealName = name;
        revealTest = test;
        revealFinal = finalScored;
        revealTicks = REVEAL_TICKS;
        revealing = true;
    }

    private static Component titleAnim(String name, float progress) {
        return Component.literal("Depouillement... " + Math.round(progress * 100) + "%")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
    }

    private static void tickReveal() {
        if (!revealing) {
            return;
        }
        revealTicks--;
        float progress = Math.min(1f, 1f - (float) revealTicks / REVEAL_TICKS);
        if (revealTicks > 0) {
            if (revealTicks % 4 == 0) { // ~5 maj/s : suffisant et leger
                setName(revealTitleStand, titleAnim(revealName, progress));
                for (int i = 0; i < revealCandidateStands.size(); i++) {
                    Scored sc = revealFinal.get(i);
                    setName(revealCandidateStands.get(i), candidateBar(sc.name(), Math.round(sc.percent() * progress), false));
                }
            }
            return;
        }
        // Revelation finale.
        revealing = false;
        setName(revealTitleStand, Component.literal("RESULTATS : " + revealName)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        for (int i = 0; i < revealCandidateStands.size(); i++) {
            Scored sc = revealFinal.get(i);
            boolean winner = i == revealFinal.size() - 1 && sc.votes() > 0;
            setName(revealCandidateStands.get(i), candidateBar(sc.name(), sc.percent(), winner));
        }
        announceChat(revealServer, revealName, revealFinal, revealTest);
        startFireworks(revealServer);
    }

    private static void setName(ArmorStand stand, Component text) {
        if (stand != null && !stand.isRemoved()) {
            stand.setCustomName(text);
            stand.setCustomNameVisible(true);
        }
    }

    private static void announceChat(MinecraftServer server, String name, List<Scored> scored, boolean test) {
        Scored winner = scored.isEmpty() ? null : scored.get(scored.size() - 1);
        boolean tie = scored.size() >= 2 && winner != null
                && scored.get(scored.size() - 2).votes() == winner.votes() && winner.votes() > 0;
        Component bar = Component.literal("==================================")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD));
        sendCeremony(server, test, bar);
        sendCeremony(server, test, Component.literal((test ? "[TEST] " : "") + "Resultats de l'election : " + name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        sendCeremony(server, test, bar);
        if (winner == null || winner.votes() == 0) {
            sendCeremony(server, test, Component.literal("Aucun vote exprime.")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY)));
        } else if (tie) {
            sendCeremony(server, test, Component.literal("EGALITE en tete avec " + winner.percent()
                    + "% des voix ! Departage requis.").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withBold(true)));
        } else {
            sendCeremony(server, test, Component.literal("*** Elu(e) : ")
                    .withStyle(s -> s.withColor(ChatFormatting.YELLOW))
                    .append(Component.literal(winner.name()).withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)))
                    .append(Component.literal(" avec " + winner.percent() + "% des voix ! ***")
                            .withStyle(s -> s.withColor(ChatFormatting.YELLOW))));
        }
        sendCeremony(server, test, bar);
    }

    // ============================================================
    //  Hologramme
    // ============================================================

    /** (Re)cree l'hologramme des resultats a la position sauvegardee. Renvoie false si non configure. */
    public static boolean spawnHologram(MinecraftServer server, String name, List<Scored> scored, boolean test) {
        ElectionData data = ElectionData.get(server);
        if (!data.holoConfigured()) {
            return false;
        }
        ServerLevel level = resolveLevel(server, data.holoDim());
        if (level == null) {
            return false;
        }
        removeHolograms(server, test); // remplace l'hologramme du meme type
        List<Component> lines = buildLines(name, scored, test);
        double cx = data.holoX();
        double cz = data.holoZ();
        double topY = data.holoY() + (lines.size() - 1) * LINE_GAP;
        for (int i = 0; i < lines.size(); i++) {
            spawnLine(level, cx, topY - i * LINE_GAP, cz, lines.get(i), test);
        }
        return true;
    }

    private static List<Component> buildLines(String name, List<Scored> scored, boolean test) {
        List<Component> lines = new ArrayList<>();
        if (test) {
            lines.add(Component.literal("[TEST]").withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)));
        }
        lines.add(Component.literal("RESULTATS : " + name)
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
        int total = scored.stream().mapToInt(Scored::votes).sum();
        lines.add(Component.literal(total + " votant(s)").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
        // Tries du plus bas au plus haut : le gagnant (dernier) est mis en valeur.
        for (int i = 0; i < scored.size(); i++) {
            Scored sc = scored.get(i);
            boolean isWinner = i == scored.size() - 1 && sc.votes() > 0;
            lines.add(candidateLine(sc, isWinner));
        }
        return lines;
    }

    private static Component candidateLine(Scored sc, boolean winner) {
        return candidateBar(sc.name(), sc.percent(), winner);
    }

    /** Une ligne candidat : nom + barre de progression + pourcentage (gagnant mis en valeur en or). */
    private static Component candidateBar(String name, int pct, boolean winner) {
        int filled = Math.round(pct / 100f * BAR_LEN);
        String full = "|".repeat(Math.max(0, filled));
        String empty = ".".repeat(Math.max(0, BAR_LEN - filled));
        ChatFormatting nameCol = winner ? ChatFormatting.GOLD : ChatFormatting.WHITE;
        return Component.literal((winner ? "* " : "") + name + " ")
                .withStyle(s -> s.withColor(nameCol).withBold(winner))
                .append(Component.literal("[").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY)))
                .append(Component.literal(full).withStyle(s -> s.withColor(winner ? ChatFormatting.GOLD : ChatFormatting.GREEN)))
                .append(Component.literal(empty).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY)))
                .append(Component.literal("] " + pct + "%" + (winner ? " *" : ""))
                        .withStyle(s -> s.withColor(nameCol).withBold(winner)));
    }

    private static ArmorStand spawnLine(ServerLevel level, double x, double y, double z, Component text, boolean test) {
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
        stand.getPersistentData().putBoolean(HOLO_TAG, true);
        if (test) {
            stand.getPersistentData().putBoolean(HOLO_TEST, true);
        }
        stand.setPos(x, y, z);
        level.addFreshEntity(stand);
        return stand;
    }

    /** Supprime les hologrammes d'election. {@code onlyTest} : ne retire que les hologrammes de test. */
    public static void removeHolograms(MinecraftServer server, boolean onlyTest) {
        if (!onlyTest || revealTest) {
            revealing = false; // annule une animation de depouillement en cours
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ArmorStand && e.getPersistentData().getBoolean(HOLO_TAG)
                        && (!onlyTest || e.getPersistentData().getBoolean(HOLO_TEST))) {
                    e.discard();
                }
            }
        }
    }

    // ============================================================
    //  Feux d'artifice
    // ============================================================

    public static void startFireworks(MinecraftServer server) {
        ElectionData data = ElectionData.get(server);
        if (!data.holoConfigured()) {
            return;
        }
        ServerLevel level = resolveLevel(server, data.holoDim());
        if (level == null) {
            return;
        }
        fwLevel = level;
        fwX = data.holoX();
        fwY = data.holoY();
        fwZ = data.holoZ();
        fwRemaining = FW_COUNT;
        fwTimer = 0;
    }

    private static void tickFireworks() {
        if (fwRemaining <= 0 || fwLevel == null) {
            return;
        }
        if (fwTimer > 0) {
            fwTimer--;
            return;
        }
        spawnFirework();
        fwRemaining--;
        fwTimer = FW_DELAY_TICKS;
        if (fwRemaining <= 0) {
            fwLevel = null;
        }
    }

    private static void spawnFirework() {
        double ox = fwX + (RNG.nextDouble() * 2 - 1) * FW_SPREAD;
        double oz = fwZ + (RNG.nextDouble() * 2 - 1) * FW_SPREAD;
        double oy = fwY + 1.0;
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        FireworkExplosion.Shape[] shapes = FireworkExplosion.Shape.values();
        List<FireworkExplosion> explosions = new ArrayList<>();
        int bursts = 1 + RNG.nextInt(2);
        for (int i = 0; i < bursts; i++) {
            int c1 = FW_COLORS[RNG.nextInt(FW_COLORS.length)];
            int c2 = FW_COLORS[RNG.nextInt(FW_COLORS.length)];
            explosions.add(new FireworkExplosion(shapes[RNG.nextInt(shapes.length)],
                    IntList.of(c1, c2), IntList.of(0xFFFFFF), true, true));
        }
        rocket.set(DataComponents.FIREWORKS, new Fireworks(2, explosions));
        fwLevel.addFreshEntity(new FireworkRocketEntity(fwLevel, ox, oy, oz, rocket));
    }

    // ============================================================
    //  Mode test / preview (admin only)
    // ============================================================

    public static boolean previewEmpty(MinecraftServer server) {
        return spawnHologram(server, "Position OK", List.of(), true);
    }

    public static boolean previewFakeResults(MinecraftServer server) {
        return spawnHologram(server, "Election (TEST)", fakeScores(), true);
    }

    public static void previewFireworks(MinecraftServer server) {
        startFireworks(server);
    }

    public static boolean previewFull(MinecraftServer server) {
        if (!ElectionData.get(server).holoConfigured()) {
            return false;
        }
        startReveal(server, "Election (TEST)", fakeScores(), true);
        return true;
    }

    public static void removePreview(MinecraftServer server) {
        removeHolograms(server, true);
    }

    private static List<Scored> fakeScores() {
        int[] pct = { 52, 31, 17 };
        List<Scored> list = new ArrayList<>();
        for (int i = 0; i < FAKE_CANDIDATES.length; i++) {
            list.add(new Scored(FAKE_CANDIDATES[i], pct[i], pct[i]));
        }
        list.sort(Comparator.comparingInt(Scored::votes));
        return list;
    }

    // ============================================================
    //  Tick serveur
    // ============================================================

    public static void tick(MinecraftServer server) {
        tickFireworks();
        tickReveal();
        Election el = ElectionData.get(server).current();
        if (el != null && el.status == Status.OPEN && System.currentTimeMillis() >= el.endMillis) {
            close(server);
        }
    }

    // ============================================================
    //  Utilitaires
    // ============================================================

    private static ServerLevel resolveLevel(MinecraftServer server, String dim) {
        ResourceLocation loc = ResourceLocation.tryParse(dim);
        return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    private static void broadcast(MinecraftServer server, Component c) {
        server.getPlayerList().broadcastSystemMessage(c, false);
    }

    /** Envoie un message de ceremonie : a tous, ou seulement aux op si {@code adminOnly} (mode test). */
    private static void sendCeremony(MinecraftServer server, boolean adminOnly, Component c) {
        if (!adminOnly) {
            broadcast(server, c);
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) {
                p.sendSystemMessage(c);
            }
        }
    }
}
