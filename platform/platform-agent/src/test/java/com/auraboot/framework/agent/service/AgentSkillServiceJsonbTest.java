package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the JSONB-PGobject read-side fix in {@link AgentSkillService}.
 *
 * <p>{@code skill_tools} and {@code skill_input_schema} are JSONB columns. Read via the
 * generic {@code DynamicDataMapper.selectByQuery}, the driver returns a {@code PGobject}.
 * The pre-fix {@code parseToolCodes}/{@code parseJsonObject} handled only String/List/Map,
 * so a PGobject fell through — silently dropping a legacy skill's tools (empty list) and
 * its input schema (null). These tests feed a real PGobject directly (no DB/Spring) and
 * fail against the pre-fix code. Lives in the {@code .service} package to reach the
 * package-private parse helpers.
 */
class AgentSkillServiceJsonbTest {

    // mapper + observationService + declaredAgentToolResolver are unused by the pure
    // parse helpers under test.
    private final AgentSkillService service =
            new AgentSkillService(null, null, new ObjectMapper(), null);

    private static PGobject jsonb(String value) {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        try {
            pg.setValue(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return pg;
    }

    @Test
    void parseToolCodes_pgobjectJsonArray_notDroppedToEmpty() {
        assertEquals(List.of("tool_a", "tool_b"),
                service.parseToolCodes(jsonb("[\"tool_a\",\"tool_b\"]")),
                "skill_tools returned as PGobject must parse, not drop to an empty list");
    }

    @Test
    void parseToolCodes_alreadyList_passthrough() {
        assertEquals(List.of("x"), service.parseToolCodes(List.of("x")));
    }

    @Test
    void parseToolCodes_jsonString_parsed() {
        assertEquals(List.of("x"), service.parseToolCodes("[\"x\"]"));
    }

    @Test
    void parseToolCodes_null_empty() {
        assertTrue(service.parseToolCodes(null).isEmpty());
    }

    @Test
    void parseJsonObject_pgobject_parsedNotNull() {
        Map<String, Object> result = service.parseJsonObject(jsonb("{\"type\":\"object\"}"));
        assertNotNull(result, "skill_input_schema PGobject must parse, not return null");
        assertEquals("object", result.get("type"));
    }
}
