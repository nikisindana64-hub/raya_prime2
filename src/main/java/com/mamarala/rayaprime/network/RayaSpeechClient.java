package com.mamarala.rayaprime.network;

import com.mamarala.rayaprime.RayaPrimeMod;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Клиентская часть голоса Раи: проигрывает полученный PCM через Java Sound.
 * Если устройство не принимает исходный формат — пересэмплирует в 44,1 кГц стерео (его принимает почти всё).
 * Если звук не получилось проиграть вообще — сообщает об этом в чат владельцу.
 */
public final class RayaSpeechClient {

    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RayaPrime-Speech");
        t.setDaemon(true);
        return t;
    });

    private RayaSpeechClient() {}

    public static void handle(RayaSpeechPayload payload, IPayloadContext context) {
        final byte[] pcm = payload.pcm();
        final int rate = payload.sampleRate();
        RayaPrimeMod.LOGGER.info("[RayaPrime] Получен голос Раи: {} байт, {} Гц", pcm.length, rate);
        PLAYER.execute(() -> play(pcm, rate)); // очередь: фразы не накладываются друг на друга
    }

    private static void play(byte[] pcm, int rate) {
        try {
            playRaw(pcm, new AudioFormat(rate, 16, 1, true, false));
            return;
        } catch (Throwable first) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Прямое воспроизведение {} Гц не удалось ({}), пробую 44,1 кГц стерео", rate, first.toString());
        }
        try {
            playRaw(toStereo44k(pcm, rate), new AudioFormat(44100, 16, 2, true, false));
        } catch (Throwable second) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось проиграть голос Раи: {}", second.toString());
            RayaClientNotice.show("§c[Райя-Прайм] Не могу проиграть звук на этом компьютере (Java Sound): " + second);
        }
    }

    private static void playRaw(byte[] data, AudioFormat format) throws Exception {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(data, 0, data.length);
            line.drain();
        }
    }

    /** 16-бит моно little-endian → 44,1 кГц стерео (линейная интерполяция). */
    private static byte[] toStereo44k(byte[] pcm, int rate) {
        int n = pcm.length / 2;
        if (n < 2) return new byte[0];
        int outLen = (int) ((long) n * 44100 / rate);
        byte[] out = new byte[outLen * 4];
        double step = (double) rate / 44100.0;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = Math.min((int) pos, n - 1);
            int i1 = Math.min(i0 + 1, n - 1);
            double frac = pos - (int) pos;
            short a = (short) ((pcm[2 * i0] & 0xFF) | (pcm[2 * i0 + 1] << 8));
            short b = (short) ((pcm[2 * i1] & 0xFF) | (pcm[2 * i1 + 1] << 8));
            short v = (short) (a * (1.0 - frac) + b * frac);
            byte lo = (byte) (v & 0xFF);
            byte hi = (byte) ((v >> 8) & 0xFF);
            out[4 * i] = lo;
            out[4 * i + 1] = hi;
            out[4 * i + 2] = lo;
            out[4 * i + 3] = hi;
        }
        return out;
    }
}
