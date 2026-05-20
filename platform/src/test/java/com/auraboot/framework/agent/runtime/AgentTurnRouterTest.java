package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.triage.TriageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentTurnRouter")
class AgentTurnRouterTest {

    private final AgentTurnRouter router = new AgentTurnRouter();

    @Test
    @DisplayName("routes aurabot ACP and ownerless contextual buckets to durable runtime")
    void routesAurabotAcpBucketsToDurableRuntime() {
        AgentTurnRouter.RuntimeDecision acp = router.decide("aurabot", TriageBucket.ACP_RUN);
        assertThat(acp.route()).isEqualTo(AgentTurnRouter.RuntimeRoute.DURABLE_RUN);
        assertThat(acp.reason()).isEqualTo(AgentTurnRouter.DecisionReason.AURABOT_DURABLE_BUCKET);
        assertThat(acp.durableLifecycleRequired()).isTrue();
        assertThat(acp.policySignals())
                .containsExactlyInAnyOrder(
                        AgentTurnRouter.PolicySignal.AURABOT_ALIAS,
                        AgentTurnRouter.PolicySignal.DURABLE_TRIAGE_BUCKET);

        assertThat(router.decide("AuraBot", TriageBucket.CONTEXTUAL_ANSWER).route())
                .as("AuraBot alias matching must be case-insensitive")
                .isEqualTo(AgentTurnRouter.RuntimeRoute.DURABLE_RUN);
    }

    @Test
    @DisplayName("routes read-only contextual answers to chat runtime")
    void routesReadOnlyContextualAnswersToChatRuntime() {
        AgentTurnRouter.RuntimeDecision contextual = router.decide(new AgentTurnRouter.RuntimePolicyInput(
                "AuraBot",
                TriageBucket.CONTEXTUAL_ANSWER,
                java.util.Set.of("schema.lookup", "record.view"),
                false,
                false,
                false,
                false));

        assertThat(contextual.route()).isEqualTo(AgentTurnRouter.RuntimeRoute.CHAT_TURN);
        assertThat(contextual.reason()).isEqualTo(AgentTurnRouter.DecisionReason.AURABOT_CONTEXTUAL_READ_ONLY);
        assertThat(contextual.policySignals())
                .contains(AgentTurnRouter.PolicySignal.READ_ONLY_CONTEXT);
    }

    @Test
    @DisplayName("routes aurabot light and absent buckets to chat runtime")
    void routesAurabotLightBucketsToChatRuntime() {
        AgentTurnRouter.RuntimeDecision light = router.decide("aurabot", TriageBucket.LIGHT_CHAT);
        assertThat(light.route()).isEqualTo(AgentTurnRouter.RuntimeRoute.CHAT_TURN);
        assertThat(light.reason()).isEqualTo(AgentTurnRouter.DecisionReason.AURABOT_LIGHT_OR_ABSENT_BUCKET);
        assertThat(light.policySignals())
                .containsExactlyInAnyOrder(
                        AgentTurnRouter.PolicySignal.AURABOT_ALIAS,
                        AgentTurnRouter.PolicySignal.CHAT_TRIAGE_BUCKET);

        assertThat(router.decide(null, null).route())
                .isEqualTo(AgentTurnRouter.RuntimeRoute.CHAT_TURN);
        assertThat(router.decide("   ", TriageBucket.LIGHT_CHAT).normalizedAgentCode()).isNull();
    }

    @Test
    @DisplayName("does not route human approval alone to durable runtime")
    void approvalAloneDoesNotRequireDurableRuntime() {
        AgentTurnRouter.RuntimeDecision decision = router.decide(new AgentTurnRouter.RuntimePolicyInput(
                "aurabot",
                TriageBucket.LIGHT_CHAT,
                java.util.Set.of(),
                false,
                true,
                false,
                false));

        assertThat(decision.route()).isEqualTo(AgentTurnRouter.RuntimeRoute.CHAT_TURN);
        assertThat(decision.reason()).isEqualTo(AgentTurnRouter.DecisionReason.AURABOT_LIGHT_OR_ABSENT_BUCKET);
    }

    @Test
    @DisplayName("routes explicit named agents to named-agent chat runtime")
    void routesNamedAgentsToNamedAgentChatRuntime() {
        AgentTurnRouter.RuntimeDecision named = router.decide("sales_agent", TriageBucket.ACP_RUN);
        assertThat(named.route()).isEqualTo(AgentTurnRouter.RuntimeRoute.NAMED_AGENT_CHAT);
        assertThat(named.reason()).isEqualTo(AgentTurnRouter.DecisionReason.NAMED_AGENT_CODE);
        assertThat(named.namedAgent()).isTrue();
        assertThat(named.policySignals())
                .containsExactly(AgentTurnRouter.PolicySignal.EXPLICIT_NAMED_AGENT);

        assertThat(router.decide("sales_agent", TriageBucket.LIGHT_CHAT).route())
                .isEqualTo(AgentTurnRouter.RuntimeRoute.NAMED_AGENT_CHAT);
    }

    @Test
    @DisplayName("keeps legacy route API as a convenience wrapper")
    void routeReturnsDecisionRouteForLegacyCallers() {
        assertThat(router.route("aurabot", TriageBucket.ACP_RUN))
                .isEqualTo(router.decide("aurabot", TriageBucket.ACP_RUN).route());
    }
}
