package dev.flowstate.client.performance;

import java.util.function.LongSupplier;

/**
 * Длительность клиентского тика. START_CLIENT_TICK фиксирует начало,
 * END_CLIENT_TICK — завершает и записывает в кольцевой буфер.
 * Один поток (render thread), нулевые аллокации после создания.
 */
public final class ClientTickDurationTracker {

    public static final int CAPACITY = 256;
    private static final long ABSENT = Long.MIN_VALUE;
    private static final long MAX_PLAUSIBLE_NANOS = 10_000_000_000L; // пауза системы — не тик

    private final LongSupplier clock;
    private final long[] ring = new long[CAPACITY];

    private int head;
    private int filled;
    private long tickStartNanos = ABSENT;
    private long lastTickNanos;
    private long totalTicks;

    public ClientTickDurationTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public void onTickStart() {
        tickStartNanos = clock.getAsLong();
    }

    public void onTickEnd() {
        if (tickStartNanos == ABSENT) return; // END без START (не должен происходить) — игнорируем
        long duration = clock.getAsLong() - tickStartNanos;
        tickStartNanos = ABSENT;
        if (duration < 0 || duration >= MAX_PLAUSIBLE_NANOS) return;
        lastTickNanos = duration;
        totalTicks++;
        ring[head] = duration;
        head = (head + 1) & (CAPACITY - 1);
        if (filled < CAPACITY) filled++;
    }

    /** Длительность последнего завершённого тика, нс. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    public long totalTicks() {
        return totalTicks;
    }

    public double averageMs() {
        if (filled == 0) return 0.0;
        long sum = 0;
        for (int i = 0; i < filled; i++) sum += ring[i];
        return sum / (double) filled / 1_000_000.0;
    }

    public double maxMs() {
        long max = 0;
        for (int i = 0; i < filled; i++) max = Math.max(max, ring[i]);
        return max / 1_000_000.0;
    }

    public void reset() {
        head = 0;
        filled = 0;
        tickStartNanos = ABSENT;
        lastTickNanos = 0;
        totalTicks = 0;
    }
}
