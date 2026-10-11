package dev.flowstate.client.compatibility;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Детекция Sodium без единой зависимости от его классов (только Fabric Loader).
 * FLOWSTATE не вмешивается в рендер-пайплайн, поэтому сосуществование
 * пассивное по построению.
 */
public final class SodiumCompat {

    private static final String SODIUM_ID = "sodium";

    private SodiumCompat() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(SODIUM_ID);
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(SODIUM_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("n/a");
    }

    public static void logStatus(Logger log) {
        if (installed()) {
            log.info("[FlowState] Sodium detected: v{} — FlowState runs alongside, no API conflicts", version());
        } else {
            log.info("[FlowState] Sodium not installed — basic mode");
        }
    }
}
