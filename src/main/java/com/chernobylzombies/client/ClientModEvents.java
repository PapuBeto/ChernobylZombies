package com.chernobylzombies.client;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.registry.ModEntities;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/**
 * Client-only event bindings for entity renderers.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ChernobylZombiesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {

    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (RegistryObject<EntityType<CustomZombieEntity>> entityObj : ModEntities.getAllEntities()) {
            event.registerEntityRenderer(entityObj.get(), CustomZombieRenderer::new);
        }
    }
}
