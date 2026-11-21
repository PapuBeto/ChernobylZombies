package com.chernobylzombies.registry;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.entity.CustomZombieEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers the legacy multi-model alias entity and the 10 individual variant entities (ModelId 0-9),
 * reusing CustomZombieEntity and CustomZombieRenderer to keep memory usage minimal.
 */
@Mod.EventBusSubscriber(modid = ChernobylZombiesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ChernobylZombiesMod.MODID);

    public static final RegistryObject<EntityType<CustomZombieEntity>> CUSTOM_ZOMBIE =
            registerZombieType("custom_zombie", -1);

    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_HOMBRE_1 =
            registerZombieType("zombie_hombre_1", 0);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_HOMBRE_2 =
            registerZombieType("zombie_hombre_2", 1);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_HOMBRE_3 =
            registerZombieType("zombie_hombre_3", 2);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_HOMBRE_4 =
            registerZombieType("zombie_hombre_4", 3);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_HOMBRE_5 =
            registerZombieType("zombie_hombre_5", 4);

    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_MUJER_1 =
            registerZombieType("zombie_mujer_1", 5);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_MUJER_2 =
            registerZombieType("zombie_mujer_2", 6);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_MUJER_3 =
            registerZombieType("zombie_mujer_3", 7);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_MUJER_4 =
            registerZombieType("zombie_mujer_4", 8);
    public static final RegistryObject<EntityType<CustomZombieEntity>> ZOMBIE_MUJER_5 =
            registerZombieType("zombie_mujer_5", 9);

    private static final List<RegistryObject<EntityType<CustomZombieEntity>>> VARIANT_ENTITIES = List.of(
            ZOMBIE_HOMBRE_1,
            ZOMBIE_HOMBRE_2,
            ZOMBIE_HOMBRE_3,
            ZOMBIE_HOMBRE_4,
            ZOMBIE_HOMBRE_5,
            ZOMBIE_MUJER_1,
            ZOMBIE_MUJER_2,
            ZOMBIE_MUJER_3,
            ZOMBIE_MUJER_4,
            ZOMBIE_MUJER_5
    );

    private static final List<RegistryObject<EntityType<CustomZombieEntity>>> ALL_ENTITIES = List.of(
            CUSTOM_ZOMBIE,
            ZOMBIE_HOMBRE_1,
            ZOMBIE_HOMBRE_2,
            ZOMBIE_HOMBRE_3,
            ZOMBIE_HOMBRE_4,
            ZOMBIE_HOMBRE_5,
            ZOMBIE_MUJER_1,
            ZOMBIE_MUJER_2,
            ZOMBIE_MUJER_3,
            ZOMBIE_MUJER_4,
            ZOMBIE_MUJER_5
    );

    private ModEntities() {
    }

    private static RegistryObject<EntityType<CustomZombieEntity>> registerZombieType(String path, int fixedModelId) {
        return ENTITY_TYPES.register(path, () -> EntityType.Builder.<CustomZombieEntity>of(
                        (type, level) -> new CustomZombieEntity(type, level, fixedModelId),
                        MobCategory.MONSTER)
                .sized(0.6F, 1.95F)
                .clientTrackingRange(8)
                .build(new ResourceLocation(ChernobylZombiesMod.MODID, path).toString()));
    }

    public static List<RegistryObject<EntityType<CustomZombieEntity>>> getVariantEntities() {
        return VARIANT_ENTITIES;
    }

    public static List<RegistryObject<EntityType<CustomZombieEntity>>> getAllEntities() {
        return ALL_ENTITIES;
    }

    /**
     * Resolves the dedicated EntityType matching a MODEL_ID (0-9).
     */
    public static EntityType<CustomZombieEntity> getEntityTypeForModel(int modelId) {
        int clamped = (modelId >= 0 && modelId < VARIANT_ENTITIES.size()) ? modelId : 0;
        return VARIANT_ENTITIES.get(clamped).get();
    }

    /**
     * Resolves the fixed MODEL_ID (0-9) bound to an EntityType, or -1 for the legacy alias.
     */
    public static int getFixedModelId(EntityType<?> entityType) {
        for (int i = 0; i < VARIANT_ENTITIES.size(); i++) {
            RegistryObject<EntityType<CustomZombieEntity>> entry = VARIANT_ENTITIES.get(i);
            if (entry.isPresent() && entry.get() == entityType) {
                return i;
            }
        }
        return -1;
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        for (RegistryObject<EntityType<CustomZombieEntity>> entry : ALL_ENTITIES) {
            event.put(entry.get(), Zombie.createAttributes().build());
        }
    }
}
