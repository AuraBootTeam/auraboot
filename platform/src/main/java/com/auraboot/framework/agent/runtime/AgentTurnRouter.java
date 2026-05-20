package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.triage.TriageBucket;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic runtime router for a conversation turn.
 *
 * <p>The router owns platform control flow. LLM triage may supply a
 * {@link TriageBucket}, but this class decides which runtime lifecycle handles
 * the turn. Named-agent turns keep the named-agent chat path. AuraBot
 * {@code ACP_RUN} and durable policy signals use the durable runtime; read-only
 * contextual answers and {@code LIGHT_CHAT} use the chat runtime.
 */
@Component
public class AgentTurnRouter {

    public enum RuntimeRoute {
        CHAT_TURN,
        DURABLE_RUN,
        NAMED_AGENT_CHAT
    }

    public enum DecisionReason {
        NAMED_AGENT_CODE,
        AURABOT_DURABLE_BUCKET,
        AURABOT_DURABLE_POLICY,
        AURABOT_CONTEXTUAL_READ_ONLY,
        AURABOT_LIGHT_OR_ABSENT_BUCKET
    }

    public enum PolicySignal {
        EXPLICIT_NAMED_AGENT,
        AURABOT_ALIAS,
        DURABLE_TRIAGE_BUCKET,
        EXPLICIT_DURABLE_REQUEST,
        DURABLE_LIFECYCLE_SIGNAL,
        READ_ONLY_CONTEXT,
        CHAT_TRIAGE_BUCKET
    }

    public record RuntimePolicyInput(String agentCode,
                                     TriageBucket triageBucket,
                                     Set<String> allowedReadOnlyTools,
                                     boolean explicitDurableRequest,
                                     boolean requiresApproval,
                                     boolean externalSideEffect,
                                     boolean batch) {

        public RuntimePolicyInput {
            allowedReadOnlyTools = allowedReadOnlyTools == null ? Set.of() : Set.copyOf(allowedReadOnlyTools);
        }

        boolean readOnlyContextualAnswer() {
            return triageBucket == TriageBucket.CONTEXTUAL_ANSWER && !allowedReadOnlyTools.isEmpty();
        }

        boolean durablePolicyRequired() {
            return explicitDurableRequest || externalSideEffect || batch;
        }
    }

    public record RuntimeDecision(RuntimeRoute route,
                                  DecisionReason reason,
                                  String normalizedAgentCode,
                                  TriageBucket triageBucket,
                                  Set<PolicySignal> policySignals) {

        public RuntimeDecision {
            policySignals = policySignals == null ? Set.of() : Set.copyOf(policySignals);
        }

        public boolean durableLifecycleRequired() {
            return route == RuntimeRoute.DURABLE_RUN;
        }

        public boolean namedAgent() {
            return route == RuntimeRoute.NAMED_AGENT_CHAT;
        }
    }

    public RuntimeRoute route(String agentCode, TriageBucket triageBucket) {
        return decide(agentCode, triageBucket).route();
    }

    public RuntimeDecision decide(String agentCode, TriageBucket triageBucket) {
        return decide(new RuntimePolicyInput(agentCode, triageBucket, Set.of(),
                false, false, false, false));
    }

    public RuntimeDecision decide(RuntimePolicyInput input) {
        RuntimePolicyInput effective = input == null
                ? new RuntimePolicyInput(null, null, Set.of(), false, false, false, false)
                : input;
        String normalizedAgentCode = normalizeAgentCode(effective.agentCode());
        if (!isAuraBotPath(effective.agentCode())) {
            return new RuntimeDecision(
                    RuntimeRoute.NAMED_AGENT_CHAT,
                    DecisionReason.NAMED_AGENT_CODE,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.EXPLICIT_NAMED_AGENT));
        }
        if (effective.triageBucket() == TriageBucket.ACP_RUN) {
            return new RuntimeDecision(
                    RuntimeRoute.DURABLE_RUN,
                    DecisionReason.AURABOT_DURABLE_BUCKET,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.AURABOT_ALIAS, PolicySignal.DURABLE_TRIAGE_BUCKET));
        }
        if (effective.durablePolicyRequired()) {
            EnumSet<PolicySignal> signals = EnumSet.of(PolicySignal.AURABOT_ALIAS);
            if (effective.explicitDurableRequest()) {
                signals.add(PolicySignal.EXPLICIT_DURABLE_REQUEST);
            }
            if (effective.requiresApproval() || effective.externalSideEffect() || effective.batch()) {
                signals.add(PolicySignal.DURABLE_LIFECYCLE_SIGNAL);
            }
            return new RuntimeDecision(
                    RuntimeRoute.DURABLE_RUN,
                    DecisionReason.AURABOT_DURABLE_POLICY,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    signals);
        }
        if (effective.readOnlyContextualAnswer()) {
            return new RuntimeDecision(
                    RuntimeRoute.CHAT_TURN,
                    DecisionReason.AURABOT_CONTEXTUAL_READ_ONLY,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.AURABOT_ALIAS, PolicySignal.READ_ONLY_CONTEXT,
                            PolicySignal.CHAT_TRIAGE_BUCKET));
        }
        if (effective.triageBucket() == TriageBucket.CONTEXTUAL_ANSWER) {
            return new RuntimeDecision(
                    RuntimeRoute.DURABLE_RUN,
                    DecisionReason.AURABOT_DURABLE_BUCKET,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.AURABOT_ALIAS, PolicySignal.DURABLE_TRIAGE_BUCKET));
        }
        return new RuntimeDecision(
                RuntimeRoute.CHAT_TURN,
                DecisionReason.AURABOT_LIGHT_OR_ABSENT_BUCKET,
                normalizedAgentCode,
                effective.triageBucket(),
                EnumSet.of(PolicySignal.AURABOT_ALIAS, PolicySignal.CHAT_TRIAGE_BUCKET));
    }

    public static boolean isAuraBotPath(String agentCode) {
        String normalized = normalizeAgentCode(agentCode);
        return normalized == null || "aurabot".equals(normalized);
    }

    private static String normalizeAgentCode(String agentCode) {
        if (agentCode == null || agentCode.isBlank()) {
            return null;
        }
        return agentCode.trim().toLowerCase(Locale.ROOT);
    }
}
