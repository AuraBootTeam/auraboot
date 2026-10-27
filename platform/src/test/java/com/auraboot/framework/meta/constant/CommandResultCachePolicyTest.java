package com.auraboot.framework.meta.constant;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandResultCachePolicyTest {
    @Test
    void defaultAndTrueRetainExistingBehavior() {
        assertTrue(CommandResultCachePolicy.enabled(null));
        assertTrue(CommandResultCachePolicy.enabled(Map.of()));
        assertTrue(CommandResultCachePolicy.enabled(Map.of("type", "update", "cacheResult", true)));
    }

    @Test
    void onlyAnExplicitQueryCanDisableResultReuse() {
        assertFalse(CommandResultCachePolicy.enabled(Map.of("type", "query", "cacheResult", false)));
        assertFalse(CommandResultCachePolicy.enabled(Map.of("type", "query", "cacheResult", false,
                "requireIdempotencyKey", false, "handlerParams", Map.of("targetRecordAction", "read"))));
        for (String type : List.of("update", "create", "delete", "custom", "action", "state_transition", "QUERY", ""))
            assertThrows(IllegalArgumentException.class, () -> CommandResultCachePolicy.enabled(
                    Map.of("type", type, "cacheResult", false)));
        assertThrows(IllegalArgumentException.class, () -> CommandResultCachePolicy.enabled(Map.of("cacheResult", false)));
    }

    @Test
    void invalidTypesAndContradictoryContractsFailClosed() {
        for (Object value : Arrays.asList("false", "true", 0, 1, List.of(), Map.of(), null)) {
            var config = new HashMap<String,Object>();
            config.put("type", "query"); config.put("cacheResult", value);
            assertThrows(IllegalArgumentException.class, () -> CommandResultCachePolicy.enabled(config));
        }
        assertThrows(IllegalArgumentException.class, () -> CommandResultCachePolicy.enabled(
                Map.of("type", "query", "cacheResult", false, "requireIdempotencyKey", true)));
        for (String action : List.of("update", "delete", "create", "unknown"))
            assertThrows(IllegalArgumentException.class, () -> CommandResultCachePolicy.enabled(
                    Map.of("type", "query", "cacheResult", false, "handlerParams", Map.of("targetRecordAction", action))));
    }
}
