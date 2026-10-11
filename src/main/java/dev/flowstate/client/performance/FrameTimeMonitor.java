package dev.flowstate.client.performance;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Кольцевой буфер времён кадра + агрегаты (avg/median/p95/p99/stddev/fps/spikes).
 *
 * Потокобезопасность: намеренно НЕ потокобезопасен. Все вызовы происходят на потоке
 * рендера (WorldRenderEvents / HUD / клиентский тик — всё это один поток).
 *
 * Аллокации: нулевые в установившемся режиме. Единственный источник аллокаций —
 * неизменяемый Snapshot, пересчитываемый не чаще statsRefreshNanos (по умолчанию 250 мс).
 */
public final class FrameTimeMonitor {

    public static final int CAPACITY = 1024;      // степень двойки
    public static final int SPIKE_CAPACITY = 256; // степень двойки

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long TEN_SECONDS_NANOS = 10_000_000_000L;
    private static final long SIXTY_SECONDS_NANOS = 60_000_000_000L;
    private static final double MS = 1_000_000.0;

    public static final class Snapshot {
        public final double avgMs;
        public final double medianMs;
        public final double p95Ms;
        public final double p99Ms;
        public final double stdDevMs;
        public final double maxMs;
        public final int fps;
        public final int spikesLast60s;
        public final long spikesTotal;
        public final long totalFrames;

        Snapshot(double avgMs, double medianMs, double p95Ms, double p99Ms, double stdDevMs,
                 double maxMs, int fps, int spikesLast60s, long spikesTotal, long totalFrames) {
            this.avgMs = avgMs;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.stdDevMs = stdDevMs;
            this.maxMs = maxMs;
            this.fps = fps;
            this.spikesLast60s = spikesLast60s;
            this.spikesTotal = spikesTotal;
            this.totalFrames = totalFrames;
        }
    }

    private final LongSupplier clock;
    private final long statsRefreshNanos;

    private final long[] ring = new long[CAPACITY];
    private final long[] scratch = new long[CAPACITY];   // рабочая копия для сортировки
    private final long[] spikes = new long[SPIKE_CAPACITY]; // метки времени скачков

    private int head;
    private int filled;
    private int spikeHead;
    private int spikeFilled;

    private long totalFrames;
    private long spikesTotal;
    private long lastFrameNanos = Long.MIN_VALUE;

    private long spikeThresholdNanos = 40_000_000L; // 40 мс
    private double spikeRatio = 2.5;
    private long lastMedianNanos; // для относительного порога; обновляется при расчёте статистики

    private Snapshot cached;
    private long lastStatsNanos = Long.MIN_VALUE;

    public FrameTimeMonitor(LongSupplier clock, long statsRefreshNanos) {
        this.clock = clock;
        this.statsRefreshNanos = Math.max(0L, statsRefreshNanos);
    }

    public void setSpikeThresholdMs(double ms) {
        if (ms >= 5.0 && ms <= 500.0) this.spikeThresholdNanos = (long) (ms * MS);
    }

    public void setSpikeRatio(double ratio) {
        if (ratio >= 1.5 && ratio <= 10.0) this.spikeRatio = ratio;
    }

    /** Вызывается один раз за кадр на потоке рендера. Без аллокаций. */
    public void beginFrame() {
        long now = clock.getAsLong();
        long last = lastFrameNanos;
        lastFrameNanos = now;
        if (last == Long.MIN_VALUE) return;
        long dt = now - last;
        // Пауза системы/сна/дебаггера — не измеряем, чтобы не портить статистику.
        if (dt <= 0 || dt >= TEN_SECONDS_NANOS) return;
        ring[head] = dt;
        head = (head + 1) & (CAPACITY - 1);
        if (filled < CAPACITY) filled++;
        totalFrames++;
        classifySpike(dt, now);
    }

    private void classifySpike(long dt, long now) {
        boolean isSpike = dt >= spikeThresholdNanos;
        // Относительный порог: кадр заметно хуже медианы. Пол 10 мс, чтобы
        // при 300+ FPS «скачки» в 1 мс не считались событием.
        if (!isSpike && lastMedianNanos > 0 && dt >= 10_000_000L) {
            isSpike = dt >= (long) (lastMedianNanos * spikeRatio);
        }
        if (!isSpike) return;
        spikes[spikeHead] = now;
        spikeHead = (spikeHead + 1) & (SPIKE_CAPACITY - 1);
        if (spikeFilled < SPIKE_CAPACITY) spikeFilled++;
        spikesTotal++;
    }

