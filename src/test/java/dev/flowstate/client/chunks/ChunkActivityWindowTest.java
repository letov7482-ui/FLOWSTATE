package dev.flowstate.client.chunks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkActivityWindowTest {

    @Test
    void slowTickCorrelationIsCounted() {
        ChunkActivityWindow window = new ChunkActivityWindow(50.0);
        window.onChunkLoad();
        window.onChunkLoad();
        window.onChunkUnload();
        window.onTickEnd(1_000_000_000L, 60_000_000L); // медленный тик с событиями
        window.onTickEnd(1_050_000_000L, 10_000_000L); // быстрый тик

        assertEquals(1, window.slowTicksSince(0));
        assertEquals(1, window.slowTicksWithChunkEventsSince(0));
        assertEquals(2, window.loadsSince(0));
        assertEquals(1, window.unloadsSince(0));
        assertEquals(1, window.slowTicksTotal());
    }

    @Test
    void cutoffExcludesOldRecords() {
        ChunkActivityWindow window = new ChunkActivityWindow(50.0);
        window.onTickEnd(1_000_000L, 80_000_000L);      // старый медленный тик
        window.onTickEnd(61_000_000_000L, 80_000_000L); // свежий медленный тик
        assertEquals(2, window.slowTicksSince(0));
        assertEquals(1, window.slowTicksSince(60_000_000_000L));
    }

    @Test
    void resetClearsEverything() {
        ChunkActivityWindow window = new ChunkActivityWindow(50.0);
        window.onChunkLoad();
        window.onTickEnd(1_000L, 80_000_000L);
        window.reset();
        assertEquals(0, window.slowTicksSince(0));
        assertEquals(0, window.loadsTotal());
        assertEquals(0, window.slowTicksTotal());
    }
}
