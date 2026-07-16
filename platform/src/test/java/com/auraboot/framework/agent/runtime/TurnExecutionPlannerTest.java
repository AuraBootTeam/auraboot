package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.triage.TriageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TurnExecutionPlanner")
class TurnExecutionPlannerTest {

    private final TurnExecutionPlanner planner = new TurnExecutionPlanner();

    @Test
    @DisplayName("plans aurabot ACP bucket as durable workflow")
    void plansAurabotAcpBucketsAsDurableWorkflow() {
        TurnExecutionPlanner.TurnExecutionPlan acp = planner.decide("aurabot", TriageBucket.ACP_RUN);
        assertThat(acp.initialMode()).isEqualTo(TurnExecutionPlanner.InitialExecutionMode.DURABLE_WORKFLOW);
        assertThat(acp.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.DURABLE_TRIAGE_SIGNAL);
        assertThat(acp.durableLifecycleRequired()).isTrue();
        assertThat(acp.policySignals())
                .containsExactlyInAnyOrder(
                        TurnExecutionPlanner.PolicySignal.DEFAULT_AGENT_PROFILE,
                        TurnExecutionPlanner.PolicySignal.DURABLE_TRIAGE_BUCKET);
    }

    @Test
    @DisplayName("plans read-only contextual answers as synchronous agent turns")
    void plansReadOnlyContextualAnswersAsSyncAgentTurns() {
        TurnExecutionPlanner.TurnExecutionPlan contextual = planner.decide(new TurnExecutionPlanner.TurnExecutionInput(
                "AuraBot",
                TriageBucket.CONTEXTUAL_ANSWER,
                java.util.Set.of("schema.lookup", "record.view"),
                false,
                false,
                false,
                false));

        assertThat(contextual.initialMode()).isEqualTo(TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN);
        assertThat(contextual.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.SYNC_READ_ONLY_TURN);
        assertThat(contextual.policySignals())
                .contains(TurnExecutionPlanner.PolicySignal.READ_ONLY_CONTEXT);
    }

    @Test
    @DisplayName("plans contextual buckets without durable semantics as synchronous agent turns")
    void plansContextualBucketsWithoutDurableSemanticsAsSyncAgentTurns() {
        TurnExecutionPlanner.TurnExecutionPlan contextual = planner.decide("AuraBot", TriageBucket.CONTEXTUAL_ANSWER);

        assertThat(contextual.initialMode())
                .as("AuraBot alias matching must be case-insensitive")
                .isEqualTo(TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN);
        assertThat(contextual.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.SYNC_CHAT_TURN);
        assertThat(contextual.durableLifecycleRequired()).isFalse();
    }

    @Test
    @DisplayName("plans aurabot light and absent buckets as synchronous agent turns")
    void plansAurabotLightBucketsAsSyncAgentTurns() {
        TurnExecutionPlanner.TurnExecutionPlan light = planner.decide("aurabot", TriageBucket.LIGHT_CHAT);
        assertThat(light.initialMode()).isEqualTo(TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN);
        assertThat(light.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.SYNC_CHAT_TURN);
        assertThat(light.policySignals())
                .containsExactlyInAnyOrder(
                        TurnExecutionPlanner.PolicySignal.DEFAULT_AGENT_PROFILE,
                        TurnExecutionPlanner.PolicySignal.CHAT_TRIAGE_BUCKET);

        assertThat(planner.decide(null, null).initialMode())
                .isEqualTo(TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN);
        assertThat(planner.decide("   ", TriageBucket.LIGHT_CHAT).normalizedAgentCode()).isNull();
    }

    @Test
    @DisplayName("does not turn human approval alone into durable workflow")
    void approvalAloneDoesNotRequireDurableRuntime() {
        TurnExecutionPlanner.TurnExecutionPlan decision = planner.decide(new TurnExecutionPlanner.TurnExecutionInput(
                "aurabot",
                TriageBucket.LIGHT_CHAT,
                java.util.Set.of(),
                false,
                true,
                false,
                false));

        assertThat(decision.initialMode()).isEqualTo(TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN);
        assertThat(decision.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.SYNC_CHAT_TURN);
    }

    @Test
    @DisplayName("plans explicit named agents as named-agent turns")
    void plansNamedAgentsAsNamedAgentTurns() {
        TurnExecutionPlanner.TurnExecutionPlan named = planner.decide("sales_agent", TriageBucket.ACP_RUN);
        assertThat(named.initialMode()).isEqualTo(TurnExecutionPlanner.InitialExecutionMode.NAMED_AGENT_TURN);
        assertThat(named.reason()).isEqualTo(TurnExecutionPlanner.DecisionReason.NAMED_AGENT_PROFILE);
        assertThat(named.namedAgent()).isTrue();
        assertThat(named.policySignals())
                .containsExactly(TurnExecutionPlanner.PolicySignal.EXPLICIT_NAMED_AGENT);

        assertThat(planner.decide("sales_agent", TriageBucket.LIGHT_CHAT).initialMode())
                .isEqualTo(TurnExecutionPlanner.InitialExecutionMode.NAMED_AGENT_TURN);
    }

    @Test
    @DisplayName("decision reasons are execution-semantics based, not scenario names")
    void decisionReasonsDoNotEncodeBusinessScenarios() {
        assertThat(TurnExecutionPlanner.DecisionReason.values())
                .extracting(Enum::name)
                .allSatisfy(reason -> assertThat(reason)
                        .doesNotContain("AURABOT")
                        .doesNotContain("CONTEXTUAL")
                        .doesNotContain("LIGHT"));
    }

    @Test
    @DisplayName("cs_widget is a RAG-only channel; other channels and null are not")
    void csWidgetIsRagOnlyChannel() {
        assertThat(TurnExecutionPlanner.isRagOnlyChannel("cs_widget")).isTrue();
        assertThat(TurnExecutionPlanner.isRagOnlyChannel("web")).isFalse();
        assertThat(TurnExecutionPlanner.isRagOnlyChannel("im")).isFalse();
        assertThat(TurnExecutionPlanner.isRagOnlyChannel(null)).isFalse();
    }
}