    /**
     * Агрегаты. Пересчитываются не чаще statsRefreshNanos, между пересчётами
     * возвращается закэшированный неизменяемый Snapshot.
     */
    public Snapshot stats() {
        long now = clock.getAsLong();
        Snapshot snapshot = cached;
        if (snapshot != null && lastStatsNanos != Long.MIN_VALUE
                && now - lastStatsNanos < statsRefreshNanos) {
            return snapshot;
        }
        snapshot = computeSnapshot(now);
        cached = snapshot;
        lastStatsNanos = now;
        return snapshot;
    }

    private Snapshot computeSnapshot(long now) {
        int n = filled;
        if (n == 0) {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, spikesTotal, 0);
        }
        int start = (head - n + CAPACITY) & (CAPACITY - 1); // самый старый
        long sum = 0;
        for (int i = 0; i < n; i++) {
            long v = ring[(start + i) & (CAPACITY - 1)];
            scratch[i] = v;
            sum += v;
        }
        Arrays.sort(scratch, 0, n);

        double avgMs = sum / (double) n / MS;
        double medianMs = percentileMs(n, 0.50);
        double p95Ms = percentileMs(n, 0.95);
        double p99Ms = percentileMs(n, 0.99);
        double maxMs = scratch[n - 1] / MS;

        double varianceSum = 0;
        for (int i = 0; i < n; i++) {
            double d = scratch[i] / MS - avgMs;
            varianceSum += d * d;
        }
        double stdDevMs = Math.sqrt(varianceSum / n);

        lastMedianNanos = (long) (medianMs * MS);

        return new Snapshot(avgMs, medianMs, p95Ms, p99Ms, stdDevMs, maxMs,
                estimateFps(), countSpikesSince(now - SIXTY_SECONDS_NANOS),
                spikesTotal, totalFrames);
    }

    private double percentileMs(int n, double q) {
        return scratch[(int) (q * (n - 1))] / MS;
    }

    /** FPS = число кадров, чьи интервалы суммарно покрывают >= 1 с. */
    private int estimateFps() {
        long acc = 0;
        int frames = 0;
        int idx = (head - 1 + CAPACITY) & (CAPACITY - 1);
        for (int i = 0; i < filled; i++) {
            acc += ring[idx];
            frames++;
            if (acc >= ONE_SECOND_NANOS) break;
            idx = (idx - 1 + CAPACITY) & (CAPACITY - 1);
        }
        return frames;
    }

    public int countSpikesSince(long cutoffNanos) {
        int count = 0;
        for (int i = 0; i < spikeFilled; i++) {
            int idx = (spikeHead - 1 - i + 2 * SPIKE_CAPACITY) & (SPIKE_CAPACITY - 1);
            if (spikes[idx] < cutoffNanos) break; // метки добавляются по возрастанию
            count++;
        }
        return count;
    }

    /**
     * Копирует метки времени скачков (новые первыми) за последние windowNanos.
     * out.length должен быть >= SPIKE_CAPACITY.
     */
    public int copyRecentSpikeTimes(long[] out, long now, long windowNanos) {
        long cutoff = now - windowNanos;
        int count = 0;
        for (int i = 0; i < spikeFilled; i++) {
            int idx = (spikeHead - 1 - i + 2 * SPIKE_CAPACITY) & (SPIKE_CAPACITY - 1);
            long t = spikes[idx];
            if (t < cutoff) break;
            out[count++] = t;
        }
        return count;
    }

    /** Полный сброс при выходе из мира. */
    public void reset() {
        head = 0;
        filled = 0;
        spikeHead = 0;
        spikeFilled = 0;
        totalFrames = 0;
        spikesTotal = 0;
        lastFrameNanos = Long.MIN_VALUE;
        lastMedianNanos = 0;
        cached = null;
        lastStatsNanos = Long.MIN_VALUE;
    }

    public long clockNanos() {
        return clock.getAsLong();
    }
}
