package dev.flowstate.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStateConfigTest {

    @Test
    void roundTrip(@TempDir Path dir) {
        Path path = dir.resolve("flowstate.json");
        FlowStateConfig.LoadResult first = FlowStateConfig.load(path);
        assertEquals(FlowStateConfig.LoadState.CREATED_DEFAULT, first.state());

        first.config().spikeThresholdMs = 55.0;
        first.config().mode = PerformanceMode.MAX_FPS;
        assertNull(first.config().save(path));

        FlowStateConfig.LoadResult second = FlowStateConfig.load(path);
        assertEquals(FlowStateConfig.LoadState.LOADED, second.state());
        assertEquals(55.0, second.config().spikeThresholdMs, 1e-9);
        assertEquals(PerformanceMode.MAX_FPS, second.config().mode);
    }

    @Test
    void corruptedFileRecoversWithBackup(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("flowstate.json");
        Files.writeString(path, "{ это не json ]");
        FlowStateConfig.LoadResult result = FlowStateConfig.load(path);
        assertEquals(FlowStateConfig.LoadState.CORRUPTED_RECOVERED, result.state());
        assertEquals(40.0, result.config().spikeThresholdMs, 1e-9);
        assertTrue(Files.exists(dir.resolve("flowstate.json.corrupt")));
        assertTrue(Files.readString(path).contains("\"showHud\"")); // дефолты записаны
    }

    @Test
    void outOfRangeValuesAreClamped() {
        FlowStateConfig cfg = new FlowStateConfig();
        cfg.spikeThresholdMs = -5;
        cfg.spikeRatio = 999;
        cfg.statsRefreshMs = 0;
        cfg.hudMargin = 1000;
        String fixes = cfg.sanitize();
        assertNotNull(fixes);
        assertEquals(5.0, cfg.spikeThresholdMs, 1e-9);
        assertEquals(10.0, cfg.spikeRatio, 1e-9);
        assertEquals(50, cfg.statsRefreshMs);
        assertEquals(32, cfg.hudMargin);
    }

    @Test
    void nanIsRejected() {
        FlowStateConfig cfg = new FlowStateConfig();
        cfg.spikeThresholdMs = Double.NaN;
        cfg.sanitize();
        assertEquals(5.0, cfg.spikeThresholdMs, 1e-9);
    }
}
