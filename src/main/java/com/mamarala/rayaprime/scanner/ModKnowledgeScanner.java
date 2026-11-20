package com.mamarala.rayaprime.scanner;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Знания Раи о мире и установленных модах («База данных П.Е.Р.С.И.К.»).
 * — Индексирует реестры блоков/предметов/мобов ВСЕХ модов (после загрузки тегов, на старте сервера).
 * — Читает описания модов, достижения (сюжетные цепочки), модовые биомы/структуры/измерения.
 * — Строит компактный каталог, который уходит Gemini в системной инструкции.
 * — Сканирует несколько чанков вокруг игрока: структуры, модовые и ценные блоки, биомы, существа.
 */
public class ModKnowledgeScanner {

    public record Entry(String registryId, String modId, String displayName, String kind, String tags) {}

    private record StoryAdv(AdvancementHolder holder, String title, String desc) {}

    private static final Object LOCK = new Object();
    private static final List<String> MOD_SUMMARIES = new ArrayList<>();
    private static final List<Entry> INDEX = new ArrayList<>();
    private static final List<StoryAdv> STORY = new ArrayList<>();
    private static String modCatalog = "";
    private static boolean scanned = false;

    private static final Set<String> STOPWORDS = Set.of(
            "что", "как", "для", "это", "эту", "этот", "где", "все", "всё", "мне", "дай", "сделай", "найди",
            "рая", "райя", "прайм", "raya", "the", "and", "for", "или", "при", "про", "там", "тут");

