package dev.flowstate.client.chunks;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

/**
 * Клей между Fabric-событиями и чистым окном. Вывод — только факты:
 * корреляция чанковой активности с медленными тиками. Причина НЕ утверждается.
 *
 * Честное ограничение: приоритезация чанковых задач через публичный Fabric API
 * (без Mixin в пайплайн клиента) нереализуема — этот модуль только измеряет.
 */
public final class ChunkPerformanceAdvisor {

    private final ChunkActivityWindow window;

    public ChunkPerformanceAdvisor(ChunkActivityWindow window) {
        this.window = window;
    }

    public void registerChunkEvents() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> window.onChunkLoad());
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> window.onChunkUnload());
    }

    /** Текстовая сводка для HUD. Дёшево: два прохода по ограниченному кольцу. */
    public String summary(long nowNanos, long windowNanos) {
        long cutoff = nowNanos - windowNanos;
        int slow = window.slowTicksSince(cutoff);
        if (slow == 0) {
            return "no slow ticks in window";
        }
        int withChunks = window.slowTicksWithChunkEventsSince(cutoff);
        return String.format("+%d/-%d chunks | slow ticks %d (with chunk events: %d) | correlation only, cause not confirmed",
                window.loadsSince(cutoff), window.unloadsSince(cutoff), slow, withChunks);
    }

    public ChunkActivityWindow window() {
        return window;
    }
}
