package com.mamarala.rayaprime.client;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.network.RayaPatPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Поглаживание Раи на стороне клиента: Shift + удержание ПКМ пустой рукой, пока прицел на ней (ровно так же гладит мод PatPat).
 * Не зависит от внутренностей PatPat — работает и с ним, и без него.
 */
@EventBusSubscriber(modid = RayaPrimeMod.MODID, value = Dist.CLIENT)
public final class RayaPatClient {

    private static int cooldown = 0;

    private RayaPatClient() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (cooldown > 0) cooldown--;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null || cooldown > 0) return;
        if (!p.isShiftKeyDown() || !mc.options.keyUse.isDown() || !p.getMainHandItem().isEmpty()) return;
        if (mc.crosshairPickEntity instanceof RayaPrimeEntity raya && p.distanceToSqr(raya) <= 25.0D) {
            cooldown = 20;
            PacketDistributor.sendToServer(new RayaPatPayload(raya.getId()));
        }
    }
}
