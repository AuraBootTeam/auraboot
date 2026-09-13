package com.auraboot.framework.behavior;

import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import org.junit.jupiter.api.Test;
import com.auraboot.framework.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorQueryWindowTest {
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void defaultIsExactlyThirtyDaysEndingAtTheProvidedClock() {
        assertEquals(new BehaviorQueryWindow(NOW.minus(Duration.ofDays(30)), NOW),
                BehaviorQueryWindow.resolve(null, null, NOW));
    }

    @Test
    void rejectsPartialEmptyReversedAndUnboundedWindows() {
        assertThrows(BusinessException.class, () -> BehaviorQueryWindow.resolve(null, NOW, NOW));
        assertThrows(BusinessException.class, () -> BehaviorQueryWindow.resolve(NOW, null, NOW));
        assertThrows(BusinessException.class, () -> new BehaviorQueryWindow(NOW, NOW));
        assertThrows(BusinessException.class, () -> new BehaviorQueryWindow(NOW.plusSeconds(1), NOW));
        assertThrows(BusinessException.class,
                () -> new BehaviorQueryWindow(NOW.minus(Duration.ofDays(366)).minusSeconds(1), NOW));
    }

    @Test
    void acceptsMaximumWindowWithoutChangingBoundaries() {
        Instant from = NOW.minus(Duration.ofDays(366));
        assertEquals(from, BehaviorQueryWindow.resolve(from, NOW, NOW).from());
        assertEquals(NOW, BehaviorQueryWindow.resolve(from, NOW, NOW).to());
    }
}
