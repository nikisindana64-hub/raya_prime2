package com.mamarala.rayaprime.network;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Клиент → сервер: «я глажу Райю» (id сущности). Срабатывает от Shift+ПКМ пустой рукой, в том числе вместе с модом PatPat. */
public record RayaPatPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RayaPatPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RayaPrimeMod.MODID, "pat"));

    public static final StreamCodec<ByteBuf, RayaPatPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RayaPatPayload::entityId,
            RayaPatPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Серверная обработка: проверяем, что это её владелец и он рядом. */
    public static void handle(RayaPatPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Entity e = player.level().getEntity(payload.entityId());
            if (e instanceof RayaPrimeEntity raya && raya.isAlive() && raya.isOwnedBy(player)
                    && player.distanceToSqr(raya) < 36.0D) {
                raya.onPatted(player);
            }
        });
    }
}
