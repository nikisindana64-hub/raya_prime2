package com.mamarala.rayaprime.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Сообщение игроку в чат на стороне клиента (используется только из клиентского кода голоса). */
final class RayaClientNotice {

    private RayaClientNotice() {}

    static void show(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(text));
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
