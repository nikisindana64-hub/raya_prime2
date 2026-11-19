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
 * Не использует классы net.minecraft.client — безопасно регистрировать и на сервере.
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
        PLAYER.execute(() -> play(pcm, rate)); // очередь: фразы не накладываются друг на друга
    }

    private static void play(byte[] pcm, int rate) {
        try {
            AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                line.write(pcm, 0, pcm.length);
                line.drain();
            }
        } catch (Throwable t) {
            RayaPrimeMod.LOGGER.warn("[RayaPrime] Не удалось проиграть голос Раи: {}", t.toString());
        }
    }
}
