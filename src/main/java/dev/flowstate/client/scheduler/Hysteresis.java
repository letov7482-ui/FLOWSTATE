package dev.flowstate.client.scheduler;

import java.util.function.LongSupplier;

/**
 * Переключатель с гистерезисом и минимальным временем удержания состояния.
 * Строительный блок SMART_BALANCE: защищает от постоянных переключений
 * ("дребезга") при значениях вблизи порога. Полностью протестирован.
 */
public final class Hysteresis {

    private final double enterValue;
    private final double exitValue;
    private final long dwellNanos;
    private final LongSupplier clock;

    private boolean active;
    private long lastFlipNanos = Long.MIN_VALUE;

    /**
     * @param enterValue активация при значении >= enterValue
     * @param exitValue  деактивация при значении <= exitValue (строго меньше enterValue)
     * @param dwellNanos минимальное время удержания любого состояния
     */
    public Hysteresis(double enterValue, double exitValue, long dwellNanos, LongSupplier clock) {
        if (!(exitValue < enterValue)) {
            throw new IllegalArgumentException("exitValue must be strictly less than enterValue");
        }
        this.enterValue = enterValue;
        this.exitValue = exitValue;
        this.dwellNanos = dwellNanos;
        this.clock = clock;
    }

    /**
     * Обновляет состояние по измеренному значению.
     * @return true, если состояние изменилось в этом вызове.
     */
    public boolean update(double value) {
        long now = clock.getAsLong();
        boolean wantActive = active ? value > exitValue : value >= enterValue;
        if (wantActive != active) {
            if (lastFlipNanos != Long.MIN_VALUE && now - lastFlipNanos < dwellNanos) {
                return false; // слишком рано — удерживаем состояние
            }
            active = wantActive;
            lastFlipNanos = now;
            return true;
        }
        return false;
    }

    public boolean isActive() {
        return active;
    }

    public void reset() {
        active = false;
        lastFlipNanos = Long.MIN_VALUE;
    }
}
