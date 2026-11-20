package com.mamarala.rayaprime.entity;

import com.mamarala.rayaprime.config.RayaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ИИ-цели тела Раи Прайм:
 * 1. RayaHideGoal — прятаться за спиной игрока или за блоками (с проверкой линии видимости).
 * 2. RayaCombatGoal — атаковать врагов в ближнем бою.
 * 3. RayaExecuteTaskGoal — очередь задач от Gemini (ломать/ставить блоки, лететь, собирать предметы).
 * 4. RayaFollowOwnerGoal — парить рядом с владельцем.
 */
public class RayaGoals {

    public static class RayaHideGoal extends Goal {
        private final RayaPrimeEntity raya;

        public RayaHideGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            String mode = raya.getActiveMode();
            return "HIDE_BEHIND_PLAYER".equals(mode) || "HIDE_BEHIND_BLOCKS".equals(mode);
        }

        @Override
        public void start() {
            raya.setEmotionState(RayaPrimeEntity.EMOTION_HIDING);
        }

        @Override
        public void stop() {
            raya.restoreBaseEmotion();
        }

        @Override
        public void tick() {
            boolean behindBlocks = "HIDE_BEHIND_BLOCKS".equals(raya.getActiveMode());
            Vec3 hideSpot = raya.getHidePositionCached(behindBlocks);
            if (raya.position().distanceToSqr(hideSpot) > 0.5D) {
                raya.flyTo(hideSpot.x, hideSpot.y, hideSpot.z, 1.45D);
            }

            LivingEntity threat = raya.findNearestThreat();
            if (threat != null) {
                raya.getLookControl().setLookAt(threat, 30.0F, 30.0F);
            } else if (raya.getOwnerPlayer() != null) {
                raya.getLookControl().setLookAt(raya.getOwnerPlayer(), 30.0F, 30.0F);
            }
        }
    }

    public static class RayaCombatGoal extends Goal {
        private final RayaPrimeEntity raya;
        private int attackCooldown = 0;

        public RayaCombatGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            if (!"COMBAT".equals(raya.getActiveMode()) || !RayaConfig.ALLOW_COMBAT.get()) {
                return false;
            }
            LivingEntity target = raya.getTarget();
            if (target == null || !target.isAlive()) {
                target = raya.findNearestThreat();
                raya.setTarget(target);
            }
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            raya.setEmotionState(RayaPrimeEntity.EMOTION_CRIMSON);
        }

        @Override
        public void stop() {
            raya.setTarget(null);
            raya.restoreBaseEmotion();
        }

        @Override
        public void tick() {
            LivingEntity target = raya.getTarget();
            if (target == null || !target.isAlive()) {
                raya.endCombat();
                return;
            }
            raya.getLookControl().setLookAt(target, 30.0F, 30.0F);
            raya.flyTo(target.getX(), target.getEyeY(), target.getZ(), 1.4D);

            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (raya.distanceToSqr(target) <= 5.5D && attackCooldown <= 0) {
                raya.doHurtTarget(target);
                attackCooldown = 16;
            }
        }
    }

    public static class RayaExecuteTaskGoal extends Goal {
        private final RayaPrimeEntity raya;

        public RayaExecuteTaskGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            return raya.peekOrPollTask() != null;
        }

        @Override
        public void tick() {
            RayaPrimeEntity.RayaTask task = raya.peekOrPollTask();
            if (task == null) return;

            BlockPos pos = task.targetPos();
            switch (task.type()) {
                case MINE_BLOCK -> {
                    if (raya.tickMineBlock(pos)) {
                        raya.completeCurrentTask();
                    }
                }
                case PLACE_BLOCK -> {
                    if (raya.tickPlaceBlock(pos, task.filter())) {
                        raya.completeCurrentTask();
                    }
                }
                case FLY_TO -> {
                    if (!raya.canActOn(pos)) {
                        raya.tell("Эта точка слишком далеко от тебя, туда я не полечу.");
                        raya.completeCurrentTask();
                        return;
                    }
                    raya.flyTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1.3D);
                    if (raya.distanceToSqr(Vec3.atCenterOf(pos)) <= 3.0D) {
                        raya.completeCurrentTask();
                    }
                }
                case COLLECT_ITEMS -> {
                    if (raya.tickCollectItems(task.filter())) {
                        raya.completeCurrentTask();
                    }
                }
                case DEPOSIT -> {
                    if (RayaBuild.tickDeposit(raya, pos, task.filter())) {
                        raya.completeCurrentTask();
                    }
                }
                case USE_BLOCK -> {
                    if (raya.tickUseBlock(pos)) {
                        raya.completeCurrentTask();
                    }
                }
            }
        }
    }

    /** Строительство по плану (дом, башня, чертёж Gemini, факелы). */
    public static class RayaBuildGoal extends Goal {
        private final RayaPrimeEntity raya;

        public RayaBuildGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            if (!raya.hasBuildPlan() || raya.isAfraid()) return false;
            String mode = raya.getActiveMode();
            return !mode.startsWith("HIDE") && !"COMBAT".equals(mode);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            RayaBuild.tick(raya);
        }
    }

    /** Проводник: ведёт игрока к цели, дожидаясь его, если он отстал. */
    public static class RayaGuideGoal extends Goal {
        private final RayaPrimeEntity raya;

        public RayaGuideGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            if (raya.isAfraid() || raya.getGuideTarget() == null) return false;
            Player owner = raya.getOwnerPlayer();
            return owner != null && owner.isAlive() && owner.level() == raya.level();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            Player owner = raya.getOwnerPlayer();
            if (owner != null) raya.tickGuideStep(owner);
        }
    }

    public static class RayaFollowOwnerGoal extends Goal {
        private final RayaPrimeEntity raya;

        public RayaFollowOwnerGoal(RayaPrimeEntity raya) {
            this.raya = raya;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            if ("STAY".equals(raya.getActiveMode())) {
                return false;
            }
            Player owner = raya.getOwnerPlayer();
            return owner != null && owner.isAlive() && !owner.isSpectator();
        }

        @Override
        public void tick() {
            Player owner = raya.getOwnerPlayer();
            if (owner == null) return;

            double distSq = raya.distanceToSqr(owner);
            raya.getLookControl().setLookAt(owner, 25.0F, 25.0F);

            if (distSq > 9.0D) {
                // Парим чуть сбоку и выше плеча носителя (далеко-далеко — телепорт делает сама Райя в tickReturn)
                Vec3 right = owner.getLookAngle().cross(new Vec3(0, 1, 0)).normalize().scale(1.4D);
                Vec3 hoverPos = owner.position().add(right).add(0, 1.55D, 0);
                raya.flyTo(hoverPos.x, hoverPos.y, hoverPos.z, 1.2D);
            }
        }
    }
}
