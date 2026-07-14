package com.utopia.entity;

import java.util.List;

import com.utopia.structure.ShopMenus;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Marchand d'une structure : PNJ rendu avec le modele joueur, present uniquement dans l'un des deux
 * etats de la structure. Clic droit -> boutique (achat / revente en Utopieces, stock illimite).
 *
 * <p>Inerte : ni IA, ni gravite, ni degats. Non sauvegarde : recree par la synchro des structures.
 */
public class ShopNpc extends LivingEntity implements SkinNpc {

    private static final EntityDataAccessor<String> SHOP_NAME =
            SynchedEntityData.defineId(ShopNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_VALUE =
            SynchedEntityData.defineId(ShopNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE =
            SynchedEntityData.defineId(ShopNpc.class, EntityDataSerializers.STRING);

    /** Nom de la structure a laquelle ce marchand appartient (cote serveur). */
    private String structName = "";

    public ShopNpc(EntityType<? extends ShopNpc> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setSilent(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SHOP_NAME, "");
        builder.define(SKIN_VALUE, "");
        builder.define(SKIN_SIGNATURE, "");
    }

    // -------- Etat --------

    @Override
    public String ownerName() {
        return this.entityData.get(SHOP_NAME);
    }

    @Override
    public String skinValue() {
        return this.entityData.get(SKIN_VALUE);
    }

    @Override
    public String skinSignature() {
        return this.entityData.get(SKIN_SIGNATURE);
    }

    public String structName() {
        return structName;
    }

    public void setStructName(String name) {
        this.structName = name == null ? "" : name;
    }

    /** Applique la configuration du marchand d'une structure (nom affiche + skin). */
    public void applyShop(com.utopia.data.StructureData.Struct struct) {
        this.entityData.set(SHOP_NAME, struct.npcName == null ? "" : struct.npcName);
        this.entityData.set(SKIN_VALUE, struct.npcSkinValue == null ? "" : struct.npcSkinValue);
        this.entityData.set(SKIN_SIGNATURE, struct.npcSkinSignature == null ? "" : struct.npcSkinSignature);
        this.setCustomName(net.minecraft.network.chat.Component.literal(
                struct.npcName == null ? "Marchand" : struct.npcName));
        this.setCustomNameVisible(true); // le nom du marchand est utile au joueur
    }

    // -------- Interaction : ouvre la boutique --------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        ShopMenus.openShop(sp, structName);
        return InteractionResult.CONSUME;
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
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return false; // recree par la synchro des structures
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
        tag.putString("structName", structName);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        structName = tag.getString("structName");
    }
}
