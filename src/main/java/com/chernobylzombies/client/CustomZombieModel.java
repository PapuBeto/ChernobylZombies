package com.chernobylzombies.client;

import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model resolver that dynamically queries pre-cached ResourceLocations
 * from ZombieVariantRegistry in O(1) without per-frame allocations.
 */
@OnlyIn(Dist.CLIENT)
public class CustomZombieModel extends GeoModel<CustomZombieEntity> {

    @Override
    public ResourceLocation getModelResource(CustomZombieEntity animatable) {
        return ZombieVariantRegistry.getModelResource(animatable.getModelId());
    }

    @Override
    public ResourceLocation getTextureResource(CustomZombieEntity animatable) {
        return ZombieVariantRegistry.getTextureResource(animatable.getModelId(), animatable.getTextureId());
    }

    @Override
    public ResourceLocation getAnimationResource(CustomZombieEntity animatable) {
        return ZombieVariantRegistry.getAnimationResource(animatable.getModelId());
    }
}
