package com.mamarala.rayaprime.client;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class RayaPrimeRenderer extends MobRenderer<RayaPrimeEntity, RayaPrimeModel> {

    private static final ResourceLocation TEXTURE_NORMAL =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime.png");
    private static final ResourceLocation TEXTURE_CRIMSON =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime_crimson.png");

    public RayaPrimeRenderer(EntityRendererProvider.Context context) {
        super(context, new RayaPrimeModel(context.bakeLayer(RayaPrimeModel.LAYER_LOCATION)), 0.35F);
    }

    @Override
    public ResourceLocation getTextureLocation(RayaPrimeEntity entity) {
        return entity.isCrimsonMode() ? TEXTURE_CRIMSON : TEXTURE_NORMAL;
    }
}
