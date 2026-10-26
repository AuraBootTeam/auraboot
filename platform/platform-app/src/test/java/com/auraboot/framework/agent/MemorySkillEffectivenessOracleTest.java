package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** Reject evaluator artifacts: echoed questions, historic values, substrings and blank answers. */
class MemorySkillEffectivenessOracleTest {
    @Test
    void currentStructuredFactsAreRequired() {
        assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent("status=OPEN; days_late=12")).isTrue();
        assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent("**status=OPEN; days_late=12**")).isTrue();
        for (String bad : new String[]{"", "OPEN 12", "status=CLOSED; days_late=12", "status=OPEN; days_late=112",
                "status=<raw enum>; days_late=<actual integer>", "Historical quote: status=OPEN; days_late=12"}) {
            assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent(bad)).as(bad).isFalse();
        }
    }
    @Test
    void bothProductionToolResultShapesProveReadButModelTextDoesNot() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String data = "{\"success\":true,\"records\":[{\"order_ref\":\"ACME-184\",\"order_status\":\"OPEN\",\"days_late\":12}]}";
        for (String field : new String[]{"content", "result"}) {
            String encoded = field.equals("result") ? "Data only\n<tool-output>\n" + data + "\n</tool-output>" : data;
            java.util.List<java.util.Map<String, Object>> calls = java.util.List.of(java.util.Map.of("type", "tool_result", field, encoded));
            assertThat(MemorySkillBusinessEffectivenessLiveIT.observedBusinessRead(json, calls)).isTrue();
        }
        assertThat(MemorySkillBusinessEffectivenessLiveIT.observedBusinessRead(json,
                java.util.List.of(java.util.Map.of("type", "text", "content", data)))).isFalse();
        assertThat(MemorySkillBusinessEffectivenessLiveIT.observedBusinessRead(json,
                java.util.List.of(java.util.Map.of("type", "tool_result", "content", data.replace("OPEN", "CLOSED"))))).isFalse();
    }

}
