package com.mamarala.rayaprime;

import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.item.RayaStorage;
import com.mamarala.rayaprime.voice.RayaVoice;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Райя сама замечает происходящее: достижения, смена измерения (и переходит за игроком), победа над боссом,
 * смерть владельца. Всё локально, без запросов к Gemini.
 */
@EventBusSubscriber(modid = RayaPrimeMod.MODID)
public final class RayaReactions {

    private RayaReactions() {}

    private static RayaPrimeEntity raya(ServerPlayer p) {
        return RayaPrimeMod.findOwnedRaya(p);
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !RayaConfig.EVENT_REACTIONS.get()) return;
        var display = e.getAdvancement().value().display();
        if (display.isEmpty() || !display.get().shouldAnnounceChat()) return;
        RayaPrimeEntity r = raya(p);
        if (r == null) return;
        String title = display.get().getTitle().getString();
        String[] lines = {"Достижение «" + title + "»! Горжусь тобой. И собой, что рядом.",
                "О, «" + title + "»! Вероятность такого успеха была не так уж и мала. Шучу, я в восторге!",
                "«" + title + "» — записываю в архив достижений. Молодец, носитель!"};
        r.reactAny(lines, RayaVoice.Mood.HAPPY, RayaPrimeEntity.ANIM_FLIP);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.getServer() == null) return;
        RayaPrimeEntity here = raya(p);
        RayaPrimeEntity r = here;
        if (here == null && RayaConfig.FOLLOW_ACROSS_DIMENSIONS.get()) {
            ServerLevel from = p.getServer().getLevel(e.getFrom());
            if (from != null) {
                RayaPrimeEntity old = from.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(RayaPrimeEntity.class),
                        x -> x.isOwnedBy(p) && x.isAlive()).stream().findFirst().orElse(null);
                if (old != null) r = RayaStorage.transfer(p, old);
            }
        }
        if (r == null) return;
        String dim = e.getTo().location().getPath();
        String[] lines;
        if (dim.contains("nether")) {
            lines = new String[]{"Незер. Жарковато для вольфрамовой стали, но я справлюсь. Держись рядом!", "Здесь жутко пахнет серой. Ну, по моим датчикам."};
        } else if (dim.contains("end")) {
            lines = new String[]{"Энд. Пустота и эндермены — датчики на максимум, будь осторожен!", "Тут тихо, а от этого тревожнее."};
        } else if (dim.equals("overworld")) {
            lines = new String[]{"Вернулись в обычный мир. Дом, милый дом!", "Верхний мир! Хоть небо здесь настоящее."};
        } else {
            lines = new String[]{"Новое измерение: «" + dim + "». Сканирую, осторожно!", "Интересно, чем это место отличается от остальных. Идём изучать!"};
        }
        r.reactAny(lines, RayaVoice.Mood.NORMAL, RayaPrimeEntity.ANIM_THINK);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead.level().isClientSide) return;
        // Игрок погиб
        if (dead instanceof ServerPlayer p) {
            RayaPrimeEntity r = raya(p);
            if (r != null) {
                String[] lines = {"Нет-нет-нет! Носитель! Я рядом, возвращайся!", "Система не готова к такому. Ты вернёшься, я знаю."};
                r.reactAny(lines, RayaVoice.Mood.FEAR, RayaPrimeEntity.ANIM_BOW);
            }
            return;
        }
        // Игрок победил босса (много здоровья)
        Entity src = e.getSource().getEntity();
        if (src instanceof ServerPlayer p && dead.getMaxHealth() >= 100.0F) {
            RayaPrimeEntity r = raya(p);
            if (r != null) {
                String[] lines = {"Босс повержен! Вероятность победы я оценивала в 50%, но ты сделал это!", "Вот это бой! Записываю в летопись."};
                r.reactAny(lines, RayaVoice.Mood.HAPPY, RayaPrimeEntity.ANIM_DANCE);
            }
        }
    }
}
