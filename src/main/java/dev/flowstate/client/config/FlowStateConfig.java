package dev.flowstate.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON-конфигурация. Безопасные значения по умолчанию, валидация,
 * восстановление после повреждённого файла (бэкап + дефолты).
 * Логика не зависит от Minecraft — покрыта юнит-тестами.
 */
public final class FlowStateConfig {

    public static final String FILE_NAME = "flowstate.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- Значения по умолчанию (безопасные) ----
    public boolean showHud = true;
    public PerformanceMode mode = PerformanceMode.ULTRA_SMOOTH;
    public double spikeThresholdMs = 40.0;
    public double spikeRatio = 2.5;
    public int statsRefreshMs = 250;
    public int hudMargin = 4;
    public boolean experimentalFeatures = false;
    public boolean logSpikes = false;

    public enum LoadState { LOADED, CREATED_DEFAULT, CORRUPTED_RECOVERED }

    public record LoadResult(FlowStateConfig config, LoadState state, String message) {}

    public static LoadResult load(Path path) {
        FlowStateConfig defaults = new FlowStateConfig();
        if (!Files.exists(path)) {
            String saveError = defaults.save(path);
            return new LoadResult(defaults, LoadState.CREATED_DEFAULT, saveError);
        }
        FlowStateConfig parsed;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parsed = GSON.fromJson(reader, FlowStateConfig.class);
        } catch (JsonSyntaxException e) {
            return recover(path, defaults, "broken JSON: " + e.getMessage());
        } catch (IOException e) {
            return recover(path, defaults, "read error: " + e.getMessage());
        }
        if (parsed == null) {
            return recover(path, defaults, "file is empty");
        }
        return new LoadResult(parsed, LoadState.LOADED, parsed.sanitize());
    }

    private static LoadResult recover(Path path, FlowStateConfig defaults, String reason) {
        String backupError = backup(path);
        String saveError = defaults.save(path);
        StringBuilder message = new StringBuilder(reason);
        if (backupError != null) message.append("; ").append(backupError);
        message.append(saveError == null
                ? "; defaults written (old file kept as *.corrupt)"
                : "; defaults NOT saved: " + saveError);
        return new LoadResult(defaults, LoadState.CORRUPTED_RECOVERED, message.toString());
    }

    /** @return null при успехе, описание ошибки иначе. */
    private static String backup(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            return "backup failed: " + e.getMessage();
        }
    }

    /** Атомарная запись через временный файл. @return null при успехе. */
    public String save(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            return "save failed: " + e.getMessage();
        }
    }

    /**
     * Приводит значения к допустимым диапазонам (NaN тоже отбраковывается).
     * @return описание исправлений или null, если всё было корректно.
     */
    public String sanitize() {
        StringBuilder fixes = new StringBuilder();
        if (mode == null) {
            mode = PerformanceMode.ULTRA_SMOOTH;
            fixes.append("mode=null->ULTRA_SMOOTH; ");
        }
        spikeThresholdMs = clamp(spikeThresholdMs, 5.0, 500.0, "spikeThresholdMs", fixes);
        spikeRatio = clamp(spikeRatio, 1.5, 10.0, "spikeRatio", fixes);
        statsRefreshMs = (int) clamp(statsRefreshMs, 50, 2000, "statsRefreshMs", fixes);
        hudMargin = (int) clamp(hudMargin, 0, 32, "hudMargin", fixes);
        return fixes.length() == 0 ? null : fixes.toString().trim();
    }

    private static double clamp(double v, double min, double max, String name, StringBuilder fixes) {
        if (Double.isNaN(v)) {
            fixes.append(name).append("=NaN->").append(min).append("; ");
            return min;
        }
        if (v >= min && v <= max) return v;
        double clamped = Math.min(max, Math.max(min, v));
        fixes.append(name).append('=').append(v).append("->").append(clamped).append("; ");
        return clamped;
    }
              }
