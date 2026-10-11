package dev.flowstate.client.scheduler;

import dev.flowstate.client.config.PerformanceMode;
import dev.flowstate.client.performance.FrameTimeMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveWorkSchedulerTest {

    private AdaptiveWorkScheduler.Task countingTask(String name, long intervalNanos, int[] counter) {
        return new AdaptiveWorkScheduler.Task() {
            @Override public String name() { return name; }
            @Override public boolean diagnostic() { return true; }
            @Override public long baseIntervalNanos() { return intervalNanos; }
            @Override public void run(long nowNanos) { counter[0]++; }
        };
    }

    @Test
    void ultraSmoothRunsDiagnosticsOnSchedule() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        AdaptiveWorkScheduler scheduler = new AdaptiveWorkScheduler(() -> t[0]);
        int[] runs = {0};
        assertTrue(scheduler.register(countingTask("diag", 1_000_000_000L, runs)));

        scheduler.runDue(monitor, PerformanceMode.ULTRA_SMOOTH, 16.7);
        assertEquals(1, runs[0]);

        t[0] += 500_000_000L; // 0.5 с — интервал не истёк
        scheduler.runDue(monitor, PerformanceMode.ULTRA_SMOOTH, 16.7);
        assertEquals(1, runs[0]);

        t[0] += 600_000_000L; // суммарно 1.1 с
        scheduler.runDue(monitor, PerformanceMode.ULTRA_SMOOTH, 16.7);
        assertEquals(2, runs[0]);
    }

    @Test
    void maxFpsSkipsDiagnosticsCompletely() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        AdaptiveWorkScheduler scheduler = new AdaptiveWorkScheduler(() -> t[0]);
        int[] runs = {0};
        scheduler.register(countingTask("diag", 1_000_000L, runs));
        for (int i = 0; i < 5; i++) {
            t[0] += 100_000_000L;
            scheduler.runDue(monitor, PerformanceMode.MAX_FPS, 16.7);
        }
        assertEquals(0, runs[0]);
    }

    @Test
    void smartBalanceThrottlesDiagnosticsUnderLoad() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        // 200 кадров по 30 мс => p95 ≈ 30 мс, ratio к цели 16.7 ≈ 1.8, фактор ≈ 2.6
        for (int i = 0; i < 200; i++) {
            t[0] += 30_000_000L;
            monitor.beginFrame();
        }
        AdaptiveWorkScheduler scheduler = new AdaptiveWorkScheduler(() -> t[0]);
        int[] runs = {0};
        scheduler.register(countingTask("diag", 1_000_000_000L, runs));

        scheduler.runDue(monitor, PerformanceMode.SMART_BALANCE, 16.7);
        assertEquals(1, runs[0]);

        t[0] += 1_000_000_000L; // 1 с < 2.6 с — не выполняется
        scheduler.runDue(monitor, PerformanceMode.SMART_BALANCE, 16.7);
        assertEquals(1, runs[0]);

        t[0] += 2_000_000_000L; // суммарно 3 с > 2.6 с
        scheduler.runDue(monitor, PerformanceMode.SMART_BALANCE, 16.7);
        assertEquals(2, runs[0]);
    }

    @Test
    void registerRejectsDuplicates() {
        long[] t = {0};
        AdaptiveWorkScheduler scheduler = new AdaptiveWorkScheduler(() -> t[0]);
        int[] runs = {0};
        assertTrue(scheduler.register(countingTask("a", 1, runs)));
        assertFalse(scheduler.register(countingTask("a", 1, runs)));
        assertEquals(1, scheduler.taskCount());
    }
}
