package com.mamarala.rayaprime.network;

import com.mamarala.rayaprime.RayaPrimeMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Голос Раи: сырой PCM (16 бит, моно, little-endian) от сервера к клиенту владельца. */
public record RayaSpeechPayload(byte[] pcm, int sampleRate) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RayaSpeechPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "speech"));

    public static final StreamCodec<ByteBuf, RayaSpeechPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(900_000), RayaSpeechPayload::pcm,
            ByteBufCodecs.VAR_INT, RayaSpeechPayload::sampleRate,
            RayaSpeechPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
