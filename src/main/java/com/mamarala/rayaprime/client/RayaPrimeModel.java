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
    private final ModelPart leftEye;
    private final ModelPart rightEye;
    private final ModelPart mouthMid;
    private final ModelPart mouthL;
    private final ModelPart mouthR;
    private final ModelPart antenna;
    private final ModelPart flame;
    private final ModelPart lFingerA;
    private final ModelPart lFingerB;
    private final ModelPart rFingerA;
    private final ModelPart rFingerB;

    public RayaPrimeModel(ModelPart root) {
        this.root = root.getChild("root");
        this.coreCube = this.root.getChild("core_cube");
        this.leftEar = this.coreCube.getChild("left_ear");
        this.rightEar = this.coreCube.getChild("right_ear");
        this.leftArm = this.coreCube.getChild("left_arm");
        this.rightArm = this.coreCube.getChild("right_arm");
        this.leftEye = this.coreCube.getChild("left_eye");
        this.rightEye = this.coreCube.getChild("right_eye");
        this.mouthMid = this.coreCube.getChild("mouth_mid");
        this.mouthL = this.coreCube.getChild("mouth_l");
        this.mouthR = this.coreCube.getChild("mouth_r");
        this.antenna = this.coreCube.getChild("antenna");
        this.flame = this.coreCube.getChild("flame");
        this.lFingerA = this.leftArm.getChild("finger_a");
        this.lFingerB = this.leftArm.getChild("finger_b");
        this.rFingerA = this.rightArm.getChild("finger_a");
        this.rFingerB = this.rightArm.getChild("finger_b");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition partRoot = mesh.getRoot();

        PartDefinition root = partRoot.addOrReplaceChild("root", CubeListBuilder.create(),
                PartPose.offset(0.0F, 16.0F, 0.0F));

        // Корпус 8x8x8 + внешняя рамка, экран, световая полоса, задний и нижний двигатели
        PartDefinition coreCube = root.addOrReplaceChild("core_cube",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 16).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.3F))
                        .texOffs(32, 0).addBox(-3.0F, -3.3F, -5.0F, 6.0F, 5.0F, 1.0F, new CubeDeformation(0.0F))
                        .texOffs(64, 24).addBox(-3.0F, 2.2F, -4.6F, 6.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                        .texOffs(64, 0).addBox(-2.0F, -2.0F, 4.0F, 4.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(64, 8).addBox(-2.0F, 4.0F, -2.0F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.0F)),
                PartPose.ZERO);

        // Пламя нижнего двигателя (масштабируется по Y)
        coreCube.addOrReplaceChild("flame",
                CubeListBuilder.create().texOffs(64, 16).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 6.0F, 0.0F));

        // Антенна с огоньком
        coreCube.addOrReplaceChild("antenna",
                CubeListBuilder.create()
                        .texOffs(40, 16).addBox(-0.5F, -3.0F, -0.5F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                        .texOffs(40, 24).addBox(-1.0F, -5.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -4.2F, 1.5F));

        // «Ушки» с светящимися кончиками
        coreCube.addOrReplaceChild("left_ear",
                CubeListBuilder.create()
                        .texOffs(0, 32).addBox(-1.0F, -5.0F, -1.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(8, 32).addBox(-1.0F, -6.0F, -1.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(3.2F, -3.8F, 0.0F, 0.0F, 0.0F, 0.25F));
        coreCube.addOrReplaceChild("right_ear",
                CubeListBuilder.create()
                        .texOffs(16, 32).addBox(-1.0F, -5.0F, -1.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(24, 32).addBox(-1.0F, -6.0F, -1.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-3.2F, -3.8F, 0.0F, 0.0F, 0.0F, -0.25F));

        // Лицо: глаза и рот (светящиеся кубики на экране, меняются по эмоциям)
        coreCube.addOrReplaceChild("left_eye",
                CubeListBuilder.create().texOffs(32, 8).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(1.6F, -1.3F, -5.0F));
        coreCube.addOrReplaceChild("right_eye",
                CubeListBuilder.create().texOffs(32, 8).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-1.6F, -1.3F, -5.0F));
        coreCube.addOrReplaceChild("mouth_mid",
                CubeListBuilder.create().texOffs(32, 12).addBox(-1.0F, -0.5F, -1.0F, 2.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 0.8F, -5.0F));
        coreCube.addOrReplaceChild("mouth_l",
                CubeListBuilder.create().texOffs(32, 15).addBox(-0.5F, -0.5F, -1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(1.5F, 0.8F, -5.0F));
        coreCube.addOrReplaceChild("mouth_r",
                CubeListBuilder.create().texOffs(32, 15).addBox(-0.5F, -0.5F, -1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-1.5F, 0.8F, -5.0F));

        // Манипуляторы ПМ-фабрикатора с двумя пальцами-«клешнями»
        PartDefinition leftArm = coreCube.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(0, 48).addBox(0.0F, 0.0F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(4.2F, 0.5F, -0.5F));
        leftArm.addOrReplaceChild("finger_a",
                CubeListBuilder.create().texOffs(8, 48).addBox(0.0F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 4.0F, 0.0F));
        leftArm.addOrReplaceChild("finger_b",
                CubeListBuilder.create().texOffs(8, 48).addBox(0.0F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(1.0F, 4.0F, 0.0F));
        PartDefinition rightArm = coreCube.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(16, 48).addBox(-2.0F, 0.0F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-4.2F, 0.5F, -0.5F));
        rightArm.addOrReplaceChild("finger_a",
                CubeListBuilder.create().texOffs(24, 48).addBox(0.0F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-2.0F, 4.0F, 0.0F));
        rightArm.addOrReplaceChild("finger_b",
                CubeListBuilder.create().texOffs(24, 48).addBox(0.0F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-1.0F, 4.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(RayaPrimeEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // Плавное парение (левитация) вверх-вниз
        this.root.x = 0.0F;
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
        this.leftArm.zRot = 0.0F;
        this.rightArm.zRot = 0.0F;
        this.coreCube.zRot = 0.0F;

        // Наклон корпуса вперёд при движении
        this.coreCube.xRot += Mth.clamp(limbSwingAmount, 0.0F, 1.0F) * 0.35F;

        // Говорит: ушки подрагивают, корпус чуть пульсирует, жестикулирует манипуляторами
        if (entity.isTalking()) {
            float talk = ageInTicks * 0.9F;
            this.leftEar.zRot += Mth.sin(talk) * 0.18F;
            this.rightEar.zRot -= Mth.sin(talk) * 0.18F;
            this.root.y += Mth.sin(talk * 0.7F) * 0.5F;
            this.leftArm.xRot += Mth.sin(talk * 0.5F) * 0.3F;
            this.rightArm.xRot -= Mth.sin(talk * 0.5F + 1.0F) * 0.3F;
        }

        // Особые анимации (танец, вращение, кивок, отрицание, приветствие, работа, дрожь)
        if (entity.isAnimActive()) {
            int len = Math.max(1, entity.getAnimLen());
            float t = entity.getAnimElapsed() + (ageInTicks - (float) Math.floor(ageInTicks));
            float p = Mth.clamp(t / len, 0.0F, 1.0F);
            switch (entity.getAnimId()) {
                case RayaPrimeEntity.ANIM_DANCE -> {
                    this.root.y -= Math.abs(Mth.sin(t * 0.5F)) * 3.0F;
                    this.coreCube.zRot = Mth.sin(t * 0.4F) * 0.35F;
                    this.coreCube.yRot += Mth.sin(t * 0.2F) * 0.6F;
                    this.leftEar.zRot = 0.6F + Mth.sin(t * 0.8F) * 0.5F;
                    this.rightEar.zRot = -0.6F - Mth.sin(t * 0.8F) * 0.5F;
                    this.leftArm.xRot = Mth.sin(t * 0.6F) * 1.2F;
                    this.rightArm.xRot = -Mth.sin(t * 0.6F) * 1.2F;
                }
                case RayaPrimeEntity.ANIM_SPIN -> {
                    this.coreCube.yRot += p * ((float) Math.PI * 2.0F);
                    this.leftEar.zRot = 0.7F;
                    this.rightEar.zRot = -0.7F;
                }
                case RayaPrimeEntity.ANIM_NOD -> this.coreCube.xRot += Mth.sin(t * 0.7F) * 0.4F * (1.0F - p);
                case RayaPrimeEntity.ANIM_NO -> this.coreCube.yRot += Mth.sin(t * 0.9F) * 0.5F * (1.0F - p);
                case RayaPrimeEntity.ANIM_WAVE -> {
                    this.rightArm.zRot = -2.2F + Mth.sin(t * 0.8F) * 0.4F;
                    this.rightArm.xRot = 0.0F;
                    this.coreCube.zRot = 0.1F;
                }
                case RayaPrimeEntity.ANIM_WORK -> {
                    this.leftArm.xRot = -1.0F + Mth.sin(t * 1.2F) * 0.8F;
                    this.rightArm.xRot = -1.0F - Mth.sin(t * 1.2F) * 0.8F;
                    this.coreCube.xRot += 0.2F;
                }
                case RayaPrimeEntity.ANIM_TREMBLE -> {
                    this.root.x = Mth.sin(t * 2.5F) * 0.4F;
                    this.leftEar.zRot = 1.15F;
                    this.rightEar.zRot = -1.15F;
                }
                case RayaPrimeEntity.ANIM_FLIP -> {
                    this.coreCube.xRot += p * ((float) Math.PI * 2.0F);
                    this.root.y -= Mth.sin(p * (float) Math.PI) * 6.0F;
                    this.leftEar.zRot = 0.9F;
                    this.rightEar.zRot = -0.9F;
                }
                case RayaPrimeEntity.ANIM_LAUGH -> {
                    this.root.y -= Math.abs(Mth.sin(t * 0.9F)) * 2.2F;
                    this.coreCube.xRot += Mth.sin(t * 1.8F) * 0.12F;
                    this.leftEar.zRot = 0.5F + Mth.sin(t * 1.5F) * 0.3F;
                    this.rightEar.zRot = -0.5F - Mth.sin(t * 1.5F) * 0.3F;
                    this.leftArm.xRot = -0.8F + Mth.sin(t * 1.8F) * 0.4F;
                    this.rightArm.xRot = -0.8F - Mth.sin(t * 1.8F) * 0.4F;
                }
                case RayaPrimeEntity.ANIM_SHRUG -> {
                    float k = Mth.sin(p * (float) Math.PI);
                    this.leftArm.zRot = 1.1F * k;
                    this.rightArm.zRot = -1.1F * k;
                    this.leftEar.zRot = 0.9F * k + 0.25F * (1.0F - k);
                    this.rightEar.zRot = -0.9F * k - 0.25F * (1.0F - k);
                    this.coreCube.zRot = Mth.sin(t * 0.5F) * 0.15F * k;
                    this.root.y -= 1.5F * k;
                }
                case RayaPrimeEntity.ANIM_THINK -> {
                    this.coreCube.zRot = 0.22F;
                    this.coreCube.xRot -= 0.12F;
                    this.leftEar.zRot = 0.2F + Mth.sin(t * 0.3F) * 0.25F;
                    this.rightEar.zRot = -0.7F;
                    this.rightArm.xRot = -1.5F;
                    this.rightArm.zRot = -0.5F;
                }
                case RayaPrimeEntity.ANIM_BOW -> {
                    float k = Mth.sin(p * (float) Math.PI);
                    this.coreCube.xRot += 0.95F * k;
                    this.root.y += 2.0F * k;
                    this.leftEar.zRot = 1.0F * k + 0.25F * (1.0F - k);
                    this.rightEar.zRot = -1.0F * k - 0.25F * (1.0F - k);
                }
                case RayaPrimeEntity.ANIM_SALUTE -> {
                    float k = Mth.clamp(Mth.sin(p * (float) Math.PI) * 2.5F, 0.0F, 1.0F);
                    this.rightArm.zRot = -2.4F * k;
                    this.rightArm.xRot = -0.3F * k;
                    this.coreCube.xRot -= 0.1F * k;
                }
                case RayaPrimeEntity.ANIM_LOVE -> {
                    this.coreCube.zRot = Mth.sin(t * 0.25F) * 0.18F;
                    this.root.y -= 1.0F + Mth.sin(t * 0.5F) * 1.2F;
                    this.leftEar.zRot = 0.8F + Mth.sin(t * 0.6F) * 0.25F;
                    this.rightEar.zRot = -0.8F - Mth.sin(t * 0.6F) * 0.25F;
                    this.leftArm.zRot = 0.5F;
                    this.rightArm.zRot = -0.5F;
                }
                default -> { }
            }
        }

        updateFace(entity, ageInTicks, limbSwingAmount);
    }

    /** Живое лицо: глаза/рот по эмоции и анимации, моргание, рот при речи, антенна, пальцы, пламя. */
    private void updateFace(RayaPrimeEntity entity, float age, float limb) {
        int emotion = entity.getEmotionState();
        int anim = entity.isAnimActive() ? entity.getAnimId() : RayaPrimeEntity.ANIM_NONE;
        float lSy = 1.0F, rSy = 1.0F, lSx = 1.0F, rSx = 1.0F;
        float eyeY = -1.3F, slant = 0.0F, smile = 0.5F, mouthW = 1.0F, mouthOpen = 1.0F, eyeShake = 0.0F, eyeDx = 0.0F;

        switch (emotion) {
            case RayaPrimeEntity.EMOTION_HAPPY -> { lSy = rSy = 0.45F; eyeY = -1.5F; slant = -0.3F; smile = 1.2F; mouthOpen = 1.4F; }
            case RayaPrimeEntity.EMOTION_THINKING -> { lSy = lSx = 0.85F; rSy = rSx = 1.35F; smile = 0.0F; mouthW = 0.6F; eyeDx = 0.4F; }
            case RayaPrimeEntity.EMOTION_CRIMSON -> { lSy = rSy = 0.6F; slant = 0.5F; smile = -0.9F; mouthW = 1.4F; }
            case RayaPrimeEntity.EMOTION_HIDING -> { lSx = rSx = 1.3F; lSy = rSy = 1.5F; smile = -1.2F; mouthW = 1.5F; eyeShake = 0.15F; }
            default -> { }
        }
        switch (anim) {
            case RayaPrimeEntity.ANIM_LAUGH, RayaPrimeEntity.ANIM_DANCE, RayaPrimeEntity.ANIM_LOVE, RayaPrimeEntity.ANIM_FLIP -> {
                lSy = rSy = 0.4F; eyeY = -1.5F; slant = -0.35F; smile = 1.3F; mouthOpen = 1.6F;
            }
            case RayaPrimeEntity.ANIM_THINK -> { lSy = lSx = 0.85F; rSy = rSx = 1.35F; smile = 0.0F; mouthW = 0.6F; eyeDx = 0.4F; }
            case RayaPrimeEntity.ANIM_TREMBLE -> { lSx = rSx = 1.3F; lSy = rSy = 1.5F; smile = -1.2F; mouthW = 1.5F; eyeShake = 0.15F; }
            case RayaPrimeEntity.ANIM_SHRUG -> smile = -0.3F;
            case RayaPrimeEntity.ANIM_NO -> { smile = -0.6F; slant = 0.3F; }
            case RayaPrimeEntity.ANIM_BOW -> lSy = rSy = 0.2F;
            default -> { }
        }
        // моргание раз в ~5 секунд (у каждой Раи свой сдвиг)
        int phase = (int) (age + entity.getId() * 13) % 100;
        if (phase < 3 && lSy > 0.5F) {
            lSy *= 0.12F;
            rSy *= 0.12F;
        }
        // говорит — рот открывается и закрывается
        if (entity.isTalking()) {
            mouthOpen = 1.0F + 1.7F * Math.abs(Mth.sin(age * 0.95F));
        }
        float shake = eyeShake * Mth.sin(age * 2.7F);

        this.leftEye.y = eyeY;
        this.rightEye.y = eyeY;
        this.leftEye.x = 1.6F + eyeDx + shake;
        this.rightEye.x = -1.6F + eyeDx + shake;
        this.leftEye.xScale = lSx;
        this.leftEye.yScale = lSy;
        this.rightEye.xScale = rSx;
        this.rightEye.yScale = rSy;
        this.leftEye.zRot = -slant;
        this.rightEye.zRot = slant;

        float cornerY = 0.8F - smile * 0.9F;
        float cornerX = 1.5F * mouthW;
        this.mouthMid.xScale = mouthW;
        this.mouthMid.yScale = mouthOpen;
        this.mouthL.x = cornerX;
        this.mouthR.x = -cornerX;
        this.mouthL.y = cornerY;
        this.mouthR.y = cornerY;

        // антенна покачивается, отклоняется при полёте; пальцы живут своей жизнью
        boolean fast = anim == RayaPrimeEntity.ANIM_THINK || anim == RayaPrimeEntity.ANIM_LAUGH;
        this.antenna.zRot = Mth.sin(age * (fast ? 0.5F : 0.1F)) * 0.12F;
        this.antenna.xRot = -Mth.clamp(limb, 0.0F, 1.0F) * 0.4F;
        this.flame.yScale = 0.6F + 0.4F * Math.abs(Mth.sin(age * 0.8F)) + Mth.clamp(limb, 0.0F, 1.0F) * 1.2F;

        float open = 0.15F + Mth.sin(age * 0.15F) * 0.05F;
        if (anim == RayaPrimeEntity.ANIM_WORK) open = 0.1F + 0.55F * Math.abs(Mth.sin(age * 0.6F));
        else if (anim == RayaPrimeEntity.ANIM_WAVE || anim == RayaPrimeEntity.ANIM_SALUTE) open = 0.4F;
        this.lFingerA.zRot = open;
        this.lFingerB.zRot = -open;
        this.rFingerA.zRot = open;
        this.rFingerB.zRot = -open;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                               int packedOverlay, int color) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }
}
