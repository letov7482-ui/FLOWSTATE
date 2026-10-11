package dev.flowstate.client.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HysteresisTest {

    @Test
    void hysteresisAndDwellPreventFlapping() {
        long[] t = {0};
        Hysteresis h = new Hysteresis(20.0, 15.0, 5_000_000_000L, () -> t[0]);

        assertTrue(h.update(25.0));   // вход
        assertTrue(h.isActive());
        assertFalse(h.update(16.0));  // выше выхода, ниже входа — без изменений
        assertTrue(h.isActive());
        assertFalse(h.update(10.0));  // хочется выйти, но dwell не истёк
        assertTrue(h.isActive());

        t[0] += 6_000_000_000L;
        assertTrue(h.update(10.0));   // dwell истёк — выход
        assertFalse(h.isActive());
        assertFalse(h.update(25.0));  // хочется войти, но dwell не истёк
        assertFalse(h.isActive());

        t[0] += 6_000_000_000L;
        assertTrue(h.update(25.0));
        assertTrue(h.isActive());
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Hysteresis(10.0, 10.0, 0, () -> 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new Hysteresis(10.0, 12.0, 0, () -> 0L));
    }
}