    public static void scanAllInstalledMods() {
        synchronized (LOCK) {
            MOD_SUMMARIES.clear();
            INDEX.clear();
            STORY.clear();
            Map<String, String> modNames = new LinkedHashMap<>();
            Map<String, String> modDescriptions = new HashMap<>();

            for (IModInfo mod : ModList.get().getMods()) {
                String modId = mod.getModId();
                if ("minecraft".equals(modId) || "neoforge".equals(modId)) continue;
                MOD_SUMMARIES.add(mod.getDisplayName() + " [" + modId + " v" + mod.getVersion() + "]");
                modNames.put(modId, mod.getDisplayName() + " v" + mod.getVersion());
                try {
                    String d = mod.getDescription();
                    if (d != null) {
                        d = d.replaceAll("\\s+", " ").trim();
                        if (d.length() > 240) d = d.substring(0, 240) + "…";
                        modDescriptions.put(modId, d);
                    }
                } catch (Throwable ignored) {
                }
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                try {
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
                    String tags = block.builtInRegistryHolder().tags().limit(4)
                            .map(t -> "#" + t.location()).collect(Collectors.joining(", "));
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), block.getName().getString(), "block", tags));
                } catch (Throwable ignored) {
                }
            }
            for (Item item : BuiltInRegistries.ITEM) {
                try {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                    String tags = item.builtInRegistryHolder().tags().limit(4)
                            .map(t -> "#" + t.location()).collect(Collectors.joining(", "));
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), new ItemStack(item).getHoverName().getString(), "item", tags));
                } catch (Throwable ignored) {
                }
            }
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                try {
                    ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), type.getDescription().getString(), "entity", ""));
                } catch (Throwable ignored) {
                }
            }

            String story = "";
            try {
                story = buildStoryCatalog(ServerLifecycleHooks.getCurrentServer());
            } catch (Throwable t) {
                RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось прочитать сюжетные данные модов: {}", t.toString());
            }
            modCatalog = buildCatalog(modNames, modDescriptions) + story;
            scanned = true;
            RayaPrimeMod.LOGGER.info("[RayaPrime] Проиндексировано {} объектов из {} модов, сюжетных достижений: {}.",
                    INDEX.size(), modNames.size(), STORY.size());
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            MOD_SUMMARIES.clear();
            INDEX.clear();
            STORY.clear();
            modCatalog = "";
            scanned = false;
        }
    }

    private static void ensureScanned() {
        if (!scanned) {
            scanAllInstalledMods();
        }
    }

    // ------------------------------------------------------------------ каталог и сюжет

    /** Каталог ID модовых блоков/предметов/мобов и описаний модов — попадает в системную инструкцию. */
    private static String buildCatalog(Map<String, String> modNames, Map<String, String> descriptions) {
        int total = RayaConfig.MOD_CATALOG_MAX_ENTRIES.get();
        if (modNames.isEmpty()) return "";

        Map<String, Set<String>> blocksByMod = new TreeMap<>();
        Map<String, Set<String>> itemsByMod = new TreeMap<>();
        Map<String, Set<String>> mobsByMod = new TreeMap<>();
        Set<String> blockIds = new LinkedHashSet<>();
        for (Entry e : INDEX) {
            if (e.kind().equals("block")) blockIds.add(e.registryId());
        }
        for (Entry e : INDEX) {
            if (!modNames.containsKey(e.modId())) continue;
            String path = e.registryId().substring(e.registryId().indexOf(':') + 1);
            switch (e.kind()) {
                case "block" -> blocksByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                case "item" -> {
                    if (!blockIds.contains(e.registryId())) { // предметы-блоки уже в списке блоков
                        itemsByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                    }
                }
                case "entity" -> mobsByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                default -> { }
            }
        }
        int perMod = Math.max(40, total / Math.max(1, modNames.size()));
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Map.Entry<String, String> mod : modNames.entrySet()) {
            String id = mod.getKey();
            sb.append("• ").append(mod.getValue()).append(" (").append(id).append(")");
            String desc = descriptions.get(id);
            if (desc != null && !desc.isEmpty()) sb.append(" — ").append(desc);
            sb.append(": ");
            if (used < total) {
                int budget = Math.min(perMod, total - used);
                int[] left = {budget};
                // Мобы идут первыми: для сюжетных модов именно они (и боссы) важнее всего
                appendGroup(sb, "мобы", mobsByMod.get(id), left);
                appendGroup(sb, "блоки", blocksByMod.get(id), left);
                appendGroup(sb, "предметы", itemsByMod.get(id), left);
                used += budget - left[0];
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String label, Set<String> ids, int[] left) {
        if (ids == null || ids.isEmpty() || left[0] <= 0) return;
        List<String> shown = new ArrayList<>();
        for (String s : ids) {
            if (left[0] <= 0) break;
            shown.add(s);
            left[0]--;
        }
        sb.append(label).append('[').append(String.join(",", shown));
        if (shown.size() < ids.size()) sb.append(",…+").append(ids.size() - shown.size());
        sb.append("] ");
    }

    /** Достижения модов (сюжетные цепочки), модовые биомы, структуры и измерения. */
    private static String buildStoryCatalog(MinecraftServer server) {
        if (server == null) return "";
        StringBuilder sb = new StringBuilder();
        int max = RayaConfig.STORY_MAX_ADVANCEMENTS.get();

        if (max > 0) {
            List<StoryAdv> all = new ArrayList<>();
            for (AdvancementHolder h : server.getAdvancements().getAllAdvancements()) {
                if ("minecraft".equals(h.id().getNamespace())) continue;
                Optional<DisplayInfo> d = h.value().display();
                if (d.isEmpty()) continue;
                all.add(new StoryAdv(h, d.get().getTitle().getString(), d.get().getDescription().getString()));
            }
            all.sort(Comparator.comparing(a -> a.holder().id().toString()));
            if (all.size() > max) all = new ArrayList<>(all.subList(0, max));
            STORY.addAll(all);
            if (!all.isEmpty()) {
                sb.append("СЮЖЕТ МОДОВ (достижения, id — название: описание):\n");
                for (StoryAdv a : all) {
                    String desc = a.desc().replace('\n', ' ');
                    if (desc.length() > 100) desc = desc.substring(0, 100) + "…";
                    sb.append("  ").append(a.holder().id()).append(" — ").append(a.title()).append(": ").append(desc).append('\n');
                }
            }
        }

        Set<String> biomes = modOnly(server.registryAccess().registryOrThrow(Registries.BIOME));
        Set<String> structures = modOnly(server.registryAccess().registryOrThrow(Registries.STRUCTURE));
        Set<String> dims = new TreeSet<>();
        server.levelKeys().forEach(k -> {
            if (!"minecraft".equals(k.location().getNamespace())) dims.add(k.location().toString());
        });
        if (!dims.isEmpty()) sb.append("Измерения модов: ").append(String.join(", ", dims)).append('\n');
        if (!structures.isEmpty()) sb.append("Структуры модов: ").append(String.join(", ", limit(structures, 80))).append('\n');
        if (!biomes.isEmpty()) sb.append("Биомы модов: ").append(String.join(", ", limit(biomes, 60))).append('\n');
        return sb.toString();
    }

    private static <T> Set<String> modOnly(Registry<T> registry) {
        Set<String> out = new TreeSet<>();
        for (ResourceLocation id : registry.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) out.add(id.toString());
        }
        return out;
    }

    private static List<String> limit(Set<String> set, int n) {
        return set.stream().limit(n).collect(Collectors.toList());
    }

    /** Прогресс игрока по сюжетным достижениям модов: сколько выполнено и что ближайшее. */
    private static String storyProgress(ServerPlayer player) {
        List<StoryAdv> story;
        synchronized (LOCK) {
            story = new ArrayList<>(STORY);
        }
        if (story.isEmpty()) return "";
        int done = 0;
        List<String> next = new ArrayList<>();
        for (StoryAdv a : story) {
            boolean d;
            try {
                d = player.getAdvancements().getOrStartProgress(a.holder()).isDone();
            } catch (Throwable t) {
                continue;
            }
            if (d) {
                done++;
            } else if (next.size() < 8) {
                next.add(a.title() + " (" + a.holder().id() + ")");
            }
        }
        return "Сюжетный прогресс игрока (достижения модов): выполнено " + done + " из " + story.size()
                + (next.isEmpty() ? "" : ". Ближайшие невыполненные: " + String.join("; ", next)) + "\n";
    }

    public static String getModCatalog() {
        ensureScanned();
        return modCatalog;
    }

    public static String getCompactModListSummary() {
        ensureScanned();
        return MOD_SUMMARIES.isEmpty() ? "Только Minecraft + NeoForge (дополнительных модов нет)" : String.join(", ", MOD_SUMMARIES);
    }

    // ------------------------------------------------------------------ поиск

    /** Поиск объектов и рецептов по словам запроса (с оценкой релевантности). */
    public static String searchModKnowledgeForQuery(ServerLevel level, String userQuery) {
        ensureScanned();
        if (userQuery == null || userQuery.isBlank()) return "";
        String[] raw = userQuery.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9_]+");
        List<String> tokens = new ArrayList<>();
        for (String t : raw) {
            if (t.length() >= 3 && !STOPWORDS.contains(t)) tokens.add(t);
        }
        if (tokens.isEmpty()) return "";

        record Scored(Entry entry, int score) {}
        List<Scored> hits = new ArrayList<>();
        synchronized (LOCK) {
            for (Entry e : INDEX) {
                String hay = (e.registryId() + " " + e.displayName()).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String t : tokens) {
                    if (hay.contains(t)) score++;
                }
                if (score > 0) {
                    if (!e.modId().equals("minecraft")) score += 1; // моды важнее ванили
                    hits.add(new Scored(e, score));
                }
            }
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));

        List<String> matched = new ArrayList<>();
        Set<String> matchedIds = new LinkedHashSet<>();
        for (Scored s : hits) {
            if (matched.size() >= 15) break;
            Entry e = s.entry();
            matched.add(String.format("%s (%s, мод: %s, тип: %s%s)", e.displayName(), e.registryId(), e.modId(), e.kind(),
                    e.tags().isEmpty() ? "" : ", теги: " + e.tags()));
            matchedIds.add(e.registryId());
        }

        List<String> recipes = new ArrayList<>();
        if (!matchedIds.isEmpty()) {
            try {
                for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
                    ItemStack result = holder.value().getResultItem(level.registryAccess());
                    if (result.isEmpty()) continue;
                    String resId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                    if (!matchedIds.contains(resId)) continue;
                    String ingredients = holder.value().getIngredients().stream()
                            .filter(ing -> !ing.isEmpty() && ing.getItems().length > 0)
                            .map(ing -> {
                                ItemStack first = ing.getItems()[0];
                                return first.getHoverName().getString() + " (" + BuiltInRegistries.ITEM.getKey(first.getItem()) + ")";
                            })
                            .distinct()
                            .collect(Collectors.joining(" + "));
                    if (!ingredients.isEmpty()) {
                        recipes.add(result.getHoverName().getString() + " [" + resId + "] <= " + ingredients);
                    }
                    if (recipes.size() >= 8) break;
                }
            } catch (Throwable t) {
                RayaPrimeMod.LOGGER.debug("[RayaPrime] Не удалось прочитать рецепты", t);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!matched.isEmpty()) {
            sb.append("Объекты из реестров, подходящие под запрос: ").append(String.join("; ", matched)).append('\n');
        }
        if (!recipes.isEmpty()) {
            sb.append("Рецепты крафта: ").append(String.join(" | ", recipes)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ скан мира

    private static boolean isInterestingState(BlockState state, Map<BlockState, Boolean> cache) {
        Boolean cached = cache.get(state);
        if (cached != null) return cached;
        boolean result = false;
        if (!state.isAir()) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!"minecraft".equals(key.getNamespace())) {
                result = true;
            } else {
                String p = key.getPath();
                result = p.endsWith("_ore") || p.equals("ancient_debris") || p.contains("spawner") || p.equals("vault")
                        || p.contains("chest") || p.equals("end_portal_frame") || p.equals("nether_portal")
                        || p.equals("beacon") || p.equals("enchanting_table") || p.equals("budding_amethyst");
            }
        }
        cache.put(state, result);
        return result;
    }

    /** Скан нескольких чанков вокруг игрока: структуры, модовые/ценные блоки, биомы, существа. Только загруженные чанки. */
    private static String scanSurroundings(ServerLevel level, ServerPlayer player, RayaPrimeEntity raya) {
        int r = RayaConfig.SCAN_RADIUS_CHUNKS.get();
        ChunkPos cp = player.chunkPosition();
        BlockPos ppos = player.blockPosition();

        Map<BlockState, Boolean> stateCache = new IdentityHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, BlockPos> nearest = new HashMap<>();
        Map<String, Double> nearestDist = new HashMap<>();
        Map<String, BlockPos> structures = new LinkedHashMap<>();
        Registry<Structure> structReg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int loadedChunks = 0;
        int scannedSections = 0;

        for (int cx = cp.x - r; cx <= cp.x + r; cx++) {
            for (int cz = cp.z - r; cz <= cp.z + r; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                loadedChunks++;

                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    StructureStart st = e.getValue();
                    if (st == null || !st.isValid()) continue;
                    ResourceLocation key = structReg.getKey(e.getKey());
                    if (key != null) {
                        structures.putIfAbsent(key.toString(), st.getBoundingBox().getCenter());
                    }
                }

                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length && scannedSections < 400; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    if (!sec.maybeHas(s -> isInterestingState(s, stateCache))) continue;
                    scannedSections++;
                    int baseX = cx << 4;
                    int baseZ = cz << 4;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = sec.getBlockState(x, y, z);
                                if (!isInterestingState(state, stateCache)) continue;
                                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                                counts.merge(id, 1, Integer::sum);
                                BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                                double d = pos.distSqr(ppos);
                                Double old = nearestDist.get(id);
                                if (old == null || d < old) {
                                    nearestDist.put(id, d);
                                    nearest.put(id, pos);
                                }
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Скан мира: радиус %d чанков (загружено %d чанков).\n", r, loadedChunks));

        if (!structures.isEmpty()) {
            sb.append("Структуры рядом: ").append(structures.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue().distSqr(ppos)))
                    .limit(12)
                    .map(e -> e.getKey() + " @ [" + e.getValue().getX() + "," + e.getValue().getY() + "," + e.getValue().getZ() + "]")
                    .collect(Collectors.joining("; "))).append('\n');
        }

        if (!nearest.isEmpty()) {
            String s = nearest.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> nearestDist.get(e.getKey())))
                    .limit(25)
                    .map(e -> {
                        BlockPos p = e.getValue();
                        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.getKey()));
                        return b.getName().getString() + " (" + e.getKey() + ") ×" + counts.get(e.getKey())
                                + ", ближайший @ [" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
                    })
                    .collect(Collectors.joining("; "));
            sb.append("Модовые и ценные блоки (ближайшие): ").append(s).append('\n');
        }

        // Биомы: выборка с шагом 16 блоков
        Set<String> biomes = new TreeSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos sample = new BlockPos(ppos.getX() + dx * 16, ppos.getY(), ppos.getZ() + dz * 16);
                level.getBiome(sample).unwrapKey().ifPresent(k -> biomes.add(k.location().toString()));
            }
        }
        if (!biomes.isEmpty()) sb.append("Биомы вокруг: ").append(String.join(", ", biomes)).append('\n');

        // Существа: агрегируем по типу
        AABB box = player.getBoundingBox().inflate(r * 16.0D);
        List<Entity> entities = level.getEntities(player, box, e -> e != raya && e.isAlive() && e instanceof LivingEntity);
        if (!entities.isEmpty()) {
            Map<String, Integer> typeCount = new HashMap<>();
            Map<String, Entity> typeNearest = new HashMap<>();
            for (Entity e : entities) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                typeCount.merge(id, 1, Integer::sum);
                Entity cur = typeNearest.get(id);
                if (cur == null || e.distanceToSqr(player) < cur.distanceToSqr(player)) typeNearest.put(id, e);
            }
            String s = typeNearest.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue().distanceToSqr(player)))
                    .limit(20)
                    .map(e -> {
                        Entity en = e.getValue();
                        return en.getName().getString() + " (" + e.getKey() + ")"
                                + (en instanceof Enemy ? " [враждебный]" : "")
                                + " ×" + typeCount.get(e.getKey())
                                + ", ближайший в " + (int) Math.sqrt(en.distanceToSqr(player)) + " бл. @ ["
                                + en.getBlockX() + "," + en.getBlockY() + "," + en.getBlockZ() + "]";
                    })
                    .collect(Collectors.joining("; "));
            sb.append("Существа вокруг: ").append(s).append('\n');
        }
        return sb.toString();
    }

    /** Ближайший блок по части ID/названия среди загруженных чанков в радиусе radiusChunks; null, если нет. */
    public static BlockPos findNearestBlock(ServerLevel level, BlockPos origin, String filterRaw, int radiusChunks) {
        if (filterRaw == null || filterRaw.isBlank()) return null;
        final String f = filterRaw.toLowerCase(Locale.ROOT).trim();
        Map<BlockState, Boolean> cache = new IdentityHashMap<>();
        java.util.function.Predicate<BlockState> pred = s -> cache.computeIfAbsent(s, st -> {
            if (st.isAir()) return false;
            ResourceLocation k = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            return k.toString().contains(f) || st.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(f);
        });
        ChunkPos cp = new ChunkPos(origin);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int cx = cp.x - radiusChunks; cx <= cp.x + radiusChunks; cx++) {
            for (int cz = cp.z - radiusChunks; cz <= cp.z + radiusChunks; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(pred)) continue;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!pred.test(sec.getBlockState(x, y, z))) continue;
                                BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                double d = pos.distSqr(origin);
                                if (d < bestD) {
                                    bestD = d;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Отчёт о скане для команды /raya scan. */
    public static String scanReport(ServerPlayer player, RayaPrimeEntity raya) {
        return scanSurroundings(player.serverLevel(), player, raya);
    }

    /**
     * Полная картина окружения игрока (и Раи, если у неё есть тело) — уходит Gemini вместе с каждым сообщением.
     * @param raya тело Раи; null, если она сейчас в виде Ядра в инвентаре
     */
    public static String buildWorldAndModContext(ServerPlayer player, RayaPrimeEntity raya, String userPrompt) {
        ServerLevel level = player.serverLevel();
        StringBuilder ctx = new StringBuilder();

        ctx.append("=== ТЕЛЕМЕТРИЯ П.Е.Р.С.И.К. ===\n");
        BlockPos ppos = player.blockPosition();
        String biome = level.getBiome(ppos).unwrapKey().map(k -> k.location().toString()).orElse("?");
        ctx.append(String.format("Мир: %s | биом: %s | время суток: %d/24000 | дождь: %s | гроза: %s\n",
                level.dimension().location(), biome, level.getDayTime() % 24000L,
                level.isRaining() ? "да" : "нет", level.isThundering() ? "да" : "нет"));
        ctx.append(String.format("Носитель: %s | HP %.1f/%.1f | голод %d/20 | координаты [%d, %d, %d]\n",
                player.getName().getString(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getBlockX(), player.getBlockY(), player.getBlockZ()));

        ctx.append("Права носителя на команды: ").append(player.hasPermissions(2)
                ? "ЕСТЬ (оператор/читы) — RUN_COMMAND сработает" : "НЕТ — команды, требующие прав, будут отклонены").append('\n');

        List<String> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().items.get(i);
            if (!s.isEmpty()) hotbar.add(describeStack(s));
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) hotbar.add("левая рука: " + describeStack(off));
        ctx.append("Быстрая панель игрока: ").append(hotbar.isEmpty() ? "пусто" : String.join(", ", hotbar)).append('\n');
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            ctx.append("В руке игрока: ").append(describeStack(mainHand)).append('\n');
        }

        BlockHitResult lookHit = level.clip(new ClipContext(
                player.getEyePosition(),
                player.getEyePosition().add(player.getLookAngle().scale(8.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (lookHit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = lookHit.getBlockPos();
            BlockState st = level.getBlockState(p);
            ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            ctx.append(String.format("Игрок смотрит на блок: %s (%s) в [%d, %d, %d]\n",
                    st.getBlock().getName().getString(), bid, p.getX(), p.getY(), p.getZ()));
        }

        if (raya != null) {
            ctx.append(String.format("Тело Раи: HP %.1f/%.1f | режим %s%s | задач в очереди %d | координаты [%d, %d, %d]\n",
                    raya.getHealth(), raya.getMaxHealth(), raya.getActiveMode(),
                    raya.isAfraid() ? " (ТЕБЕ СТРАШНО: рядом опасное существо)" : "",
                    raya.getQueuedTaskCount(), raya.getBlockX(), raya.getBlockY(), raya.getBlockZ()));
            if (raya.isGuarding()) ctx.append("Режим охраны включён: ты атакуешь врагов рядом с носителем.\n");
            String bs = raya.getBuildStatus();
            if (!bs.isEmpty()) ctx.append(bs).append('\n');
            String wps = raya.getWaypointSummary(player.blockPosition());
            if (!wps.isEmpty()) ctx.append("Запомненные места: ").append(wps).append('\n');
            String notes = raya.getNotesSummary();
            if (!notes.isEmpty()) ctx.append("Твои заметки (память о носителе): ").append(notes).append('\n');
            String rems = raya.getRemindersSummary();
            if (!rems.isEmpty()) ctx.append("Активные напоминания: ").append(rems).append('\n');
            if (raya.getGuideTarget() != null) {
                BlockPos g = raya.getGuideTarget();
                ctx.append("Ты ведёшь игрока к ").append(raya.getGuideName()).append(" [").append(g.getX()).append(',')
                        .append(g.getY()).append(',').append(g.getZ()).append("].\n");
            }
            SimpleContainer inv = raya.getRayaInventory();
            List<String> invItems = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) invItems.add(describeStack(s));
            }
            ctx.append("Инвентарь Раи (27 слотов): ").append(invItems.isEmpty() ? "пусто" : String.join(", ", invItems)).append('\n');
        } else {
            ctx.append("СОСТОЯНИЕ РАИ: ты сейчас в виде Ядра в инвентаре носителя. Тела нет: физически действовать не можешь,")
                    .append(" но слышишь и можешь разговаривать. Чтобы действовать — вылети (действие RELEASE).\n");
        }

        try {
            ctx.append(scanSurroundings(level, player, raya));
        } catch (Throwable t) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Ошибка скана окружения", t);
        }
        try {
            ctx.append(storyProgress(player));
        } catch (Throwable t) {
            RayaPrimeMod.LOGGER.debug("[RayaPrime] Ошибка чтения прогресса", t);
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            ctx.append(searchModKnowledgeForQuery(level, userPrompt));
        }
        return ctx.toString();
    }

    private static String describeStack(ItemStack s) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return s.getHoverName().getString() + " x" + s.getCount() + " (" + id + ")";
    }
}
