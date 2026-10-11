package dev.flowstate.client;

import dev.flowstate.client.chunks.ChunkActivityWindow;
import dev.flowstate.client.chunks.ChunkPerformanceAdvisor;
import dev.flowstate.client.compatibility.SodiumCompat;
import dev.flowstate.client.config.FlowStateConfig;
import dev.flowstate.client.hud.FlowStateHUD;
import dev.flowstate.client.memory.MemoryPressureAdvisor;
import dev.flowstate.client.performance.ClientTickDurationTracker;
import dev.flowstate.client.performance.FramePacingAnalyzer;
import dev.flowstate.client.performance.FrameTimeMonitor;
import dev.flowstate.client.performance.FrameTimeMonitor.Snapshot;
import dev.flowstate.client.scheduler.AdaptiveWorkScheduler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint. Этап 2: измерения (кадры, тики, чанки, heap) + HUD. Ноль игровых оптимизаций.
 *
 * ПОРЯДОК регистрации тик-обработчиков важен (все вызываются в порядке регистрации):
 *   START: (1) tickTracker.onTickStart
 *   END:   (1) tickTracker.onTickEnd  — фиксирует длительность тика
 *          (2) chunkWindow.onTickEnd  — читает её и пишет в окно
 *          (3) scheduler + хоткей     — наша собственная работа, в измерения не входит
 * Всё на одном потоке (render thread). Файловый I/O — только при старте и остановке клиента.
 */
public class FlowStateClient implements ClientModInitializer {

    public static final String MOD_ID = "flowstate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final double TARGET_P95_MS = 1000.0 / 60.0;

    private static FlowStateConfig config;
    private static Path configPath;
    private static FrameTimeMonitor monitor;
    private static FramePacingAnalyzer analyzer;
    private static AdaptiveWorkScheduler scheduler;
    private static ClientTickDurationTracker tickTracker;
    private static ChunkActivityWindow chunkWindow;
    private static ChunkPerformanceAdvisor chunkAdvisor;
    private static MemoryPressureAdvisor memoryAdvisor;
    private static FlowStateHUD hud;
    private static KeyBinding toggleHudKey;

    private static long lastReportedSpikes;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(FlowStateConfig.FILE_NAME);
        FlowStateConfig.LoadResult result = FlowStateConfig.load(configPath);
        config = result.config();
        switch (result.state()) {
            case CREATED_DEFAULT -> LOGGER.info("[FlowState] config created with defaults");
            case CORRUPTED_RECOVERED -> LOGGER.warn("[FlowState] config corrupted ({}), defaults restored", result.message());
            case LOADED -> {
                if (result.message() != null) LOGGER.info("[FlowState] config values fixed: {}", result.message());
            }
        }

        monitor = new FrameTimeMonitor(System::nanoTime, config.statsRefreshMs * 1_000_000L);
        monitor.setSpikeThresholdMs(config.spikeThresholdMs);
        monitor.setSpikeRatio(config.spikeRatio);

        analyzer = new FramePacingAnalyzer(monitor);
        tickTracker = new ClientTickDurationTracker(System::nanoTime);
        chunkWindow = new ChunkActivityWindow(config.tickSlowThresholdMs);
        chunkAdvisor = new ChunkPerformanceAdvisor(chunkWindow);
        memoryAdvisor = new MemoryPressureAdvisor(System::nanoTime,
                MemoryPressureAdvisor::defaultUsedHeapBytes, 1_000_000_000L);
        scheduler = new AdaptiveWorkScheduler(System::nanoTime);
        registerSchedulerTasks();

        hud = new FlowStateHUD(config, monitor, analyzer, tickTracker, chunkAdvisor,
                memoryAdvisor, System::nanoTime);
        hud.register();

        // --- Тики: порядок см. в javadoc класса ---
        ClientTickEvents.START_CLIENT_TICK.register(client -> tickTracker.onTickStart());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickTracker.onTickEnd());
        chunkAdvisor.registerChunkEvents(); // только chunk load/unload, тики не трогает
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                chunkWindow.onTickEnd(System.nanoTime(), tickTracker.lastTickNanos()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            scheduler.runDue(monitor, config.mode, TARGET_P95_MS);
            if (toggleHudKey != null && toggleHudKey.wasPressed()) {
                config.showHud = !config.showHud; // сохранение файла — только при выходе из клиента
            }
        });

        registerHudToggleKey();

        // Кадры: событие начала мирового рендера — один раз за кадр, работает и при F1.
        WorldRenderEvents.START.register(context -> monitor.beginFrame());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetSession();
            SodiumCompat.logStatus(LOGGER);
            LOGGER.info("[FlowState] session start; mode={}", config.mode);
        });

        // Единственная файловая запись вне старта: при завершении клиента.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveConfigOnExit());

        LOGGER.info("[FlowState] initialized (stage 2: monitoring + diagnostics, no gameplay optimizations)");
    }

    private void registerSchedulerTasks() {
        boolean registered = scheduler.register(new AdaptiveWorkScheduler.Task() {
            @Override public String name() { return "spike-summary"; }
            @Override public boolean diagnostic() { return true; }
            @Override public long baseIntervalNanos() { return 10_000_000_000L; }
            @Override public void run(long nowNanos) {
                if (!config.logSpikes) return;
                Snapshot s = monitor.stats();
                if (s.spikesTotal > lastReportedSpikes) {
                    LOGGER.info("[FlowState] new frame spikes: +{} (last 60s: {}, p95={} ms)",
                            s.spikesTotal - lastReportedSpikes, s.spikesLast60s,
                            String.format("%.2f", s.p95Ms));
                    lastReportedSpikes = s.spikesTotal;
                }
            }
        });
        if (!registered) LOGGER.error("[FlowState] task 'spike-summary' rejected: {}", scheduler.lastRegisterError());

        registered = scheduler.register(new AdaptiveWorkScheduler.Task() {
            @Override public String name() { return "heap-samples"; }
            @Override public boolean diagnostic() { return true; }
            @Override public long baseIntervalNanos() { return 1_000_000_000L; }
            @Override public void run(long nowNanos) { memoryAdvisor.tick(nowNanos); }
        });
        if (!registered) LOGGER.error("[FlowState] task 'heap-samples' rejected: {}", scheduler.lastRegisterError());
    }

    private void registerHudToggleKey() {
        if (config.hudToggleKeyCode <= 0) return;
        try {
            toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.flowstate.toggle_hud",
                    InputUtil.Type.KEYSYM,
                    config.hudToggleKeyCode,
                    "category.flowstate"));
        } catch (RuntimeException e) {
            LOGGER.warn("[FlowState] HUD toggle key not registered: {}", e.getMessage());
        }
    }

    private void saveConfigOnExit() {
        String error = config.save(configPath);
        if (error != null) LOGGER.warn("[FlowState] config save on exit failed: {}", error);
    }

    private static void resetSession() {
        monitor.reset();
        scheduler.resetTimers();
        tickTracker.reset();
        chunkWindow.reset();
        memoryAdvisor.reset();
        hud.reset();
        lastReportedSpikes = 0;
    }
            }
