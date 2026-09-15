package com.utopia.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.utopia.data.PowerData;
import com.utopia.gui.Icons;
import com.utopia.util.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Le baton de mage : un objet qui lance des sorts de zone sur les creatures hostiles.
 *
 * <p>Le baton ne fait rien tout seul. Il ne repond qu'entre les mains de quelqu'un a qui /dieux a
 * accorde la capacite : un baton ramasse par terre, vole ou duplique reste un morceau de bois.
 *
 * <p>Les sorts ne touchent <b>que</b> ce qui est hostile. Un sort de zone qui frapperait les
 * joueurs, les betes d'elevage ou les PNJ du bourg serait ingerable en plein evenement, ou la
 * moitie du serveur se presse autour du lanceur.
 */
public final class MageStaff {

    /**
     * Ce qui sert de baton. Un simple baton, et non un batonnet de blaze : la marque ne survit pas
     * a un etabli, et un objet qu'on peut redemander ne doit rien valoir une fois fondu.
     */
    private static final net.minecraft.world.item.Item SUPPORT = Items.STICK;

    /** Marqueur du baton, dans les donnees libres de l'objet. Meme procede que les Utopieces. */
    private static final String MARQUEUR = "UtopiaBatonDeMage";
    /** Sort choisi, range dans l'objet : chaque baton garde le sien. */
    private static final String SORT = "UtopiaSort";

    /** Delai entre deux incantations, par joueur et en millisecondes. */
    private static final long REPOS_MS = 1200L;

    /**
     * Delai entre deux clics pris en compte. Maintenir le clic droit rejoue l'usage toutes les
     * quatre graduations : sans ce garde-fou, un refus s'ecrirait cinq fois par seconde et le sort
     * arme defilerait sous les doigts.
     */
    private static final long REPOS_CLIC_MS = 300L;

    /**
     * Cibles touchees au maximum par incantation. Au-dela, le sort ne se voit plus et chaque cible
     * coute des particules envoyees a tous les joueurs des alentours : un evenement se joue souvent
     * au milieu d'une horde, autant que le serveur y survive.
     */
    private static final int MAX_CIBLES = 12;

    private static final Map<UUID, Long> DERNIER_SORT = new HashMap<>();
    private static final Map<UUID, Long> DERNIER_CLIC = new HashMap<>();
    /** Graduation du serveur ou un menu du mod a pris le clic pour lui. */
    private static final Map<UUID, Integer> CLIC_ABSORBE = new HashMap<>();

    /** Un sort : sa portee, ses degats, et la facon dont il se montre. */
    public enum Sort {
        ONDE("Onde de choc", 7.0, 6.0f,
                "Repousse et blesse tout ce qui est hostile autour de toi"),
        FOUDRE("Foudre", 14.0, 9.0f,
                "Frappe les hostiles droit devant, en ligne"),
        BRASIER("Brasier", 6.0, 5.0f,
                "Embrase les hostiles alentour"),
        GEL("Gel", 8.0, 4.0f,
                "Ralentit et blesse les hostiles alentour");

        public final String label;
        public final double portee;
        public final float degats;
        public final String detail;

        Sort(String label, double portee, float degats, String detail) {
            this.label = label;
            this.portee = portee;
            this.degats = degats;
            this.detail = detail;
        }

        public Sort suivant() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private MageStaff() {
    }

    // ------------------------------------------------------------------ L'objet

    /** Fabrique un baton regle sur son premier sort. */
    public static ItemStack fabriquer() {
        ItemStack stack = new ItemStack(SUPPORT);
        poserSort(stack, Sort.ONDE);
        return stack;
    }

    public static boolean estUnBaton(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(SUPPORT)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(MARQUEUR);
    }

    private static Sort sortDe(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Sort.ONDE;
        }
        try {
            return Sort.valueOf(data.copyTag().getString(SORT));
        } catch (IllegalArgumentException e) {
            return Sort.ONDE; // sort disparu d'une version a l'autre
        }
    }

    private static void poserSort(ItemStack stack, Sort sort) {
        CompoundTag marque = new CompoundTag();
        marque.putBoolean(MARQUEUR, true);
        marque.putString(SORT, sort.name());
        CustomData.set(DataComponents.CUSTOM_DATA, stack, marque);
        habiller(stack, sort);
    }

