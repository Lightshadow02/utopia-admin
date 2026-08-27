package com.utopia.entity;

import com.utopia.UtopiaMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entites du mod : pour l'instant le PNJ "vendeur" des stands de marche. */
public final class UtopiaEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, UtopiaMod.MODID);

    /** PNJ d'un stand de marche (modele joueur, skin Steve si libre / du proprietaire sinon). */
    public static final DeferredHolder<EntityType<?>, EntityType<StallNpc>> STALL_NPC =
            ENTITIES.register("stall_npc", () -> EntityType.Builder.<StallNpc>of(StallNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("stall_npc"));

    /** Marchand d'une structure (present seulement dans l'un des deux etats). */
    public static final DeferredHolder<EntityType<?>, EntityType<ShopNpc>> SHOP_NPC =
            ENTITIES.register("shop_npc", () -> EntityType.Builder.<ShopNpc>of(ShopNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("shop_npc"));

    /** Capitaine Transit : embarquement entre Utopia et le continent de ressources. */
    public static final DeferredHolder<EntityType<?>, EntityType<TransitNpc>> TRANSIT_NPC =
            ENTITIES.register("transit_npc", () -> EntityType.Builder.<TransitNpc>of(TransitNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("transit_npc"));

    /** PNJ d'un chantier communautaire (collecte de ressources). */
    public static final DeferredHolder<EntityType<?>, EntityType<ChantierNpc>> CHANTIER_NPC =
            ENTITIES.register("chantier_npc", () -> EntityType.Builder.<ChantierNpc>of(ChantierNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("chantier_npc"));

    private UtopiaEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(UtopiaEntities::onAttributes);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(STALL_NPC.get(), LivingEntity.createLivingAttributes().build());
        event.put(SHOP_NPC.get(), LivingEntity.createLivingAttributes().build());
        event.put(TRANSIT_NPC.get(), LivingEntity.createLivingAttributes().build());
        event.put(CHANTIER_NPC.get(), LivingEntity.createLivingAttributes().build());
    }
}
