package com.chernobylzombies.registry;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.item.ZoneWandItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers custom mod items, the 10 outfit-colored spawn eggs, and the Chernobyl Zombies creative tab.
 */
@Mod.EventBusSubscriber(modid = ChernobylZombiesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ChernobylZombiesMod.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ChernobylZombiesMod.MODID);

    public static final RegistryObject<Item> ZONE_WAND =
            ITEMS.register("zone_wand", () -> new ZoneWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // Colors extracted from each model's first texture PNG (outfit primary + secondary, ignoring transparent & pure black)
    public static final RegistryObject<Item> HOMBRE_1_SPAWN_EGG =
            registerSpawnEgg("hombre_1_spawn_egg", ModEntities.ZOMBIE_HOMBRE_1, 0x733029, 0x1D2820);
    public static final RegistryObject<Item> HOMBRE_2_SPAWN_EGG =
            registerSpawnEgg("hombre_2_spawn_egg", ModEntities.ZOMBIE_HOMBRE_2, 0x363B42, 0x4A3E31);
    public static final RegistryObject<Item> HOMBRE_3_SPAWN_EGG =
            registerSpawnEgg("hombre_3_spawn_egg", ModEntities.ZOMBIE_HOMBRE_3, 0x344964, 0x8B1F12);
    public static final RegistryObject<Item> HOMBRE_4_SPAWN_EGG =
            registerSpawnEgg("hombre_4_spawn_egg", ModEntities.ZOMBIE_HOMBRE_4, 0x1A1B1F, 0xB8AD96);
    public static final RegistryObject<Item> HOMBRE_5_SPAWN_EGG =
            registerSpawnEgg("hombre_5_spawn_egg", ModEntities.ZOMBIE_HOMBRE_5, 0x283B57, 0x1C1D21);

    public static final RegistryObject<Item> MUJER_1_SPAWN_EGG =
            registerSpawnEgg("mujer_1_spawn_egg", ModEntities.ZOMBIE_MUJER_1, 0x1E1F24, 0xCFCBC0);
    public static final RegistryObject<Item> MUJER_2_SPAWN_EGG =
            registerSpawnEgg("mujer_2_spawn_egg", ModEntities.ZOMBIE_MUJER_2, 0x4D543D, 0x5C4936);
    public static final RegistryObject<Item> MUJER_3_SPAWN_EGG =
            registerSpawnEgg("mujer_3_spawn_egg", ModEntities.ZOMBIE_MUJER_3, 0x5C645C, 0x692A32);
    public static final RegistryObject<Item> MUJER_4_SPAWN_EGG =
            registerSpawnEgg("mujer_4_spawn_egg", ModEntities.ZOMBIE_MUJER_4, 0x334D38, 0x6E543A);
    public static final RegistryObject<Item> MUJER_5_SPAWN_EGG =
            registerSpawnEgg("mujer_5_spawn_egg", ModEntities.ZOMBIE_MUJER_5, 0xB8B6AE, 0x690D0D);

    private static final List<RegistryObject<Item>> SPAWN_EGGS = List.of(
            HOMBRE_1_SPAWN_EGG,
            HOMBRE_2_SPAWN_EGG,
            HOMBRE_3_SPAWN_EGG,
            HOMBRE_4_SPAWN_EGG,
            HOMBRE_5_SPAWN_EGG,
            MUJER_1_SPAWN_EGG,
            MUJER_2_SPAWN_EGG,
            MUJER_3_SPAWN_EGG,
            MUJER_4_SPAWN_EGG,
            MUJER_5_SPAWN_EGG
    );

    public static final RegistryObject<CreativeModeTab> CHERNOBYL_ZOMBIES_TAB = CREATIVE_MODE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("creativetab.chernobylzombies.main"))
                    .icon(() -> new ItemStack(HOMBRE_1_SPAWN_EGG.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ZONE_WAND.get());
                        for (RegistryObject<Item> egg : SPAWN_EGGS) {
                            output.accept(egg.get());
                        }
                    })
                    .build()
    );

    private ModItems() {
    }

    private static RegistryObject<Item> registerSpawnEgg(
            String name,
            RegistryObject<EntityType<CustomZombieEntity>> entityType,
            int primaryColor,
            int secondaryColor
    ) {
        return ITEMS.register(name, () -> new ForgeSpawnEggItem(entityType, primaryColor, secondaryColor, new Item.Properties()));
    }

    @SubscribeEvent
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            for (RegistryObject<Item> egg : SPAWN_EGGS) {
                event.accept(egg);
            }
        }
    }
}
