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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Строительство Раи: план из блоков (дом со скатной крышей, башня, стена, платформа, пирамида, лестница, забор,
 * свой чертёж от Gemini), расстановка факелов и раскладка вещей по сундукам.
 * Блоки берутся из инвентаря Раи, затем (если разрешено) из инвентаря владельца; в творческом режиме — бесплатно.
 *
 * Материал шага — цепочка вариантов через «|»: пробуется по порядку ("oak_stairs|oak_planks").
 * Вариант с «~» в начале берётся только у самой Раи (украшения: мебель, факелы), не из инвентаря владельца.
 */
public final class RayaBuild {

    private RayaBuild() {}

    /**
     * Один блок плана.
     * @param chain    цепочка материалов
     * @param facing   горизонтальное направление при установке (ступени, двери, сундуки), null — без ориентации
     * @param optional если материала нет — молча пропустить (двери, окна, мебель), а не ждать
     */
    public record Step(BlockPos pos, String chain, @Nullable Direction facing, boolean optional) {}

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
        /** Какой конкретный блок уже выбран для варианта (чтобы стены не получались из разных досок). */
        final Map<String, String> resolved = new HashMap<>();

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

        Direction right() {
            return fwd.getClockWise();
        }

        Direction left() {
            return fwd.getCounterClockWise();
        }
    }

    public static int maxBlocks() {
        return RayaConfig.MAX_BUILD_BLOCKS.get();
    }

    private static Step st(BlockPos p, String chain) {
        return new Step(p, chain, null, false);
    }

    private static Step opt(BlockPos p, String chain, @Nullable Direction facing) {
        return new Step(p, chain, facing, true);
    }

    // ------------------------------------------------------------------ палитра

    /** Набор материалов под основной блок стен: из «oak_planks» выводятся «oak_log», «oak_stairs», «oak_door». */
    private record Palette(String wall, String pillar, String stairs, String door, String base) {
        static Palette of(String wallRaw) {
            String f = wallRaw == null ? "" : wallRaw.toLowerCase(Locale.ROOT).trim();
            if (f.isEmpty()) f = "planks";
            if (f.startsWith("minecraft:")) f = f.substring(10);
            if (f.endsWith("_planks")) {
                String wood = f.substring(0, f.length() - 7);
                return new Palette(f, wood + "_log|" + f, wood + "_stairs|" + f, wood + "_door|door", "~cobblestone|" + f);
            }
            if (f.equals("planks")) {
                return new Palette(f, "log|" + f, "stairs|" + f, "door", "~cobblestone|" + f);
            }
            // каменные и прочие: cobblestone → cobblestone_stairs, stone_bricks → stone_brick_stairs
            String single = f.endsWith("bricks") ? f.substring(0, f.length() - 1) : f;
            String stairs = f + "_stairs|" + single + "_stairs|" + f;
            return new Palette(f, f, stairs, "door", f);
        }
    }

    /** Подходящий основной блок по тому, что реально лежит у Раи и владельца (больше всего штук). */
    public static String detectWall(RayaPrimeEntity raya, @Nullable Player owner) {
        Map<String, Integer> totals = new HashMap<>();
        collectBuildBlocks(contents(raya.getRayaInventory()), totals);
        if (owner != null && RayaConfig.BUILD_FROM_OWNER_INVENTORY.get()) {
            collectBuildBlocks(owner.getInventory().items, totals);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best == null ? "planks" : best;
    }

    private static void collectBuildBlocks(List<ItemStack> stacks, Map<String, Integer> totals) {
        for (ItemStack s : stacks) {
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            boolean good = path.endsWith("_planks") || path.equals("cobblestone") || path.equals("stone")
                    || path.endsWith("bricks") || path.equals("sandstone") || path.equals("smooth_stone")
                    || path.equals("deepslate_bricks") || path.equals("cobbled_deepslate") || path.equals("mud_bricks");
            if (good) totals.merge(path, s.getCount(), Integer::sum);
        }
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
        Palette pal = Palette.of(wall);
        String wallBlock = pal.wall();
        List<Step> out = new ArrayList<>();
        String name;

        switch (shape) {
            case "house", "hut", "home", "cabin", "дом", "домик" -> {
                name = "дом";
                int ww = clamp(w, 3, 11, 7), dd = clamp(d, 3, 11, 7), hh = clamp(h, 3, 7, 4);
                houseSteps(out, f, pal, roof, ww, dd, hh);
            }
            case "tower", "башня" -> {
                name = "башня";
                int ww = clamp(w, 3, 7, 3), hh = clamp(h, 3, 32, 10);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                int c = (ww - 1) / 2;
                for (int y = -1; y < hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) {
                        for (int rz = 0; rz < ww; rz++) {
                            boolean edge = rx == lo || rx == hi || rz == 0 || rz == ww - 1;
                            if (y == -1) {
                                out.add(st(f.at(rx, y, rz), wallBlock));
                            } else if (edge) {
                                if (rz == 0 && rx == 0 && y <= 1) continue; // вход
                                boolean slit = y % 4 == 2 && y > 2 && ww >= 3
                                        && ((rx == 0 && (rz == 0 || rz == ww - 1)) || (rz == c && (rx == lo || rx == hi)));
                                out.add(slit ? opt(f.at(rx, y, rz), "glass_pane", null) : st(f.at(rx, y, rz), (rx == lo || rx == hi) && (rz == 0 || rz == ww - 1) ? pal.pillar() : wallBlock));
                            }
                        }
                    }
                }
                // зубцы наверху
                for (int rx = lo; rx <= hi; rx++) {
                    for (int rz = 0; rz < ww; rz++) {
                        boolean edge = rx == lo || rx == hi || rz == 0 || rz == ww - 1;
                        if (edge && (rx + rz) % 2 == 0) out.add(st(f.at(rx, hh, rz), wallBlock));
                    }
                }
            }
            case "wall", "стена" -> {
                name = "стена";
                int ww = clamp(w, 1, 40, 7), hh = clamp(h, 1, 12, 3);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = 0; y < hh; y++) {
                    for (int rx = lo; rx <= hi; rx++) out.add(st(f.at(rx, y, 0), wallBlock));
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
                            if (edge) out.add(st(f.at(rx, y, rz), wallBlock));
                        }
                    }
                }
            }
            case "platform", "floor", "bridge", "платформа", "пол", "мост" -> {
                name = "платформа";
                int ww = clamp(w, 1, 25, 5), dd = clamp(d, 1, 40, 5);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int rz = 0; rz < dd; rz++) {
                    for (int rx = lo; rx <= hi; rx++) out.add(st(f.at(rx, -1, rz), wallBlock));
                }
            }
            case "box", "cube", "куб", "блок" -> {
                name = "куб";
                int ww = clamp(w, 1, 10, 3), dd = clamp(d, 1, 10, 3), hh = clamp(h, 1, 10, 3);
                int lo = -((ww - 1) / 2), hi = ww / 2;
                for (int y = 0; y < hh; y++) {
                    for (int rz = 0; rz < dd; rz++) {
                        for (int rx = lo; rx <= hi; rx++) out.add(st(f.at(rx, y, rz), wallBlock));
                    }
                }
            }
            case "pyramid", "пирамида" -> {
                name = "пирамида";
                int ww = clamp(w, 3, 17, 9);
                int layers = (ww + 1) / 2;
                for (int i = 0; i < layers; i++) {
                    int size = ww - 2 * i;
                    int lo = -((size - 1) / 2), hi = size / 2;
                    int zOff = i;
                    for (int rz = 0; rz < size; rz++) {
                        for (int rx = lo; rx <= hi; rx++) out.add(st(f.at(rx, i, rz + zOff), wallBlock));
                    }
                }
            }
            case "pillar", "column", "колонна", "столб" -> {
                name = "колонна";
                int hh = clamp(h, 1, 40, 5);
                for (int y = 0; y < hh; y++) out.add(st(f.at(0, y, 0), wallBlock));
            }
            case "stairs", "staircase", "лестница" -> {
                name = "лестница";
                int hh = clamp(h, 1, 30, 6);
                for (int i = 0; i < hh; i++) {
                    out.add(st(f.at(0, i, i), wallBlock));
                    out.add(st(f.at(-1, i, i), wallBlock));
                }
            }
            default -> {
                return null;
            }
        }
        return finish(name, out);
    }

    /** Дом: пол, каменный цоколь, угловые брёвна, окна, дверь, скатная крыша из ступеней, фронтоны, факелы и мебель. */
    private static void houseSteps(List<Step> out, Frame f, Palette pal, @Nullable String roofRaw, int ww, int dd, int hh) {
        int lo = -((ww - 1) / 2), hi = ww / 2;
        String wall = pal.wall();
        String stairs = (roofRaw == null || roofRaw.isBlank()) ? pal.stairs() : Palette.of(roofRaw).stairs();
        String roofFill = (roofRaw == null || roofRaw.isBlank()) ? wall : Palette.of(roofRaw).wall();

        // сколько и где окна
        int c = (dd - 1) / 2;
        List<Integer> sideWin = new ArrayList<>();
        sideWin.add(c);
        if (dd >= 9) {
            sideWin.add(c - 3);
            sideWin.add(c + 3);
        }
        List<Integer> fbWin = new ArrayList<>();
        if (ww >= 7) {
            fbWin.add(-2);
            fbWin.add(2);
        }
        boolean tall = hh >= 4;

        // пол
        for (int rz = 0; rz < dd; rz++) {
            for (int rx = lo; rx <= hi; rx++) out.add(st(f.at(rx, -1, rz), wall));
        }
        // стены
        for (int y = 0; y < hh; y++) {
            for (int rx = lo; rx <= hi; rx++) {
                for (int rz = 0; rz < dd; rz++) {
                    boolean edge = rx == lo || rx == hi || rz == 0 || rz == dd - 1;
                    if (!edge) continue;
                    boolean corner = (rx == lo || rx == hi) && (rz == 0 || rz == dd - 1);
                    if (rz == 0 && rx == 0) {
                        if (y == 0) out.add(new Step(f.at(rx, y, rz), pal.door(), f.fwd(), true));
                        continue; // проём двери 1×2
                    }
                    if (corner) {
                        out.add(st(f.at(rx, y, rz), pal.pillar()));
                        continue;
                    }
                    boolean winY = y == 1 || (tall && y == 2);
                    boolean window = winY && (((rx == lo || rx == hi) && sideWin.contains(rz))
                            || ((rz == 0 || rz == dd - 1) && fbWin.contains(rx))
                            || (rz == dd - 1 && ww >= 5 && rx == 0));
                    if (window) {
                        out.add(st(f.at(rx, y, rz), "glass_pane|glass|" + wall));
                    } else if (y == 0) {
                        out.add(st(f.at(rx, y, rz), pal.base()));
                    } else {
                        out.add(st(f.at(rx, y, rz), wall));
                    }
                }
            }
        }
        // скатная крыша: ступени с краёв к коньку, свес по бокам
        for (int k = 0; ; k++) {
            int left = lo - 1 + k, right = hi + 1 - k;
            int y = hh - 1 + k;
            if (left > right) break;
            if (left == right) { // конёк
                for (int rz = 0; rz < dd; rz++) out.add(st(f.at(left, y, rz), roofFill));
                break;
            }
            for (int rz = 0; rz < dd; rz++) {
                out.add(new Step(f.at(left, y, rz), stairs, f.right(), false));
                out.add(new Step(f.at(right, y, rz), stairs, f.left(), false));
            }
            // фронтоны: треугольник между ступенями на торцевых стенах
            if (k >= 1) {
                for (int rx = left + 1; rx <= right - 1; rx++) {
                    out.add(st(f.at(rx, y, 0), wall));
                    if (dd > 1) out.add(st(f.at(rx, y, dd - 1), wall));
                }
            }
        }
        // факелы и мебель — только если они есть у самой Раи (или в креативе)
        if (ww >= 5 && dd >= 5) {
            out.add(opt(f.at(lo + 1, 0, 1), "~torch", null));
            out.add(opt(f.at(hi - 1, 0, 1), "~torch", null));
            out.add(opt(f.at(lo + 1, 0, dd - 2), "~torch", null));
            out.add(opt(f.at(hi - 1, 0, dd - 2), "~crafting_table", null));
            out.add(opt(f.at(hi - 2, 0, dd - 2), "~furnace", f.fwd()));
            out.add(opt(f.at(lo + 2, 0, dd - 2), "~chest", f.fwd()));
        }
        // порядок: снизу вверх (стабильная сортировка сохраняет порядок обхода внутри слоя)
        out.sort(Comparator.comparingInt(s -> s.pos().getY()));
    }

    /** Свой чертёж: список блоков в локальных координатах (dx вправо, dy вверх, dz вперёд). */
    public static Plan custom(String name, BlockPos origin, Direction fwd, List<int[]> coords, List<String> blocks) {
        Frame f = new Frame(origin, fwd);
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < coords.size(); i++) {
            int[] c = coords.get(i);
            String b = blocks.get(i);
            out.add(st(f.at(c[0], c[1], c[2]), b == null || b.isBlank() ? "planks" : b));
        }
        out.sort(Comparator.comparingInt(s -> s.pos().getY()));
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
        for (BlockPos p : placed) out.add(st(p, "torch"));
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

    private static String firstAlt(String chain) {
        String c = chain.contains("|") ? chain.substring(0, chain.indexOf('|')) : chain;
        return c.startsWith("~") ? c.substring(1) : c;
    }

    /** Сколько блоков каждого типа нужно (для отчёта игроку). Необязательные украшения не считаются. */
    public static String needs(Plan p) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Step s : p.steps) {
            if (s.optional()) continue;
            counts.merge(lastAlt(s.chain()), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append("× ").append(e.getKey());
        }
        return sb.toString();
    }

    /** Последний (основной) вариант цепочки — то, что обязательно нужно, если остальные недоступны. */
    private static String lastAlt(String chain) {
        String c = chain.contains("|") ? chain.substring(chain.lastIndexOf('|') + 1) : chain;
        return c.startsWith("~") ? c.substring(1) : c;
    }

    // ------------------------------------------------------------------ материалы

    private record Material(@Nullable ItemStack source, BlockItem item, boolean free) {}

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

    /** Лучший подходящий стек: сначала точность совпадения, потом тот вид блоков, которого больше всего (единый стиль). */
    @Nullable
    private static Material findIn(List<ItemStack> stacks, String filter) {
        int bestScore = 0;
        for (ItemStack s : stacks) {
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            bestScore = Math.max(bestScore, scoreItem(s.getItem(), s.getHoverName().getString(), filter));
        }
        if (bestScore == 0) return null;
        Map<Item, Integer> totals = new HashMap<>();
        for (ItemStack s : stacks) {
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            if (scoreItem(s.getItem(), s.getHoverName().getString(), filter) == bestScore) {
                totals.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        Item bestItem = null;
        int bestTotal = -1;
        for (Map.Entry<Item, Integer> e : totals.entrySet()) {
            if (e.getValue() > bestTotal) {
                bestTotal = e.getValue();
                bestItem = e.getKey();
            }
        }
        ItemStack best = null;
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && s.getItem() == bestItem && (best == null || s.getCount() > best.getCount())) best = s;
        }
        return best == null ? null : new Material(best, (BlockItem) best.getItem(), false);
    }

    @Nullable
    private static Material findOne(RayaPrimeEntity raya, @Nullable Player owner, String filter, boolean ownOnly) {
        Material m = findIn(contents(raya.getRayaInventory()), filter);
        if (m != null) return m;
        if (!ownOnly && owner != null && RayaConfig.BUILD_FROM_OWNER_INVENTORY.get()) {
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

    /** Подбирает материал по цепочке вариантов; запоминает выбранный конкретный блок для единообразия. */
    @Nullable
    private static Material findMaterial(RayaPrimeEntity raya, @Nullable Player owner, Plan plan, String chain) {
        for (String altRaw : chain.split("\\|")) {
            boolean ownOnly = altRaw.startsWith("~");
            String alt = ownOnly ? altRaw.substring(1) : altRaw;
            String concrete = plan.resolved.get(alt);
            if (concrete != null) {
                Material m = findOne(raya, owner, concrete, ownOnly);
                if (m != null) return m;
                plan.resolved.remove(alt); // закончился — выберем другой вид
            }
            Material m = findOne(raya, owner, alt, ownOnly);
            if (m != null) {
                plan.resolved.put(alt, BuiltInRegistries.ITEM.getKey(m.item()).toString());
                return m;
            }
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

        Material m = findMaterial(raya, owner, plan, step.chain());
        if (m == null) {
            if (step.optional()) { // украшение без материала — просто пропускаем
                plan.steps.poll();
                return;
            }
            plan.missing = lastAlt(step.chain());
            long now = level.getGameTime();
            if (now - plan.lastNoMaterialMsg > 600) {
                plan.lastNoMaterialMsg = now;
                int left = 0;
                for (Step s : plan.steps) if (!s.optional() && lastAlt(s.chain()).equals(plan.missing)) left++;
                raya.tell("Мне не хватает блоков «" + plan.missing + "» (нужно ещё около " + left
                        + "). Положи их мне в инвентарь (ПКМ по мне) или к себе — и я продолжу. Отменить стройку: «Рая, отмена».");
            }
            return;
        }
        plan.missing = "";

        ItemStack single = new ItemStack(m.item());
        Direction dir = step.facing() != null ? step.facing() : Direction.DOWN;
        DirectionalPlaceContext ctx = new DirectionalPlaceContext(level, pos, dir, single, Direction.UP);
        InteractionResult result = m.item().place(ctx);
        if (result.consumesAction()) {
            if (!m.free() && m.source() != null) {
                m.source().shrink(1);
                raya.getRayaInventory().setChanged();
            }
            plan.steps.poll();
            plan.fails = 0;
            raya.playBuildSwing();
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
        raya.playAnimation(RayaPrimeEntity.ANIM_DANCE, 60);
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
