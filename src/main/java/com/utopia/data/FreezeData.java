package com.utopia.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Le gel global des fonds : l'interrupteur, son historique, et ce qu'il a empeche.
 *
 * <p>L'historique n'est pas un luxe. Un salaire calcule a la reconnexion d'un joueur doit savoir si
 * l'echeance manquee tombait pendant un gel, et non seulement si le gel est actif maintenant :
 * sans les periodes, un joueur revenu apres le degel se ferait payer des journees que le blocage
 * avait justement annulees.
 */
public final class FreezeData extends SavedData {

    private static final String ID = "utopia_freeze";

    public static final SavedData.Factory<FreezeData> FACTORY =
            new SavedData.Factory<>(FreezeData::new, FreezeData::load, null);

    /** Lignes de journal conservees : de quoi retracer un scenario, pas tenir une comptabilite. */
    public static final int MAX_LOG = 400;

    /** Une periode de gel. {@code end} vaut 0 tant qu'elle dure encore. */
    public record Period(long start, long end) {

        public boolean contains(long millis) {
            return millis >= start && (end == 0L || millis <= end);
        }
    }

    private boolean frozen;
    private final List<Period> periods = new ArrayList<>();
    /** Echeances suspendues et pas encore annoncees, par joueur. */
    private final Map<UUID, Integer> pendingNotices = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();

    public FreezeData() {
    }

    public static FreezeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean frozen() {
        return frozen;
    }

    /** Ouvre ou referme une periode de gel. Renvoie vrai si l'etat a reellement change. */
    public boolean setFrozen(boolean value, long millis) {
        if (frozen == value) {
            return false;
        }
        frozen = value;
        if (value) {
            periods.add(new Period(millis, 0L));
        } else {
            for (int i = periods.size() - 1; i >= 0; i--) {
                Period p = periods.get(i);
                if (p.end() == 0L) {
                    periods.set(i, new Period(p.start(), millis));
                    break;
                }
            }
        }
        setDirty();
        return true;
    }

    /** Le gel etait-il actif a cet instant ? C'est la question que pose un rattrapage d'echeance. */
    public boolean wasFrozenAt(long millis) {
        for (Period p : periods) {
            if (p.contains(millis)) {
                return true;
            }
        }
        return false;
    }

    /** Debut de la periode de gel en cours, ou 0 si rien n'est gele. */
    public long currentStart() {
        for (int i = periods.size() - 1; i >= 0; i--) {
            if (periods.get(i).end() == 0L) {
                return periods.get(i).start();
            }
        }
        return 0L;
    }

    public List<Period> periods() {
        return java.util.Collections.unmodifiableList(periods);
    }

    // -------- Notifications d'echeances suspendues --------

    /** Une echeance de plus n'a pas pu etre versee a ce joueur. */
    public void addPendingNotice(UUID player) {
        pendingNotices.merge(player, 1, Integer::sum);
        setDirty();
    }

    public int pendingNotices(UUID player) {
        return pendingNotices.getOrDefault(player, 0);
    }

    /** Consomme les avis en attente : ils ne sont annonces qu'une fois. */
    public int takePendingNotices(UUID player) {
        Integer n = pendingNotices.remove(player);
        if (n != null) {
            setDirty();
        }
        return n == null ? 0 : n;
    }

    // -------- Journal --------

    public void log(String line) {
        log.add(0, com.utopia.job.JobManager.stamp(System.currentTimeMillis()) + " " + line);
        while (log.size() > MAX_LOG) {
            log.remove(log.size() - 1);
        }
        setDirty();
    }

    public List<String> log() {
        return java.util.Collections.unmodifiableList(log);
    }

    // -------- Serialisation --------

    public static FreezeData load(CompoundTag tag, HolderLookup.Provider registries) {
        FreezeData data = new FreezeData();
        data.frozen = tag.getBoolean("frozen");
        ListTag list = tag.getList("periods", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            data.periods.add(new Period(p.getLong("start"), p.getLong("end")));
        }
        ListTag notices = tag.getList("notices", Tag.TAG_COMPOUND);
        for (int i = 0; i < notices.size(); i++) {
            CompoundTag n = notices.getCompound(i);
            try {
                data.pendingNotices.put(UUID.fromString(n.getString("uuid")), n.getInt("count"));
            } catch (IllegalArgumentException ignored) {
                // uuid corrompu : l'avis est perdu, le gel reste correct
            }
        }
        ListTag lines = tag.getList("log", Tag.TAG_STRING);
        for (int i = 0; i < lines.size(); i++) {
            data.log.add(lines.getString(i));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("frozen", frozen);
        ListTag list = new ListTag();
        for (Period p : periods) {
            CompoundTag c = new CompoundTag();
            c.putLong("start", p.start());
            c.putLong("end", p.end());
            list.add(c);
        }
        tag.put("periods", list);

        ListTag notices = new ListTag();
        for (Map.Entry<UUID, Integer> e : pendingNotices.entrySet()) {
            CompoundTag n = new CompoundTag();
            n.putString("uuid", e.getKey().toString());
            n.putInt("count", e.getValue());
            notices.add(n);
        }
        tag.put("notices", notices);

        ListTag lines = new ListTag();
        for (String line : log) {
            lines.add(net.minecraft.nbt.StringTag.valueOf(line));
        }
        tag.put("log", lines);
        return tag;
    }
}
