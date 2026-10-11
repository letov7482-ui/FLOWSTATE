package dev.flowstate.client.performance;

/**
 * Анализ ритмичности кадров. Принцип модуля: отделять измеренные факты от предположений.
 * Всё, что сформулировано как "cause NOT confirmed", — гипотеза, а не установленные данные.
 * Анализатор не утверждает, что знает причину фриза, пока причина не измерена.
 */
public final class FramePacingAnalyzer {

    public static final long PATTERN_WINDOW_NANOS = 120_000_000_000L; // 2 минуты

    public static final class Analysis {
        /** СКО времён кадра — измеренный факт. */
        public final double jitterMs;
        /** p95/median — измеренный факт (NaN, если медиана нулевая). */
        public final double p95ToMedianRatio;
        /** Измеренный факт. */
        public final int spikesLast60s;
        /** Сколько интервалов между скачками попало в анализ. */
        public final int spikesConsidered;
        /** Средний интервал между скачками, мс (NaN — недостаточно данных). */
        public final double spikeIntervalMeanMs;
        /** Коэффициент вариации интервалов (NaN — недостаточно данных). */
        public final double spikeIntervalCv;
        /** ИЗМЕРЕННЫЙ факт: интервалы между скачками регулярны. О ПРИЧИНЕ ничего не утверждается. */
        public final boolean regularSpikePattern;
        /** Текстовая сводка с явной маркировкой фактов и неопределённости. */
        public final String note;

        Analysis(double jitterMs, double p95ToMedianRatio, int spikesLast60s, int spikesConsidered,
                 double spikeIntervalMeanMs, double spikeIntervalCv, boolean regularSpikePattern, String note) {
            this.jitterMs = jitterMs;
            this.p95ToMedianRatio = p95ToMedianRatio;
            this.spikesLast60s = spikesLast60s;
            this.spikesConsidered = spikesConsidered;
            this.spikeIntervalMeanMs = spikeIntervalMeanMs;
            this.spikeIntervalCv = spikeIntervalCv;
            this.regularSpikePattern = regularSpikePattern;
            this.note = note;
        }
    }

    private final FrameTimeMonitor monitor;
    private final long[] spikeTimes = new long[FrameTimeMonitor.SPIKE_CAPACITY];
    private final long[] intervals = new long[FrameTimeMonitor.SPIKE_CAPACITY];

    public FramePacingAnalyzer(FrameTimeMonitor monitor) {
        this.monitor = monitor;
    }

    public Analysis analyze(FrameTimeMonitor.Snapshot snapshot) {
        long now = monitor.clockNanos();
        int spikeCount = monitor.copyRecentSpikeTimes(spikeTimes, now, PATTERN_WINDOW_NANOS);

        int intervalCount = Math.max(0, spikeCount - 1);
        double meanMs = Double.NaN;
        double cv = Double.NaN;
        boolean regular = false;

        if (intervalCount >= 3) {
            // spikeTimes[0] — самый свежий, поэтому разности соседних положительны.
            long sum = 0;
            for (int i = 0; i < intervalCount; i++) {
                intervals[i] = spikeTimes[i] - spikeTimes[i + 1];
                sum += intervals[i];
            }
            meanMs = sum / (double) intervalCount / 1_000_000.0;
            double varSum = 0;
            for (int i = 0; i < intervalCount; i++) {
                double d = intervals[i] / 1_000_000.0 - meanMs;
                varSum += d * d;
            }
            double sd = Math.sqrt(varSum / intervalCount);
            cv = meanMs > 0 ? sd / meanMs : Double.NaN;
            regular = cv < 0.25; // низкий разброс интервалов — факт о паттерне, не о причине
        }

        String note;
        if (regular) {
            note = "regular spike pattern observed (CV=" + String.format("%.2f", cv) + "); cause NOT confirmed";
        } else if (intervalCount >= 1) {
            note = "spike intervals irregular or sample too small; cause NOT confirmed";
        } else {
            note = "no recent spikes";
        }

        double ratio = snapshot.medianMs > 0 ? snapshot.p95Ms / snapshot.medianMs : Double.NaN;
        return new Analysis(snapshot.stdDevMs, ratio, snapshot.spikesLast60s,
                intervalCount, meanMs, cv, regular, note);
    }
}
