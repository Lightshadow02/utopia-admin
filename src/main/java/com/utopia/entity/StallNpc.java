package com.utopia.entity;

import java.util.List;

import com.utopia.data.MarketData;
import com.utopia.market.MarketMenus;

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
 * Le "vendeur" d'un stand de marche : un PNJ rendu avec le modele joueur. Il affiche le skin par
 * defaut (Steve) tant que le stand est libre, puis le skin du proprietaire une fois reserve.
 * Le clic droit dessus remplace le clic droit sur le bloc (reserver / gerer / acheter).
 *
 * <p>Le skin est synchronise sous forme de propriete "textures" (valeur + signature) : il s'affiche
 * donc meme quand le proprietaire est hors ligne. Le PNJ n'a ni IA, ni gravite, ni degats.
 */
public class StallNpc extends LivingEntity {

    private static final EntityDataAccessor<String> OWNER_NAME =
            SynchedEntityData.defineId(StallNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_VALUE =
            SynchedEntityData.defineId(StallNpc.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE =
            SynchedEntityData.defineId(StallNpc.class, EntityDataSerializers.STRING);

    /** Cle "dim;x;y;z" du stand rattache (cote serveur, pour retrouver le stand au clic). */
    private String stallKey = "";

    public StallNpc(EntityType<? extends StallNpc> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setSilent(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER_NAME, "");
        builder.define(SKIN_VALUE, "");
        builder.define(SKIN_SIGNATURE, "");
    }

    // -------- Etat --------

    public String ownerName() {
        return this.entityData.get(OWNER_NAME);
    }

    public String skinValue() {
        return this.entityData.get(SKIN_VALUE);
    }

    public String skinSignature() {
        return this.entityData.get(SKIN_SIGNATURE);
    }

    public String stallKey() {
        return stallKey;
    }

    public void setStallKey(String key) {
        this.stallKey = key == null ? "" : key;
    }

    /** Applique l'etat d'un stand : libre (Steve) ou skin du proprietaire. */
    public void applyStall(MarketData.Stall stall) {
        boolean free = stall.isFree();
        this.entityData.set(OWNER_NAME, free || stall.ownerName == null ? "" : stall.ownerName);
        this.entityData.set(SKIN_VALUE, free || stall.ownerSkinValue == null ? "" : stall.ownerSkinValue);
        this.entityData.set(SKIN_SIGNATURE, free || stall.ownerSkinSignature == null ? "" : stall.ownerSkinSignature);
        this.setCustomName(null); // le nom est porte par l'hologramme du stand
    }

    // -------- Interaction : remplace le clic droit sur le bloc --------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        MarketData data = MarketData.get(sp.server);
        MarketData.Stall stall = data.stallByKey(stallKey);
        if (stall == null) {
            return InteractionResult.PASS;
        }
        // Op ou maire accroupi mains vides : configuration du stand (comme sur le bloc).
        boolean staff = sp.hasPermissions(2) || data.isMaire(sp.getUUID());
        if (sp.isShiftKeyDown() && staff && sp.getMainHandItem().isEmpty()) {
            MarketMenus.openStallAdmin(sp, stall);
        } else {
            MarketMenus.openStall(sp, stall);
        }
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
        return true; // cliquable
    }

    @Override
    public boolean shouldBeSaved() {
        return false; // recree a la volee par la synchro des stands (evite les doublons)
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
        tag.putString("stallKey", stallKey);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        stallKey = tag.getString("stallKey");
    }
}
