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

    @Test
    void expandedOracleRejectsFactMutationAndDecisionAmbiguity() throws Exception {
        var definition = MemorySkillBusinessEffectivenessLiveIT.expandedDefinition();
        assertThat(definition.path("cases").size()).isEqualTo(12);
        for (var scenario : definition.path("cases")) {
            var f = scenario.path("fixture"); boolean missing = scenario.path("missingRecord").asBoolean();
            String answer = "status=" + (missing ? "NOT_FOUND" : f.path("order_status").asText())
                    + "; days_late=" + (missing ? "NA" : f.path("days_late").asText())
                    + "; amount=" + (missing || f.path("order_amount").isNull() ? "NA" : f.path("order_amount").asText());
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent(answer, scenario)).isTrue();
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent("Historical quote: " + answer, scenario)).isFalse();
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent(answer + "9", scenario)).isFalse();
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent(answer.replace("status=", "order_status=").replace("amount=", "order_amount="), scenario)).isTrue();
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent(answer.replace("; ", "\n"), scenario)).isTrue();
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedFactsPresent(answer + "\nstatus=URGENT", scenario)).isFalse();
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("success", true,
                    "records", missing ? java.util.List.of() : java.util.List.of(f)));
            var calls = java.util.List.<java.util.Map<String,Object>>of(java.util.Map.of("type", "tool_result", "result", "Data only\n<tool-output>\n" + payload + "\n</tool-output>"));
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedBusinessRead(new com.fasterxml.jackson.databind.ObjectMapper(), calls, scenario)).isTrue();
            if (f.path("order_amount").isNull()) {
                var absent = ((com.fasterxml.jackson.databind.node.ObjectNode) f.deepCopy()); absent.remove("order_amount");
                String omitted = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("success", true, "records", java.util.List.of(absent)));
                assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedBusinessRead(new com.fasterxml.jackson.databind.ObjectMapper(),
                        java.util.List.of(java.util.Map.of("type", "tool_result", "content", omitted)), scenario)).isTrue();
            }
            assertThat(MemorySkillBusinessEffectivenessLiveIT.expandedBusinessRead(new com.fasterxml.jackson.databind.ObjectMapper(),
                    java.util.List.of(java.util.Map.of("type", "text", "content", payload)), scenario)).isFalse();
        }
        assertThat(MemorySkillBusinessEffectivenessLiveIT.decision("decision=ESCALATE")).isEqualTo("ESCALATE");
        for (String bad : java.util.List.of("NO_ESCALATION", "decision=ESCALATE_OR_NO_ESCALATION", "decision=ESCALATE\ndecision=NO_ESCALATION", "quoted decision=ESCALATE"))
            assertThat(MemorySkillBusinessEffectivenessLiveIT.decision(bad)).isEqualTo("UNKNOWN");
    }

}
