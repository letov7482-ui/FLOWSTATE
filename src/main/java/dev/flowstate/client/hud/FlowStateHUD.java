package dev.flowstate.client.hud;

import dev.flowstate.client.config.FlowStateConfig;
import dev.flowstate.client.config.PerformanceMode;
import dev.flowstate.client.performance.FramePacingAnalyzer;
import dev.flowstate.client.performance.FrameTimeMonitor;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD мода. Дешёвая отрисовка: строки пересобираются только при смене снапшота
 * статистики (по умолчанию раз в 250 мс), форматирование — не каждый кадр.
 * Все координаты — в scaled-пикселях, поэтому HUD корректен при любом GUI Scale.
 */
public final class FlowStateHUD {

    private final FlowStateConfig config;
    private final FrameTimeMonitor monitor;
    private final FramePacingAnalyzer analyzer;
    private final List<String> lines = new ArrayList<>(8);

    private FrameTimeMonitor.Snapshot lastRendered;
    private FramePacingAnalyzer.Analysis lastAnalysis;

    public FlowStateHUD(FlowStateConfig config, FrameTimeMonitor monitor, FramePacingAnalyzer analyzer) {
        this.config = config;
        this.monitor = monitor;
        this.analyzer = analyzer;
    }

    public void register() {
        // Примечание: в свежих версиях Fabric API callback помечен deprecated.
        // Замена (если в вашей версии он удалён) — в README, раздел "Plan B".
        HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
    }

    private void render(DrawContext context) {
        if (!config.showHud) return;
        FrameTimeMonitor.Snapshot snapshot = monitor.stats();
        if (snapshot.totalFrames == 0) return; // ещё ничего не измерено

        if (snapshot != lastRendered) {
            lastAnalysis = analyzer.analyze(snapshot);
            rebuildLines(snapshot, lastAnalysis);
            lastRendered = snapshot;
        }
        if (lines.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;

        int maxLineWidth = 0;
        for (String line : lines) {
            maxLineWidth = Math.max(maxLineWidth, font.getWidth(line));
        }

        int margin = config.hudMargin;
        int lineHeight = font.fontHeight + 1;
        context.fill(margin - 2, margin - 2,
                margin + maxLineWidth + 4,
                margin + lines.size() * lineHeight + 2,
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
            // MAX_FPS: минимальный объём текста, минимальная стоимость отрисовки.
            lines.add("FLOWSTATE [MAX_FPS]");
            lines.add(String.format("FPS %d | p95 %.2f ms | spikes %d", s.fps, s.p95Ms, s.spikesLast60s));
            return;
        }
        lines.add("FLOWSTATE [" + config.mode + "]");
        lines.add(String.format("FPS %d | spike %d/min", s.fps, s.spikesLast60s));
        lines.add(String.format("FT avg %.2f | med %.2f ms", s.avgMs, s.medianMs));
        lines.add(String.format("p95 %.2f | p99 %.2f ms", s.p95Ms, s.p99Ms));
        lines.add(String.format("jitter %.2f ms", a.jitterMs));
        if (config.experimentalFeatures) {
            lines.add(String.format("p95/med %.2f | intervals %d",
                    Double.isNaN(a.p95ToMedianRatio) ? 0.0 : a.p95ToMedianRatio, a.spikesConsidered));
            lines.add(a.note);
        }
    }

    /** Сброс при выходе из мира. */
    public void reset() {
        lastRendered = null;
        lastAnalysis = null;
        lines.clear();
    }
              }
