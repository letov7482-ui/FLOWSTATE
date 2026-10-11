package dev.flowstate.client;

import dev.flowstate.client.config.FlowStateConfig;
import dev.flowstate.client.hud.FlowStateHUD;
import dev.flowstate.client.performance.FramePacingAnalyzer;
import dev.flowstate.client.performance.FrameTimeMonitor;
import dev.flowstate.client.performance.FrameTimeMonitor.Snapshot;
import dev.flowstate.client.scheduler.AdaptiveWorkScheduler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint FLOWSTATE. Этап 1: только измерения, конфигурация и HUD.
 * Никаких игровых оптимизаций — сначала корректность и стабильность.
 * Весь код мода выполняется на клиентском потоке; фоновых потоков нет.
 */
public class FlowStateClient implements ClientModInitializer {

    public static final String MOD_ID = "flowstate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Цель по p95 для SMART_BALANCE (60 FPS). Настраиваемость — на следующих этапах. */
    private static final double TARGET_P95_MS = 1000.0 / 60.0;

    private static FlowStateConfig config;
    private static FrameTimeMonitor monitor;
    private static FramePacingAnalyzer analyzer;
    private static AdaptiveWorkScheduler scheduler;
    private static FlowStateHUD hud;

    private static long lastReportedSpikes;

    @Override
    public void onInitializeClient() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FlowStateConfig.FILE_NAME);
        FlowStateConfig.LoadResult result = FlowStateConfig.load(configPath);
        config = result.config();
        switch (result.state()) {
            case CREATED_DEFAULT -> LOGGER.info("[FlowState] config created with defaults");
            case CORRUPTED_RECOVERED -> LOGGER.warn("[FlowState] config corrupted ({}), defaults restored", result.message());
            case LOADED -> {
                if (result.message() != null) {
                    LOGGER.info("[FlowState] config values fixed: {}", result.message());
                }
            }
        }

        monitor = new FrameTimeMonitor(System::nanoTime, config.statsRefreshMs * 1_000_000L);
        monitor.setSpikeThresholdMs(config.spikeThresholdMs);
        monitor.setSpikeRatio(config.spikeRatio);

        analyzer = new FramePacingAnalyzer(monitor);
        scheduler = new AdaptiveWorkScheduler(System::nanoTime);
        registerSchedulerTasks();

        hud = new FlowStateHUD(config, monitor, analyzer);
        hud.register();

        // Измерение кадров: событие начала мирового рендера срабатывает один раз за кадр
        // и не зависит от видимости HUD (работает и при скрытом интерфейсе по F1).
        WorldRenderEvents.START.register(context -> monitor.beginFrame());

        // Планировщик задач мода: только клиентский тик, никаких потоков и блокировок.
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                scheduler.runDue(monitor, config.mode, TARGET_P95_MS));

        // Очистка состояния при входе/выходе из мира — защита от утечек ссылок.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetSession();
            LOGGER.info("[FlowState] session start; mode={}, sodium={}",
                    config.mode, FabricLoader.getInstance().isModLoaded("sodium"));
        });

        LOGGER.info("[FlowState] initialized (stage 1: monitoring + HUD, no gameplay optimizations)");
    }

    private void registerSchedulerTasks() {
        boolean registered = scheduler.register(new AdaptiveWorkScheduler.Task() {
            @Override public String name() { return "spike-summary"; }
            @Override public boolean diagnostic() { return true; }
            @Override public long baseIntervalNanos() { return 10_000_000_000L; } // раз в 10 с
            @Override public void run(long nowNanos) {
                if (!config.logSpikes) return;
                Snapshot s = monitor.stats();
                if (s.spikesTotal > lastReportedSpikes) {
                    LOGGER.info("[FlowState] new frame spikes: +{} (last 60s: {}, p95={} ms)",
                            s.spikesTotal - lastReportedSpikes,
                            s.spikesLast60s,
                            String.format("%.2f", s.p95Ms));
                    lastReportedSpikes = s.spikesTotal;
                }
            }
        });
        if (!registered) {
            LOGGER.error("[FlowState] failed to register scheduler task: {}", scheduler.lastRegisterError());
        }
    }

    private static void resetSession() {
        monitor.reset();
        scheduler.resetTimers();
        hud.reset();
        lastReportedSpikes = 0;
    }
                  }
