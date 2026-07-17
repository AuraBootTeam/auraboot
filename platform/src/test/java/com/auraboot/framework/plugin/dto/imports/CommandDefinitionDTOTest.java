package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DR-20260715-A-006: {@code concurrencyKey} / {@code lockTimeoutMs} are read at runtime
 * (LoadPhase / CommandExecutorImpl via {@code config.get(...)}), but were never declared
 * on {@link CommandDefinitionDTO}. Jackson dropped them into {@code unknownFields} and
 * {@code getConsolidatedExecutionConfig()} — which only merges declared fields — never
 * surfaced them, so a plugin's declared concurrency lock silently did nothing.
 */
class CommandDefinitionDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void concurrencyFields_parseIntoDeclaredFields_notUnknownFields() throws Exception {
        String json = """
            {
              "code": "so:approve",
              "type": "state_transition",
              "concurrencyKey": "so:${payload.sl_so_code}",
              "lockTimeoutMs": 8000
            }
            """;

        CommandDefinitionDTO dto = mapper.readValue(json, CommandDefinitionDTO.class);

        // Parsed into the declared fields...
        assertEquals("so:${payload.sl_so_code}", dto.getConcurrencyKey());
        assertEquals(8000L, dto.getLockTimeoutMs());
        // ...and therefore NOT swallowed into unknownFields.
        Map<String, Object> unknown = dto.getUnknownFields();
        if (unknown != null) {
            assertFalse(unknown.containsKey("concurrencyKey"),
                    "concurrencyKey must be a declared field, not an unknown one");
            assertFalse(unknown.containsKey("lockTimeoutMs"),
                    "lockTimeoutMs must be a declared field, not an unknown one");
        }
    }

    @Test
    void consolidatedExecutionConfig_carriesConcurrencyKeys_soRuntimeCanReadThem() throws Exception {
        String json = """
            {
              "code": "so:approve",
              "type": "state_transition",
              "concurrencyKey": "so:${payload.sl_so_code}",
              "lockTimeoutMs": 8000
            }
            """;

        Map<String, Object> config = mapper.readValue(json, CommandDefinitionDTO.class)
                .getConsolidatedExecutionConfig();

        assertNotNull(config);
        // These are the exact keys LoadPhase / CommandExecutorImpl read via config.get(...).
        assertEquals("so:${payload.sl_so_code}", config.get("concurrencyKey"));
        assertEquals(8000L, config.get("lockTimeoutMs"));
    }

    @Test
    void consolidatedExecutionConfig_omitsConcurrencyKeys_whenUnset() throws Exception {
        String json = "{\"code\": \"so:approve\", \"type\": \"update\"}";
        Map<String, Object> config = mapper.readValue(json, CommandDefinitionDTO.class)
                .getConsolidatedExecutionConfig();

        assertNotNull(config);
        assertTrue(config.containsKey("type"));
        // Absent keys are not injected (runtime distinguishes "no lock" from a lock).
        assertNull(config.get("concurrencyKey"));
        assertNull(config.get("lockTimeoutMs"));
    }
}
