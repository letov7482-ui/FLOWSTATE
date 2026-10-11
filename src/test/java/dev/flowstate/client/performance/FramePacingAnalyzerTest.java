package dev.flowstate.client.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramePacingAnalyzerTest {

    @Test
    void detectsRegularSpikePatternWithoutClaimingCause() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        // каждые 5.1 с — кадр 100 мс, всего 4 скачка
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 0; i < 500; i++) {
                t[0] += 10_000_000L;
                monitor.beginFrame();
            }
            t[0] += 100_000_000L;
            monitor.beginFrame();
        }
        FrameTimeMonitor.Snapshot s = monitor.stats();
        assertEquals(4, s.spikesTotal);

        FramePacingAnalyzer analyzer = new FramePacingAnalyzer(monitor);
        FramePacingAnalyzer.Analysis a = analyzer.analyze(s);
        assertTrue(a.regularSpikePattern); // регулярность — измеренный факт
        assertTrue(a.note.contains("NOT confirmed")); // причина не утверждается
        assertEquals(3, a.spikesConsidered);
    }

    @Test
    void noSpikesGivesNoPattern() {
        long[] t = {0};
        FrameTimeMonitor monitor = new FrameTimeMonitor(() -> t[0], 0);
        for (int i = 0; i < 100; i++) {
            t[0] += 10_000_000L;
            monitor.beginFrame();
        }
        FramePacingAnalyzer analyzer = new FramePacingAnalyzer(monitor);
        FramePacingAnalyzer.Analysis a = analyzer.analyze(monitor.stats());
        assertFalse(a.regularSpikePattern);
        assertEquals(0, a.spikesConsidered);
        assertEquals("no recent spikes", a.note);
    }
}
