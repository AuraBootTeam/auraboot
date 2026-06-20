package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class W3cTraceparentTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c"; // 32 hex
    private static final String SPAN_ID = "b7ad6b7169203331";                  // 16 hex

    @Test
    void formatsSampledTraceparent() {
        assertEquals("00-" + TRACE_ID + "-" + SPAN_ID + "-01",
                W3cTraceparent.format(TRACE_ID, SPAN_ID, true));
    }

    @Test
    void usesZeroFlagsWhenUnsampled() {
        assertTrue(W3cTraceparent.format(TRACE_ID, SPAN_ID, false).endsWith("-00"));
    }

    @Test
    void returnsNullForInvalidIds() {
        assertNull(W3cTraceparent.format(null, SPAN_ID, true));
        assertNull(W3cTraceparent.format("tooshort", SPAN_ID, true));
        assertNull(W3cTraceparent.format(TRACE_ID, null, true));
        assertNull(W3cTraceparent.format(TRACE_ID, "badspanid", true));
    }
}
