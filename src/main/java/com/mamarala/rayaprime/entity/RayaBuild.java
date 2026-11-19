package com.mamarala.rayaprime.entity;

import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.voice.RayaVoice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Строительство Раи: план из блоков (дом, башня, стена, платформа, лестница, забор, свой чертёж от Gemini),
 * расстановка факелов и раскладка вещей по сундукам.
 * Блоки берутся из инвентаря Раи, затем (если разрешено) из инвентаря владельца; в творческом режиме — бесплатно.
 */
public final class RayaBuild {

    private RayaBuild() {}

    /** Один блок плана. block — часть ID/названия ("" = любой строительный блок); fallback — запасной блок. */
    public record Step(BlockPos pos, String block, @Nullable String fallback) {}

    public static final class Plan {
        public final ArrayDeque<Step> steps = new ArrayDeque<>();
        public final String name;
        public int total;
        int fails;
        int stall;
        int outOfRange;
        int skipped;
        long lastNoMaterialMsg = -10000;
        String missing = "";

        public Plan(String name) {
            this.name = name;
        }
    }

    // ------------------------------------------------------------------ система координат плана

    /** Локальные координаты: rx — вправо, ry — вверх, rz — вперёд (от игрока). */
    private record Frame(BlockPos origin, Direction fwd) {
        BlockPos at(int rx, int ry, int rz) {
            Direction right = fwd.getClockWise();
            return origin.relative(right, rx).relative(Direction.UP, ry).relative(fwd, rz);
        }
    }

    public static int maxBlocks() {
        return RayaConfig.MAX_BUILD_BLOCKS.get();
    }

    // ------------------------------------------------------------------ генерация фигур

