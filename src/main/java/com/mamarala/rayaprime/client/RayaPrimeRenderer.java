package com.mamarala.rayaprime.client;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public class RayaPrimeRenderer extends MobRenderer<RayaPrimeEntity, RayaPrimeModel> {

    private static final ResourceLocation TEXTURE_NORMAL =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime.png");
    private static final ResourceLocation TEXTURE_CRIMSON =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime_crimson.png");
    private static final ResourceLocation GLOW_NORMAL =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime_glow.png");
    private static final ResourceLocation GLOW_CRIMSON =
            ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "textures/entity/raya_prime_crimson_glow.png");

    public RayaPrimeRenderer(EntityRendererProvider.Context context) {
        super(context, new RayaPrimeModel(context.bakeLayer(RayaPrimeModel.LAYER_LOCATION)), 0.35F);
        this.addLayer(new GlowLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(RayaPrimeEntity entity) {
        return entity.isCrimsonMode() ? TEXTURE_CRIMSON : TEXTURE_NORMAL;
    }

    /** Свечение: глаза, рот, огоньки и пламя рисуются «полной яркости» поверх обычной текстуры. */
    private static final class GlowLayer extends RenderLayer<RayaPrimeEntity, RayaPrimeModel> {
        GlowLayer(RenderLayerParent<RayaPrimeEntity, RayaPrimeModel> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, RayaPrimeEntity entity,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (entity.isInvisible()) return;
            VertexConsumer vc = buffer.getBuffer(RenderType.eyes(entity.isCrimsonMode() ? GLOW_CRIMSON : GLOW_NORMAL));
            this.getParentModel().renderToBuffer(poseStack, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, -1);
        }
    }
}
