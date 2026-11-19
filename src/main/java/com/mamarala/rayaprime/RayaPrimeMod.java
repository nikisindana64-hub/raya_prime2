package com.mamarala.rayaprime;

import com.mamarala.rayaprime.ai.GeminiBrainService;
import com.mamarala.rayaprime.client.RayaPrimeModel;
import com.mamarala.rayaprime.client.RayaPrimeRenderer;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaBuild;
import com.mamarala.rayaprime.entity.RayaPresence;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import com.mamarala.rayaprime.item.RayaCoreItem;
import com.mamarala.rayaprime.item.RayaStorage;
import com.mamarala.rayaprime.network.RayaSpeechClient;
import com.mamarala.rayaprime.network.RayaSpeechPayload;
import com.mamarala.rayaprime.scanner.ModKnowledgeScanner;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Mod(RayaPrimeMod.MODID)
public class RayaPrimeMod {
    public static final String MODID = "rayaprime";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** «Рая…», «Райя…», «Raya…», «Персик…» в начале сообщения (как отдельное слово). */
    private static final Pattern WAKE = Pattern.compile(
            "^\\s*(?:рая|райя|raya|персик)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RayaPrimeEntity>> RAYA_PRIME =
            ENTITIES.register("raya_prime", () -> EntityType.Builder.of(RayaPrimeEntity::new, MobCategory.CREATURE)
                    .sized(0.65F, 0.65F)
                    .fireImmune()
                    .clientTrackingRange(12)
                    .build("raya_prime"));

    public static final DeferredItem<Item> RAYA_CORE_SPAWNER =
            ITEMS.register("raya_core_spawner", () -> new DeferredSpawnEggItem(
                    RAYA_PRIME, 0xCED8E2, 0x00E4FF, new Item.Properties().stacksTo(1)));

    /** Ядро Раи — она в «свёрнутом» виде в инвентаре игрока. */
    public static final DeferredItem<Item> RAYA_CORE =
            ITEMS.register("raya_core", () -> new RayaCoreItem(new Item.Properties().stacksTo(1)));

    public RayaPrimeMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, RayaConfig.SPEC);

        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);

        modEventBus.addListener(this::registerAttributes);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.register(this);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(RAYA_PRIME.get(), RayaPrimeEntity.createAttributes().build());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(RayaSpeechPayload.TYPE, RayaSpeechPayload.STREAM_CODEC, RayaSpeechClient::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(RAYA_CORE_SPAWNER);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RAYA_CORE);
        }
    }

    // Реестры тегов уже загружены к этому моменту, поэтому индекс модов строим на старте сервера.
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            ModKnowledgeScanner.scanAllInstalledMods();
        } catch (Throwable t) {
            LOGGER.error("[RayaPrime] Не удалось проиндексировать моды", t);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ModKnowledgeScanner.clear();
        GeminiBrainService.clearHistory();
        RayaPresence.clear();
    }

    /** Пока Ядро Раи лежит в инвентаре, она «слышит» владельца (голосовой чат учитывает её присутствие). */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.tickCount % 20 == 0 && RayaStorage.hasCore(sp)) {
            RayaPresence.mark(sp.getUUID());
        }
    }

    private static boolean canManageKey(CommandSourceStack src) {
        if (src.hasPermission(2)) return true;
        return src.getEntity() instanceof ServerPlayer sp
                && src.getServer().isSingleplayerOwner(sp.getGameProfile());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("raya")
                .then(Commands.literal("summon").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ServerLevel level = player.serverLevel();
                    double x = player.getX() + 1.2, y = player.getY() + 1.4, z = player.getZ() + 1.2;

                    // Райя свёрнута в Ядро — просто выпускаем её
                    ItemStack core = RayaStorage.findCore(player);
                    if (!core.isEmpty()) {
                        if (RayaStorage.release(player, core)) {
                            player.sendSystemMessage(Component.translatable("rayaprime.msg.summoned"));
                        }
                        return 1;
                    }

                    // Если твоя Райя уже есть в этом мире — просто подзываем её, а не создаём вторую
                    List<? extends RayaPrimeEntity> existing = level.getEntities(
                            EntityTypeTest.forClass(RayaPrimeEntity.class), e -> e.isOwnedBy(player));
                    if (!existing.isEmpty()) {
                        RayaPrimeEntity raya = existing.get(0);
                        raya.clearTasks();
                        raya.setActiveMode("FOLLOW");
                        raya.teleportTo(x, y, z);
                        player.sendSystemMessage(Component.translatable("rayaprime.msg.summoned"));
                        return 1;
                    }
                    RayaPrimeEntity raya = RAYA_PRIME.get().create(level);
                    if (raya != null) {
                        raya.moveTo(x, y, z, player.getYRot(), 0);
                        raya.setOwnerUUID(player.getUUID());
                        raya.setCustomName(Component.literal("Райя-Прайм"));
                        level.addFreshEntity(raya);
                        player.sendSystemMessage(Component.translatable("rayaprime.msg.summoned"));
                    }
                    return 1;
                }))
                .then(Commands.literal("store").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    RayaPrimeEntity raya = findOwnedRaya(player);
                    if (raya == null) {
                        player.sendSystemMessage(Component.literal(RayaStorage.hasCore(player)
                                ? "§b[Райя-Прайм]§r Я уже в Ядре у тебя в инвентаре."
                                : "§c[Райя-Прайм]§r Меня нет рядом. Позови: /raya summon"));
                        return 0;
                    }
                    if (RayaStorage.store(player, raya)) {
                        player.sendSystemMessage(Component.literal(
                                "§b[Райя-Прайм]§r Свернулась в Ядро. Я всё слышу! Чтобы выпустить — ПКМ по Ядру, /raya release или просто скажи «Рая, вылезай»."));
                    }
                    return 1;
                }))
                .then(Commands.literal("release").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ItemStack core = RayaStorage.findCore(player);
                    if (core.isEmpty()) {
                        player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Ядра в инвентаре нет."));
                        return 0;
                    }
                    if (RayaStorage.release(player, core)) {
                        player.sendSystemMessage(Component.translatable("rayaprime.msg.summoned"));
                    }
                    return 1;
                }))
                .then(Commands.literal("build")
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .executes(ctx -> buildCommand(ctx.getSource(), StringArgumentType.getString(ctx, "shape"), ""))
                                .then(Commands.argument("block", StringArgumentType.greedyString())
                                        .executes(ctx -> buildCommand(ctx.getSource(), StringArgumentType.getString(ctx, "shape"),
                                                StringArgumentType.getString(ctx, "block"))))))
                .then(Commands.literal("cancel").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    RayaPrimeEntity raya = findOwnedRaya(player);
                    if (raya != null && raya.hasBuildPlan()) {
                        raya.setBuildPlan(null);
                        player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r Стройку отменила."));
                    }
                    return 1;
                }))
                .then(Commands.literal("guard").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    RayaPrimeEntity raya = findOwnedRaya(player);
                    if (raya == null) {
                        player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Меня нет рядом. Позови: /raya summon"));
                        return 0;
                    }
                    boolean on = !raya.isGuarding();
                    raya.clearTasks();
                    raya.setActiveMode(on ? "GUARD" : "FOLLOW");
                    player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r " + (on
                            ? "Режим охраны включён: буду бить врагов рядом с тобой." : "Охрана выключена, просто лечу рядом.")));
                    return 1;
                }))
                .then(Commands.literal("scan").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    player.sendSystemMessage(Component.literal("§b[Райя-Прайм | Скан]§r\n"
                            + ModKnowledgeScanner.scanReport(player, findOwnedRaya(player))));
                    return 1;
                }))
                .then(Commands.literal("help").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§b[Райя-Прайм | Команды]§r\n"
                            + "/raya summon — позвать; /raya store — свернуться в Ядро; /raya release — выпустить\n"
                            + "/raya build <house|tower|wall|fence|platform|box|pillar|stairs> [блок] — построить; /raya cancel — отмена\n"
                            + "/raya guard — вкл/выкл охрану; /raya scan — что вокруг; /raya status; /raya mods\n"
                            + "/raya ask <текст> или в чате «Рая, ...» — поговорить (строить, ломать, команды, сюжет)"), false);
                    return 1;
                }))
                .then(Commands.literal("apikey")
                        .requires(RayaPrimeMod::canManageKey)
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    RayaConfig.RUNTIME_API_KEY_OVERRIDE = StringArgumentType.getString(ctx, "key").trim();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§b[Райя-Прайм]§r Ключ Gemini API обновлён до перезапуска сервера. Чтобы сохранить навсегда — впиши его в config/rayaprime-common.toml"), false);
                                    return 1;
                                })))
                .then(Commands.literal("ask")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    RayaPrimeEntity raya = findOwnedRaya(player);
                                    if (raya == null && !RayaStorage.hasCore(player)) {
                                        player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Я тебя не вижу рядом. Позови меня: /raya summon"));
                                        return 0;
                                    }
                                    GeminiBrainService.processTextRequestAsync(player, raya, StringArgumentType.getString(ctx, "prompt"));
                                    return 1;
                                })))
                .then(Commands.literal("status").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    RayaPrimeEntity raya = findOwnedRaya(player);
                    String keyState = RayaConfig.getEffectiveApiKey().isEmpty()
                            ? "§cНЕ ЗАДАН§r" : "§aзадан§r (" + RayaConfig.getEffectiveApiKey().length() + " символов)";
                    player.sendSystemMessage(Component.literal("§b[Райя-Прайм | Диагностика]§r\n"
                            + "Ключ Gemini: " + keyState + "\n"
                            + "Модель: " + RayaConfig.GEMINI_MODEL.get() + "\n"
                            + "Твоя Райя: " + (raya != null ? "§aв мире§r, режим " + raya.getActiveMode()
                                    : RayaStorage.hasCore(player) ? "§aв Ядре (инвентарь)§r" : "§cнет§r") + "\n"
                            + "Simple Voice Chat загружен: " + (com.mamarala.rayaprime.voice.RayaVoice.isVoiceChatLoaded() ? "да" : "нет")
                            + ", запущен: " + (com.mamarala.rayaprime.voice.RayaVoice.isVoiceChatActive() ? "да" : "нет (голос Раи идёт напрямую в игру)") + "\n"
                            + "Голосовое управление включено: " + (RayaConfig.VOICE_ACTIVATION_ENABLED.get() ? "да" : "нет")));
                    return 1;
                }))
                .then(Commands.literal("mods").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    player.sendSystemMessage(Component.literal("§b[Райя-Прайм | База П.Е.Р.С.И.К.]§r Загруженные моды: "
                            + ModKnowledgeScanner.getCompactModListSummary()));
                    return 1;
                }))
        );
    }

    private static int buildCommand(CommandSourceStack src, String shape, String block) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        RayaPrimeEntity raya = findOwnedRaya(player);
        if (raya == null) {
            player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Меня нет в мире (или я в Ядре). Позови: /raya summon"));
            return 0;
        }
        RayaBuild.Plan plan = RayaBuild.generate(shape, player.blockPosition().relative(player.getDirection(), 2),
                player.getDirection(), 0, 0, 0, block.isBlank() ? "planks" : block, "");
        if (plan == null) {
            player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r Не знаю такую фигуру. Есть: house, tower, wall, fence, platform, box, pillar, stairs."));
            return 0;
        }
        GeminiBrainService.startBuild(raya, plan);
        return 1;
    }

    /** «Рая, …» / «Райя, …» в чате → запрос к Gemini. Реагирует только на Райю самого автора сообщения. */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String raw = event.getRawText().trim();
        if (!WAKE.matcher(raw).find()) return;
        ServerPlayer player = event.getPlayer();
        RayaPrimeEntity raya = findOwnedRaya(player);
        boolean inCore = raya == null && RayaStorage.hasCore(player);
        LOGGER.info("[RayaPrime] Обращение в чате от {}: {} (Райя в мире: {}, в Ядре: {})", player.getName().getString(), raw, raya != null, inCore);
        if (raya != null || inCore) {
            GeminiBrainService.processTextRequestAsync(player, raya, raw);
        } else {
            player.sendSystemMessage(Component.literal(
                    "§c[Райя-Прайм]§r Я не вижу твоей Райи (и Ядра в инвентаре нет). Позови её: /raya summon"));
        }
    }

    /** Ближайшая Райя этого игрока среди всех загруженных сущностей его измерения (она может быть далеко). */
    public static RayaPrimeEntity findOwnedRaya(ServerPlayer player) {
        return player.serverLevel().getEntities(EntityTypeTest.forClass(RayaPrimeEntity.class), e -> e.isOwnedBy(player) && e.isAlive())
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(RayaPrimeModel.LAYER_LOCATION, RayaPrimeModel::createBodyLayer);
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(RAYA_PRIME.get(), RayaPrimeRenderer::new);
        }
    }
}
