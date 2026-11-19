package com.mamarala.rayaprime.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.config.RayaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Низкоуровневый HTTP-клиент Gemini API (текст/аудио → JSON, текст → речь). Вызывать ТОЛЬКО из фонового потока. */
public final class GeminiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1) // HTTP/2 через некоторые VPN/прокси зависает
            .build();
    private static final Gson GSON = new Gson();
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private GeminiClient() {}

    public static class GeminiException extends Exception {
        public final int status;

        public GeminiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Один запрос generateContent. Возвращает тело ответа как JsonObject. */
    private static JsonObject post(String model, String apiKey, JsonObject payload, int timeoutSec) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + model + ":generateContent"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String body = response.body();
            if (body != null && body.length() > 400) body = body.substring(0, 400) + "…";
            throw new GeminiException(response.statusCode(), "HTTP " + response.statusCode() + " (" + model + "): " + body);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static List<String> modelChain(String primary, String fallbackCsv) {
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        for (String m : RayaConfig.splitList(fallbackCsv)) {
            if (!chain.contains(m)) chain.add(m);
        }
        return chain;
    }

    // ------------------------------------------------------------------ мозг

    /** Запрашивает у Gemini JSON-решение. При 404/400 (модель снята / нет параметра) пробует запасные модели. */
    public static String generateJson(String apiKey, JsonObject payloadTemplate) throws Exception {
        String thinking = RayaConfig.GEMINI_THINKING_LEVEL.get().trim();
        StringBuilder errors = new StringBuilder();
        int lastStatus = 0;
        int netFails = 0;
        for (String model : modelChain(RayaConfig.GEMINI_MODEL.get().trim(), RayaConfig.GEMINI_FALLBACK_MODELS.get())) {
            if (netFails >= 3) break; // сеть недоступна — перебор моделей бессмыслен
            boolean useThinking = !thinking.isEmpty() && model.startsWith("gemini-3");
            boolean withThinking = useThinking;
            for (int attempt = 0; attempt < 3; attempt++) {
                JsonObject payload = payloadTemplate.deepCopy();
                if (withThinking) {
                    JsonObject tc = new JsonObject();
                    tc.addProperty("thinkingLevel", thinking);
                    payload.getAsJsonObject("generationConfig").add("thinkingConfig", tc);
                }
                long t0 = System.currentTimeMillis();
                try {
                    String text = extractText(post(model, apiKey, payload, 35));
                    RayaPrimeMod.LOGGER.info("[RayaPrime] Gemini ({}) ответил за {} мс", model, System.currentTimeMillis() - t0);
                    return text;
                } catch (java.io.IOException e) {
                    // таймаут / нет соединения / обрыв — это сеть, а не модель. Обрыв соединения часто разовый: один повтор
                    if (attempt == 0 && !(e instanceof java.net.http.HttpTimeoutException)) {
                        RayaPrimeMod.LOGGER.warn("[RayaPrime] Обрыв соединения ({}), повторяю: {}", model, e.toString());
                        Thread.sleep(700L);
                        continue;
                    }
                    netFails++;
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] Сеть: {} ({}) за {} мс", e.getClass().getSimpleName(), model, System.currentTimeMillis() - t0);
                    errors.append("\n• ").append(model).append(": сеть — ").append(e.getClass().getSimpleName())
                            .append(" (").append(e.getMessage()).append(')');
                    break;
                } catch (GeminiException e) {
                    lastStatus = e.status;
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] {} (попытка {}, thinking={}): {}", model, attempt + 1, withThinking, e.getMessage());
                    if (e.status == 400 && withThinking) {
                        withThinking = false; // возможно, модель не принимает thinkingConfig — повтор без него
                        continue;
                    }
                    if ((e.status == 503 || e.status == 500 || e.status == 429) && attempt < 2) {
                        Thread.sleep(1500L * (attempt + 1)); // перегрузка — подождать и повторить ту же модель
                        continue;
                    }
                    String m = e.getMessage();
                    if (m.length() > 170) m = m.substring(0, 170) + "…";
                    errors.append("\n• ").append(m.replace('\n', ' '));
                    break;
                }
            }
            // 401/403 — проблема с ключом, менять модель бессмысленно
            if (lastStatus == 401 || lastStatus == 403) {
                break;
            }
        }
        throw new GeminiException(lastStatus, errors.length() == 0 ? "Нет доступных моделей Gemini" : errors.toString());
    }

    private static String extractText(JsonObject resp) {
        JsonArray candidates = resp.has("candidates") && resp.get("candidates").isJsonArray() ? resp.getAsJsonArray("candidates") : null;
        if (candidates == null || candidates.isEmpty()) {
            String reason = "";
            if (resp.has("promptFeedback")) reason = " " + resp.get("promptFeedback");
            throw new IllegalStateException("Пустой ответ Gemini." + reason);
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) {
            throw new IllegalStateException("Gemini не вернул текст (возможно, сработал фильтр безопасности).");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement p : content.getAsJsonArray("parts")) {
            JsonObject po = p.getAsJsonObject();
            // части с "thought": true — это размышления, их пропускаем
            if (po.has("thought") && po.get("thought").getAsBoolean()) continue;
            if (po.has("text")) sb.append(po.get("text").getAsString());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ голос (TTS)

    /** Моно PCM 16 бит со своей частотой дискретизации. */
    public record Pcm(short[] samples, int rate) {}

    private static JsonObject ttsPayload(String text, String voice, boolean newShape) {
        JsonObject payload = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject tp = new JsonObject();
        // Только сам текст: модели TTS зачитывают ВСЁ, что им дали (включая «скажи весело:»)
        tp.addProperty("text", text);
        parts.add(tp);
        c.add("parts", parts);
        contents.add(c);
        payload.add("contents", contents);

        JsonObject gen = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        gen.add("responseModalities", modalities);
        JsonObject voiceCfg = new JsonObject();
        if (newShape) {
            voiceCfg.addProperty("voice", voice);
        } else {
            JsonObject prebuilt = new JsonObject();
            prebuilt.addProperty("voiceName", voice);
            voiceCfg.add("prebuiltVoiceConfig", prebuilt);
        }
        JsonObject speechCfg = new JsonObject();
        speechCfg.add("voiceConfig", voiceCfg);
        gen.add("speechConfig", speechCfg);
        payload.add("generationConfig", gen);
        return payload;
    }

    /** Причина последней неудачи TTS (для диагностики в чате), null если всё хорошо. */
    public static volatile String lastTtsError = null;

    public static Pcm synthesizeSpeech(String apiKey, String text) {
        return synthesizeSpeech(apiKey, text, RayaConfig.TTS_VOICE.get().trim());
    }

    /** Синтез речи Gemini TTS. Возвращает PCM (обычно 24 кГц) или null при неудаче. Вызывать из фонового потока. */
    public static Pcm synthesizeSpeech(String apiKey, String text, String voice) {
        if (voice == null || voice.isBlank()) voice = RayaConfig.TTS_VOICE.get().trim();
        StringBuilder errors = new StringBuilder();
        boolean newShape = false; // запоминаем форму voiceConfig, которая сработала
        for (String model : modelChain(RayaConfig.TTS_MODEL.get().trim(), RayaConfig.TTS_FALLBACK_MODELS.get())) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    JsonObject resp = post(model, apiKey, ttsPayload(text, voice, newShape), 45);
                    Pcm pcm = extractAudio(resp, model);
                    if (pcm != null) {
                        lastTtsError = null;
                        return pcm;
                    }
                    errors.append(" ").append(model).append(": пустой ответ;");
                    break;
                } catch (GeminiException e) {
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] TTS (попытка {}): {}", attempt + 1, e.getMessage());
                    if (e.status == 401 || e.status == 403) {
                        lastTtsError = "ключ не подходит для TTS (HTTP " + e.status + ")";
                        return null;
                    }
                    if (e.status == 400 && attempt == 0) {
                        newShape = !newShape; // возможно, другая форма voiceConfig
                        continue;
                    }
                    if ((e.status == 429 || e.status == 500 || e.status == 503) && attempt < 2) {
                        sleepQuietly(e.status == 429 ? 4000L : 1500L * (attempt + 1)); // лимит запросов/перегрузка — подождать
                        continue;
                    }
                    errors.append(" ").append(model).append(": HTTP ").append(e.status).append(';');
                    break;
                } catch (java.io.IOException e) {
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] TTS сеть ({}): {}", model, e.toString());
                    if (attempt < 1) {
                        sleepQuietly(800L);
                        continue;
                    }
                    errors.append(" ").append(model).append(": сеть ").append(e.getClass().getSimpleName()).append(';');
                    break;
                } catch (Exception e) {
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] TTS не удался на модели {}: {}", model, e.toString());
                    errors.append(" ").append(model).append(": ").append(e.getClass().getSimpleName()).append(';');
                    break;
                }
            }
        }
        lastTtsError = errors.length() == 0 ? "неизвестная ошибка" : errors.toString().trim();
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Pcm extractAudio(JsonObject resp, String model) {
        JsonArray cand = resp.getAsJsonArray("candidates");
        if (cand == null || cand.isEmpty()) return null;
        JsonObject content = cand.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) return null;
        for (JsonElement p : content.getAsJsonArray("parts")) {
            JsonObject po = p.getAsJsonObject();
            JsonObject inline = po.has("inlineData") ? po.getAsJsonObject("inlineData")
                    : (po.has("inline_data") ? po.getAsJsonObject("inline_data") : null);
            if (inline == null) continue;
            byte[] bytes = Base64.getDecoder().decode(inline.get("data").getAsString());
            int rate = 24000;
            String mime = inline.has("mimeType") ? inline.get("mimeType").getAsString() : "";
            int idx = mime.indexOf("rate=");
            if (idx >= 0) {
                try {
                    rate = Integer.parseInt(mime.substring(idx + 5).replaceAll("[^0-9].*$", ""));
                } catch (NumberFormatException ignored) {
                }
            }
            Pcm pcm = parseAudio(bytes, rate);
            RayaPrimeMod.LOGGER.info("[RayaPrime] TTS ({}): {} сэмплов, {} Гц", model, pcm.samples().length, pcm.rate());
            return pcm;
        }
        return null;
    }

    /** Изменить темп и высоту голоса как у магнитофона: factor>1 — выше и быстрее, <1 — ниже и медленнее. */
    public static short[] changeSpeed(short[] src, double factor) {
        if (src.length < 2 || Math.abs(factor - 1.0) < 0.01) return src;
        int outLen = (int) (src.length / factor);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * factor;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, src.length - 1);
            double frac = pos - i0;
            out[i] = (short) (src[Math.min(i0, src.length - 1)] * (1.0 - frac) + src[i1] * frac);
        }
        return out;
    }

    /** Разбор ответа TTS: либо WAV с заголовком RIFF, либо «сырой» PCM s16le. */
    static Pcm parseAudio(byte[] b, int defaultRate) {
        int rate = defaultRate;
        int offset = 0;
        if (b.length > 44 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            int pos = 12;
            while (pos + 8 <= b.length) {
                String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
                int size = (b[pos + 4] & 0xFF) | ((b[pos + 5] & 0xFF) << 8) | ((b[pos + 6] & 0xFF) << 16) | ((b[pos + 7] & 0xFF) << 24);
                if (id.equals("fmt ") && pos + 16 <= b.length) {
                    rate = (b[pos + 12] & 0xFF) | ((b[pos + 13] & 0xFF) << 8) | ((b[pos + 14] & 0xFF) << 16) | ((b[pos + 15] & 0xFF) << 24);
                } else if (id.equals("data")) {
                    offset = pos + 8;
                    break;
                }
                if (size < 0) break;
                pos += 8 + size + (size & 1);
            }
            if (offset == 0) offset = 44;
        }
        int n = (b.length - offset) / 2;
        short[] out = new short[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((b[offset + 2 * i] & 0xFF) | (b[offset + 2 * i + 1] << 8));
        }
        return new Pcm(out, rate <= 0 ? defaultRate : rate);
    }

    /** Линейная интерполяция в 48 кГц (формат Simple Voice Chat). */
    public static short[] resampleTo48k(short[] src, int rate) {
        int n = src.length;
        if (rate == 48000 || n < 2) return src;
        int outLen = (int) ((long) n * 48000 / rate);
        short[] out = new short[outLen];
        double step = (double) rate / 48000.0;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, n - 1);
            double frac = pos - i0;
            out[i] = (short) (src[i0] * (1.0 - frac) + src[i1] * frac);
        }
        return out;
    }
}
