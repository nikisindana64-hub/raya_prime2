package com.mamarala.rayaprime.voice;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.ai.GeminiClient;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.network.RayaSpeechPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Озвучка Раи. Два пути:
 * 1) Simple Voice Chat запущен (сервер / мир открыт для сети) и у Раи есть тело → 3D-звук от её тела;
 * 2) иначе (одиночная игра, Райя в виде Ядра в инвентаре) → звук отправляется на клиент владельца и играет напрямую.
 * Этот класс НЕ импортирует классы Simple Voice Chat, поэтому безопасен без мода voicechat.
 */
public final class RayaVoice {

    private RayaVoice() {}

    public static boolean isVoiceChatLoaded() {
        try {
            return ModList.get().isLoaded("voicechat");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Простой voicechat реально запущен (серверный API получен). */
    public static boolean isVoiceChatActive() {
        if (!isVoiceChatLoaded()) return false;
        try {
            return RayaVoiceChatPlugin.isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Настроение голоса. */
    public enum Mood { NORMAL, HAPPY, THINKING, CRIMSON, FEAR }

    public static Mood moodOf(String emotion) {
        if (emotion == null) return Mood.NORMAL;
        return switch (emotion.toUpperCase(java.util.Locale.ROOT)) {
            case "HAPPY" -> Mood.HAPPY;
            case "THINKING" -> Mood.THINKING;
            case "CRIMSON" -> Mood.CRIMSON;
            case "HIDING", "FEAR" -> Mood.FEAR;
            default -> Mood.NORMAL;
        };
    }

    /** Манера речи для моделей gemini-3.8-*-tts (по-английски). */
    static String styleOf(Mood mood) {
        return switch (mood) {
            case HAPPY -> "cheerful, excited and warm";
            case THINKING -> "thoughtful, a little slower, curious";
            case CRIMSON -> "cold, sharp and menacing, low and controlled";
            case FEAR -> "frightened, hurried, trembling voice";
            default -> "friendly, lively, with a slight playful irony";
        };
    }

    /**
     * Диагностика озвучки для /raya say и /raya voicetest: сообщает игроку, что именно сломано
     * (Gemini TTS или воспроизведение на клиенте).
     */
    public static void diagnose(MinecraftServer server, ServerPlayer player, String text, boolean useTts, String apiKey) {
        final UUID id = player.getUUID();
        final String clean = speakable(text);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(useTts
                ? "§7[Голос] Проверяю Gemini TTS (модель " + RayaConfig.TTS_MODEL.get() + ", голос " + RayaConfig.TTS_VOICE.get() + ")…"
                : "§7[Голос] Проверяю воспроизведение на твоём клиенте (без Gemini)…"));
        CompletableFuture.runAsync(() -> {
            long t0 = System.currentTimeMillis();
            GeminiClient.Pcm pcm = null;
            String note;
            if (useTts) {
                pcm = apiKey == null || apiKey.isEmpty() ? null
                        : GeminiClient.synthesizeSpeech(apiKey, clean, RayaConfig.TTS_VOICE.get().trim(), styleOf(Mood.NORMAL));
                note = pcm == null ? "§cGemini TTS НЕ РАБОТАЕТ: " + (apiKey == null || apiKey.isEmpty() ? "нет ключа" : GeminiClient.lastTtsError)
                        : "§aGemini TTS работает§7: " + pcm.samples().length + " сэмплов, " + pcm.rate() + " Гц, за " + (System.currentTimeMillis() - t0) + " мс.";
            } else {
                note = "§7Играю тестовый сигнал.";
            }
            final GeminiClient.Pcm result = pcm != null ? pcm : new GeminiClient.Pcm(synthesizeBeeps(clean, false), 48000);
            final String msg = note;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null) return;
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7[Голос] " + msg));
                try {
                    short[] out = applyVolume(result.samples(), RayaConfig.SPEECH_VOLUME.get());
                    PacketDistributor.sendToPlayer(p, new RayaSpeechPayload(toBytes(out, result.rate()), result.rate()));
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7[Голос] Звук отправлен на твой клиент. Если ты его не слышишь — дело в воспроизведении на ПК: пришли строки [RayaPrime] из logs/latest.log."));
                } catch (Throwable t) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Голос] Не удалось отправить звук клиенту: " + t));
                }
            });
        });
    }

    /** Кэш готовых фраз (повторяющиеся реплики не тратят запросы к TTS). */
    private static final java.util.Map<String, GeminiClient.Pcm> CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, GeminiClient.Pcm> eldest) {
                    return size() > 24;
                }
            });
    private static final java.util.Map<UUID, Long> LAST_FAIL_NOTICE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void speak(MinecraftServer server, @Nullable RayaPrimeEntity raya, @Nullable ServerPlayer listener,
                             String text, String apiKey) {
        speak(server, raya, listener, text, apiKey, raya != null && raya.isCrimsonMode() ? Mood.CRIMSON : Mood.NORMAL);
    }

    /** Убрать форматирование, координаты и эмодзи, оставить 1–3 предложения: так озвучка быстрее и естественнее. */
    static String speakable(String text) {
        String t = text.replaceAll("§.", "")
                .replaceAll("[*_`#>]+", "")
                .replaceAll("\\[[^\\]]*\\d+\\s*,\\s*-?\\d+[^\\]]*\\]", "")
                .replaceAll("[\\p{So}\\p{Cs}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.length() <= 280) return t;
        int cut = Math.max(t.lastIndexOf('.', 280), Math.max(t.lastIndexOf('!', 280), t.lastIndexOf('?', 280)));
        return cut > 80 ? t.substring(0, cut + 1) : t.substring(0, 280);
    }

    /**
     * Озвучить реплику (вызывать из серверного потока). Синтез идёт в фоне, воспроизведение — снова на сервере.
     * @param raya     тело Раи (null — если она сейчас в виде Ядра в инвентаре)
     * @param listener игрок-владелец, которому адресован голос
     * @param mood     настроение: влияет на голос, темп и высоту
     */
    public static void speak(MinecraftServer server, @Nullable RayaPrimeEntity raya, @Nullable ServerPlayer listener,
                             String text, String apiKey, Mood mood) {
        final String clean = text == null ? "" : speakable(text);
        if (!RayaConfig.SPEECH_ENABLED.get() || clean.isBlank()) {
            return;
        }
        final boolean crimson = mood == Mood.CRIMSON;
        final boolean tts = RayaConfig.USE_GEMINI_TTS.get() && apiKey != null && !apiKey.isEmpty();
        final double volume = RayaConfig.SPEECH_VOLUME.get();
        final UUID listenerId = listener != null ? listener.getUUID() : null;
        String voice = RayaConfig.TTS_VOICE.get().trim();
        if (crimson && !RayaConfig.TTS_VOICE_CRIMSON.get().isBlank()) voice = RayaConfig.TTS_VOICE_CRIMSON.get().trim();
        final String voiceName = voice;
        final double speed = !RayaConfig.MOOD_VOICE_EFFECTS.get() ? 1.0 : switch (mood) {
            case CRIMSON -> 0.90;
            case FEAR -> 1.10;
            case HAPPY -> 1.04;
            case THINKING -> 0.97;
            default -> 1.0;
        };

        CompletableFuture.runAsync(() -> {
            String failure = null;
            short[] samples = null;
            int rate = 24000;
            String key = voiceName + "|" + mood + "|" + clean;
            GeminiClient.Pcm cached = CACHE.get(key);
            if (cached != null) {
                samples = cached.samples();
                rate = cached.rate();
            } else if (tts) {
                GeminiClient.Pcm pcm = GeminiClient.synthesizeSpeech(apiKey, clean, voiceName,
                        RayaConfig.TTS_STYLE.get() ? styleOf(mood) : "");
                if (pcm != null && pcm.samples().length > 0) {
                    samples = pcm.samples();
                    rate = pcm.rate();
                    CACHE.put(key, pcm);
                } else {
                    failure = GeminiClient.lastTtsError;
                }
            } else {
                failure = "озвучка Gemini TTS выключена в конфиге";
            }
            if (samples == null) {
                samples = synthesizeBeeps(clean, crimson);
                rate = 48000;
            } else {
                samples = GeminiClient.changeSpeed(samples, speed);
            }
            final short[] out = applyVolume(samples, volume);
            final int outRate = rate;
            final String fail = failure;
            server.execute(() -> {
                try {
                    if (raya != null && !raya.isRemoved()) {
                        raya.setTalkTicks((int) Math.min(20L * 20, out.length * 20L / Math.max(1, outRate)));
                    }
                    ServerPlayer target = listenerId == null ? null : server.getPlayerList().getPlayer(listenerId);
                    if (fail != null && target != null) {
                        long now = System.currentTimeMillis();
                        Long last = LAST_FAIL_NOTICE.get(listenerId);
                        if (last == null || now - last > 60_000L) {
                            LAST_FAIL_NOTICE.put(listenerId, now);
                            String shown = fail.length() > 200 ? fail.substring(0, 200) + "…" : fail;
                            target.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "§7[Райя-Прайм] (голос не получился, говорю бипами: " + shown + ")"));
                        }
                    }
                    if (RayaConfig.VOICECHAT_3D_SPEECH.get() && raya != null && raya.isAlive() && !raya.isRemoved()
                            && isVoiceChatActive() && RayaVoiceChatPlugin.isConnected(listenerId)) {
                        RayaPrimeMod.LOGGER.info("[RayaPrime] Голос: 3D через Simple Voice Chat ({} сэмплов)", out.length);
                        RayaVoiceChatPlugin.play(raya, GeminiClient.resampleTo48k(out, outRate));
                        return;
                    }
                    if (target != null) {
                        RayaPrimeMod.LOGGER.info("[RayaPrime] Голос: отправка клиенту напрямую ({} сэмплов, {} Гц)", out.length, outRate);
                        PacketDistributor.sendToPlayer(target, new RayaSpeechPayload(toBytes(out, outRate), outRate));
                    } else {
                        RayaPrimeMod.LOGGER.warn("[RayaPrime] Голос: игрок недоступен, звук не отправлен");
                    }
                } catch (Throwable t) {
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось воспроизвести голос: {}", t.toString());
                }
            });
        });
    }

    private static short[] applyVolume(short[] in, double volume) {
        if (Math.abs(volume - 1.0) < 0.01) return in;
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(in[i] * volume)));
        }
        return out;
    }

    /** PCM → байты little-endian, не длиннее ~18 секунд (лимит размера сетевого пакета). */
    private static byte[] toBytes(short[] samples, int rate) {
        int n = Math.min(samples.length, rate * 18);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            out[2 * i] = (byte) (samples[i] & 0xFF);
            out[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Запасной «роботизированный» голос: мелодичные тона по слогам (48 кГц, 16 бит, моно). */
    static short[] synthesizeBeeps(String text, boolean crimson) {
        int sampleRate = 48000;
        int syllables = Math.min(24, Math.max(4, text.length() / 4));
        int samplesPerSyllable = sampleRate / 12;
        short[] out = new short[syllables * samplesPerSyllable];
        double baseFreq = crimson ? 260.0 : 520.0;
        for (int s = 0; s < syllables; s++) {
            char c = text.charAt((s * 3) % text.length());
            double freq = baseFreq + ((c % 7) * (crimson ? 28.0 : 55.0));
            for (int i = 0; i < samplesPerSyllable; i++) {
                double t = (double) i / sampleRate;
                double env = Math.sin(Math.PI * i / samplesPerSyllable);
                double wave = Math.sin(2.0 * Math.PI * freq * t) * 0.65
                        + Math.sin(2.0 * Math.PI * (freq * 1.5) * t) * 0.35;
                out[s * samplesPerSyllable + i] = (short) (wave * env * 5500);
            }
        }
        return out;
    }
}