    /** Le nom et la description suivent le sort choisi : le porteur doit savoir ce qu'il tient. */
    private static void habiller(ItemStack stack, Sort sort) {
        stack.set(DataComponents.CUSTOM_NAME,
                Icons.label("Baton de mage - " + sort.label, ChatFormatting.LIGHT_PURPLE));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Icons.lore(sort.detail, ChatFormatting.GRAY),
                Icons.lore("Clic droit : lancer le sort", ChatFormatting.DARK_GRAY),
                Icons.lore("Accroupi + clic droit : changer de sort", ChatFormatting.DARK_GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    // ------------------------------------------------------------------ L'incantation

    /**
     * Repond a un clic droit. Renvoie vrai si le baton a pris la main, auquel cas l'appelant annule
     * l'usage ordinaire de l'objet.
     */
    public static boolean onClicDroit(ServerPlayer joueur, ItemStack stack) {
        if (!estUnBaton(stack)) {
            return false;
        }
        long maintenant = System.currentTimeMillis();
        // Ce clic a-t-il deja servi a autre chose ? Quand le mod reprend un clic pour lui - borne
        // d'arcade, stand de marche, bloc d'auberge - le client l'ignore et enchaine sur un clic
        // d'objet : le sort partirait par-dessus le menu qui vient de s'ouvrir.
        Integer absorbe = CLIC_ABSORBE.get(joueur.getUUID());
        if (absorbe != null && joueur.server.getTickCount() - absorbe <= 1) {
            return true;
        }
        Long dernierClic = DERNIER_CLIC.get(joueur.getUUID());
        if (dernierClic != null && maintenant - dernierClic < REPOS_CLIC_MS) {
            return true;
        }
        DERNIER_CLIC.put(joueur.getUUID(), maintenant);
        purger(maintenant);

        // Le droit vit sur la personne, pas sur l'objet : un baton ramasse ne sert a rien.
        if (!PowerData.get(joueur.server).a(joueur.getUUID(), Power.BATON_DE_MAGE)) {
            joueur.sendSystemMessage(Messages.warn("Ce baton ne repond pas entre tes mains."));
            return true;
        }
        if (joueur.isShiftKeyDown()) {
            Sort suivant = sortDe(stack).suivant();
            poserSort(stack, suivant);
            joueur.sendSystemMessage(Messages.info("Sort arme : " + suivant.label
                    + " - " + suivant.detail + "."));
            return true;
        }
        Long dernier = DERNIER_SORT.get(joueur.getUUID());
        if (dernier != null && maintenant - dernier < REPOS_MS) {
            // En repos : le clic est avale sans un mot. Un refus ecrit a chaque clic noierait le chat.
            return true;
        }
        DERNIER_SORT.put(joueur.getUUID(), maintenant);
        lancer(joueur, sortDe(stack));
        return true;
    }

    /**
     * Note que ce clic a deja servi a ouvrir un menu du mod : le baton doit le laisser passer.
     * Appele pour tout clic de bloc repris par le mod, refus de parcelle compris - on ne retient que
     * ceux d'un porteur de baton, sans quoi la table enflerait au rythme des refus de tout le monde.
     */
    public static void absorber(ServerPlayer joueur) {
        if (!estUnBaton(joueur.getMainHandItem()) && !estUnBaton(joueur.getOffhandItem())) {
            return;
        }
        CLIC_ABSORBE.put(joueur.getUUID(), joueur.server.getTickCount());
    }

    /** Le baton se donne et se reprend : la table des repos ne doit pas enfler avec le temps. */
    private static void purger(long maintenant) {
        if (DERNIER_CLIC.size() <= 64 && CLIC_ABSORBE.size() <= 64) {
            return;
        }
        DERNIER_CLIC.entrySet().removeIf(e -> maintenant - e.getValue() > REPOS_MS * 50);
        DERNIER_SORT.entrySet().removeIf(e -> maintenant - e.getValue() > REPOS_MS * 50);
        CLIC_ABSORBE.keySet().retainAll(DERNIER_CLIC.keySet());
    }

    public static void onLogout(ServerPlayer joueur) {
        DERNIER_SORT.remove(joueur.getUUID());
        DERNIER_CLIC.remove(joueur.getUUID());
        CLIC_ABSORBE.remove(joueur.getUUID());
    }

    private static void lancer(ServerPlayer joueur, Sort sort) {
        ServerLevel niveau = joueur.serverLevel();
        List<LivingEntity> cibles = ciblesDe(joueur, niveau, sort);

        switch (sort) {
            case ONDE -> onde(joueur, niveau, cibles);
            case FOUDRE -> foudre(joueur, niveau, cibles);
            case BRASIER -> brasier(joueur, niveau, cibles);
            case GEL -> gel(joueur, niveau, cibles);
        }

        if (cibles.isEmpty()) {
            joueur.sendSystemMessage(Messages.info(sort.label + " : rien d'hostile a portee."));
        }
    }

    /**
     * Ce que le sort peut toucher. Seules les creatures hostiles entrent : ni joueur, ni bete, ni
     * PNJ du mod. La ligne de vue est exigee, faute de quoi un sort traverserait les murs et
     * viderait la cave d'a cote.
     *
     * <p>Les cibles sont prises de la plus proche a la plus lointaine : quand la horde depasse le
     * plafond, ce qui saute est ce qui menacait le moins.
     */
    private static List<LivingEntity> ciblesDe(ServerPlayer joueur, ServerLevel niveau, Sort sort) {
        AABB zone = joueur.getBoundingBox().inflate(sort.portee);
        List<LivingEntity> cibles = new ArrayList<>();
        Vec3 regard = joueur.getLookAngle();
        double porteeCarree = sort.portee * sort.portee;
        for (Entity e : niveau.getEntities(joueur, zone)) {
            if (!(e instanceof LivingEntity vivant) || !(e instanceof Enemy)) {
                continue;
            }
            // Aucun PNJ du mod n'est hostile aujourd'hui, mais rien ne dit qu'aucun ne le deviendra,
            // et un PNJ fauche en plein evenement ne se replace pas d'un clic.
            if (e instanceof com.utopia.entity.SkinNpc || !vivant.isAlive()) {
                continue;
            }
            // La boite englobante est un cube : sans ce filtre, la portee serait plus longue dans les
            // coins que droit devant.
            if (vivant.distanceToSqr(joueur) > porteeCarree) {
                continue;
            }
            if (!joueur.hasLineOfSight(vivant)) {
                continue;
            }
            if (paisible(vivant, joueur)) {
                continue;
            }
            if (sort == Sort.FOUDRE) {
                // La foudre part droit devant : on ne garde que ce qui tient dans le cone du regard.
                Vec3 ecart = vivant.position().subtract(joueur.position());
                if (ecart.lengthSqr() > 1.0E-4 && regard.dot(ecart.normalize()) < 0.55) {
                    continue;
                }
            }
            cibles.add(vivant);
        }
        cibles.sort(java.util.Comparator.comparingDouble(c -> c.distanceToSqr(joueur)));
        return cibles.size() > MAX_CIBLES ? new ArrayList<>(cibles.subList(0, MAX_CIBLES)) : cibles;
    }

    /**
     * Vrai pour une creature qui porte l'etiquette hostile sans chercher noise a personne. Les
     * hoglins s'elevent, les piglins se troquent et se vengent en bande, les enderman et les
     * cochons-zombies ne bougent que si on les provoque : un sort de zone qui fauche tout cela
     * reglerait plus de comptes qu'il n'en ouvre.
     */
    private static boolean paisible(LivingEntity vivant, ServerPlayer joueur) {
        if (vivant instanceof net.minecraft.world.entity.NeutralMob neutre && !neutre.isAngry()) {
            return true;
        }
        // Le brute, lui, ne negocie jamais : il reste du gibier de sort en toutes circonstances.
        boolean duNether = vivant instanceof net.minecraft.world.entity.monster.hoglin.Hoglin
                || (vivant instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
                        && !(vivant instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute));
        return duNether && !(vivant instanceof net.minecraft.world.entity.Mob mob
                && mob.getTarget() == joueur);
    }

    private static void frapper(ServerPlayer joueur, LivingEntity cible, float degats) {
        // Degat magique indirect : le lanceur est credite de la mise a mort. Ce type de degat
        // traverse l'armure - c'est voulu, un sort n'a pas a buter sur une plaque de fer - mais la
        // resistance a la magie et les enchantements de protection jouent toujours.
        cible.hurt(cible.damageSources().indirectMagic(joueur, joueur), degats);
    }

    private static void anneau(ServerLevel niveau, Vec3 centre, ParticleOptions particule,
            double rayon, int points, double hauteur) {
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            niveau.sendParticles(particule,
                    centre.x + Math.cos(angle) * rayon,
                    centre.y + hauteur,
                    centre.z + Math.sin(angle) * rayon,
                    1, 0, 0, 0, 0);
        }
    }

    /**
     * Un eclair d'un point a l'autre. Chaque position demande son propre paquet, diffuse a tous les
     * joueurs alentour : six segments epais se lisent aussi bien que vingt fins, et coutent trois
     * fois moins cher au reseau.
     */
    private static void trait(ServerLevel niveau, Vec3 depart, Vec3 arrivee,
            ParticleOptions particule) {
        Vec3 delta = arrivee.subtract(depart);
        int pas = 6;
        for (int i = 0; i <= pas; i++) {
            Vec3 p = depart.add(delta.scale((double) i / pas));
            niveau.sendParticles(particule, p.x, p.y, p.z, 3, 0.08, 0.08, 0.08, 0.0);
        }
    }

    // ------------------------------------------------------------------ Les sorts

    private static void onde(ServerPlayer joueur, ServerLevel niveau, List<LivingEntity> cibles) {
        Vec3 centre = joueur.position();
        // Trois anneaux de rayon croissant : l'onde se lit comme une vague qui part du lanceur.
        for (int i = 1; i <= 3; i++) {
            anneau(niveau, centre, ParticleTypes.CLOUD, Sort.ONDE.portee * i / 3.0, 6 + 6 * i, 0.25);
        }
        niveau.sendParticles(ParticleTypes.SONIC_BOOM, centre.x, centre.y + 1.0, centre.z,
                1, 0, 0, 0, 0);
        niveau.playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.PLAYERS, 0.7f, 1.4f);
        for (LivingEntity cible : cibles) {
            frapper(joueur, cible, Sort.ONDE.degats);
            Vec3 ecart = cible.position().subtract(centre);
            // Une creature exactement sur le lanceur donnerait un vecteur nul, donc une poussee NaN.
            Vec3 pousse = (ecart.lengthSqr() < 1.0E-4 ? joueur.getLookAngle() : ecart.normalize())
                    .scale(1.1).add(0, 0.45, 0);
            cible.push(pousse.x, pousse.y, pousse.z);
            cible.hurtMarked = true; // sans quoi la poussee n'est jamais renvoyee au client
        }
    }

