package com.mamarala.rayaprime.voice;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.ai.GeminiBrainService;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPresence;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.item.RayaStorage;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Интеграция с Simple Voice Chat (voicechat-neoforge-1.21.1-2.6.x).
 * 1. Ловит MicrophonePacketEvent владельца Раи, декодирует Opus → PCM (в потоке голосового чата, мир НЕ трогает).
 * 2. По паузе тишины склеивает фразу в WAV 16 кГц и отправляет в Gemini (мультимодальный вход).
 * 3. Проигрывает речь Раи как 3D-звук от её тела (EntityAudioChannel).
 * Класс грузится только модом voicechat (аннотация @ForgeVoicechatPlugin), напрямую его вызывает лишь {@link RayaVoice}.
 */
@ForgeVoicechatPlugin
public class RayaVoiceChatPlugin implements VoicechatPlugin {

    private static final int MAX_PHRASE_BYTES = 16000 * 2 * 12; // ~12 с при 16 кГц
    private static final int MIN_PHRASE_BYTES = 16000 * 2 * 4 / 10; // ~0.4 с

    private static volatile VoicechatServerApi serverApi;
    private final Map<UUID, PlayerVoiceBuffer> buffers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Override
    public String getPluginId() {
        return RayaPrimeMod.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        RayaPrimeMod.LOGGER.info("[RayaPrime] Интеграция с Simple Voice Chat активна.");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RayaPrime-VAD");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                flushFinishedPhrases();
            } catch (Throwable t) {
                RayaPrimeMod.LOGGER.debug("[RayaPrime] VAD error", t);
            }
        }, 300, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> serverApi = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        PlayerVoiceBuffer buf = buffers.remove(event.getPlayerUuid());
        if (buf != null) {
            buf.close();
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        try {
            if (!RayaConfig.VOICE_ACTIVATION_ENABLED.get()) return;
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null) return;
            UUID uuid = sender.getPlayer().getUuid();
            // Только владелец, у которого сейчас есть Райя (реестр обновляется из серверного потока)
            if (!RayaPresence.hasRaya(uuid)) return;

            byte[] opus = event.getPacket().getOpusEncodedData();
            if (opus == null || opus.length == 0) return;

            PlayerVoiceBuffer buf = buffers.computeIfAbsent(uuid,
                    id -> new PlayerVoiceBuffer(event.getVoicechat().createDecoder()));
            buf.onPacket(opus, RayaConfig.VOICE_TRIGGER_RMS.get(), MAX_PHRASE_BYTES);
        } catch (Throwable t) {
            RayaPrimeMod.LOGGER.debug("[RayaPrime] Ошибка обработки пакета микрофона", t);
        }
    }

    private void flushFinishedPhrases() {
        long now = System.currentTimeMillis();
        long threshold = RayaConfig.SILENCE_THRESHOLD_MS.get();
        for (Map.Entry<UUID, PlayerVoiceBuffer> entry : buffers.entrySet()) {
            byte[] wav = entry.getValue().flushIfSilent(now, threshold, RayaConfig.VOICE_MIN_MS.get(), RayaConfig.VOICE_TRIGGER_RMS.get());
            if (wav == null) continue;
            UUID uuid = entry.getKey();
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) continue;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) return;
                RayaPrimeEntity raya = RayaPrimeMod.findOwnedRaya(player);
                if (raya != null || RayaStorage.hasCore(player)) {
                    GeminiBrainService.processVoiceAudioAsync(player, raya, wav);
                }
            });
        }
    }

    /** Серверный API voicechat получен — голосовой чат реально запущен. */
    public static boolean isActive() {
        return serverApi != null;
    }

    /** Игрок реально подключён к голосовому чату (иначе 3D-звук он не услышит — используем прямую отправку). */
    public static boolean isConnected(UUID playerUuid) {
        VoicechatServerApi api = serverApi;
        if (api == null || playerUuid == null) return false;
        try {
            VoicechatConnection c = api.getConnectionOf(playerUuid);
            return c != null && c.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Проиграть PCM (48 кГц, 16 бит, моно) как 3D-звук от тела Раи. Вызывать из серверного потока. */
    public static void play(RayaPrimeEntity raya, short[] pcm48k) {
        VoicechatServerApi api = serverApi;
        if (api == null || pcm48k == null || pcm48k.length == 0) return;
        EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(raya));
        if (channel == null) return;
        channel.setDistance(32.0F);
        OpusEncoder encoder = api.createEncoder();
        AudioPlayer player = api.createAudioPlayer(channel, encoder, pcm48k);
        player.setOnStopped(encoder::close);
        player.startPlaying();
    }

    // ------------------------------------------------------------------ буфер фразы одного игрока

    private static class PlayerVoiceBuffer {
        private final OpusDecoder decoder;
        private final ByteArrayOutputStream pcm16k = new ByteArrayOutputStream();
        private final java.util.ArrayDeque<byte[]> preroll = new java.util.ArrayDeque<>();
        private long lastVoiceMs = 0L;
        private boolean speaking = false;
        private long voicedSamples = 0L;
        private double peakRms = 0.0;
        private int voicedBytePos = 0;

        PlayerVoiceBuffer(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        /** 48 кГц → 16 кГц усреднением троек (в 3 раза меньше трафика к Gemini). */
        private static byte[] downsample(short[] pcm) {
            ByteBuffer bb = ByteBuffer.allocate((pcm.length / 3) * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i + 2 < pcm.length; i += 3) {
                bb.putShort((short) ((pcm[i] + pcm[i + 1] + pcm[i + 2]) / 3));
            }
            return bb.array();
        }

        synchronized void onPacket(byte[] opus, double rmsThreshold, int maxBytes) {
            short[] pcm = decoder.decode(opus);
            if (pcm == null || pcm.length == 0) return;
            double r = rms(pcm);
            byte[] down = downsample(pcm);
            // начало речи — по полному порогу, продолжение — по сниженному (гистерезис)
            boolean voiced = r >= (speaking ? rmsThreshold * 0.55 : rmsThreshold);
            if (!speaking) {
                if (!voiced) {
                    preroll.addLast(down); // небольшой запас перед началом, чтобы не терять первый слог
                    while (preroll.size() > 8) preroll.removeFirst();
                    return;
                }
                speaking = true;
                voicedSamples = 0L;
                peakRms = 0.0;
                for (byte[] b : preroll) pcm16k.writeBytes(b);
                preroll.clear();
            }
            if (pcm16k.size() < maxBytes) pcm16k.writeBytes(down);
            if (voiced) {
                lastVoiceMs = System.currentTimeMillis();
                voicedSamples += pcm.length;
                peakRms = Math.max(peakRms, r);
                voicedBytePos = pcm16k.size();
            }
        }

        /**
         * @return WAV-байты, если фраза закончилась (пауза ≥ threshold после ПОСЛЕДНЕГО громкого кадра), иначе null.
         * Шум, щелчки и слишком короткие/тихие фразы отбрасываются, хвост тишины обрезается.
         */
        synchronized byte[] flushIfSilent(long now, long thresholdMs, int minVoiceMs, double trigger) {
            if (!speaking || now - lastVoiceMs < thresholdMs) return null;
            speaking = false;
            byte[] raw = pcm16k.toByteArray();
            int endPos = Math.min(raw.length, voicedBytePos + 16000 * 2 * 3 / 10); // последний громкий кадр + 0,3 с
            long voicedMs = voicedSamples / 48L;
            double peak = peakRms;
            pcm16k.reset();
            decoder.resetState();
            voicedBytePos = 0;
            if (voicedMs < minVoiceMs || peak < trigger * 1.5 || endPos < 16000 * 2 * 3 / 10) {
                RayaPrimeMod.LOGGER.debug("[RayaPrime] Шум отброшен: речи {} мс, пик {}", voicedMs, (int) peak);
                return null;
            }
            return wav(java.util.Arrays.copyOf(raw, endPos), 16000);
        }

        synchronized void close() {
            try {
                decoder.close();
            } catch (Throwable ignored) {
            }
        }

        private static double rms(short[] s) {
            long sum = 0;
            for (short v : s) sum += (long) v * v;
            return Math.sqrt((double) sum / s.length);
        }

        private static byte[] wav(byte[] pcm, int sampleRate) {
            ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
            bb.put(new byte[]{'R', 'I', 'F', 'F'});
            bb.putInt(36 + pcm.length);
            bb.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            bb.putInt(16);
            bb.putShort((short) 1);          // PCM
            bb.putShort((short) 1);          // моно
            bb.putInt(sampleRate);
            bb.putInt(sampleRate * 2);       // byte rate
            bb.putShort((short) 2);          // block align
            bb.putShort((short) 16);         // бит на сэмпл
            bb.put(new byte[]{'d', 'a', 't', 'a'});
            bb.putInt(pcm.length);
            bb.put(pcm);
            return bb.array();
        }
    }
}
