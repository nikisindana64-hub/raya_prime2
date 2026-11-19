package com.mamarala.rayaprime.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class RayaConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> GEMINI_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> GEMINI_MODEL;
    public static final ModConfigSpec.ConfigValue<String> GEMINI_FALLBACK_MODELS;
    public static final ModConfigSpec.ConfigValue<String> GEMINI_THINKING_LEVEL;

    public static final ModConfigSpec.BooleanValue VOICE_ACTIVATION_ENABLED;
    public static final ModConfigSpec.IntValue SILENCE_THRESHOLD_MS;
    public static final ModConfigSpec.DoubleValue VOICE_TRIGGER_RMS;
    public static final ModConfigSpec.BooleanValue SPEECH_ENABLED;
    public static final ModConfigSpec.DoubleValue SPEECH_VOLUME;
    public static final ModConfigSpec.BooleanValue USE_GEMINI_TTS;
    public static final ModConfigSpec.ConfigValue<String> TTS_MODEL;
    public static final ModConfigSpec.ConfigValue<String> TTS_FALLBACK_MODELS;
    public static final ModConfigSpec.ConfigValue<String> TTS_VOICE;

    public static final ModConfigSpec.BooleanValue ALLOW_BLOCK_BREAKING;
    public static final ModConfigSpec.BooleanValue ALLOW_BLOCK_PLACING;
    public static final ModConfigSpec.BooleanValue ALLOW_COMBAT;
    public static final ModConfigSpec.IntValue MAX_TASK_DISTANCE;
    public static final ModConfigSpec.IntValue RETURN_DISTANCE;

    public static final ModConfigSpec.BooleanValue FEAR_ENABLED;
    public static final ModConfigSpec.IntValue FEAR_RADIUS;
    public static final ModConfigSpec.ConfigValue<String> FEAR_KEYWORDS;
    public static final ModConfigSpec.DoubleValue FEAR_MIN_HEALTH;
    public static final ModConfigSpec.DoubleValue FEAR_MIN_DAMAGE;

    public static final ModConfigSpec.IntValue SCAN_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue MOD_CATALOG_MAX_ENTRIES;
    public static final ModConfigSpec.IntValue STORY_MAX_ADVANCEMENTS;
    public static final ModConfigSpec.IntValue HISTORY_TURNS;

    /** Ключ, заданный командой /raya apikey (живёт до перезапуска). */
    public static volatile String RUNTIME_API_KEY_OVERRIDE = "";

    static {
        BUILDER.push("gemini_api");
        GEMINI_API_KEY = BUILDER
                .comment("API-ключ Google Gemini (Google AI Studio). Можно задать и командой /raya apikey <КЛЮЧ> (нужны права оператора),",
                        "или переменной окружения GEMINI_API_KEY.")
                .define("geminiApiKey", "");
        GEMINI_MODEL = BUILDER
                .comment("Основная модель для мозга Раи (текст + аудио на входе, JSON на выходе).")
                .define("geminiModel", "gemini-3.5-flash-lite");
        GEMINI_FALLBACK_MODELS = BUILDER
                .comment("Запасные модели через запятую: пробуются по очереди, если основная недоступна/перегружена.")
                .define("geminiFallbackModels", "gemini-3.6-flash,gemini-3.8-flash");
        GEMINI_THINKING_LEVEL = BUILDER
                .comment("Уровень «размышлений» для Gemini 3.x (minimal/low/medium/high). Меньше = быстрее ответ. Пусто = по умолчанию модели.")
                .define("geminiThinkingLevel", "minimal");
        BUILDER.pop();

        BUILDER.push("voice_chat");
        VOICE_ACTIVATION_ENABLED = BUILDER
                .comment("Слушать голос владельца через Simple Voice Chat (нужен запущенный voicechat: на сервере или в мире, открытом для сети).",
                        "ВНИМАНИЕ: записанная фраза отправляется в Google (Gemini API). Gemini сам решает, обращаются ли к Рае.")
                .define("voiceActivationEnabled", true);
        SILENCE_THRESHOLD_MS = BUILDER
                .comment("Пауза тишины (мс), после которой фраза считается законченной и уходит в Gemini.")
                .defineInRange("silenceThresholdMs", 900, 300, 3000);
        VOICE_TRIGGER_RMS = BUILDER
                .comment("Порог громкости (RMS), с которого начинается запись фразы.")
                .defineInRange("voiceTriggerRms", 220.0, 20.0, 5000.0);
        SPEECH_ENABLED = BUILDER
                .comment("Озвучивать ответы Раи. Если Simple Voice Chat работает — звук идёт от тела Раи в 3D,",
                        "иначе — напрямую в игру владельцу (работает и в одиночной игре без voicechat).")
                .define("speechEnabled", true);
        SPEECH_VOLUME = BUILDER
                .comment("Громкость голоса Раи (1.0 = как есть).")
                .defineInRange("speechVolume", 1.0, 0.1, 2.0);
        USE_GEMINI_TTS = BUILDER
                .comment("Использовать настоящий синтез речи Gemini TTS (иначе — мелодичные «бипы» как у робота).")
                .define("useGeminiTts", true);
        TTS_MODEL = BUILDER
                .comment("Модель синтеза речи Gemini.")
                .define("ttsModel", "gemini-3.8-flash-lite-tts");
        TTS_FALLBACK_MODELS = BUILDER
                .comment("Запасные модели TTS через запятую.")
                .define("ttsFallbackModels", "gemini-3.8-flash-tts,gemini-3.1-flash-tts-preview");
        TTS_VOICE = BUILDER
                .comment("Имя голоса Gemini TTS: Aoede (лёгкий), Zephyr (яркий), Kore (твёрдый), Puck (бодрый), Charon, Gacrux и др.")
                .define("ttsVoice", "Aoede");
        BUILDER.pop();

        BUILDER.push("abilities");
        ALLOW_BLOCK_BREAKING = BUILDER
                .comment("Разрешить Рае ломать блоки по команде ИИ.")
                .define("allowBlockBreaking", true);
        ALLOW_BLOCK_PLACING = BUILDER
                .comment("Разрешить Рае ставить блоки из своего инвентаря.")
                .define("allowBlockPlacing", true);
        ALLOW_COMBAT = BUILDER
                .comment("Разрешить Рае атаковать мобов и использовать ЭМИ-импульс.")
                .define("allowCombat", true);
        MAX_TASK_DISTANCE = BUILDER
                .comment("Максимальное расстояние (в блоках) от владельца до цели задачи (ломать/ставить/лететь).")
                .defineInRange("maxTaskDistance", 48, 8, 256);
        RETURN_DISTANCE = BUILDER
                .comment("Если Райя дальше этого расстояния от владельца (и не прячется/не работает) — она летит искать владельца.",
                        "Втрое дальше — телепортируется к нему.")
                .defineInRange("returnDistance", 48, 16, 256);
        BUILDER.pop();

        BUILDER.push("fear");
        FEAR_ENABLED = BUILDER
                .comment("Райя сама прячется и говорит, что ей страшно, когда рядом появляется очень опасное существо.")
                .define("fearEnabled", true);
        FEAR_RADIUS = BUILDER
                .comment("В каком радиусе (блоки) она замечает опасных существ.")
                .defineInRange("fearRadius", 40, 8, 128);
        FEAR_KEYWORDS = BUILDER
                .comment("Существо считается «жутким», если любая запись (через запятую) подходит: запись с двоеточием (minecraft:warden) — точный ID,",
                        "без двоеточия (herobrine) — подстрока в ID или названии. Сюда можно дописать чудовищ из сюжетных модов (Broken Script и др.).")
                .define("fearKeywords", "minecraft:warden,minecraft:wither,minecraft:ender_dragon,minecraft:elder_guardian,herobrine,entity_303,entity303,brokenscript,broken_script,the_broken");
        FEAR_MIN_HEALTH = BUILDER
                .comment("Автоопределение опасности: враждебное существо с таким или большим здоровьем пугает Раю.")
                .defineInRange("fearMinHealth", 150.0, 20.0, 100000.0);
        FEAR_MIN_DAMAGE = BUILDER
                .comment("Автоопределение опасности: враждебное существо с такой или большей силой атаки пугает Раю.")
                .defineInRange("fearMinDamage", 18.0, 4.0, 100000.0);
        BUILDER.pop();

        BUILDER.push("knowledge");
        SCAN_RADIUS_CHUNKS = BUILDER
                .comment("Радиус сканирования мира вокруг игрока в чанках (структуры, модовые блоки, биомы, существа).")
                .defineInRange("scanRadiusChunks", 3, 1, 6);
        MOD_CATALOG_MAX_ENTRIES = BUILDER
                .comment("Сколько ID блоков/предметов/мобов из установленных модов (не vanilla) передавать Gemini в каталоге.",
                        "Больше = лучше понимает моды, но дороже и медленнее запрос.")
                .defineInRange("modCatalogMaxEntries", 800, 0, 8000);
        STORY_MAX_ADVANCEMENTS = BUILDER
                .comment("Сколько достижений модов (сюжетные цепочки) читать для понимания сюжета.")
                .defineInRange("storyMaxAdvancements", 120, 0, 600);
        HISTORY_TURNS = BUILDER
                .comment("Сколько последних реплик диалога помнит Райя (на игрока).")
                .defineInRange("historyTurns", 10, 0, 40);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static String getEffectiveApiKey() {
        String override = RUNTIME_API_KEY_OVERRIDE;
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return GEMINI_API_KEY.get().trim();
    }

    public static java.util.List<String> splitList(String csv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