    /**
     * @return план или null, если фигура неизвестна
     */
    @Nullable
    public static Plan generate(String shapeRaw, BlockPos origin, Direction fwd, int w, int d, int h,
                                String wall, String roof) {
        String shape = shapeRaw == null ? "" : shapeRaw.toLowerCase(Locale.ROOT).trim();
        Frame f = new Frame(origin, fwd);
        String wallBlock = (wall == null || wall.isBlank()) ? "planks" : wall.trim(); // без названия — доски, а не что попало
        String roofBlock = (roof == null || roof.isBlank()) ? wallBlock : roof.trim();
        List<Step> out = new ArrayList<>();
        String name;

        switch (shape) {
            case "house", "hut", "home", "дом", "домик" -> {
                name = "дом";
                int ww = clamp(w, 3, 15, 7), dd = clamp(d, 3, 15, 7), hh = clamp(h, 3, 8, 3);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = -1; y <= hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) {
                        for (int rz = 0; rz < dd; rz++) {
                            boolean edge = rx == lo || rx == hi || rz == 0 || rz == dd - 1;
                            if (y == -1) {
                                out.add(new Step(f.at(rx, y, rz), wallBlock, null));
                            } else if (y == hh) {
                                out.add(new Step(f.at(rx, y, rz), roofBlock, wallBlock));
                            } else if (edge) {
                                if (rz == 0 && rx == 0 && y <= 1) continue; // дверной проём 1×2
                                boolean corner = (rx == lo || rx == hi) && (rz == 0 || rz == dd - 1);
                                boolean window = y == 1 && hh >= 3 && !corner && ((rx + rz) % 3 == 0)
                                        && !(rz == 0 && Math.abs(rx) <= 1);
                                out.add(new Step(f.at(rx, y, rz), window ? "glass" : wallBlock, window ? wallBlock : null));
                            }
                        }
                    }
                }
            }
            case "tower", "башня" -> {
                name = "башня";
                int ww = clamp(w, 3, 7, 3), hh = clamp(h, 3, 32, 10);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = -1; y < hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) {
                        for (int rz = 0; rz < ww; rz++) {
                            boolean edge = rx == lo || rx == hi || rz == 0 || rz == ww - 1;
                            if (y == -1 || edge) {
                                if (y >= 0 && rz == 0 && rx == 0 && y <= 1) continue;
                                out.add(new Step(f.at(rx, y, rz), wallBlock, null));
                            }
                        }
                    }
                }
            }
            case "wall", "стена" -> {
                name = "стена";
                int ww = clamp(w, 1, 40, 7), hh = clamp(h, 1, 12, 3);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = 0; y < hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) out.add(new Step(f.at(rx, y, 0), wallBlock, null));
                }
            }
            case "fence", "pen", "забор" -> {
                name = "ограда";
                int ww = clamp(w, 3, 25, 9), dd = clamp(d, 3, 25, 9), hh = clamp(h, 1, 4, 2);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = 0; y < hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) {
                        for (int rz = 0; rz < dd; rz++) {
                            boolean edge = rx == lo || rx == hi || rz == 0 || rz == dd - 1;
                            if (edge) out.add(new Step(f.at(rx, y, rz), wallBlock, null));
                        }
                    }
                }
            }
            case "platform", "floor", "bridge", "платформа", "пол", "мост" -> {
                name = "платформа";
                int ww = clamp(w, 1, 25, 5), dd = clamp(d, 1, 40, 5);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int rz = 0; rz < dd; rz++) {
                    for (int rx = lo; rx <= hi; rx++) out.add(new Step(f.at(rx, -1, rz), wallBlock, null));
                }
            }
            case "box", "cube", "куб", "блок" -> {
                name = "куб";
                int ww = clamp(w, 1, 10, 3), dd = clamp(d, 1, 10, 3), hh = clamp(h, 1, 10, 3);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = 0; y < hh; y++) {
                    for (int rz = 0; rz < dd; rz++) {
                        for (int rx = lo; rx <= hi; rx++) out.add(new Step(f.at(rx, y, rz), wallBlock, null));
                    }
                }
            }
            case "pillar", "column", "колонна", "столб" -> {
                name = "колонна";
                int hh = clamp(h, 1, 40, 5);
                for (int y = 0; y < hh; y++) out.add(new Step(f.at(0, y, 0), wallBlock, null));
            }
            case "stairs", "staircase", "лестница" -> {
                name = "лестница";
                int hh = clamp(h, 1, 30, 6);
                for (int i = 0; i < hh; i++) {
                    out.add(new Step(f.at(0, i, i), wallBlock, null));
                    out.add(new Step(f.at(-1, i, i), wallBlock, null));
                }
            }
            default -> {
                return null;
            }
        }
        return finish(name, out);
    }

    /** Свой чертёж: список блоков в локальных координатах (dx вправо, dy вверх, dz вперёд). */
    public static Plan custom(String name, BlockPos origin, Direction fwd, List<int[]> coords, List<String> blocks) {
        Frame f = new Frame(origin, fwd);
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < coords.size(); i++) {
            int[] c = coords.get(i);
            String b = blocks.get(i);
            out.add(new Step(f.at(c[0], c[1], c[2]), b == null || b.isBlank() ? "planks" : b, null));
        }
        out.sort((a, b) -> Integer.compare(a.pos().getY(), b.pos().getY()));
        return finish(name, out);
    }

    /** Факелы в тёмных местах вокруг центра (через каждые ~5 блоков). */
    public static Plan lightPlan(Level level, BlockPos center, int radius) {
        List<BlockPos> placed = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) continue;
                    BlockState below = level.getBlockState(p.below());
                    if (!below.isFaceSturdy(level, p.below(), Direction.UP)) continue;
                    if (level.getBrightness(LightLayer.BLOCK, p) > 2) continue;
                    boolean near = false;
                    for (BlockPos q : placed) {
                        if (q.distSqr(p) < 25) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) placed.add(p.immutable());
                    if (placed.size() >= 16) break;
                }
            }
        }
        List<Step> out = new ArrayList<>();
        for (BlockPos p : placed) out.add(new Step(p, "torch", null));
        return finish("освещение", out);
    }

    private static Plan finish(String name, List<Step> steps) {
        Plan plan = new Plan(name);
        int max = maxBlocks();
        for (Step s : steps) {
            if (plan.steps.size() >= max) break;
            plan.steps.add(s);
        }
        plan.total = plan.steps.size();
        return plan;
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v <= 0) v = def;
        return Math.max(min, Math.min(max, v));
    }

    /** Сколько блоков каждого типа нужно (для отчёта игроку). */
    public static String needs(Plan p) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Step s : p.steps) {
            String key = s.block().isBlank() ? "любой блок" : s.block();
            counts.merge(key, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append("× ").append(e.getKey());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ материалы

    private record Material(@Nullable ItemStack source, BlockItem item, boolean free) {}

    private static int score(ItemStack s, String filter) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return 0;
        return scoreItem(s.getItem(), s.getHoverName().getString(), filter);
    }

    private static int scoreItem(Item item, String displayName, String filterRaw) {
        if (!(item instanceof BlockItem)) return 0;
        String f = filterRaw == null ? "" : filterRaw.toLowerCase(Locale.ROOT).trim();
        if (f.isEmpty()) return 1;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        String full = key.toString();
        String path = key.getPath();
        String fPath = f.startsWith("minecraft:") ? f.substring(10) : f;
        if (full.equals(f) || path.equals(fPath) || displayName.equalsIgnoreCase(f)) return 3;
        if (path.contains(fPath) || displayName.toLowerCase(Locale.ROOT).contains(f)) return 2;
        return 0;
    }

    private static List<ItemStack> contents(Container c) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) list.add(c.getItem(i));
        return list;
    }

    @Nullable
    private static Material findIn(List<ItemStack> stacks, String filter) {
        int bestScore = 0, bestLen = Integer.MAX_VALUE;
        ItemStack best = null;
        for (ItemStack s : stacks) {
            int sc = score(s, filter);
            if (sc == 0) continue;
            int len = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().length();
            if (sc > bestScore || (sc == bestScore && len < bestLen)) {
                bestScore = sc;
                bestLen = len;
                best = s;
            }
        }
        return best == null ? null : new Material(best, (BlockItem) best.getItem(), false);
    }

    @Nullable
    private static Material findMaterial(RayaPrimeEntity raya, @Nullable Player owner, String filter) {
        Material m = findIn(contents(raya.getRayaInventory()), filter);
        if (m != null) return m;
        if (owner != null && RayaConfig.BUILD_FROM_OWNER_INVENTORY.get()) {
            m = findIn(owner.getInventory().items, filter);
            if (m != null) return m;
        }
        if (owner != null && owner.isCreative() && RayaConfig.FREE_BUILD_IN_CREATIVE.get()) {
            int bestScore = 0, bestLen = Integer.MAX_VALUE;
            BlockItem best = null;
            for (Item it : BuiltInRegistries.ITEM) {
                int sc = scoreItem(it, new ItemStack(it).getHoverName().getString(), filter);
                if (sc == 0) continue;
                int len = BuiltInRegistries.ITEM.getKey(it).getPath().length();
                if (sc > bestScore || (sc == bestScore && len < bestLen)) {
                    bestScore = sc;
                    bestLen = len;
                    best = (BlockItem) it;
                }
            }
            if (best != null) return new Material(null, best, true);
        }
        return null;
    }

    // ------------------------------------------------------------------ выполнение плана

    /** Вызывается из цели RayaBuildGoal каждый тик. */
    public static void tick(RayaPrimeEntity raya) {
        Plan plan = raya.getBuildPlan();
        if (plan == null) return;
        Level level = raya.level();
        Player owner = raya.getOwnerPlayer();

        if (!RayaConfig.ALLOW_BLOCK_PLACING.get()) {
            raya.tell("Установка блоков отключена в конфиге.");
            raya.setBuildPlan(null);
            return;
        }
        Step step = plan.steps.peek();
        if (step == null) {
            finishPlan(raya, plan);
            return;
        }
        BlockPos pos = step.pos();

        if (!raya.canActOn(pos)) {
            plan.steps.poll();
            plan.skipped++;
            if (++plan.outOfRange >= 6) {
                raya.tell("Эта стройка слишком далеко от тебя — прекращаю.");
                raya.setBuildPlan(null);
            }
            return;
        }
        plan.outOfRange = 0;

        BlockState cur = level.getBlockState(pos);
        if (!cur.isAir() && !cur.canBeReplaced()) { // место занято — пропускаем, ничего не ломаем
            plan.steps.poll();
            plan.stall = 0;
            return;
        }

        Vec3 center = Vec3.atCenterOf(pos);
        if (raya.distanceToSqr(center) > 20.0D) {
            raya.getMoveControl().setWantedPosition(center.x, center.y + 2.2D, center.z, 1.5D);
            if (++plan.stall > 600) { // не можем долететь — пропускаем блок
                plan.steps.poll();
                plan.skipped++;
                plan.stall = 0;
            }
            return;
        }
        plan.stall = 0;
        raya.getLookControl().setLookAt(center.x, center.y, center.z);
        if (raya.tickCount % 3 != 0) return;

        Material m = findMaterial(raya, owner, step.block());
        if (m == null && step.fallback() != null) m = findMaterial(raya, owner, step.fallback());
        if (m == null) {
            plan.missing = step.block().isBlank() ? "любые блоки" : step.block();
            long now = level.getGameTime();
            if (now - plan.lastNoMaterialMsg > 600) {
                plan.lastNoMaterialMsg = now;
                int left = 0;
                for (Step s : plan.steps) if (s.block().equals(step.block())) left++;
                raya.tell("Мне не хватает блоков «" + plan.missing + "» (нужно ещё около " + left
                        + "). Положи их мне в инвентарь (ПКМ по мне) или к себе — и я продолжу. Отменить стройку: «Рая, отмена».");
            }
            return;
        }

        ItemStack single = new ItemStack(m.item());
        DirectionalPlaceContext ctx = new DirectionalPlaceContext(level, pos, Direction.DOWN, single, Direction.UP);
        InteractionResult result = m.item().place(ctx);
        if (result.consumesAction()) {
            if (!m.free() && m.source() != null) {
                m.source().shrink(1);
                raya.getRayaInventory().setChanged();
            }
            plan.steps.poll();
            plan.fails = 0;
        } else if (++plan.fails >= 4) { // что-то мешает (сущность, неподходящее место) — пропускаем блок
            plan.steps.poll();
            plan.skipped++;
            plan.fails = 0;
        }
        if (plan.steps.isEmpty()) {
            finishPlan(raya, plan);
        }
    }

    private static void finishPlan(RayaPrimeEntity raya, Plan plan) {
        raya.setBuildPlan(null);
        int done = plan.total - plan.skipped;
        raya.say("Готово! Стройка «" + plan.name + "» завершена: поставлено около " + done + " блоков"
                + (plan.skipped > 0 ? " (" + plan.skipped + " пропустила — место было занято или недоступно)." : "."),
                RayaVoice.Mood.HAPPY);
    }

    // ------------------------------------------------------------------ сундуки

    /** Ближайший контейнер (сундук, бочка…) около точки. */
    @Nullable
    public static BlockPos findContainer(Level level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(p)) continue;
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof Container) {
                        double d = p.distSqr(center);
                        if (d < bestD) {
                            bestD = d;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Сложить вещи Раи в контейнер. @return true, когда задача закончена
     */
    public static boolean tickDeposit(RayaPrimeEntity raya, BlockPos pos, String filterRaw) {
        Level level = raya.level();
        if (!raya.canActOn(pos)) {
            raya.tell("Этот сундук слишком далеко от тебя.");
            return true;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        if (raya.distanceToSqr(center) > 9.0D) {
            raya.getMoveControl().setWantedPosition(center.x, center.y + 1.6D, center.z, 1.4D);
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) {
            raya.tell("Здесь нет сундука.");
            return true;
        }
        String f = filterRaw == null ? "" : filterRaw.toLowerCase(Locale.ROOT).trim();
        if (f.equals("all") || f.equals("всё") || f.equals("все")) f = "";
        int moved = 0;
        Container inv = raya.getRayaInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (!f.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString().toLowerCase(Locale.ROOT);
                if (!id.contains(f) && !s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(f)) continue;
            }
            int before = s.getCount();
            insert(container, s);
            moved += before - s.getCount();
        }
        inv.setChanged();
        container.setChanged();
        raya.say(moved > 0 ? "Сложила в сундук " + moved + " предметов." : "Сложить нечего или сундук полон.", RayaVoice.Mood.NORMAL);
        return true;
    }

    /** Кладёт стек в контейнер; остаток остаётся в stack. */
    private static void insert(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack t = c.getItem(i);
            if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, stack)) continue;
            int room = Math.min(t.getMaxStackSize(), c.getMaxStackSize()) - t.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, stack.getCount());
            t.grow(move);
            stack.shrink(move);
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, stack)) continue;
            int move = Math.min(Math.min(stack.getMaxStackSize(), c.getMaxStackSize()), stack.getCount());
            c.setItem(i, stack.copyWithCount(move));
            stack.shrink(move);
        }
    }

    // ------------------------------------------------------------------ статус

    public static String status(@Nullable Plan p) {
        if (p == null || p.steps.isEmpty()) return "";
        return "Стройка «" + p.name + "»: осталось " + p.steps.size() + " из " + p.total + " блоков"
                + (p.missing.isEmpty() ? "" : " (не хватает: " + p.missing + ")");
    }

    public static boolean isOwnerCreative(@Nullable Player owner) {
        return owner instanceof ServerPlayer sp && sp.isCreative();
    }
}
