package dev.flowstate.client.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryPressureAdvisorTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void samplesAndTrend() {
        long[] t = {0};
        long[] heap = {0};
        MemoryPressureAdvisor advisor = new MemoryPressureAdvisor(() -> t[0], () -> heap[0], 1_000_000_000L);

        // 60 семплов: heap растёт со 100 до 159 MB, по 1 MB в секунду
        for (int i = 0; i < 60; i++) {
            t[0] = i * 1_000_000_000L;
            heap[0] = (100 + i) * MB;
            advisor.tick(t[0]);
        }
        assertEquals(60, advisor.samples());
        assertEquals(159 * MB, advisor.latestUsedBytes());
        assertEquals(100 * MB, advisor.minUsedBytes());
        assertEquals(159 * MB, advisor.maxUsedBytes());
        // за 59 с прирост 59 MB => 60 MB/мин
        assertEquals(60.0, advisor.trendBytesPerMinute() / MB, 1e-6);
    }

    @Test
    void intervalIsRespected() {
        long[] t = {0};
        MemoryPressureAdvisor advisor = new MemoryPressureAdvisor(() -> t[0], () -> 5 * MB, 1_000_000_000L);
        advisor.tick(0);
        advisor.tick(500_000_000L); // слишком рано — пропускается
        assertEquals(1, advisor.samples());
        advisor.tick(1_000_000_000L); // ровно интервал — берётся
        assertEquals(2, advisor.samples());
    }

    @Test
    void emptyStateIsZero() {
        MemoryPressureAdvisor advisor = new MemoryPressureAdvisor(() -> 0L, () -> 0L, 1_000_000_000L);
        assertEquals(0, advisor.samples());
        assertEquals(0.0, advisor.trendBytesPerMinute(), 1e-9);
        advisor.reset();
        assertEquals(0, advisor.samples());
    }
}
