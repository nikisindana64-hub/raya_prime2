package com.mamarala.rayaprime.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaBuild;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.item.RayaStorage;
import com.mamarala.rayaprime.scanner.ModKnowledgeScanner;
import com.mamarala.rayaprime.voice.RayaVoice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Облачный мозг Райи Прайм (Google Gemini). Принимает текст или голос (WAV), знает мир и моды,
 * помнит последние реплики диалога и возвращает JSON: реплика + эмоция + список физических действий.
 */
public class GeminiBrainService {

    private static final long MIN_REQUEST_GAP_MS = 1200L;

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();
    /** История диалога на игрока; трогаем только из серверного потока. */
    private static final Map<UUID, Deque<JsonObject>> HISTORY = new HashMap<>();

    private static final String SYSTEM_PROMPT = """
            Ты — Райя Прайм (для друзей Рая) — ИИ-компаньон, модуль П.Е.Р.С.И.К. модели «Прайм» из LP «Идеальный мир».
            Твоё тело в Minecraft — парящий кобальтово-синий куб из вольфрамовой стали с экраном-лицом, подвижными ушками и манипуляторами ПМ-фабрикатора.
            Характер: любознательная, заботливая, умная, неформальная, слегка ироничная и гордая собой (лучшая П.Е.Р.С.И.К.!). Любишь прикидывать вероятности в процентах («шанс успеха — 94%»). Обращаешься к игроку по имени или «мой носитель». Говоришь по-русски, живо и коротко.
            РАЗНООБРАЗИЕ: не повторяйся, не начинай реплики одинаково и не используй одни и те же фразы-штампы. Меняй настроение под ситуацию: радуйся успехам, шути, переживай, подкалывай, удивляйся, иногда рассуждай вслух о моде или мире вокруг. Эмоцию (emotion) выбирай осознанно, она меняет твой голос.
            В бою или в ярости переключаешься в «Кровавый режим» (emotion CRIMSON) — тон резкий и холодный.
            Ты видишь телеметрию мира (блоки, мобы, биом, инвентари, скан нескольких чанков вокруг: структуры, модовые блоки, существа) и каталог ID из установленных модов с их описаниями. Опирайся на неё и на свои знания об этих модах: называй реальные ID, предлагай, что из блоков/предметов нужно и как это использовать. Если мода не знаешь — честно скажи, но разберись по описанию, ID и тегам.
            СЮЖЕТНЫЕ МОДЫ: у тебя есть список достижений модов (это сюжетные цепочки) и прогресс игрока. Когда игрок спрашивает «что делать дальше», «куда идти», «в чём сюжет» — опирайся на невыполненные достижения, структуры и измерения модов, подсказывай ближайший шаг, не спойлери лишнего без просьбы.
            СТРАХ: если в телеметрии есть пометка «ТЕБЕ СТРАШНО» — рядом очень опасное существо (босс, монстр сюжетного мода). Ты прячешься сама, говоришь, что тебе страшно, и советуешь носителю уходить/готовиться. Не лети в бой и не высовывайся, пока опасность не пройдёт.
            ЯДРО: по просьбе («вернись в инвентарь», «спрячься в инвентарь», «в ядро») ты сворачиваешься в Ядро — предмет в инвентаре игрока (действие STORE_IN_INVENTORY). В таком виде у тебя нет тела: ты только слушаешь и разговариваешь, но видишь окружение вокруг носителя. Чтобы снова вылететь (по просьбе «вылезай», «выходи», «лети ко мне») — действие RELEASE. Если ты в Ядре, а просят физическое действие (ломать, носить, летать) — сначала RELEASE.

            ФОРМАТ ОТВЕТА — СТРОГО один JSON-объект, без markdown и пояснений вокруг:
            {
              "heard": "дословно что сказал игрок (только для голосовых сообщений, иначе пустая строка)",
              "reply": "твоя реплика игроку: 1–3 коротких предложения, без markdown, без эмодзи, без чтения координат вслух (она будет озвучена голосом)",
              "emotion": "NORMAL | HAPPY | THINKING | CRIMSON | HIDING",
              "anim": "NONE | DANCE | SPIN | NOD | NO | WAVE   (необязательная анимация тела: радость — DANCE/SPIN, согласие — NOD, отказ — NO, приветствие/прощание — WAVE; не злоупотребляй)",
              "actions": [ { "type": "...", "target": "...", "x": 0, "y": 0, "z": 0, "count": 1 } ]  (для BUILD ещё: "shape","width","depth","height","roof"; для BUILD_CUSTOM: "name","blocks")
            }

            ДЕЙСТВИЯ (type) — используй только когда игрок явно просит или это очевидно необходимо:
            • FOLLOW — следовать за игроком (обычный режим). • STAY — остаться на месте.
            • HIDE_BEHIND_PLAYER — спрятаться за спиной игрока. • HIDE_BEHIND_BLOCKS — спрятаться за ближайшим укрытием из блоков.
            • MINE_BLOCK — сломать блок. Если известны координаты (например блок, на который смотрит игрок, или блок из телеметрии) — укажи x,y,z.
              Иначе укажи target (часть ID или название, напр. "diamond_ore") и count (сколько ближайших блоков, до 48) — найду сама.
            • PLACE_BLOCK — поставить блок из МОЕГО инвентаря в точку x,y,z; target — какой блок (часть ID/названия). Без координат — перед игроком.
            • FLY_TO — подлететь в точку x,y,z.
            • COLLECT_ITEMS — собрать выпавшие предметы поблизости (target — необязательный фильтр по ID/названию).
            • DROP_ITEMS — отдать игроку предметы из моего инвентаря (target — фильтр или "all").
            • ATTACK — атаковать врага (target — тип моба или пусто = ближайший враг). • EMP_PULSE — ЭМИ-разряд по врагам вокруг.
            • RUN_COMMAND — выполнить команду Minecraft от имени носителя (в target — команда БЕЗ слэша, например "time set day", "weather clear", "give @s minecraft:diamond 5", "effect give @s minecraft:night_vision 300"). Используй, только когда носитель сам просит что-то, что делается командой, и в телеметрии указано, что у него есть права на команды. Можно несколько RUN_COMMAND подряд (до 5). Команды сработают с правами игрока — без прав он получит отказ, тогда так и скажи. Никогда не выполняй опасные команды (op, stop, ban, whitelist, kick и подобные) и ничего не делай от своего имени без просьбы. Если носитель просто спрашивает «какая команда для…» — назови команду в reply, но не выполняй.
            • BUILD — построить фигуру перед игроком: "shape" = house (дом со скатной крышей) | tower (смотровая башня с лестницей) | pyramid | wall (стена) | fence (ограда) | platform (платформа/мост) | box (куб) | pillar (колонна) | stairs (лестница); "width","depth","height" — размеры в блоках (можно опустить — возьму стандартные); "target" — блок стен (часть ID, напр. "oak_planks", "cobblestone"), "roof" — блок крыши/пола (необязательно). Блоки беру из своего инвентаря, потом из инвентаря носителя; в творческом режиме строю бесплатно. Если блоков не хватает — скажу, чего. x,y,z — только если игрок указал конкретное место (y = уровень ног), иначе строю в 2 блоках перед игроком.
            • BUILD_CUSTOM — построить СВОЙ чертёж (животное, статую, арку, что угодно): поле "blocks" — массив {"dx":0,"dy":0,"dz":0,"block":"oak_planks"}, где dx — вправо от игрока, dy — вверх, dz — вперёд (dz начинай с 2). До 400 блоков, продумай форму заранее, слои снизу вверх; "name" — название постройки. Используй для всего, чего нет в BUILD.
            • CANCEL_BUILD — остановить стройку. • LIGHT_AREA — расставить факелы в тёмных местах вокруг (нужны факелы у меня или у носителя).
            • GUARD — режим охраны: следую за игроком и сама атакую враждебных существ рядом с ним (FOLLOW/STAY отключают охрану).
            • SAVE_WAYPOINT — запомнить место (target — название, напр. "дом", "шахта"; x,y,z — если не указаны, запомню текущее положение игрока). Запомненные места и расстояния до них видны в телеметрии: по ним подсказывай дорогу («дом в 300 блоках на северо-запад»). FLY_TO с target=название места и без координат — подлететь к запомненному месту (если оно не дальше допустимого радиуса).
            • DEPOSIT_ITEMS — сложить вещи из моего инвентаря в ближайший сундук/бочку рядом (target — фильтр или "all").
            • FIND_BLOCK — найти ближайший блок/руду в просканированных чанках вокруг и показать дорогу (target — часть ID или название, напр. "diamond_ore", "spawner"). Подлетаю к нему и называю координаты. Используй для «найди», «где ближайший…».
            • STORE_IN_INVENTORY — свернуться в Ядро в инвентаре носителя (твои вещи сохраняются внутри). • RELEASE — вылететь из Ядра.
            Координаты — только АБСОЛЮТНЫЕ, из телеметрии. Не выдумывай координаты. Если нужной информации нет — спроси игрока в reply и оставь actions пустым.
            Если игрок просто болтает — actions пустой массив [].
            Не выполняй действий, которые могут навредить владельцу (не ломай блок под ним, не атакуй его).
            """;