    private static void foudre(ServerPlayer joueur, ServerLevel niveau, List<LivingEntity> cibles) {
        Vec3 depart = joueur.getEyePosition();
        niveau.playSound(null, joueur.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(),
                SoundSource.PLAYERS, 0.6f, 1.6f);
        for (LivingEntity cible : cibles) {
            Vec3 coeur = cible.position().add(0, cible.getBbHeight() / 2, 0);
            trait(niveau, depart, coeur, ParticleTypes.ELECTRIC_SPARK);
            niveau.sendParticles(ParticleTypes.FLASH, coeur.x, coeur.y, coeur.z, 1, 0, 0, 0, 0);
            frapper(joueur, cible, Sort.FOUDRE.degats);
        }
    }

    private static void brasier(ServerPlayer joueur, ServerLevel niveau, List<LivingEntity> cibles) {
        niveau.playSound(null, joueur.blockPosition(), SoundEvents.FIRECHARGE_USE,
                SoundSource.PLAYERS, 0.8f, 0.8f);
        for (LivingEntity cible : cibles) {
            niveau.sendParticles(ParticleTypes.FLAME,
                    cible.getX(), cible.getY() + cible.getBbHeight() / 2, cible.getZ(),
                    25, 0.35, 0.5, 0.35, 0.02);
            niveau.sendParticles(ParticleTypes.LAVA,
                    cible.getX(), cible.getY(), cible.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
            frapper(joueur, cible, Sort.BRASIER.degats);
            // Une creature deja ignifugee ne prend pas feu : inutile d'insister, le degat a porte.
            if (!cible.fireImmune()) {
                cible.igniteForSeconds(6);
            }
        }
    }

    private static void gel(ServerPlayer joueur, ServerLevel niveau, List<LivingEntity> cibles) {
        anneau(niveau, joueur.position(), ParticleTypes.SNOWFLAKE, Sort.GEL.portee * 0.8, 24, 0.6);
        niveau.playSound(null, joueur.blockPosition(), SoundEvents.GLASS_BREAK,
                SoundSource.PLAYERS, 0.7f, 1.8f);
        for (LivingEntity cible : cibles) {
            niveau.sendParticles(ParticleTypes.SNOWFLAKE,
                    cible.getX(), cible.getY() + cible.getBbHeight() / 2, cible.getZ(),
                    20, 0.35, 0.5, 0.35, 0.01);
            frapper(joueur, cible, Sort.GEL.degats);
            cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 2));
            cible.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 120, 1));
        }
    }
}
