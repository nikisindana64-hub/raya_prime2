package com.mamarala.rayaprime.client;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 3D-модель Раи Прайм (модуль П.Е.Р.С.И.К. модели «Прайм» из сезона «Идеальный МИР»):
 * - Кубический корпус из вольфрамовой стали с передним экраном
 * - Подвижные боковые/верхние "ушки" для выражения эмоций
 * - Нижний и задний реактивные двигатели
 * - Два компактных манипулятора ПМ-Фабрикатора для ломания/установки блоков и боя
 */
public class RayaPrimeModel extends EntityModel<RayaPrimeEntity> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "raya_prime"), "main");

    private final ModelPart root;
    private final ModelPart coreCube;
    private final ModelPart leftEar;
    private final ModelPart rightEar;
    private final ModelPart leftArm;
    private final ModelPart rightArm;

    public RayaPrimeModel(ModelPart root) {
        this.root = root.getChild("root");
        this.coreCube = this.root.getChild("core_cube");
        this.leftEar = this.coreCube.getChild("left_ear");
        this.rightEar = this.coreCube.getChild("right_ear");
        this.leftArm = this.coreCube.getChild("left_arm");
        this.rightArm = this.coreCube.getChild("right_arm");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition partRoot = mesh.getRoot();

        PartDefinition root = partRoot.addOrReplaceChild("root", CubeListBuilder.create(),
                PartPose.offset(0.0F, 16.0F, 0.0F));

        // Центральный куб корпуса 8x8x8
        PartDefinition coreCube = root.addOrReplaceChild("core_cube",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                        // Задний и нижний двигатели
                        .texOffs(0, 32).addBox(-2.5F, -2.0F, 4.0F, 5.0F, 4.0F, 2.5F, new CubeDeformation(0.0F))
                        .texOffs(16, 32).addBox(-2.5F, 4.0F, -2.5F, 5.0F, 2.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.ZERO);

        // Левое и правое "ушки" для выражения эмоций Раи
        coreCube.addOrReplaceChild("left_ear",
                CubeListBuilder.create()
                        .texOffs(0, 18).addBox(-1.0F, -4.0F, -1.5F, 3.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(3.5F, -3.5F, 0.0F, 0.0F, 0.0F, 0.25F));

        coreCube.addOrReplaceChild("right_ear",
                CubeListBuilder.create()
                        .texOffs(16, 18).addBox(-2.0F, -4.0F, -1.5F, 3.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-3.5F, -3.5F, 0.0F, 0.0F, 0.0F, -0.25F));

        // Боковые манипуляторы ПМ-фабрикатора
        coreCube.addOrReplaceChild("left_arm",
                CubeListBuilder.create()
                        .texOffs(0, 40).addBox(0.0F, 0.0F, -2.0F, 1.5F, 3.5F, 4.0F, new CubeDeformation(0.0F)),
                PartPose.offset(4.1F, 1.0F, -0.5F));

        coreCube.addOrReplaceChild("right_arm",
                CubeListBuilder.create()
                        .texOffs(16, 40).addBox(-1.5F, 0.0F, -2.0F, 1.5F, 3.5F, 4.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-4.1F, 1.0F, -0.5F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(RayaPrimeEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // Плавное парение (левитация) вверх-вниз
        this.root.y = 16.0F + Mth.sin(ageInTicks * 0.12F) * 1.4F;
        this.coreCube.yRot = netHeadYaw * ((float) Math.PI / 180F);
        this.coreCube.xRot = headPitch * ((float) Math.PI / 180F) + Mth.cos(ageInTicks * 0.08F) * 0.05F;

        // Анимация "ушек" в зависимости от эмоционального состояния Раи
        int emotion = entity.getEmotionState();
        if (emotion == 3) {
            // Кровавая / Боевая форма — ушки напряжены и быстро вибрируют
            this.leftEar.zRot = 0.45F + Mth.sin(ageInTicks * 0.6F) * 0.12F;
            this.rightEar.zRot = -0.45F - Mth.sin(ageInTicks * 0.6F) * 0.12F;
        } else if (emotion == 4) {
            // Прячется — ушки прижаты к корпусу
            this.leftEar.zRot = 1.15F;
            this.rightEar.zRot = -1.15F;
        } else {
            // Обычное дружелюбное покачивание ушками
            this.leftEar.zRot = 0.25F + Mth.sin(ageInTicks * 0.15F) * 0.08F;
            this.rightEar.zRot = -0.25F - Mth.sin(ageInTicks * 0.15F) * 0.08F;
        }

        // Движение манипуляторов ПМ-фабрикатора
        this.leftArm.xRot = Mth.sin(ageInTicks * 0.2F) * 0.15F;
        this.rightArm.xRot = -Mth.sin(ageInTicks * 0.2F) * 0.15F;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                               int packedOverlay, int color) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
