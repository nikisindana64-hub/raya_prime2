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

    /**
     * Озвучить реплику (вызывать из серверного потока). Синтез идёт в фоне, воспроизведение — снова на сервере.
     * @param raya     тело Раи (null — если она сейчас в виде Ядра в инвентаре)
     * @param listener игрок-владелец, которому адресован голос
     */
    public static void speak(MinecraftServer server, @Nullable RayaPrimeEntity raya, @Nullable ServerPlayer listener,
                             String text, String apiKey) {
        if (!RayaConfig.SPEECH_ENABLED.get() || text == null || text.isBlank()) {
            return;
        }
        final boolean crimson = raya != null && raya.isCrimsonMode();
        final boolean tts = RayaConfig.USE_GEMINI_TTS.get() && apiKey != null && !apiKey.isEmpty();
        final double volume = RayaConfig.SPEECH_VOLUME.get();
        final UUID listenerId = listener != null ? listener.getUUID() : null;

        CompletableFuture.runAsync(() -> {
            GeminiClient.Pcm pcm = null;
            if (tts) {
                pcm = GeminiClient.synthesizeSpeech(apiKey, text);
            }
            if (pcm == null || pcm.samples().length == 0) {
                pcm = new GeminiClient.Pcm(synthesizeBeeps(text, crimson), 48000);
            }
            final short[] samples = applyVolume(pcm.samples(), volume);
            final int rate = pcm.rate();
            server.execute(() -> {
                try {
                    if (raya != null && raya.isAlive() && !raya.isRemoved() && isVoiceChatActive()) {
                        RayaVoiceChatPlugin.play(raya, GeminiClient.resampleTo48k(samples, rate));
                        return;
                    }
                    ServerPlayer target = listenerId == null ? null : server.getPlayerList().getPlayer(listenerId);
                    if (target != null) {
                        PacketDistributor.sendToPlayer(target, new RayaSpeechPayload(toBytes(samples, rate), rate));
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
