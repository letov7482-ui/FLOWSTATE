package dev.flowstate.client.memory;

import java.util.function.LongSupplier;

/**
 * Только измерения heap: периодические семплы used-памяти в кольцевой буфер.
 * Никакого System.gc(), никаких изменений JVM-флагов, никаких ссылок на миры
 * и сущности (храним только числа). Тренд — наблюдаемый факт, не диагноз.
 */
public final class MemoryPressureAdvisor {

    public static final int CAPACITY = 60; // одна минута при интервале 1 с

    private final LongSupplier clock;
    private final LongSupplier usedBytes;
    private final long sampleIntervalNanos;

    private final long[] sampleTime = new long[CAPACITY];
    private final long[] sampleValue = new long[CAPACITY];
    private int head;
    private int filled;
    private long lastSampleNanos = Long.MIN_VALUE;

    public MemoryPressureAdvisor(LongSupplier clock, LongSupplier usedBytes, long sampleIntervalNanos) {
        this.clock = clock;
        this.usedBytes = usedBytes;
        this.sampleIntervalNanos = Math.max(1L, sampleIntervalNanos);
    }

    /** Используемая часть кучи. Дёшево, без аллокаций. */
    public static long defaultUsedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Вызывается из AdaptiveWorkScheduler (в MAX_FPS автоматически не вызывается). */
    public void tick(long nowNanos) {
        if (clock == null) return;
        if (lastSampleNanos != Long.MIN_VALUE && nowNanos - lastSampleNanos < sampleIntervalNanos) return;
        lastSampleNanos = nowNanos;
        sampleTime[head] = nowNanos;
        sampleValue[head] = usedBytes.getAsLong();
        head = (head + 1) & (CAPACITY - 1);
        if (filled < CAPACITY) filled++;
    }

    private int index(int i) {
        return (head - 1 - i + 2 * CAPACITY) & (CAPACITY - 1);
    }

    public int samples() {
        return filled;
    }

    public long latestUsedBytes() {
        return filled == 0 ? 0 : sampleValue[index(0)];
    }

    public long minUsedBytes() {
        if (filled == 0) return 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < filled; i++) min = Math.min(min, sampleValue[index(i)]);
        return min;
    }

    public long maxUsedBytes() {
        long max = 0;
        for (int i = 0; i < filled; i++) max = Math.max(max, sampleValue[index(i)]);
        return max;
    }

    /** Средний прирост used heap, байт/мин; 0 при менее чем 2 сэмплах. */
    public double trendBytesPerMinute() {
        if (filled < 2) return 0.0;
        long dv = sampleValue[index(0)] - sampleValue[index(filled - 1)];
        long dt = sampleTime[index(0)] - sampleTime[index(filled - 1)];
        if (dt <= 0) return 0.0;
        return dv * 60_000_000_000.0 / dt;
    }

    public void reset() {
        head = 0;
        filled = 0;
        lastSampleNanos = Long.MIN_VALUE;
    }
}
