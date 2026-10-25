package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExecutionEnvelopePlanner")
class ExecutionEnvelopePlannerTest {

    private final ExecutionEnvelopePlanner planner = new ExecutionEnvelopePlanner();

    @Test
    @DisplayName("preserves explicit envelopes from callers")
    void preservesExplicitEnvelope() {
        ExecutionEnvelope explicit = ExecutionEnvelope.readOnlyCatalog();

        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                explicit,
                false,
                true,
                false));

        assertThat(planned).isSameAs(explicit);
    }

    @Test
    @DisplayName("plans answer-only envelope when no tools are available")
    void plansAnswerOnlyWhenNoToolsAvailable() {
        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                false,
                false,
                false));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.NO_TOOLS);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.ANSWER_ONLY);
        assertThat(planned.durabilityPreference()).isEqualTo(DurabilityPreference.NONE);
    }

    @Test
    @DisplayName("plans read-only catalog for read-only contextual turns")
    void plansReadOnlyCatalogForContextualTurns() {
        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                true,
                true,
                false));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.READ_ONLY);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.READ_ONLY_CATALOG);
    }

    @Test
    @DisplayName("plans write catalog with gate when tools are available")
    void plansWriteCatalogWithGateWhenToolsAvailable() {
        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                true,
                false,
                false));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.WRITE_CATALOG_WITH_GATE);
        assertThat(planned.durabilityPreference()).isEqualTo(DurabilityPreference.ALLOWED);
    }

    @Test
    @DisplayName("plans durable workflow entry only when durable semantics are required")
    void plansDurableWorkflowEntryWhenDurableRequired() {
        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                true,
                false,
                true));

        assertThat(planned.initialMode()).isEqualTo(InitialExecutionMode.DURABLE_WORKFLOW_ENTRY);
        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.WRITE_CAPABLE);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.ACTION_PROPOSAL);
        assertThat(planned.durabilityPreference()).isEqualTo(DurabilityPreference.REQUIRED);
    }

    @Test
    @DisplayName("profile context policy can cap an otherwise write-capable turn to read-only tools")
    void profileContextPolicyCapsTurnToReadOnlyTools() {
        AgentProfile profile = new AgentProfile(
                "sales_agent",
                null,
                new AgentContextPolicy(
                        java.util.Set.of("page", "record"),
                        false,
                        ToolCapabilityCeiling.READ_ONLY,
                        ToolExposure.READ_ONLY_CATALOG,
                        DurabilityPreference.NONE),
                false);

        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                true,
                false,
                false,
                profile));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.READ_ONLY);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.READ_ONLY_CATALOG);
        assertThat(planned.durabilityPreference()).isEqualTo(DurabilityPreference.NONE);
    }

    @Test
    @DisplayName("tenant policy caps a write-capable profile to read-only catalog")
    void tenantPolicyCapsWriteCapableProfileToReadOnlyCatalog() {
        AgentProfile profile = new AgentProfile(
                "sales_agent",
                null,
                new AgentContextPolicy(
                        java.util.Set.of("page", "record"),
                        false,
                        ToolCapabilityCeiling.WRITE_CAPABLE,
                        ToolExposure.WRITE_CATALOG_WITH_GATE,
                        DurabilityPreference.ALLOWED),
                false);
        AgentTenantPolicy tenantPolicy = new AgentTenantPolicy(
                ToolCapabilityCeiling.READ_ONLY,
                ToolExposure.READ_ONLY_CATALOG,
                null);

        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                null,
                true,
                false,
                false,
                profile,
                tenantPolicy));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.READ_ONLY);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.READ_ONLY_CATALOG);
        assertThat(planned.durabilityPreference()).isEqualTo(DurabilityPreference.ALLOWED);
    }

    @Test
    @DisplayName("tenant policy caps explicit write envelopes from callers")
    void tenantPolicyCapsExplicitWriteEnvelope() {
        AgentTenantPolicy tenantPolicy = new AgentTenantPolicy(
                ToolCapabilityCeiling.READ_ONLY,
                ToolExposure.READ_ONLY_CATALOG,
                null);

        ExecutionEnvelope planned = planner.plan(new ExecutionEnvelopePlanner.Request(
                ExecutionEnvelope.writeCatalogWithGate(),
                true,
                false,
                false,
                null,
                tenantPolicy));

        assertThat(planned.capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.READ_ONLY);
        assertThat(planned.toolExposure()).isEqualTo(ToolExposure.READ_ONLY_CATALOG);
    }
}
