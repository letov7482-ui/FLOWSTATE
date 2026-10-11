package dev.flowstate.client.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientTickDurationTrackerTest {

    @Test
    void measuresTickDuration() {
        long[] t = {0};
        ClientTickDurationTracker tracker = new ClientTickDurationTracker(() -> t[0]);
        tracker.onTickStart();
        t[0] += 15_000_000L;
        tracker.onTickEnd();
        assertEquals(15_000_000L, tracker.lastTickNanos());
        assertEquals(15.0, tracker.averageMs(), 1e-9);
        assertEquals(15.0, tracker.maxMs(), 1e-9);
    }

    @Test
    void ignoresEndWithoutStartAndAbsurdDurations() {
        long[] t = {0};
        ClientTickDurationTracker tracker = new ClientTickDurationTracker(() -> t[0]);
        tracker.onTickEnd(); // без START
        assertEquals(0, tracker.totalTicks());
        tracker.onTickStart();
        t[0] += 60_000_000_000L; // пауза системы
        tracker.onTickEnd();
        assertEquals(0, tracker.totalTicks());
    }

    @Test
    void resetClearsState() {
        long[] t = {0};
        ClientTickDurationTracker tracker = new ClientTickDurationTracker(() -> t[0]);
        tracker.onTickStart();
        t[0] += 10_000_000L;
        tracker.onTickEnd();
        tracker.reset();
        assertEquals(0, tracker.totalTicks());
        assertEquals(0, tracker.lastTickNanos());
    }
}
