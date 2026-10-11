package dev.flowstate.client.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameTimeMonitorTest {

    @Test
    void percentilesSpikesAndMedian() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);

        // 99 кадров по 10 мс + 2 кадра по 100 мс => всего 101
        for (int i = 0; i < 99; i++) {
            t[0] += 10_000_000L;
            monitor.beginFrame();
        }
        t[0] += 100_000_000L;
        monitor.beginFrame();
        t[0] += 10_000_000L;
        monitor.beginFrame();
        t[0] += 100_000_000L;
        monitor.beginFrame();

        FrameTimeMonitor.Snapshot s = monitor.stats();
        assertEquals(101, s.totalFrames);
        assertEquals(10.0, s.medianMs, 0.001);
        assertEquals(10.0, s.p95Ms, 0.001);   // 2 скачка из 101 — ниже p95
        assertEquals(100.0, s.p99Ms, 0.001);
        assertEquals(100.0, s.maxMs, 0.001);
        assertEquals(2, s.spikesLast60s);
        assertEquals(2, s.spikesTotal);
    }

    @Test
    void fpsEstimate() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        for (int i = 0; i < 150; i++) {
            t[0] += 10_000_000L; // 10 мс => 100 FPS
            monitor.beginFrame();
        }
        assertEquals(100, monitor.stats().fps);
    }

    @Test
    void ignoresNegativeAndAbsurdGaps() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        t[0] += 10_000_000L;
        monitor.beginFrame();
        t[0] -= 5_000_000L;          // отрицательный dt
        monitor.beginFrame();
        t[0] += 60_000_000_000L;     // пауза > 10 с (сон системы)
        monitor.beginFrame();
        assertEquals(1, monitor.stats().totalFrames);
    }

    @Test
    void relativeSpikeDetectionUsesMedian() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        for (int i = 0; i < 100; i++) {
            t[0] += 10_000_000L;
            monitor.beginFrame();
        }
        monitor.stats(); // обновляет медиану (10 мс)
        t[0] += 30_000_000L; // 30 мс >= 10 * 2.5 => относительный скачок
        monitor.beginFrame();
        t[0] += 12_000_000L; // 12 мс < 25 мс => нет
        monitor.beginFrame();
        assertEquals(1, monitor.stats().spikesLast60s);
    }

    @Test
    void resetClearsAllState() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        for (int i = 0; i < 50; i++) {
            t[0] += 10_000_000L;
            monitor.beginFrame();
        }
        monitor.reset();
        FrameTimeMonitor.Snapshot s = monitor.stats();
        assertEquals(0, s.totalFrames);
        assertEquals(0, s.spikesTotal);
    }
}
