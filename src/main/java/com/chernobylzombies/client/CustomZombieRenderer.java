package com.chernobylzombies.client;

import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * GeckoLib entity renderer for CustomZombieEntity with per-model dynamic scaling.
 */
@OnlyIn(Dist.CLIENT)
public class CustomZombieRenderer extends GeoEntityRenderer<CustomZombieEntity> {

    public CustomZombieRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CustomZombieModel());
        this.shadowRadius = 0.5F;
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            CustomZombieEntity animatable,
            BakedGeoModel model,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        float scale = animatable.getModelScale();
        if (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0.01F) {
            scale = 1.0F;
        }
        this.scaleWidth = scale;
        this.scaleHeight = scale;
        this.shadowRadius = 0.5F * scale;
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    @Override
    public ResourceLocation getTextureLocation(CustomZombieEntity animatable) {
        return ZombieVariantRegistry.getTextureResource(animatable.getModelId(), animatable.getTextureId());
    }

    @Override
    protected float getDeathMaxRotation(CustomZombieEntity animatable) {
        // Custom death animation already rotates the body onto the ground.
        return 0.0F;
    }
}
