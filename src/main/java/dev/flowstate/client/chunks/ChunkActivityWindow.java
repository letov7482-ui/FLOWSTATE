package dev.flowstate.client.chunks;

/**
 * Чистая логика (без Minecraft): кольцевой буфер по тикам — длительность тика
 * и чанковые события внутри него. Не зависит от TPS при запросах по времени.
 */
public final class ChunkActivityWindow {

    public static final int CAPACITY = 2048; // ~100 с при 20 TPS

    private final long slowThresholdNanos;

    private final long[] tickTime = new long[CAPACITY];
    private final long[] tickDuration = new long[CAPACITY];
    private final short[] loads = new short[CAPACITY];
    private final short[] unloads = new short[CAPACITY];

    private int head;
    private int filled;
    private int pendingLoads;
    private int pendingUnloads;
    private long loadsTotal;
    private long unloadsTotal;
    private long slowTicksTotal;
    private long slowTicksWithChunkEvents;

    public ChunkActivityWindow(double slowThresholdMs) {
        this.slowThresholdNanos = (long) (slowThresholdMs * 1_000_000.0);
    }

    public void onChunkLoad() {
        if (pendingLoads < Short.MAX_VALUE) pendingLoads++;
        loadsTotal++;
    }

    public void onChunkUnload() {
        if (pendingUnloads < Short.MAX_VALUE) pendingUnloads++;
        unloadsTotal++;
    }

    /** Вызывается один раз за тик, ПОСЛЕ того как трекер зафиксировал длительность тика. */
    public void onTickEnd(long nowNanos, long tickDurationNanos) {
        int idx = head;
        tickTime[idx] = nowNanos;
        tickDuration[idx] = tickDurationNanos;
        loads[idx] = (short) pendingLoads;
        unloads[idx] = (short) pendingUnloads;
        head = (head + 1) & (CAPACITY - 1);
        if (filled < CAPACITY) filled++;

        if (tickDurationNanos >= slowThresholdNanos) {
            slowTicksTotal++;
            if (pendingLoads > 0 || pendingUnloads > 0) slowTicksWithChunkEvents++;
        }
        pendingLoads = 0;
        pendingUnloads = 0;
    }

    private int indexFromNewest(int i) {
        return (head - 1 - i + 2 * CAPACITY) & (CAPACITY - 1);
    }

    public int slowTicksSince(long cutoffNanos) {
        int count = 0;
        for (int i = 0; i < filled; i++) {
            int idx = indexFromNewest(i);
            if (tickTime[idx] < cutoffNanos) break; // записи упорядочены по времени
            if (tickDuration[idx] >= slowThresholdNanos) count++;
        }
        return count;
    }

    public int slowTicksWithChunkEventsSince(long cutoffNanos) {
        int count = 0;
        for (int i = 0; i < filled; i++) {
            int idx = indexFromNewest(i);
            if (tickTime[idx] < cutoffNanos) break;
            if (tickDuration[idx] >= slowThresholdNanos
                    && (loads[idx] > 0 || unloads[idx] > 0)) count++;
        }
        return count;
    }

    public int loadsSince(long cutoffNanos) {
        int sum = 0;
        for (int i = 0; i < filled; i++) {
            int idx = indexFromNewest(i);
            if (tickTime[idx] < cutoffNanos) break;
            sum += loads[idx];
        }
        return sum;
    }

    public int unloadsSince(long cutoffNanos) {
        int sum = 0;
        for (int i = 0; i < filled; i++) {
            int idx = indexFromNewest(i);
            if (tickTime[idx] < cutoffNanos) break;
            sum += unloads[idx];
        }
        return sum;
    }

    public long loadsTotal() { return loadsTotal; }
    public long unloadsTotal() { return unloadsTotal; }
    public long slowTicksTotal() { return slowTicksTotal; }

    public void reset() {
        head = 0;
        filled = 0;
        pendingLoads = 0;
        pendingUnloads = 0;
        loadsTotal = 0;
        unloadsTotal = 0;
        slowTicksTotal = 0;
        slowTicksWithChunkEvents = 0;
    }
  }