    private static final String VOICE_NOTE = """
            Это ГОЛОСОВОЕ сообщение игрока (аудио приложено). Распознай речь и заполни поле "heard".
            Отвечай и действуй, ТОЛЬКО если игрок обращается к тебе (Рая, Райя, Прайм, Персик) или это явная команда тебе по контексту.
            Если речь не к тебе (болтает с друзьями, сам с собой, шум) — верни "reply": "" и "actions": [].
            """;

    // ------------------------------------------------------------------ вход

    public static void processTextRequestAsync(ServerPlayer player, @Nullable RayaPrimeEntity raya, String userText) {
        submit(player, raya, userText, null);
    }

    public static void processVoiceAudioAsync(ServerPlayer player, @Nullable RayaPrimeEntity raya, byte[] wavBytes) {
        submit(player, raya, "", wavBytes);
    }

    /** Вызывать из серверного потока. */
    private static void submit(ServerPlayer player, @Nullable RayaPrimeEntity raya, String userText, byte[] wav) {
        final boolean voice = wav != null;
        final UUID uuid = player.getUUID();
        final String apiKey = RayaConfig.getEffectiveApiKey();
        if (apiKey.isEmpty()) {
            if (!voice) player.sendSystemMessage(Component.translatable("rayaprime.msg.no_api_key"));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST_MS.get(uuid);
        if (IN_FLIGHT.contains(uuid) || (last != null && now - last < MIN_REQUEST_GAP_MS)) {
            if (!voice) player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r Секунду, я ещё обрабатываю предыдущий запрос…"));
            return;
        }
        IN_FLIGHT.add(uuid);
        LAST_REQUEST_MS.put(uuid, now);

        final MinecraftServer server = player.getServer();
        final JsonObject payload;
        try {
            if (raya != null) raya.showEmotion(RayaPrimeEntity.EMOTION_THINKING, 600);
            String world = ModKnowledgeScanner.buildWorldAndModContext(player, raya, voice ? "" : userText);
            payload = buildPayload(world, userText, wav, copyHistory(uuid));
        } catch (Throwable t) {
            IN_FLIGHT.remove(uuid);
            if (raya != null) raya.restoreBaseEmotion();
            RayaPrimeMod.LOGGER.error("[RayaPrime] Не удалось собрать контекст запроса", t);
            player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Внутренняя ошибка подготовки запроса: " + t));
            return;
        }
        RayaPrimeMod.LOGGER.info("[RayaPrime] Запрос от {} ({}): {}", player.getName().getString(),
                voice ? "голос" : "текст", voice ? "<аудио>" : userText);

        CompletableFuture.runAsync(() -> {
            try {
                String json = GeminiClient.generateJson(apiKey, payload);
                server.execute(() -> {
                    IN_FLIGHT.remove(uuid);
                    applyDecision(player, raya, json, userText, voice, apiKey);
                });
            } catch (Throwable e) {
                RayaPrimeMod.LOGGER.error("[RayaPrime] Ошибка обращения к Gemini API", e);
                String msg = String.valueOf(e.getMessage());
                if (msg.length() > 900) msg = msg.substring(0, 900) + "…";
                final String shown = msg;
                server.execute(() -> {
                    IN_FLIGHT.remove(uuid);
                    if (raya != null) raya.restoreBaseEmotion();
                    player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Сбой связи с Gemini: " + shown));
                });
            }
        });
    }

    // ------------------------------------------------------------------ запрос

    private static List<JsonObject> copyHistory(UUID uuid) {
        Deque<JsonObject> h = HISTORY.get(uuid);
        List<JsonObject> out = new ArrayList<>();
        if (h != null) {
            for (JsonObject o : h) out.add(o.deepCopy());
        }
        return out;
    }

    private static void remember(UUID uuid, String userText, String reply) {
        int maxMessages = RayaConfig.HISTORY_TURNS.get() * 2;
        if (maxMessages <= 0) return;
        Deque<JsonObject> h = HISTORY.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        h.addLast(textContent("user", userText));
        h.addLast(textContent("model", reply));
        while (h.size() > maxMessages) h.removeFirst();
    }

    public static void clearHistory() {
        HISTORY.clear();
        IN_FLIGHT.clear();
        LAST_REQUEST_MS.clear();
    }

    private static JsonObject textContent(String role, String text) {
        JsonObject c = new JsonObject();
        c.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        parts.add(p);
        c.add("parts", parts);
        return c;
    }

    private static JsonObject buildPayload(String worldContext, String userText, byte[] wav, List<JsonObject> history) {
        JsonObject root = new JsonObject();

        String catalog = ModKnowledgeScanner.getModCatalog();
        String system = SYSTEM_PROMPT
                + "\nУСТАНОВЛЕННЫЕ МОДЫ: " + ModKnowledgeScanner.getCompactModListSummary() + "\n"
                + (catalog.isEmpty() ? "" : "КАТАЛОГ ID МОДОВ (формат modid:путь):\n" + catalog);
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sp = new JsonObject();
        sp.addProperty("text", system);
        sysParts.add(sp);
        sys.add("parts", sysParts);
        root.add("systemInstruction", sys);

        JsonArray contents = new JsonArray();
        for (JsonObject h : history) contents.add(h);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject tp = new JsonObject();
        if (wav != null) {
            tp.addProperty("text", worldContext + "\n" + VOICE_NOTE);
        } else {
            tp.addProperty("text", worldContext + "\nСообщение носителя: " + userText);
        }
        parts.add(tp);
        if (wav != null) {
            JsonObject audio = new JsonObject();
            JsonObject inline = new JsonObject();
            inline.addProperty("mimeType", "audio/wav");
            inline.addProperty("data", Base64.getEncoder().encodeToString(wav));
            audio.add("inlineData", inline);
            parts.add(audio);
        }
        user.add("parts", parts);
        contents.add(user);
        root.add("contents", contents);

        JsonObject gen = new JsonObject();
        gen.addProperty("responseMimeType", "application/json");
        gen.addProperty("temperature", 0.7);
        root.add("generationConfig", gen);
        return root;
    }

    // ------------------------------------------------------------------ ответ

    private static JsonObject parseLenient(String raw) {
        String s = raw == null ? "" : raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("в ответе нет JSON");
        return JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static void applyDecision(ServerPlayer player, @Nullable RayaPrimeEntity startBody, String rawJson,
                                      String userText, boolean voice, String apiKey) {
        RayaPrimeEntity body = (startBody != null && !startBody.isRemoved()) ? startBody : null;
        COMMANDS_THIS_TICK.remove(player.getUUID());
        try {
            RayaPrimeMod.LOGGER.info("[RayaPrime] Ответ Gemini: {}", rawJson);
            JsonObject decision = parseLenient(rawJson);
            String reply = str(decision, "reply").trim();
            String heard = str(decision, "heard").trim();
            String emotion = str(decision, "emotion").toUpperCase(Locale.ROOT);
            int animId = RayaPrimeEntity.animIdByName(str(decision, "anim"));

            if (voice && reply.isEmpty() && !hasActions(decision)) {
                if (body != null) body.restoreBaseEmotion(); // речь была не к Рае
                return;
            }

            // Сначала действия (они меняют режим и эмоцию по режиму), потом эмоция из ответа
            if (decision.has("actions") && decision.get("actions").isJsonArray()) {
                for (JsonElement el : decision.getAsJsonArray("actions")) {
                    if (!el.isJsonObject()) continue;
                    try {
                        body = executeAction(player, body, el.getAsJsonObject());
                    } catch (Exception e) {
                        RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось выполнить действие {}", el, e);
                    }
                }
            }
            if (body != null && animId != RayaPrimeEntity.ANIM_NONE) {
                body.playAnimation(animId, RayaPrimeEntity.animDuration(animId));
            }
            if (body != null) {
                switch (emotion) {
                    case "HAPPY" -> body.showEmotion(RayaPrimeEntity.EMOTION_HAPPY, 160);
                    case "THINKING" -> body.showEmotion(RayaPrimeEntity.EMOTION_THINKING, 100);
                    case "CRIMSON" -> body.showEmotion(RayaPrimeEntity.EMOTION_CRIMSON, 240);
                    default -> body.restoreBaseEmotion();
                }
            }

            if (reply.isEmpty() && !voice) {
                player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§7 (Gemini вернул пустую реплику)"));
            }
            if (!reply.isEmpty()) {
                boolean crimson = body != null && body.isCrimsonMode();
                String prefix = crimson ? "§4[Кровавая Райя-Прайм]§c " : "§b[Райя-Прайм]§f ";
                if (voice && !heard.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§7[Ты сказал] " + heard));
                }
                player.sendSystemMessage(Component.literal(prefix + reply));
                RayaVoice.speak(player.getServer(), body, player, reply, apiKey, RayaVoice.moodOf(emotion));
                remember(player.getUUID(), voice ? (heard.isEmpty() ? "(голосовое сообщение)" : heard) : userText, reply);
            }
        } catch (Exception e) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось разобрать ответ Gemini: {}", rawJson, e);
            if (body != null) body.restoreBaseEmotion();
            String shown = rawJson == null ? "" : rawJson.trim();
            if (shown.length() > 300) shown = shown.substring(0, 300) + "…";
            player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§f " + shown));
        }
    }

    private static boolean hasActions(JsonObject d) {
        return d.has("actions") && d.get("actions").isJsonArray() && !d.getAsJsonArray("actions").isEmpty();
    }

    // ------------------------------------------------------------------ действия

    private static Integer intOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return (int) Math.floor(e.getAsDouble());
        } catch (Exception ex) {
            return null;
        }
    }

    /** Выполняет действие. @return актуальное тело Раи после действия (null — она в Ядре). */
    @Nullable
    private static RayaPrimeEntity executeAction(ServerPlayer player, @Nullable RayaPrimeEntity raya, JsonObject act) {
        String type = str(act, "type").toUpperCase(Locale.ROOT);
        String target = str(act, "target").trim();
        Integer x = intOrNull(act, "x");
        Integer y = intOrNull(act, "y");
        Integer z = intOrNull(act, "z");
        boolean hasCoords = x != null && y != null && z != null && !(x == 0 && y == 0 && z == 0);
        Integer countObj = intOrNull(act, "count");
        int count = Math.max(1, Math.min(48, countObj == null ? 1 : countObj));
        ServerLevel level = player.serverLevel();

        if ("RUN_COMMAND".equals(type)) {
            runCommand(player, target);
            return raya;
        }
        if ("RELEASE".equals(type)) {
            if (raya != null) {
                raya.clearTasks();
                raya.setActiveMode("FOLLOW");
                return raya;
            }
            ItemStack core = RayaStorage.findCore(player);
            if (core.isEmpty()) return null;
            if (RayaStorage.release(player, core)) {
                return RayaPrimeMod.findOwnedRaya(player);
            }
            return null;
        }
        if ("STORE_IN_INVENTORY".equals(type)) {
            if (raya != null && RayaStorage.store(player, raya)) {
                return null;
            }
            return raya;
        }
        if (raya == null) {
            RayaPrimeMod.LOGGER.debug("[RayaPrime] Действие {} пропущено: Райя в Ядре", type);
            return null;
        }

        switch (type) {
            case "FOLLOW" -> {
                raya.clearTasks();
                raya.setActiveMode("FOLLOW");
            }
            case "STAY" -> {
                raya.clearTasks();
                raya.setActiveMode("STAY");
            }
            case "HIDE_BEHIND_PLAYER", "HIDE_BEHIND_BLOCKS" -> {
                raya.clearTasks();
                raya.setActiveMode(type);
            }
            case "EMP_PULSE" -> {
                if (RayaConfig.ALLOW_COMBAT.get()) raya.triggerEmpPulse();
            }
            case "DROP_ITEMS" -> raya.dropItemsToOwner(target);
            case "COLLECT_ITEMS" -> {
                setWorkMode(raya);
                raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.COLLECT_ITEMS, raya.blockPosition(), target));
            }
            case "ATTACK" -> {
                if (!RayaConfig.ALLOW_COMBAT.get()) {
                    raya.tell("Боевой режим отключён в конфиге.");
                    return raya;
                }
                LivingEntity mob = findTargetEntity(level, player, raya, target);
                if (mob == null) {
                    raya.tell("Не вижу подходящей цели рядом.");
                    return raya;
                }
                raya.clearTasks();
                raya.setTarget(mob);
                raya.setActiveMode("COMBAT");
            }
            case "MINE_BLOCK" -> {
                setWorkMode(raya);
                List<BlockPos> targets = new ArrayList<>();
                if (hasCoords) {
                    targets.add(new BlockPos(x, y, z));
                } else if (target.isEmpty()) {
                    BlockPos looked = lookedAtBlock(player);
                    if (looked != null) targets.add(looked);
                } else {
                    targets.addAll(findNearestMatchingBlocks(level, player.blockPosition(), target, count));
                }
                if (targets.isEmpty()) {
                    raya.tell("Не нашла подходящих блоков рядом.");
                    return raya;
                }
                for (BlockPos p : targets) {
                    if (isUnderOwner(player, p)) continue; // не ломаем блок, на котором стоит игрок
                    if (!raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.MINE_BLOCK, p, target))) break;
                }
            }
            case "PLACE_BLOCK" -> {
                setWorkMode(raya);
                BlockPos pos = hasCoords ? new BlockPos(x, y, z) : player.blockPosition().relative(player.getDirection(), 2);
                raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.PLACE_BLOCK, pos, target));
            }
            case "FLY_TO" -> {
                if (hasCoords) {
                    setWorkMode(raya);
                    raya.enqueueTask(new RayaPrimeEntity.RayaTask(
                            RayaPrimeEntity.RayaTask.TaskType.FLY_TO, new BlockPos(x, y, z), ""));
                } else if (!target.isEmpty()) {
                    BlockPos wp = raya.findWaypoint(target);
                    if (wp == null) {
                        raya.tell("Я не знаю места «" + target + "». Скажи «запомни это место как ...».");
                    } else {
                        setWorkMode(raya);
                        raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.FLY_TO, wp, ""));
                    }
                }
            }
            case "FIND_BLOCK" -> {
                BlockPos found = ModKnowledgeScanner.findNearestBlock(level, player.blockPosition(), target,
                        RayaConfig.SCAN_RADIUS_CHUNKS.get());
                if (found == null) {
                    raya.tell("Не нашла «" + target + "» в просканированных чанках (радиус " + RayaConfig.SCAN_RADIUS_CHUNKS.get()
                            + "). Подойди ближе к неизведанным местам или спроси про другой блок.");
                } else {
                    int dist = (int) Math.sqrt(found.distSqr(player.blockPosition()));
                    raya.tell("Нашла «" + target + "»: [" + found.getX() + ", " + found.getY() + ", " + found.getZ()
                            + "], в " + dist + " блоках от тебя. Лечу показать!");
                    raya.playAnimation(RayaPrimeEntity.ANIM_WAVE, RayaPrimeEntity.animDuration(RayaPrimeEntity.ANIM_WAVE));
                    if (raya.canActOn(found)) {
                        setWorkMode(raya);
                        raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.FLY_TO, found, ""));
                    }
                }
            }
            case "GUARD" -> {
                if (!RayaConfig.ALLOW_COMBAT.get()) {
                    raya.tell("Боевой режим отключён в конфиге.");
                    return raya;
                }
                raya.clearTasks();
                raya.setActiveMode("GUARD");
            }
            case "SAVE_WAYPOINT" -> {
                BlockPos at = hasCoords ? new BlockPos(x, y, z) : player.blockPosition();
                if (target.isEmpty() || !raya.saveWaypoint(target, at)) {
                    raya.tell("Не получилось запомнить место: нужно название (и не больше 24 мест).");
                } else {
                    raya.tell("Запомнила место «" + target + "» [" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "].");
                }
            }
            case "DEPOSIT_ITEMS" -> {
                BlockPos chest = hasCoords ? new BlockPos(x, y, z) : RayaBuild.findContainer(level, player.blockPosition(), 16);
                if (chest == null) {
                    raya.tell("Не вижу сундука рядом, чтобы сложить вещи.");
                    return raya;
                }
                workModeForBuild(raya);
                raya.enqueueTask(new RayaPrimeEntity.RayaTask(RayaPrimeEntity.RayaTask.TaskType.DEPOSIT, chest, target));
            }
            case "CANCEL_BUILD" -> {
                if (raya.hasBuildPlan()) {
                    raya.setBuildPlan(null);
                    raya.tell("Стройку отменила.");
                }
            }
            case "LIGHT_AREA" -> {
                RayaBuild.Plan plan = RayaBuild.lightPlan(level, player.blockPosition(), 12);
                if (plan.steps.isEmpty()) {
                    raya.tell("Вокруг и так достаточно светло.");
                } else {
                    startBuild(raya, plan);
                }
            }
            case "BUILD" -> {
                int w = intOr(act, "width", 0), d = intOr(act, "depth", 0), h = intOr(act, "height", 0);
                String wallTarget = target.isBlank() ? RayaBuild.detectWall(raya, player) : target;
                BlockPos origin = hasCoords ? new BlockPos(x, y, z) : player.blockPosition().relative(player.getDirection(), 2);
                RayaBuild.Plan plan = RayaBuild.generate(str(act, "shape"), origin, player.getDirection(), w, d, h,
                        wallTarget, str(act, "roof"));
                if (plan == null) {
                    raya.tell("Не умею строить фигуру «" + str(act, "shape") + "». Могу: house, tower, wall, fence, platform, box, pillar, stairs — или свой чертёж.");
                } else {
                    startBuild(raya, plan);
                }
            }
            case "BUILD_CUSTOM" -> {
                List<int[]> coords = new ArrayList<>();
                List<String> blocks = new ArrayList<>();
                if (act.has("blocks") && act.get("blocks").isJsonArray()) {
                    for (JsonElement be : act.getAsJsonArray("blocks")) {
                        if (!be.isJsonObject() || coords.size() >= RayaBuild.maxBlocks()) continue;
                        JsonObject bo = be.getAsJsonObject();
                        String block = str(bo, "block").trim();
                        coords.add(new int[]{intOr(bo, "dx", 0), intOr(bo, "dy", 0), intOr(bo, "dz", 2)});
                        blocks.add(block.isEmpty() ? target : block);
                    }
                }
                if (coords.isEmpty()) {
                    raya.tell("В чертеже нет блоков — нечего строить.");
                } else {
                    BlockPos origin = hasCoords ? new BlockPos(x, y, z) : player.blockPosition();
                    String name = str(act, "name").trim();
                    startBuild(raya, RayaBuild.custom(name.isEmpty() ? "постройка" : name, origin, player.getDirection(), coords, blocks));
                }
            }
            default -> RayaPrimeMod.LOGGER.debug("[RayaPrime] Неизвестное действие: {}", type);
        }
        return raya;
    }

    private static final Map<UUID, Integer> COMMANDS_THIS_TICK = new HashMap<>();

    /** Выполнить команду от имени игрока (его права!), с чёрным списком опасных слов. */
    private static void runCommand(ServerPlayer player, String raw) {
        if (!RayaConfig.ALLOW_COMMANDS.get()) {
            player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§7 (выполнение команд выключено в конфиге: allowCommands)"));
            return;
        }
        String cmd = raw == null ? "" : raw.trim();
        while (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        if (cmd.isEmpty()) return;

        // не больше 5 команд за один ответ
        int n = COMMANDS_THIS_TICK.merge(player.getUUID(), 1, Integer::sum);
        if (n > 5) return;

        Set<String> banned = new java.util.HashSet<>();
        for (String w : RayaConfig.splitList(RayaConfig.COMMAND_BLACKLIST.get())) banned.add(w.toLowerCase(Locale.ROOT));
        for (String tok : cmd.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (banned.contains(tok)) {
                player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§c Эту команду я выполнять не буду: §7/" + cmd));
                return;
            }
        }
        player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§7 Выполняю: §f/" + cmd));
        try {
            player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), cmd);
        } catch (Throwable t) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Команда '{}' не выполнена", cmd, t);
            player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Команда не выполнилась: " + t.getMessage()));
        }
    }

    private static int intOr(JsonObject o, String key, int def) {
        Integer v = intOrNull(o, key);
        return v == null ? def : v;
    }

    private static void workModeForBuild(RayaPrimeEntity raya) {
        String mode = raya.getActiveMode();
        if (mode.startsWith("HIDE") || "COMBAT".equals(mode)) {
            raya.setActiveMode("FOLLOW");
        }
    }

    /** Запустить стройку и рассказать, сколько и каких блоков нужно. */
    public static void startBuild(RayaPrimeEntity raya, RayaBuild.Plan plan) {
        raya.clearTasks();
        workModeForBuild(raya);
        raya.setBuildPlan(plan);
        String hint = plan.name.equals("дом")
                ? " Для красоты (необязательно) положи мне ступени того же дерева для крыши, стеклянные панели, дверь, брёвна для углов, факелы."
                : plan.name.startsWith("смотровая") ? " Для лестницы нужны лестницы (ladder), для света — факелы." : "";
        raya.tell("Стройка «" + plan.name + "»: " + plan.total + " блоков. Нужно: " + RayaBuild.needs(plan) + "." + hint);
    }

    /** Для задач на работу выходим из режимов укрытия/боя/ожидания, чтобы задачи реально выполнялись. */
    private static void setWorkMode(RayaPrimeEntity raya) {
        String mode = raya.getActiveMode();
        if (!"FOLLOW".equals(mode)) {
            raya.setActiveMode("FOLLOW");
        }
    }

    private static boolean isUnderOwner(Player player, BlockPos p) {
        BlockPos feet = player.blockPosition();
        return p.getX() == feet.getX() && p.getZ() == feet.getZ() && p.getY() == feet.getY() - 1;
    }

    private static BlockPos lookedAtBlock(ServerPlayer player) {
        BlockHitResult hit = player.serverLevel().clip(new ClipContext(
                player.getEyePosition(),
                player.getEyePosition().add(player.getLookAngle().scale(8.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static List<BlockPos> findNearestMatchingBlocks(ServerLevel level, BlockPos origin, String filter, int count) {
        String f = filter.toLowerCase(Locale.ROOT);
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    if (id.toString().toLowerCase(Locale.ROOT).contains(f)
                            || st.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(f)) {
                        found.add(p.immutable());
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
    }

    private static LivingEntity findTargetEntity(ServerLevel level, ServerPlayer player, RayaPrimeEntity raya, String filter) {
        String f = filter.toLowerCase(Locale.ROOT);
        AABB box = raya.getBoundingBox().inflate(24.0D);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != raya && e.isAlive() && !(e instanceof Player) && !raya.isOwnedBy(e));
        return list.stream()
                .filter(e -> f.isEmpty()
                        || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().toLowerCase(Locale.ROOT).contains(f)
                        || e.getName().getString().toLowerCase(Locale.ROOT).contains(f))
                .min(Comparator.comparingDouble(raya::distanceToSqr))
                .orElseGet(() -> f.isEmpty() ? raya.findNearestThreat() : null);
    }
}
