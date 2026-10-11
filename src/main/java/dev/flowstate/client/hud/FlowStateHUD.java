package dev.flowstate.client.hud;

import dev.flowstate.client.chunks.ChunkPerformanceAdvisor;
import dev.flowstate.client.config.FlowStateConfig;
import dev.flowstate.client.config.PerformanceMode;
import dev.flowstate.client.memory.MemoryPressureAdvisor;
import dev.flowstate.client.performance.ClientTickDurationTracker;
import dev.flowstate.client.performance.FramePacingAnalyzer;
import dev.flowstate.client.performance.FrameTimeMonitor;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * HUD + измерение кадров. Callback срабатывает один раз за кадр, пока мир отрисован:
 * сначала beginFrame() (всегда, даже когда наш HUD скрыт), затем отрисовка по настройке.
 * Честное ограничение: если vanilla прячет HUD (F1) так, что callback не вызывается,
 * кадры в этот период не измеряются - статистика не портится благодаря отсечке пауз.
 */
public final class FlowStateHUD {

    private static final long SIXTY_SECONDS_NANOS = 60_000_000_000L;
    private static final long MB = 1024L * 1024L;

    private final FlowStateConfig config;
    private final FrameTimeMonitor monitor;
    private final FramePacingAnalyzer analyzer;
    private final ClientTickDurationTracker tickTracker;
    private final ChunkPerformanceAdvisor chunkAdvisor;
    private final MemoryPressureAdvisor memoryAdvisor;
    private final LongSupplier clock;
    private final List<String> lines = new ArrayList<>(8);

    private FrameTimeMonitor.Snapshot lastRendered;
    private FramePacingAnalyzer.Analysis lastAnalysis;

    public FlowStateHUD(FlowStateConfig config, FrameTimeMonitor monitor, FramePacingAnalyzer analyzer,
                        ClientTickDurationTracker tickTracker, ChunkPerformanceAdvisor chunkAdvisor,
                        MemoryPressureAdvisor memoryAdvisor, LongSupplier clock) {
        this.config = config;
        this.monitor = monitor;
        this.analyzer = analyzer;
        this.tickTracker = tickTracker;
        this.chunkAdvisor = chunkAdvisor;
        this.memoryAdvisor = memoryAdvisor;
        this.clock = clock;
    }

    @SuppressWarnings("deprecation")
    public void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            monitor.beginFrame();
            if (config.showHud) render(context);
        });
    }

    private void render(DrawContext context) {
        FrameTimeMonitor.Snapshot snapshot = monitor.stats();
        if (snapshot.totalFrames == 0) return;

        if (snapshot != lastRendered) {
            lastAnalysis = analyzer.analyze(snapshot);
            rebuildLines(snapshot, lastAnalysis);
            lastRendered = snapshot;
        }
        if (lines.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;

        int maxLineWidth = 0;
        for (String line : lines) maxLineWidth = Math.max(maxLineWidth, font.getWidth(line));

        int margin = config.hudMargin;
        int lineHeight = font.fontHeight + 1;
        context.fill(margin - 2, margin - 2,
                margin + maxLineWidth + 4, margin + lines.size() * lineHeight + 2,
                0x90101010);

        int y = margin;
        for (String line : lines) {
            context.drawText(font, line, margin, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }
    }

    private void rebuildLines(FrameTimeMonitor.Snapshot s, FramePacingAnalyzer.Analysis a) {
        lines.clear();
        if (config.mode == PerformanceMode.MAX_FPS) {
            lines.add("FLOWSTATE [MAX_FPS]");
            lines.add(String.format("FPS %d | p95 %.2f ms | spikes %d", s.fps, s.p95Ms, s.spikesLast60s));
            return;
        }
        lines.add("FLOWSTATE [" + config.mode + "]");
        lines.add(String.format("FPS %d | spike %d/min", s.fps, s.spikesLast60s));
        lines.add(String.format("FT avg %.2f | med %.2f ms", s.avgMs, s.medianMs));
        lines.add(String.format("p95 %.2f | p99 %.2f ms", s.p95Ms, s.p99Ms));
        if (config.experimentalFeatures) {
            lines.add(String.format("jitter %.2f ms", a.jitterMs));
            lines.add(String.format("tick avg %.2f | max %.2f ms",
                    tickTracker.averageMs(), tickTracker.maxMs()));
            lines.add(chunkAdvisor.summary(clock.getAsLong(), SIXTY_SECONDS_NANOS));
            lines.add(String.format("heap %d/%d MB, trend %+.1f MB/min",
                    memoryAdvisor.latestUsedBytes() / MB,
                    Runtime.getRuntime().maxMemory() / MB,
                    memoryAdvisor.trendBytesPerMinute() / MB));
            lines.add(a.note);
        }
    }

    public void reset() {
        lastRendered = null;
        lastAnalysis = null;
        lines.clear();
    }
}
