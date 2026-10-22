package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "allowed operations" checkboxes on an agent used to be decoration: the column was written by
 * the UI and by plugin import, and nothing ever read it. Clearing Delete saved, displayed as
 * cleared, and changed nothing — a boundary someone had configured and believed in.
 *
 * <p>These tests pin the connection to the ceiling that already governs every tool call, and the
 * last one is the point of the whole exercise: a read-only agent must actually be refused a write
 * tool, not merely described as read-only.
 */
class AllowedOperationsCeilingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentContextPolicy policyFor(Object allowedOperations, String guardrails) {
        Map<String, Object> agent = new java.util.HashMap<>();
        agent.put("agent_code", "test_agent");
        agent.put("allowed_operations", allowedOperations);
        agent.put("guardrails", guardrails);
        return DefaultAgentProfileResolver.INSTANCE.resolve(MAPPER, agent).contextPolicy();
    }

    @Test
    @DisplayName("query alone makes the agent read-only")
    void queryOnlyIsReadOnly() {
        assertThat(policyFor(List.of("query"), null).capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.READ_ONLY);
    }

    @Test
    @DisplayName("any write verb makes the agent write-capable")
    void writeVerbIsWriteCapable() {
        assertThat(policyFor(List.of("query", "create"), null).capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
        assertThat(policyFor(List.of("delete"), null).capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
    }

    @Test
    @DisplayName("an empty list means not configured, not forbidden from everything")
    void emptyMeansUnset() {
        // Rows written before this field existed have nothing in it. Reading that as "deny all"
        // would mute every one of them on upgrade.
        assertThat(policyFor(List.of(), null).capabilityCeiling()).isNull();
        assertThat(policyFor(null, null).capabilityCeiling()).isNull();
    }

    @Test
    @DisplayName("an explicitly written guardrail ceiling wins over the derived one")
    void explicitGuardrailWins() {
        assertThat(policyFor(List.of("query"), "{\"capabilityCeiling\":\"WRITE_CAPABLE\"}").capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
    }

    @Test
    @DisplayName("the column may arrive as a JSON-ish string rather than a list")
    void acceptsCommaSeparatedString() {
        assertThat(policyFor("query,create", null).capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
        assertThat(policyFor("query", null).capabilityCeiling())
                .isEqualTo(ToolCapabilityCeiling.READ_ONLY);
    }

    @Test
    @DisplayName("a read-only agent is refused a write tool, and allowed a read tool")
    void readOnlyAgentCannotCallWriteTools() {
        // The assertion that makes the rest of them worth having. Everything above only shows a
        // field being mapped to an enum; this shows the enum stopping a call.
        AgentContextPolicy readOnly = policyFor(List.of("query"), null);
        ExecutionEnvelope envelope = new ExecutionEnvelopePlanner().plan(
                new ExecutionEnvelopePlanner.Request(
                        null, true, false, false,
                        new AgentProfile("test_agent", java.util.Set.of(), readOnly, false)));

        ToolCapabilityPolicy capability = new ToolCapabilityPolicy();

        ToolMetadata writeTool = ToolMetadata.builder()
                .toolName("crm.create_lead")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .build();

        ToolMetadata readTool = ToolMetadata.builder()
                .toolName("crm.search_leads")
                .effectType(ToolEffectType.INTERNAL_READ)
                .build();

        ToolPolicyActor actor = new ToolPolicyActor(1L, 1L, java.util.Set.of());

        assertThat(capability.evaluate(writeTool, envelope, actor).allowed())
                .as("clearing the write operations must actually stop a write tool")
                .isFalse();
        assertThat(capability.evaluate(readTool, envelope, actor).allowed())
                .as("a read-only agent must still be able to read")
                .isTrue();
    }
}
