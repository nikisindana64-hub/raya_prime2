package com.mamarala.rayaprime.entity;

import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.voice.RayaVoice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

/**
 * Физическое тело Раи Прайм (модуль П.Е.Р.С.И.К.): летает, носит вещи в 27-слотовом инвентаре,
 * ломает и ставит блоки, дерётся, использует ЭМИ-импульс и прячется за игроком или за укрытиями.
 */
public class RayaPrimeEntity extends PathfinderMob {

    public static final int EMOTION_NORMAL = 0;
    public static final int EMOTION_HAPPY = 1;
    public static final int EMOTION_THINKING = 2;
    public static final int EMOTION_CRIMSON = 3;
    public static final int EMOTION_HIDING = 4;

    public static final int ANIM_NONE = 0;
    public static final int ANIM_DANCE = 1;
    public static final int ANIM_SPIN = 2;
    public static final int ANIM_NOD = 3;
    public static final int ANIM_NO = 4;
    public static final int ANIM_WAVE = 5;
    public static final int ANIM_WORK = 6;
    public static final int ANIM_TREMBLE = 7;

    private static final int MAX_QUEUED_TASKS = 48;
    private static final int TASK_TIMEOUT_TICKS = 20 * 45;

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> DATA_EMOTION_STATE =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.INT);
    // Режим: FOLLOW, STAY, HIDE_BEHIND_PLAYER, HIDE_BEHIND_BLOCKS, COMBAT
    private static final EntityDataAccessor<String> DATA_ACTIVE_MODE =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.STRING);

    // Анимации: id, момент старта (игровое время, int) и длительность; «говорит» — до какого игрового времени
    private static final EntityDataAccessor<Integer> DATA_ANIM =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ANIM_START =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ANIM_LEN =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TALK_UNTIL =
            SynchedEntityData.defineId(RayaPrimeEntity.class, EntityDataSerializers.INT);

    private final SimpleContainer inventory = new SimpleContainer(27);
    private final Queue<RayaTask> taskQueue = new ArrayDeque<>();
    private RayaTask currentTask = null;
    private int taskAge = 0;
    private int miningTicks = 0;
    private int pickupCooldown = 0;
    private int emotionTimer = 0;

    private Vec3 hideSpotCache = null;
    private long hideSpotComputedAt = -100;
    private boolean hideSpotBehindBlocks = false;

    // Страх перед очень опасными существами
    private boolean afraid = false;
    private String preFearMode = "FOLLOW";
    private int fearCalmTicks = 0;
    private int fearAnnounceCooldown = 0;
    private LivingEntity fearSource = null;
    private int returnAnnounceCooldown = 0;
    private int idleChatCooldown = 20 * 90;
    private RayaBuild.Plan buildPlan = null;
    private boolean guardMode = false;
    private final Map<String, BlockPos> waypoints = new LinkedHashMap<>();
    private int healCooldownTicks = 0;
    private int warnCooldownTicks = 0;
    private int lastIdleLine = -1;

    public RayaPrimeEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new FlyingMoveControl(this, 20, true);
        this.setNoGravity(true);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 60.0D)
                .add(Attributes.FLYING_SPEED, 0.65D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.ARMOR, 10.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER_UUID, Optional.empty());
        builder.define(DATA_EMOTION_STATE, EMOTION_NORMAL);
        builder.define(DATA_ACTIVE_MODE, "FOLLOW");
        builder.define(DATA_ANIM, ANIM_NONE);
        builder.define(DATA_ANIM_START, 0);
        builder.define(DATA_ANIM_LEN, 0);
        builder.define(DATA_TALK_UNTIL, 0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanFloat(true);
        nav.setCanPassDoors(true);
        return nav;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new RayaGoals.RayaHideGoal(this));
        this.goalSelector.addGoal(2, new RayaGoals.RayaCombatGoal(this));
        this.goalSelector.addGoal(3, new RayaGoals.RayaBuildGoal(this));
        this.goalSelector.addGoal(3, new RayaGoals.RayaExecuteTaskGoal(this));
        this.goalSelector.addGoal(4, new RayaGoals.RayaFollowOwnerGoal(this));
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false; // Райя не должна исчезать, когда игрок далеко
    }

    @Override
    public void baseTick() {
        super.baseTick();
        if (!this.level().isClientSide) {
            this.setAirSupply(this.getMaxAirSupply()); // корпус герметичен: под водой не тонет
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            if (this.pickupCooldown > 0) {
                this.pickupCooldown--;
            } else {
                pickupNearbyItems();
            }
            if (this.tickCount % 60 == 0 && this.getHealth() < this.getMaxHealth()) {
                this.heal(1.0F);
            }
            if (this.tickCount % 20 == 0) {
                getOwnerUUID().ifPresent(RayaPresence::mark);
                tickReturn();
                tickIdleChatter();
                tickCare();
            }
            if (this.tickCount % 10 == 0) {
                tickFear();
                tickGuard();
                tickAutoAnimation();
            }
            if (this.emotionTimer > 0 && --this.emotionTimer == 0) {
                restoreBaseEmotion();
            }
            if (this.currentTask != null && ++this.taskAge > TASK_TIMEOUT_TICKS) {
                tell("Не могу выполнить задачу — застряла. Отменяю.");
                completeCurrentTask();
            }
        } else {
            if (this.tickCount % 3 == 0) {
                this.level().addParticle(
                        isCrimsonMode() ? ParticleTypes.FLAME : ParticleTypes.END_ROD,
                        this.getX(), this.getY() - 0.05D, this.getZ(),
                        (this.random.nextDouble() - 0.5D) * 0.02D,
                        -0.03D,
                        (this.random.nextDouble() - 0.5D) * 0.02D);
            }
            clientAnimationParticles();
        }
    }

    private void pickupNearbyItems() {
        AABB pickupBox = this.getBoundingBox().inflate(2.2D);
        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, pickupBox,
                item -> item.isAlive() && !item.hasPickUpDelay());
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            int before = stack.getCount();
            ItemStack remainder = this.inventory.addItem(stack.copy());
            if (remainder.isEmpty()) {
                this.take(itemEntity, before);
                itemEntity.discard();
            } else if (remainder.getCount() < before) {
                itemEntity.getItem().setCount(remainder.getCount());
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (this.getOwnerUUID().isEmpty()) {
                this.setOwnerUUID(serverPlayer.getUUID());
            }
            if (!isOwnedBy(serverPlayer) && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r Мой инвентарь доступен только моему носителю."));
                return InteractionResult.CONSUME;
            }
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, playerInv, p) -> new ChestMenu(MenuType.GENERIC_9x3, id, playerInv, this.inventory, 3),
                    Component.translatable("container.rayaprime.inventory")));
        }
        return InteractionResult.CONSUME;
    }

    // =========================================================================
    // Эмоции
    // =========================================================================

    /** Показать эмоцию на экране на ticks тиков, затем вернуться к эмоции по режиму. */
    public void showEmotion(int state, int ticks) {
        setEmotionState(state);
        this.emotionTimer = Math.max(1, ticks);
    }

    public void restoreBaseEmotion() {
        String mode = getActiveMode();
        if ("COMBAT".equals(mode) && getTarget() != null) {
            setEmotionState(EMOTION_CRIMSON);
        } else if ("HIDE_BEHIND_PLAYER".equals(mode) || "HIDE_BEHIND_BLOCKS".equals(mode)) {
            setEmotionState(EMOTION_HIDING);
        } else {
            setEmotionState(EMOTION_NORMAL);
        }
        this.emotionTimer = 0;
    }

    // =========================================================================
    // Очередь задач
    // =========================================================================

    /** @return false, если очередь переполнена */
    public boolean enqueueTask(RayaTask task) {
        if (this.taskQueue.size() >= MAX_QUEUED_TASKS) {
            return false;
        }
        return this.taskQueue.offer(task);
    }

    public void clearTasks() {
        this.taskQueue.clear();
        completeCurrentTask();
    }

    @Nullable
    public RayaTask peekOrPollTask() {
        if (this.currentTask == null && !this.taskQueue.isEmpty()) {
            this.currentTask = this.taskQueue.poll();
            this.miningTicks = 0;
            this.taskAge = 0;
        }
        return this.currentTask;
    }

    public void completeCurrentTask() {
        if (this.currentTask != null && this.currentTask.type() == RayaTask.TaskType.MINE_BLOCK) {
            this.level().destroyBlockProgress(this.getId(), this.currentTask.targetPos(), -1);
        }
        this.currentTask = null;
        this.miningTicks = 0;
        this.taskAge = 0;
    }

    public int getQueuedTaskCount() {
        return this.taskQueue.size() + (this.currentTask != null ? 1 : 0);
    }

    public void tell(String text) {
        Player owner = getOwnerPlayer();
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r " + text));
        }
    }

    /** Сказать в чат И голосом. */
    public void say(String text, RayaVoice.Mood mood) {
        tell(text);
        if (mood == RayaVoice.Mood.HAPPY && !isAnimActive() && this.random.nextInt(3) == 0) {
            playAnimation(ANIM_WAVE, animDuration(ANIM_WAVE));
        }
        Player owner = getOwnerPlayer();
        if (owner instanceof ServerPlayer sp && this.level() instanceof ServerLevel sl) {
            RayaVoice.speak(sl.getServer(), this, sp, text, RayaConfig.getEffectiveApiKey(), mood);
        }
    }

    /** Можно ли Рае трогать этот блок: чанк загружен, рядом с владельцем, не спавн-защита. */
    public boolean canActOn(BlockPos pos) {
        Player owner = getOwnerPlayer();
        if (owner == null || !this.level().hasChunkAt(pos) || !this.level().isInWorldBounds(pos)) {
            return false;
        }
        int max = RayaConfig.MAX_TASK_DISTANCE.get();
        if (owner.distanceToSqr(Vec3.atCenterOf(pos)) > (double) max * max) {
            return false;
        }
        return this.level().mayInteract(owner, pos);
    }

    /**
     * Добыча блока: трещины, сбор дропа. Время зависит от твёрдости блока.
     * @return true, когда задача закончена (успех или отмена)
     */
    public boolean tickMineBlock(BlockPos targetPos) {
        if (!RayaConfig.ALLOW_BLOCK_BREAKING.get()) {
            tell("Ломание блоков отключено в конфиге.");
            return true;
        }
        if (!canActOn(targetPos)) {
            tell("Эта точка вне допустимой зоны — не буду ломать.");
            return true;
        }
        if (this.level().isEmptyBlock(targetPos)) {
            return true;
        }
        BlockState state = this.level().getBlockState(targetPos);
        float hardness = state.getDestroySpeed(this.level(), targetPos);
        if (hardness < 0 || !state.getFluidState().isEmpty()) {
            return true; // бедрок, жидкости и неразрушимое пропускаем
        }
        double distSq = this.distanceToSqr(Vec3.atCenterOf(targetPos));
        if (distSq > 9.0D) {
            this.getMoveControl().setWantedPosition(
                    targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5, 1.35D);
            return false;
        }
        this.getLookControl().setLookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        int needed = Mth.clamp((int) (hardness * 12.0F), 10, 160);
        this.miningTicks++;
        int progress = Math.min(9, this.miningTicks * 10 / needed);
        this.level().destroyBlockProgress(this.getId(), targetPos, progress);
        if (this.miningTicks >= needed) {
            this.level().destroyBlockProgress(this.getId(), targetPos, -1);
            this.level().destroyBlock(targetPos, true, this);
            this.pickupNearbyItems();
            return true;
        }
        return false;
    }

    /** Установка блока из инвентаря Раи с корректной обработкой состояний блока (двери, ориентация и т.д.). */
    public boolean tickPlaceBlock(BlockPos targetPos, String blockIdFilter) {
        if (!RayaConfig.ALLOW_BLOCK_PLACING.get()) {
            tell("Установка блоков отключена в конфиге.");
            return true;
        }
        if (!canActOn(targetPos)) {
            tell("Эта точка вне допустимой зоны — не буду ставить блок.");
            return true;
        }
        double distSq = this.distanceToSqr(Vec3.atCenterOf(targetPos));
        if (distSq > 16.0D) {
            this.getMoveControl().setWantedPosition(
                    targetPos.getX() + 0.5, targetPos.getY() + 1.5, targetPos.getZ() + 0.5, 1.35D);
            return false;
        }
        int slot = findBlockSlotInInventory(blockIdFilter);
        if (slot < 0) {
            tell("В моём инвентаре нет подходящих блоков" + (blockIdFilter == null || blockIdFilter.isBlank() ? "." : " («" + blockIdFilter + "»)."));
            return true;
        }
        ItemStack stack = this.inventory.getItem(slot);
        if (stack.getItem() instanceof BlockItem blockItem) {
            ItemStack single = stack.copyWithCount(1);
            DirectionalPlaceContext ctx = new DirectionalPlaceContext(
                    this.level(), targetPos, Direction.DOWN, single, Direction.UP);
            InteractionResult result = blockItem.place(ctx);
            if (result.consumesAction()) {
                stack.shrink(1);
                this.inventory.setChanged();
            } else {
                tell("Не получилось поставить блок в эту точку.");
            }
        }
        return true;
    }

    private int findBlockSlotInInventory(String blockIdFilter) {
        String filter = blockIdFilter == null ? "" : blockIdFilter.toLowerCase(Locale.ROOT);
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack s = this.inventory.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (filter.isEmpty()
                        || key.toString().toLowerCase(Locale.ROOT).contains(filter)
                        || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Лететь к ближайшему выпавшему предмету (по фильтру) и подобрать. @return true когда предметов не осталось */
    public boolean tickCollectItems(String filter) {
        Player owner = getOwnerPlayer();
        Vec3 origin = owner != null ? owner.position() : this.position();
        AABB area = new AABB(origin, origin).inflate(24.0D);
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        ItemEntity nearest = this.level().getEntitiesOfClass(ItemEntity.class, area, e -> e.isAlive()
                        && (f.isEmpty()
                        || BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString().toLowerCase(Locale.ROOT).contains(f)
                        || e.getItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains(f)))
                .stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (nearest == null) {
            return true;
        }
        if (this.inventory.canAddItem(nearest.getItem())) {
            this.getMoveControl().setWantedPosition(nearest.getX(), nearest.getY() + 0.3, nearest.getZ(), 1.4D);
            return false;
        }
        tell("Мой инвентарь полон.");
        return true;
    }

    /** Отдать предметы владельцу (или выбросить рядом), фильтр "" или "all" — всё. */
    public void dropItemsToOwner(String itemFilter) {
        Player owner = getOwnerPlayer();
        String filter = itemFilter == null ? "" : itemFilter.toLowerCase(Locale.ROOT);
        this.pickupCooldown = 100; // чтобы тут же не подобрать выброшенное обратно
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack s = this.inventory.getItem(i);
            if (s.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (filter.isEmpty() || filter.equals("all")
                    || id.toString().toLowerCase(Locale.ROOT).contains(filter)
                    || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter)) {
                ItemStack toGive = this.inventory.removeItemNoUpdate(i);
                if (owner != null && owner.getInventory().add(toGive)) {
                    continue;
                }
                if (!toGive.isEmpty()) {
                    this.spawnAtLocation(toGive);
                }
            }
        }
        this.inventory.setChanged();
    }

    /** ЭМИ-импульс: оглушает и ранит враждебных существ вокруг. */
    public void triggerEmpPulse() {
        if (this.level() instanceof ServerLevel serverLevel) {
            showEmotion(EMOTION_CRIMSON, 80);
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    this.getX(), this.getY() + 0.3, this.getZ(), 65, 2.5, 1.5, 2.5, 0.25);
            this.level().playSound(null, this.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(),
                    SoundSource.NEUTRAL, 1.2F, 1.4F);

            AABB area = this.getBoundingBox().inflate(8.0D);
            List<LivingEntity> targets = this.level().getEntitiesOfClass(LivingEntity.class, area,
                    e -> e != this && e.isAlive() && !isOwnedBy(e) && (e instanceof Enemy || e == this.getTarget()));
            for (LivingEntity target : targets) {
                target.hurt(this.damageSources().indirectMagic(this, this), 10.0F);
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 3));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 1));
            }
        }
    }

    // =========================================================================
    // Укрытия
    // =========================================================================

    /**
     * Точка укрытия. Для «за спиной игрока» пересчитывается часто (игрок двигается).
     * Для «за блоками» выбирается ОДИН раз и держится, пока укрытие реально скрывает от угрозы
     * (проверка раз в секунду) — иначе Райя металась бы между точками и летела к игроку.
     */
    public Vec3 getHidePositionCached(boolean behindBlocks) {
        long now = this.level().getGameTime();
        boolean recompute = this.hideSpotCache == null || behindBlocks != this.hideSpotBehindBlocks;
        if (!recompute) {
            if (!behindBlocks) {
                recompute = now - this.hideSpotComputedAt >= 5;
            } else if (now - this.hideSpotComputedAt >= 20) {
                LivingEntity threat = findNearestThreat();
                if (threat != null && !isHiddenFrom(threat.getEyePosition(), this.hideSpotCache)) {
                    recompute = true; // угроза сместилась и снова видит нас
                } else {
                    this.hideSpotComputedAt = now;
                }
            }
        }
        if (recompute) {
            this.hideSpotCache = computeHidePosition(behindBlocks);
            this.hideSpotBehindBlocks = behindBlocks;
            this.hideSpotComputedAt = now;
        }
        return this.hideSpotCache;
    }

    private boolean isHiddenFrom(Vec3 from, Vec3 spot) {
        BlockHitResult hit = this.level().clip(new ClipContext(
                from, spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    /**
     * behindBlocks == false: точка за спиной игрока относительно угрозы.
     * behindBlocks == true: ближайшая к САМОЙ Рае клетка у стены, которую угроза не видит (луч ClipContext).
     */
    public Vec3 computeHidePosition(boolean behindBlocks) {
        Player owner = getOwnerPlayer();
        LivingEntity threat = findNearestThreat();
        Vec3 threatPos = threat != null
                ? threat.getEyePosition()
                : (owner != null ? owner.getEyePosition().add(owner.getLookAngle().scale(6.0)) : this.position().add(0, 0, 5));

        if (!behindBlocks && owner != null) {
            return behindPlayerPosition(owner, threatPos);
        }

        Vec3 self = this.position();
        BlockPos center = this.blockPosition();
        Vec3 bestCover = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (!this.level().getBlockState(candidate).isAir()
                            || !this.level().getBlockState(candidate.above()).isAir()) {
                        continue;
                    }
                    boolean adjacentSolid = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = candidate.relative(d);
                        if (this.level().getBlockState(n).isSolidRender(this.level(), n)) {
                            adjacentSolid = true;
                            break;
                        }
                    }
                    if (!adjacentSolid) continue;

                    Vec3 candidateCenter = Vec3.atCenterOf(candidate);
                    if (threat != null && candidateCenter.distanceToSqr(threat.position()) < 25.0D) continue; // не прятаться вплотную к угрозе
                    if (!isHiddenFrom(threatPos, candidateCenter)) continue;

                    double score = candidateCenter.distanceToSqr(self);
                    if (owner != null) {
                        score += 0.1D * candidateCenter.distanceToSqr(owner.position());
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestCover = candidateCenter;
                    }
                }
            }
        }

        if (bestCover != null) {
            return bestCover;
        }
        // Подходящего укрытия нет — прячемся за спиной игрока (если он есть)
        return owner != null ? behindPlayerPosition(owner, threatPos) : this.position();
    }

    private static Vec3 behindPlayerPosition(Player owner, Vec3 threatPos) {
        Vec3 away = owner.position().subtract(threatPos).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) {
            away = owner.getLookAngle().scale(-1).multiply(1, 0, 1);
        }
        Vec3 dir = away.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : away.normalize();
        return owner.position().add(dir.scale(1.8D)).add(0, 0.8D, 0);
    }

    @Nullable
    public LivingEntity findNearestThreat() {
        if (this.afraid && this.fearSource != null && this.fearSource.isAlive()) {
            return this.fearSource;
        }
        AABB area = this.getBoundingBox().inflate(18.0D);
        List<LivingEntity> hostiles = this.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e.isAlive() && e instanceof Enemy);
        return hostiles.stream()
                .min(Comparator.comparingDouble(this::distanceToSqr))
                .orElse(this.getTarget());
    }

    // =========================================================================
    // Страх и возврат к владельцу
    // =========================================================================

    public boolean isAfraid() {
        return this.afraid;
    }

    private boolean isTerrifying(LivingEntity e, List<String> keywords) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        String id = key.toString().toLowerCase(Locale.ROOT);
        String hay = id + " " + e.getType().getDescription().getString().toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            String k = kw.toLowerCase(Locale.ROOT);
            if (k.contains(":") ? id.equals(k) : hay.contains(k)) {
                return true;
            }
        }
        boolean hostile = e instanceof Enemy || e.getType().getCategory() == MobCategory.MONSTER;
        if (!hostile) return false;
        if (e.getMaxHealth() >= RayaConfig.FEAR_MIN_HEALTH.get()) return true;
        return e.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                && e.getAttributeValue(Attributes.ATTACK_DAMAGE) >= RayaConfig.FEAR_MIN_DAMAGE.get();
    }

    @Nullable
    private LivingEntity findTerrifyingEntity() {
        List<String> keywords = RayaConfig.splitList(RayaConfig.FEAR_KEYWORDS.get());
        AABB box = this.getBoundingBox().inflate(RayaConfig.FEAR_RADIUS.get());
        List<LivingEntity> list = this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != this && e.isAlive() && !(e instanceof Player) && isTerrifying(e, keywords));
        return list.stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
    }

    private void tickFear() {
        if (!RayaConfig.FEAR_ENABLED.get()) {
            if (this.afraid) endFear();
            return;
        }
        if (this.fearAnnounceCooldown > 0) {
            this.fearAnnounceCooldown -= 10;
        }
        LivingEntity scary = findTerrifyingEntity();
        if (scary != null) {
            this.fearSource = scary;
            this.fearCalmTicks = 0;
            if (!this.afraid) {
                this.afraid = true;
                String cur = getActiveMode();
                this.preFearMode = (cur.startsWith("HIDE") || "COMBAT".equals(cur)) ? "FOLLOW" : cur;
                clearTasks();
                setTarget(null);
                setActiveMode("HIDE_BEHIND_BLOCKS");
            } else if (!"HIDE_BEHIND_BLOCKS".equals(getActiveMode())) {
                setActiveMode("HIDE_BEHIND_BLOCKS"); // страх сильнее приказов
            }
            if (this.fearAnnounceCooldown <= 0) {
                this.fearAnnounceCooldown = 600;
                announceFear(scary);
            }
        } else if (this.afraid) {
            this.fearCalmTicks += 10;
            if (this.fearCalmTicks >= 200) {
                endFear();
            }
        }
    }

    private void endFear() {
        this.afraid = false;
        this.fearSource = null;
        if ("HIDE_BEHIND_BLOCKS".equals(getActiveMode())) {
            setActiveMode(this.preFearMode);
        }
        say(CALM_LINES[this.random.nextInt(CALM_LINES.length)], RayaVoice.Mood.NORMAL);
    }

    private static final String[] CALM_LINES = {
            "Кажется, оно ушло... Фух, можно выдохнуть.",
            "Угроза пропала. Ух, мои вентиляторы до сих пор дрожат.",
            "Всё, опасность миновала. Я снова с тобой, мой носитель!"
    };

    private static final String[] FEAR_LINES = {
            "Там %s! Мне страшно, я прячусь! Не подходи к нему, носитель!",
            "Ой-ой... %s рядом! Мне очень страшно, прячусь за блоками!",
            "Носитель, осторожно — %s! Я боюсь, я спрячусь, а ты держись от него подальше!"
    };

    private void announceFear(LivingEntity scary) {
        String name = scary.getName().getString();
        String text = String.format(FEAR_LINES[this.random.nextInt(FEAR_LINES.length)], name);
        say(text, RayaVoice.Mood.FEAR);
    }

    // =========================================================================
    // Самостоятельные реплики (без запросов к Gemini)
    // =========================================================================

    private static final String[] LINES_LOW_HP = {
            "Носитель, у тебя совсем мало здоровья! Давай-ка поосторожнее.",
            "Шанс твоего выживания сейчас не радует. Может, подлечимся?",
            "Эй, ты вообще в порядке? Здоровья остаётся мало!"
    };
    private static final String[] LINES_HUNGRY = {
            "Мой носитель, у тебя урчит в животе. Пора перекусить!",
            "Показатели голода в красной зоне. Поешь, пожалуйста.",
            "Как твой П.Е.Р.С.И.К., заявляю: тебе срочно нужна еда."
    };
    private static final String[] LINES_NIGHT = {
            "Уже ночь. Шанс встретить монстров вырос процентов на семьдесят.",
            "Темнеет... Давай держаться вместе, мне так спокойнее.",
            "Ночью всё интереснее. И опаснее. Я присмотрю за тобой!"
    };
    private static final String[] LINES_RAIN = {
            "Дождь пошёл. Мой корпус водонепроницаемый, а ты не промок?",
            "Капли стучат по вольфрамовой стали. Мило, правда?",
            "Гроза! Красиво, но давай не стоять на открытом месте."
    };
    private static final String[] LINES_MOBS = {
            "Вокруг многовато враждебных существ. Хочешь, устрою ЭМИ-разряд?",
            "Я насчитала несколько противников рядом. Будь начеку!"
    };
    private static final String[] LINES_CHAT = {
            "Мне нравится летать рядом с тобой. Скажи, если нужна помощь!",
            "Если что-то понадобится построить, сломать или найти — просто скажи.",
            "Я могу рассказать про любой установленный мод. Спрашивай!",
            "Знаешь, я лучшая П.Е.Р.С.И.К. во всей вселенной. Это научный факт.",
            "Мои сканеры спокойны. Пока что. Хочешь, подскажу, что делать дальше в сюжете?",
            "Могу собрать выпавшие вещи или понести твои — только скажи."
    };

    private String pickLine(String[] lines) {
        int i = this.random.nextInt(lines.length);
        if (lines.length > 1 && i == this.lastIdleLine) i = (i + 1) % lines.length;
        this.lastIdleLine = i;
        return lines[i];
    }

    private void tickIdleChatter() {
        if (!RayaConfig.IDLE_CHATTER.get()) return;
        if ((this.idleChatCooldown -= 20) > 0) return;
        int base = RayaConfig.IDLE_CHATTER_MINUTES.get() * 1200;
        this.idleChatCooldown = base / 2 + this.random.nextInt(Math.max(1, base));

        Player owner = getOwnerPlayer();
        if (this.afraid || owner == null || owner.level() != this.level() || owner.isSpectator()) return;
        String mode = getActiveMode();
        if ("COMBAT".equals(mode) || mode.startsWith("HIDE") || this.currentTask != null) return;
        if (this.distanceToSqr(owner) > 40 * 40) return;

        long dayTime = this.level().getDayTime() % 24000L;
        boolean night = dayTime > 13000L && dayTime < 23000L;
        long hostiles = this.level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(16.0D),
                e -> e instanceof Enemy && e.isAlive()).size();

        String line;
        RayaVoice.Mood mood = RayaVoice.Mood.NORMAL;
        if (owner.getHealth() <= owner.getMaxHealth() * 0.3F) {
            line = pickLine(LINES_LOW_HP);
            mood = RayaVoice.Mood.FEAR;
        } else if (owner instanceof ServerPlayer sp && sp.getFoodData().getFoodLevel() <= 6) {
            line = pickLine(LINES_HUNGRY);
        } else if (hostiles >= 4) {
            line = pickLine(LINES_MOBS);
        } else if (this.level().isRaining() && this.random.nextInt(3) == 0) {
            line = pickLine(LINES_RAIN);
        } else if (night && this.random.nextInt(3) == 0) {
            line = pickLine(LINES_NIGHT);
        } else if (this.random.nextInt(2) == 0) {
            line = pickLine(LINES_CHAT);
            mood = RayaVoice.Mood.HAPPY;
        } else {
            return;
        }
        say(line, mood);
    }

    /** Если Райя слишком далеко от владельца — летит искать его; втрое дальше — телепортируется. */
    private void tickReturn() {
        if (this.returnAnnounceCooldown > 0) this.returnAnnounceCooldown -= 20;
        if (this.afraid || this.currentTask != null || hasBuildPlan()) return;
        String mode = getActiveMode();
        if (mode.startsWith("HIDE") || "COMBAT".equals(mode)) return;
        Player owner = getOwnerPlayer();
        if (owner == null || owner.level() != this.level() || owner.isSpectator()) return;

        double max = RayaConfig.RETURN_DISTANCE.get();
        double d2 = this.distanceToSqr(owner);
        if (d2 > max * max * 9.0D) {
            this.moveTo(owner.getX() + 1.2, owner.getY() + 1.5, owner.getZ() + 1.2, owner.getYRot(), 0.0F);
            this.setDeltaMovement(Vec3.ZERO);
            if (this.returnAnnounceCooldown <= 0) {
                this.returnAnnounceCooldown = 600;
                say("Нашла тебя!", RayaVoice.Mood.HAPPY);
            }
        } else if (d2 > max * max) {
            if ("STAY".equals(mode)) {
                setActiveMode("FOLLOW");
            }
            if (this.returnAnnounceCooldown <= 0) {
                this.returnAnnounceCooldown = 600;
                say("Ты далеко, лечу к тебе!", RayaVoice.Mood.NORMAL);
            }
        }
    }

    // =========================================================================
    // Анимации
    // =========================================================================

    public int getAnimId() {
        return this.entityData.get(DATA_ANIM);
    }

    public int getAnimLen() {
        return this.entityData.get(DATA_ANIM_LEN);
    }

    /** Сколько тиков прошло с начала анимации (работает и на клиенте). */
    public int getAnimElapsed() {
        return (int) this.level().getGameTime() - this.entityData.get(DATA_ANIM_START);
    }

    public boolean isAnimActive() {
        return getAnimId() != ANIM_NONE && getAnimElapsed() >= 0 && getAnimElapsed() < getAnimLen();
    }

    public boolean isTalking() {
        return (int) this.level().getGameTime() < this.entityData.get(DATA_TALK_UNTIL);
    }

    public void playAnimation(int id, int ticks) {
        this.entityData.set(DATA_ANIM, id);
        this.entityData.set(DATA_ANIM_START, (int) this.level().getGameTime());
        this.entityData.set(DATA_ANIM_LEN, Math.max(4, ticks));
    }

    /** Короткий взмах манипулятором при установке блока. */
    public void playBuildSwing() {
        if (!isAnimActive() || getAnimId() == ANIM_WORK) {
            playAnimation(ANIM_WORK, 8);
        }
    }

    public void setTalkTicks(int ticks) {
        this.entityData.set(DATA_TALK_UNTIL, (int) this.level().getGameTime() + Math.max(0, ticks));
    }

    /** Название анимации из ответа Gemini → id (0, если неизвестно). */
    public static int animIdByName(String name) {
        if (name == null) return ANIM_NONE;
        return switch (name.toUpperCase(Locale.ROOT).trim()) {
            case "DANCE" -> ANIM_DANCE;
            case "SPIN" -> ANIM_SPIN;
            case "NOD", "YES" -> ANIM_NOD;
            case "NO", "SHAKE" -> ANIM_NO;
            case "WAVE" -> ANIM_WAVE;
            default -> ANIM_NONE;
        };
    }

    public static int animDuration(int id) {
        return switch (id) {
            case ANIM_DANCE -> 80;
            case ANIM_SPIN -> 24;
            case ANIM_NOD -> 24;
            case ANIM_NO -> 24;
            case ANIM_WAVE -> 40;
            default -> 20;
        };
    }

    /** Страх — дрожит; работает — стучит манипуляторами; иногда без дела сама покрутится или помашет. */
    private void tickAutoAnimation() {
        if (isAnimActive() && getAnimId() != ANIM_WORK && getAnimId() != ANIM_TREMBLE) return;
        if (this.afraid) {
            if (getAnimId() != ANIM_TREMBLE || !isAnimActive()) playAnimation(ANIM_TREMBLE, 24);
        } else if (hasBuildPlan() || (this.currentTask != null
                && (this.currentTask.type() == RayaTask.TaskType.MINE_BLOCK || this.currentTask.type() == RayaTask.TaskType.PLACE_BLOCK))) {
            if (!isAnimActive()) playAnimation(ANIM_WORK, 12);
        } else if (!isAnimActive() && this.random.nextInt(450) == 0 && "FOLLOW".equals(getActiveMode())) {
            int[] pool = {ANIM_SPIN, ANIM_WAVE, ANIM_NOD};
            int id = pool[this.random.nextInt(pool.length)];
            playAnimation(id, animDuration(id));
        }
    }

    private void clientAnimationParticles() {
        int anim = isAnimActive() ? getAnimId() : ANIM_NONE;
        if (isTalking() && this.tickCount % 8 == 0) {
            this.level().addParticle(ParticleTypes.NOTE, this.getX(), this.getY() + 0.9D, this.getZ(),
                    this.random.nextDouble(), 0.0D, 0.0D);
        }
        if (anim == ANIM_DANCE && this.tickCount % 4 == 0) {
            this.level().addParticle(ParticleTypes.HAPPY_VILLAGER,
                    this.getX() + (this.random.nextDouble() - 0.5D) * 0.8D, this.getY() + 0.7D,
                    this.getZ() + (this.random.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.02D, 0.0D);
        }
        if (anim == ANIM_WORK && this.tickCount % 2 == 0) {
            this.level().addParticle(ParticleTypes.ELECTRIC_SPARK,
                    this.getX() + (this.random.nextDouble() - 0.5D) * 0.7D, this.getY() + 0.3D,
                    this.getZ() + (this.random.nextDouble() - 0.5D) * 0.7D, 0.0D, 0.0D, 0.0D);
        }
    }

    // =========================================================================
    // Стройка, охрана, места, забота о владельце
    // =========================================================================

    @Nullable
    public RayaBuild.Plan getBuildPlan() {
        return this.buildPlan;
    }

    public void setBuildPlan(@Nullable RayaBuild.Plan plan) {
        this.buildPlan = plan;
    }

    public boolean hasBuildPlan() {
        return this.buildPlan != null && !this.buildPlan.steps.isEmpty();
    }

    public String getBuildStatus() {
        return RayaBuild.status(this.buildPlan);
    }

    public boolean isGuarding() {
        return this.guardMode;
    }

    /** Бой закончился: вернуться к охране или к обычному следованию. */
    public void endCombat() {
        setActiveMode(this.guardMode ? "GUARD" : "FOLLOW");
    }

    /** Режим охраны: сама бросается на враждебных существ рядом с владельцем. */
    private void tickGuard() {
        if (!this.guardMode || this.afraid || !RayaConfig.ALLOW_COMBAT.get()) return;
        if (!"GUARD".equals(getActiveMode())) return;
        Player owner = getOwnerPlayer();
        if (owner == null || owner.level() != this.level()) return;
        List<String> keywords = RayaConfig.splitList(RayaConfig.FEAR_KEYWORDS.get());
        LivingEntity enemy = this.level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(12.0D),
                        e -> e != this && e.isAlive() && e instanceof Enemy && !isTerrifying(e, keywords))
                .stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
        if (enemy != null) {
            clearTasks();
            setTarget(enemy);
            setActiveMode("COMBAT");
        }
    }

    // --- Запомненные места

    public Map<String, BlockPos> getWaypoints() {
        return this.waypoints;
    }

    public boolean saveWaypoint(String name, BlockPos pos) {
        String key = name.trim();
        if (key.isEmpty()) return false;
        if (this.waypoints.size() >= 24 && !this.waypoints.containsKey(key)) return false;
        this.waypoints.put(key, pos.immutable());
        return true;
    }

    @Nullable
    public BlockPos findWaypoint(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) return null;
        for (Map.Entry<String, BlockPos> e : this.waypoints.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(n)) return e.getValue();
        }
        for (Map.Entry<String, BlockPos> e : this.waypoints.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.contains(n) || n.contains(k)) return e.getValue();
        }
        return null;
    }

    private static final String[] COMPASS = {"на север", "на северо-восток", "на восток", "на юго-восток",
            "на юг", "на юго-запад", "на запад", "на северо-запад"};

    /** Список мест с расстоянием и направлением от точки from (ось +X — восток, +Z — юг). */
    public String getWaypointSummary(BlockPos from) {
        if (this.waypoints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BlockPos> e : this.waypoints.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            BlockPos p = e.getValue();
            int dx = p.getX() - from.getX();
            int dz = p.getZ() - from.getZ();
            double deg = Math.toDegrees(Math.atan2(dx, -dz));
            if (deg < 0) deg += 360.0;
            String dir = COMPASS[(int) Math.round(deg / 45.0) % 8];
            sb.append(e.getKey()).append(" [").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                    .append("] — ").append((int) Math.sqrt((double) dx * dx + (double) dz * dz)).append(" бл. ").append(dir);
        }
        return sb.toString();
    }

    // --- Забота: предупреждения и ремонтный импульс

    private void tickCare() {
        if (this.healCooldownTicks > 0) this.healCooldownTicks -= 20;
        if (this.warnCooldownTicks > 0) this.warnCooldownTicks -= 20;
        Player owner = getOwnerPlayer();
        if (owner == null || owner.level() != this.level() || owner.isSpectator() || this.distanceToSqr(owner) > 48 * 48) return;

        if (RayaConfig.AUTO_HEAL_OWNER.get() && this.healCooldownTicks <= 0 && owner.isAlive()
                && owner.getHealth() <= owner.getMaxHealth() * 0.25F) {
            this.healCooldownTicks = 20 * 180;
            owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 8, 1));
            say("Запускаю ремонтный импульс! Держись, мой носитель.", RayaVoice.Mood.FEAR);
            return;
        }

        if (!RayaConfig.DANGER_WARNINGS.get() || this.warnCooldownTicks > 0 || this.afraid) return;
        String warn = null;
        if (owner.getAirSupply() < 60 && owner.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
            warn = "Ты задыхаешься! Скорее наверх, за воздухом!";
        } else if (owner.isOnFire() && !owner.fireImmune()) {
            warn = "Ты горишь! Беги к воде!";
        } else {
            boolean creeper = !this.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
                    owner.getBoundingBox().inflate(7.0D), c -> c.isAlive()).isEmpty();
            if (creeper) warn = "Осторожно, крипер рядом! Отойди подальше!";
        }
        if (warn != null) {
            this.warnCooldownTicks = 20 * 12;
            say(warn, RayaVoice.Mood.FEAR);
        }
    }

    // =========================================================================
    // Геттеры/сеттеры, NBT
    // =========================================================================

    public Optional<UUID> getOwnerUUID() {
        return this.entityData.get(DATA_OWNER_UUID);
    }

    public void setOwnerUUID(@Nullable UUID uuid) {
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(uuid));
    }

    public boolean isOwnedBy(Entity entity) {
        return entity != null && getOwnerUUID().map(id -> id.equals(entity.getUUID())).orElse(false);
    }

    @Nullable
    public Player getOwnerPlayer() {
        return getOwnerUUID().map(id -> this.level().getPlayerByUUID(id)).orElse(null);
    }

    public int getEmotionState() {
        return this.entityData.get(DATA_EMOTION_STATE);
    }

    public void setEmotionState(int state) {
        this.entityData.set(DATA_EMOTION_STATE, state);
    }

    public boolean isCrimsonMode() {
        return getEmotionState() == EMOTION_CRIMSON;
    }

    public String getActiveMode() {
        return this.entityData.get(DATA_ACTIVE_MODE);
    }

    public void setActiveMode(String mode) {
        if (!"COMBAT".equals(mode)) {
            this.guardMode = "GUARD".equals(mode);
        }
        this.entityData.set(DATA_ACTIVE_MODE, mode);
        this.hideSpotCache = null;
        restoreBaseEmotion();
    }

    public SimpleContainer getRayaInventory() {
        return this.inventory;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            // Вещи из инвентаря Раи не пропадают, а выпадают на месте
            for (int i = 0; i < this.inventory.getContainerSize(); i++) {
                ItemStack s = this.inventory.removeItemNoUpdate(i);
                if (!s.isEmpty()) {
                    this.spawnAtLocation(s);
                }
            }
        }
        super.die(source);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
            this.moveRelative(0.1F, travelVector);
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(0.82D));
        }
        this.calculateEntityAnimation(false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        getOwnerUUID().ifPresent(uuid -> tag.putUUID("Owner", uuid));
        tag.putString("ActiveMode", getActiveMode());
        ListTag items = new ListTag();
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                items.add(stack.save(this.registryAccess(), slotTag));
            }
        }
        tag.put("RayaInventory", items);
        ListTag wps = new ListTag();
        for (Map.Entry<String, BlockPos> e : this.waypoints.entrySet()) {
            CompoundTag w = new CompoundTag();
            w.putString("Name", e.getKey());
            w.putInt("X", e.getValue().getX());
            w.putInt("Y", e.getValue().getY());
            w.putInt("Z", e.getValue().getZ());
            wps.add(w);
        }
        tag.put("Waypoints", wps);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        if (tag.contains("ActiveMode")) {
            String mode = tag.getString("ActiveMode");
            // COMBAT без цели после загрузки бессмысленен
            this.entityData.set(DATA_ACTIVE_MODE, "COMBAT".equals(mode) ? "FOLLOW" : mode);
        }
        setEmotionState(EMOTION_NORMAL);
        restoreBaseEmotion();
        this.waypoints.clear();
        ListTag wps = tag.getList("Waypoints", 10);
        for (int i = 0; i < wps.size(); i++) {
            CompoundTag w = wps.getCompound(i);
            this.waypoints.put(w.getString("Name"), new BlockPos(w.getInt("X"), w.getInt("Y"), w.getInt("Z")));
        }
        this.guardMode = "GUARD".equals(tag.getString("ActiveMode"));
        ListTag items = tag.getList("RayaInventory", 10);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag slotTag = items.getCompound(i);
            int slot = slotTag.getByte("Slot") & 255;
            if (slot < this.inventory.getContainerSize()) {
                ItemStack.parse(this.registryAccess(), slotTag).ifPresent(s -> this.inventory.setItem(slot, s));
            }
        }
    }

    public record RayaTask(TaskType type, BlockPos targetPos, String filter) {
        public enum TaskType {
            MINE_BLOCK,
            PLACE_BLOCK,
            FLY_TO,
            COLLECT_ITEMS,
            DEPOSIT
        }
    }
}
