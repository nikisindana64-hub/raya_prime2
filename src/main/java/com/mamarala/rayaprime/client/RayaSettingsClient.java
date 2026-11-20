package com.mamarala.rayaprime.client;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.mamarala.rayaprime.network.RayaOpenSettingsPayload;
import org.lwjgl.glfw.GLFW;

/**
 * Меню настроек Раи: экран строится автоматически из конфига мода (NeoForge ConfigurationScreen).
 * Открывается: Моды → Райя Прайм → «Настройки», клавишей (по умолчанию O) или командой /raya settings.
 */
public final class RayaSettingsClient {

    private RayaSettingsClient() {}

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.rayaprime.settings",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.rayaprime");

    /** Вызывается из конструктора мода только на клиенте: кнопка «Настройки» в списке модов. */
    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        ModContainer container = ModList.get().getModContainerById(RayaPrimeMod.MODID).orElse(null);
        if (container == null) return;
        Screen parent = mc.screen;
        mc.setScreen(new ConfigurationScreen(container, parent));
    }

    /** Команда /raya settings: сервер просит клиент открыть меню. */
    public static void handleOpen(RayaOpenSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(RayaSettingsClient::open);
    }

    @EventBusSubscriber(modid = RayaPrimeMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_KEY);
        }
    }

    @EventBusSubscriber(modid = RayaPrimeMod.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            while (OPEN_KEY.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.screen == null) open();
            }
        }
    }
}
