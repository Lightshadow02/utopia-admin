package com.utopia.entity;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Base commune des PNJ decoratifs du mod : une entite <b>inerte</b> (ni IA, ni gravite, ni degats, ni
 * poussee) rendue avec le modele joueur et un skin arbitraire, qui suit du regard le joueur le plus
 * proche et revient a une orientation de repos quand il n'y a plus personne.
 *
 * <p>Les PNJ ne sont volontairement <b>pas sauvegardes</b> avec le monde : ils sont recrees a partir
 * de leurs donnees persistantes par une synchronisation periodique, ce qui evite les doublons et les
 * PNJ orphelins apres un crash.
 */
public abstract class AbstractNpc extends LivingEntity implements SkinNpc {

    private static final EntityDataAccessor<String> NPC_NAME =
            SynchedEntityData.defineId(AbstractNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_VALUE =
            SynchedEntityData.defineId(AbstractNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE =
            SynchedEntityData.defineId(AbstractNpc.class, EntityDataSerializers.STRING);

    /** Cle de l'element (chantier, capitaine...) auquel ce PNJ est rattache, cote serveur. */
    private String ownerKey = "";
    /** Orientation reprise quand aucun joueur n'est a portee. */
    private float restYaw;

    protected AbstractNpc(EntityType<? extends AbstractNpc> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setSilent(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(NPC_NAME, "");
        builder.define(SKIN_VALUE, "");
        builder.define(SKIN_SIGNATURE, "");
    }

    // -------- Identite / apparence --------

    @Override
    public String ownerName() {
        return this.entityData.get(NPC_NAME);
    }

    @Override
    public String skinValue() {
        return this.entityData.get(SKIN_VALUE);
    }

    @Override
    public String skinSignature() {
        return this.entityData.get(SKIN_SIGNATURE);
    }

    public String ownerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String key) {
        this.ownerKey = key == null ? "" : key;
    }

    public float restYaw() {
        return restYaw;
    }

    public void setRestYaw(float restYaw) {
        this.restYaw = restYaw;
    }

    /**
     * Applique l'apparence du PNJ. Idempotent : les donnees synchronisees ne partent sur le reseau que
     * si elles changent reellement.
     */
    public void applyLook(String name, String skinValue, String skinSignature, boolean showName) {
        this.entityData.set(NPC_NAME, name == null ? "" : name);
        this.entityData.set(SKIN_VALUE, skinValue == null ? "" : skinValue);
        this.entityData.set(SKIN_SIGNATURE, skinSignature == null ? "" : skinSignature);
        if (showName && name != null && !name.isBlank()) {
            this.setCustomName(Component.literal(name));
            this.setCustomNameVisible(true);
        } else {
            this.setCustomName(null);
            this.setCustomNameVisible(false);
        }
    }

    @Override
    public void tick() {
        super.tick();
        NpcLook.faceNearestPlayer(this, restYaw);
    }

    // -------- PNJ inerte --------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
        // ne pousse personne
    }

    @Override
    protected void pushEntities() {
        // ne se fait pas pousser
    }

    @Override
    public boolean isPickable() {
        return true; // cliquable
    }

    @Override
    public boolean shouldBeSaved() {
        return false; // recree par la synchronisation (evite les doublons)
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return List.of();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        // pas d'equipement
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ownerKey", ownerKey);
        tag.putFloat("restYaw", restYaw);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ownerKey = tag.getString("ownerKey");
        restYaw = tag.getFloat("restYaw");
    }
}
