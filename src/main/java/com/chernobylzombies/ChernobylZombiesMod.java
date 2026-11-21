package com.chernobylzombies;

import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.registry.ModEntities;
import com.chernobylzombies.registry.ModItems;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Main entry point for the Chernobyl Zombies mod.
 */
@Mod(ChernobylZombiesMod.MODID)
public class ChernobylZombiesMod {

    public static final String MODID = "chernobylzombies";
    public static final String MOD_ID = MODID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public ChernobylZombiesMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ZombieVariantRegistry.init();
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.CREATIVE_MODE_TABS.register(modEventBus);
        PacketHandler.register();

        LOGGER.info("Chernobyl Zombies initialized with {} zombie variants.", ZombieVariantRegistry.getTotalVariants());
    }
}
