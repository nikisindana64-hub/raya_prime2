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
    public static final int ANIM_FLIP = 8;
    public static final int ANIM_LAUGH = 9;
    public static final int ANIM_SHRUG = 10;
    public static final int ANIM_THINK = 11;
    public static final int ANIM_BOW = 12;
    public static final int ANIM_SALUTE = 13;
    public static final int ANIM_LOVE = 14;

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
    private int petCooldown = 0;
    private int patStreak = 0;
    private long lastPatTime = -1000L;
    private int reactCooldown = 0;
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
        this.goalSelector.addGoal(3, new RayaGoals.RayaGuideGoal(this));
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
                tickReminders();
                if (this.petCooldown > 0) this.petCooldown -= 20;
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
            if (serverPlayer.isShiftKeyDown() && serverPlayer.getItemInHand(hand).isEmpty()) {
                // Shift + ПКМ пустой рукой — погладить
                onPatted(serverPlayer);
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
        if (mood == RayaVoice.Mood.HAPPY && !isAnimActive() && this.random.nextInt(3) == 0) {
            playAnimation(ANIM_WAVE, animDuration(ANIM_WAVE));
        }
        Player owner = getOwnerPlayer();
        if (owner instanceof ServerPlayer sp && this.level() instanceof ServerLevel sl) {
            // текст в чате появляется одновременно с началом голоса
            RayaVoice.speak(sl.getServer(), this, sp, text, RayaConfig.getEffectiveApiKey(), mood,
                    RayaConfig.SYNC_CHAT_WITH_VOICE.get() ? () -> tell(text) : null);
            if (!RayaConfig.SYNC_CHAT_WITH_VOICE.get()) tell(text);
        } else {
            tell(text);
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
            this.flyTo(
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
            this.flyTo(
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
            this.flyTo(nearest.getX(), nearest.getY() + 0.3, nearest.getZ(), 1.4D);
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

    /** Прервать текущую анимацию. */
    public void stopAnimation() {
        this.entityData.set(DATA_ANIM, ANIM_NONE);
    }

    /** Подобрать анимацию по тексту реплики, если Gemini её не указал. */
    public static int animForText(String reply) {
        if (reply == null) return ANIM_NONE;
        String t = reply.toLowerCase(Locale.ROOT);
        if (t.contains("хаха") || t.contains("ха-ха") || t.contains("хихи") || t.contains("смешн")) return ANIM_LAUGH;
        if (t.contains("не знаю") || t.contains("без понятия") || t.contains("затрудняюсь")) return ANIM_SHRUG;
        if (t.contains("извин") || t.contains("прости") || t.contains("виновата")) return ANIM_BOW;
        if (t.contains("так точно") || t.contains("есть, капитан") || t.contains("слушаюсь")) return ANIM_SALUTE;
        if (t.contains("люблю") || t.contains("обожаю") || t.contains("спасибо") || t.contains("мило")) return ANIM_LOVE;
        if (t.contains("ура") || t.contains("получилось") || t.contains("победа")) return ANIM_FLIP;
        return ANIM_NONE;
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
            case "FLIP", "BACKFLIP", "ROLL" -> ANIM_FLIP;
            case "LAUGH", "LOL", "BOUNCE" -> ANIM_LAUGH;
            case "SHRUG", "DONTKNOW" -> ANIM_SHRUG;
            case "THINK", "PONDER" -> ANIM_THINK;
            case "BOW", "SORRY" -> ANIM_BOW;
            case "SALUTE" -> ANIM_SALUTE;
            case "LOVE", "HEART" -> ANIM_LOVE;
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
            case ANIM_FLIP -> 28;
            case ANIM_LAUGH -> 44;
            case ANIM_SHRUG -> 36;
            case ANIM_THINK -> 60;
            case ANIM_BOW -> 36;
            case ANIM_SALUTE -> 40;
            case ANIM_LOVE -> 60;
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
            int[] pool = {ANIM_SPIN, ANIM_WAVE, ANIM_NOD, ANIM_FLIP, ANIM_SHRUG, ANIM_THINK};
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
        if ((anim == ANIM_LOVE || anim == ANIM_LAUGH) && this.tickCount % 5 == 0) {
            this.level().addParticle(anim == ANIM_LOVE ? ParticleTypes.HEART : ParticleTypes.HAPPY_VILLAGER,
                    this.getX() + (this.random.nextDouble() - 0.5D) * 0.7D, this.getY() + 0.9D,
                    this.getZ() + (this.random.nextDouble() - 0.5D) * 0.7D, 0.0D, 0.03D, 0.0D);
        }
        if (anim == ANIM_THINK && this.tickCount % 10 == 0) {
            this.level().addParticle(ParticleTypes.ENCHANT, this.getX(), this.getY() + 0.9D, this.getZ(),
                    (this.random.nextDouble() - 0.5D) * 0.6D, 0.4D, (this.random.nextDouble() - 0.5D) * 0.6D);
        }
        if (anim == ANIM_FLIP && this.tickCount % 2 == 0) {
            this.level().addParticle(ParticleTypes.END_ROD, this.getX(), this.getY() + 0.4D, this.getZ(), 0.0D, 0.0D, 0.0D);
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
        if (this.reactCooldown > 0) this.reactCooldown -= 20;
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

    // ---------- Проводник (GUIDE): ведёт игрока к точке ----------
    @Nullable private BlockPos guideTarget = null;
    private String guideName = "";
    private int guideTicks = 0;
    private int guideWaitTicks = 0;

    public void startGuide(BlockPos target, String name) {
        this.guideTarget = target.immutable();
        this.guideName = name == null ? "" : name;
        this.guideTicks = 0;
        this.guideWaitTicks = 0;
        setActiveMode("GUIDE");
    }

    @Nullable
    public BlockPos getGuideTarget() {
        return "GUIDE".equals(getActiveMode()) ? this.guideTarget : null;
    }

    public String getGuideName() {
        return this.guideName;
    }

    /** Вызывается целью-проводником каждый тик; возвращает true, пока ведём. */
    public boolean tickGuideStep(Player owner) {
        BlockPos t = this.guideTarget;
        if (t == null) return false;
        this.guideTicks++;
        if (this.guideTicks > 20 * 60 * 12) {
            tell("Мы слишком долго идём, прекращаю вести.");
            endGuide();
            return false;
        }
        double tx = t.getX() + 0.5D, tz = t.getZ() + 0.5D;
        double dx = tx - owner.getX(), dz = tz - owner.getZ();
        double horizOwner = Math.sqrt(dx * dx + dz * dz);
        if (horizOwner < 5.0D && Math.abs(t.getY() - owner.getY()) < 8.0D) {
            say("Мы на месте" + (this.guideName.isEmpty() ? "!" : ": " + this.guideName + "!"), RayaVoice.Mood.HAPPY);
            playAnimation(ANIM_DANCE, animDuration(ANIM_DANCE));
            endGuide();
            return false;
        }
        double ownerDist = this.distanceTo(owner);
        this.getLookControl().setLookAt(owner, 25.0F, 25.0F);
        if (ownerDist > 14.0D) {
            // игрок отстал — ждём его (издалека возвращаемся к нему)
            this.guideWaitTicks++;
            if (ownerDist > 30.0D) {
                this.flyTo(owner.getX(), owner.getY() + 1.8D, owner.getZ(), 1.3D);
            }
            if (this.guideWaitTicks % 400 == 200) {
                say(this.random.nextBoolean() ? "Не отставай, я жду!" : "Эй, носитель, мы идём туда, а не обратно!", RayaVoice.Mood.NORMAL);
            }
            return true;
        }
        this.guideWaitTicks = 0;
        double dxr = tx - this.getX(), dzr = tz - this.getZ();
        double hr = Math.sqrt(dxr * dxr + dzr * dzr);
        double step = Math.min(10.0D, Math.max(hr, 1.0D));
        double px = this.getX() + dxr / Math.max(hr, 0.001D) * step;
        double pz = this.getZ() + dzr / Math.max(hr, 0.001D) * step;
        double py = this.getY() + Mth.clamp(t.getY() + 1.5D - this.getY(), -2.5D, 2.5D);
        py = Math.max(py, owner.getY() + 0.8D);
        this.flyTo(px, py, pz, 1.1D);
        return true;
    }

    public void endGuide() {
        this.guideTarget = null;
        this.guideName = "";
        if ("GUIDE".equals(getActiveMode())) setActiveMode("FOLLOW");
    }

    // ---------- Напоминания и заметки ----------
    private final java.util.List<Object[]> reminders = new java.util.ArrayList<>(); // {Long dueGameTime, String text}
    private final java.util.List<String> notes = new java.util.ArrayList<>();

    public boolean addReminder(int seconds, String text) {
        if (text == null || text.isBlank() || seconds <= 0 || this.reminders.size() >= 8) return false;
        String t = text.trim();
        if (t.length() > 160) t = t.substring(0, 160);
        this.reminders.add(new Object[]{this.level().getGameTime() + seconds * 20L, t});
        return true;
    }

    public boolean addNote(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        if (t.length() > 160) t = t.substring(0, 160);
        for (String n : this.notes) if (n.equalsIgnoreCase(t)) return true;
        if (this.notes.size() >= 30) this.notes.remove(0);
        this.notes.add(t);
        return true;
    }

    /** Забыть заметки, содержащие фрагмент; "all" или пусто — все. Возвращает, сколько забыла. */
    public int forgetNotes(String fragment) {
        String f = fragment == null ? "" : fragment.toLowerCase(Locale.ROOT).trim();
        int before = this.notes.size();
        if (f.isEmpty() || f.equals("all") || f.equals("все") || f.equals("всё")) {
            this.notes.clear();
        } else {
            this.notes.removeIf(n -> n.toLowerCase(Locale.ROOT).contains(f));
        }
        return before - this.notes.size();
    }

    public String getNotesSummary() {
        return String.join("; ", this.notes);
    }

    public String getRemindersSummary() {
        if (this.reminders.isEmpty()) return "";
        long now = this.level().getGameTime();
        StringBuilder sb = new StringBuilder();
        for (Object[] r : this.reminders) {
            if (sb.length() > 0) sb.append("; ");
            long left = Math.max(0L, ((Long) r[0] - now) / 20L);
            sb.append('«').append((String) r[1]).append("» через ").append(left >= 90 ? (left / 60) + " мин" : left + " с");
        }
        return sb.toString();
    }

    private void tickReminders() {
        if (this.reminders.isEmpty()) return;
        long now = this.level().getGameTime();
        for (java.util.Iterator<Object[]> it = this.reminders.iterator(); it.hasNext(); ) {
            Object[] r = it.next();
            if ((Long) r[0] <= now) {
                it.remove();
                say("Напоминаю: " + r[1], RayaVoice.Mood.NORMAL);
                playAnimation(ANIM_WAVE, animDuration(ANIM_WAVE));
            }
        }
    }

    // ---------- Поглаживание и реакции на события ----------

    private static final String[] PAT_LINES = {"Ой, приятно! Мои ушки это любят.", "Хи-хи, носитель, ты ласковый сегодня.",
            "Систему охлаждения заклинило от счастья!", "Фиксирую рост настроения: плюс 87 процентов.",
            "Мур. То есть, гм, звуковой сигнал удовлетворения.", "Эй, так и до перегрева недалеко. Но не останавливайся."};
    private static final String[] PAT_MANY_LINES = {"Ты так меня совсем разбалуешь!", "Ушки гудят от счастья, остановись, а то взлечу!",
            "Рекорд поглаживаний обновлён. Я, между прочим, лучшая П.Е.Р.С.И.К.!"};
    private static final String[] PAT_OVERLOAD_LINES = {"Хватит-хватит, у меня перегрев ушек! Ну... ещё чуть-чуть.", "Всё, я растаяла. Серьёзно, процессор плавится!"};

    /** Погладили: сердечки, радость и (не слишком часто) реплика. Вызывается и от Shift+ПКМ, и от клиента (PatPat). */
    public void onPatted(Player by) {
        if (!(this.level() instanceof ServerLevel sl) || !RayaConfig.PAT_REACTIONS.get()) return;
        long now = sl.getGameTime();
        if (now - this.lastPatTime < 6) return; // дубль от двух путей (ванильный интеракт + пакет)
        this.patStreak = (now - this.lastPatTime < 200) ? this.patStreak + 1 : 1;
        this.lastPatTime = now;
        sl.sendParticles(ParticleTypes.HEART, getX(), getY() + 0.9D, getZ(), 2, 0.3D, 0.2D, 0.3D, 0.02D);
        sl.playSound(null, this.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.7F, 1.4F + this.random.nextFloat() * 0.4F);
        if (!isAnimActive() || getAnimId() == ANIM_THINK) playAnimation(ANIM_LOVE, animDuration(ANIM_LOVE));
        showEmotion(EMOTION_HAPPY, 120);
        if (this.petCooldown > 0) return;
        this.petCooldown = 140;
        String[] pool = this.patStreak >= 9 ? PAT_OVERLOAD_LINES : this.patStreak >= 4 ? PAT_MANY_LINES : PAT_LINES;
        say(pool[this.random.nextInt(pool.length)], RayaVoice.Mood.HAPPY);
    }

    /** Самостоятельная реакция на событие (с общей паузой, чтобы не болтать без остановки). */
    public void react(String text, RayaVoice.Mood mood, int anim) {
        if (!RayaConfig.EVENT_REACTIONS.get() || this.reactCooldown > 0 || this.afraid) return;
        this.reactCooldown = 300;
        if (anim != ANIM_NONE) playAnimation(anim, animDuration(anim));
        say(text, mood);
    }

    public void reactAny(String[] lines, RayaVoice.Mood mood, int anim) {
        react(lines[this.random.nextInt(lines.length)], mood, anim);
    }

    /** Нажать кнопку/рычаг/дверь и т.п. Возвращает true, когда задача выполнена. */
    public boolean tickUseBlock(BlockPos pos) {
        if (!RayaConfig.ALLOW_USE_BLOCKS.get()) {
            tell("Нажимать блоки мне запрещено в конфиге.");
            return true;
        }
        if (!canActOn(pos)) {
            tell("Эта точка вне допустимой зоны.");
            return true;
        }
        double d = this.distanceToSqr(Vec3.atCenterOf(pos));
        if (d > 12.0D) {
            this.flyTo(pos.getX() + 0.5D, pos.getY() + 1.2D, pos.getZ() + 0.5D, 1.35D);
            return false;
        }
        this.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        BlockState st = this.level().getBlockState(pos);
        net.minecraft.world.level.block.Block b = st.getBlock();
        boolean ok = b instanceof net.minecraft.world.level.block.ButtonBlock
                || b instanceof net.minecraft.world.level.block.LeverBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.NoteBlock;
        Player owner = getOwnerPlayer();
        if (!ok || owner == null) {
            tell("Этот блок я не умею нажимать.");
            return true;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        st.useWithoutItem(this.level(), owner, hit);
        playAnimation(ANIM_WORK, 10);
        return true;
    }

    // ---------- Полёт с обходом препятствий ----------
    private Vec3 flyGoal = null;
    private int flyRepath = 0;
    private boolean flyClear = true;
    private int flyCheckTick = 0;
    private Vec3 flyLastPos = Vec3.ZERO;
    private int flyStuck = 0;
    private int flyUnstick = 0;
    private Vec3 flyUnstickDir = Vec3.ZERO;

    /**
     * Лететь к точке, обходя блоки. Если прямая свободна — летит напрямую; иначе строит путь через FlyingPathNavigation
     * (для далёких целей — по промежуточным точкам); если застряла — поднимается вверх и огибает сбоку.
     * Вызывать каждый тик, пока нужно двигаться.
     */
    public void flyTo(double x, double y, double z, double speed) {
        Vec3 goal = new Vec3(x, y, z);
        double dSq = this.position().distanceToSqr(goal);
        if (dSq < 6.25D) {
            this.getNavigation().stop();
            this.flyGoal = null;
            this.flyStuck = 0;
            this.flyUnstick = 0;
            this.getMoveControl().setWantedPosition(x, y, z, speed);
            return;
        }
        boolean goalMoved = this.flyGoal == null || this.flyGoal.distanceToSqr(goal) > 9.0D;
        this.flyGoal = goal;

        // обнаружение застревания: раз в секунду смотрим, сдвинулась ли она
        if (this.tickCount % 20 == 0) {
            if (this.position().distanceToSqr(this.flyLastPos) < 0.36D) this.flyStuck++;
            else this.flyStuck = 0;
            this.flyLastPos = this.position();
            if (this.flyStuck >= 2 && this.flyUnstick <= 0) {
                // выбираем обход: вверх и вбок (направление случайно слева/справа от линии к цели)
                Vec3 dir = goal.subtract(this.position());
                Vec3 side = new Vec3(-dir.z, 0, dir.x);
                if (side.lengthSqr() < 1.0E-4) side = new Vec3(1, 0, 0);
                side = side.normalize().scale(this.random.nextBoolean() ? 3.0D : -3.0D);
                this.flyUnstickDir = new Vec3(side.x, 3.5D, side.z);
                this.flyUnstick = 25;
                this.flyStuck = 0;
                this.getNavigation().stop();
            }
        }
        if (this.flyUnstick > 0) {
            this.flyUnstick--;
            Vec3 t = this.position().add(this.flyUnstickDir);
            this.getMoveControl().setWantedPosition(t.x, t.y, t.z, speed);
            return;
        }

        if (goalMoved || ++this.flyCheckTick >= 4) {
            this.flyCheckTick = 0;
            this.flyClear = isFlightLineClear(goal);
        }
        if (this.flyClear) {
            this.getNavigation().stop();
            this.getMoveControl().setWantedPosition(x, y, z, speed);
            return;
        }

        // прямой путь перегорожен — идём по навигации
        if (goalMoved || --this.flyRepath <= 0) {
            this.flyRepath = 20;
            double dist = Math.sqrt(dSq);
            Vec3 t = goal;
            if (dist > 28.0D) {
                t = this.position().add(goal.subtract(this.position()).scale(24.0D / dist));
            }
            this.getNavigation().moveTo(t.x, t.y, t.z, speed);
        }
        if (this.getNavigation().isDone()) {
            // пути нет (или он закончился у стены) — поднимаемся над препятствием и двигаемся в сторону цели
            Vec3 dir = goal.subtract(this.position());
            Vec3 h = new Vec3(dir.x, 0, dir.z);
            h = h.lengthSqr() > 1.0E-4 ? h.normalize().scale(2.0D) : Vec3.ZERO;
            this.getMoveControl().setWantedPosition(this.getX() + h.x, this.getY() + 2.5D, this.getZ() + h.z, speed);
        }
    }

    /** Три луча (по центру и по бокам) без столкновений с блоками на пути к цели. */
    private boolean isFlightLineClear(Vec3 goal) {
        Vec3 from = this.position().add(0, this.getBbHeight() * 0.5D, 0);
        Vec3 to = goal.add(0, this.getBbHeight() * 0.5D - 0.2D, 0);
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 1.0E-3) return true;
        double check = Math.min(len - 0.5D, 26.0D);
        if (check <= 0.2D) return true;
        Vec3 unit = dir.scale(1.0D / len);
        Vec3 side = new Vec3(-unit.z, 0, unit.x);
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(0.35D, 0, 0) : side.normalize().scale(0.35D);
        Vec3 end = from.add(unit.scale(check));
        Vec3[] offs = {Vec3.ZERO, side, side.scale(-1.0D), new Vec3(0, 0.4D, 0), new Vec3(0, -0.4D, 0)};
        for (Vec3 o : offs) {
            BlockHitResult hit = this.level().clip(new ClipContext(from.add(o), end.add(o),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() != HitResult.Type.MISS) return false;
        }
        return true;
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
        ListTag noteList = new ListTag();
        for (String n : this.notes) noteList.add(net.minecraft.nbt.StringTag.valueOf(n));
        tag.put("Notes", noteList);
        ListTag remList = new ListTag();
        for (Object[] r : this.reminders) {
            CompoundTag c = new CompoundTag();
            c.putLong("Due", (Long) r[0]);
            c.putString("Text", (String) r[1]);
            remList.add(c);
        }
        tag.put("Reminders", remList);
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
        if ("GUIDE".equals(tag.getString("ActiveMode"))) this.entityData.set(DATA_ACTIVE_MODE, "FOLLOW");
        this.notes.clear();
        ListTag noteList = tag.getList("Notes", 8);
        for (int i = 0; i < noteList.size(); i++) this.notes.add(noteList.getString(i));
        this.reminders.clear();
        ListTag remList = tag.getList("Reminders", 10);
        for (int i = 0; i < remList.size(); i++) {
            CompoundTag c = remList.getCompound(i);
            this.reminders.add(new Object[]{c.getLong("Due"), c.getString("Text")});
        }
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
            DEPOSIT,
            USE_BLOCK
        }
    }
}
