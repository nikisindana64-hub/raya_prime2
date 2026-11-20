package com.mamarala.rayaprime.network;

import com.mamarala.rayaprime.RayaPrimeMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сервер → клиент: открыть меню настроек Раи (команда /raya settings). */
public record RayaOpenSettingsPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RayaOpenSettingsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "open_settings"));

    public static final StreamCodec<ByteBuf, RayaOpenSettingsPayload> STREAM_CODEC =
            StreamCodec.unit(new RayaOpenSettingsPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
