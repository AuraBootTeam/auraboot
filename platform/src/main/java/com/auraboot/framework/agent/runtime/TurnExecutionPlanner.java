package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.triage.TriageBucket;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Plans the initial execution mode for a conversation turn.
 *
 * <p>This class only chooses the initial execution mode: synchronous chat,
 * named-agent chat, or durable workflow. It does not decide a domain action's
 * fate; concrete tool calls still pass through tool policy gates.
 */
@Component
public class TurnExecutionPlanner {

    public enum InitialExecutionMode {
        SYNC_AGENT_TURN,
        DURABLE_WORKFLOW,
        NAMED_AGENT_TURN
    }

    public enum DecisionReason {
        NAMED_AGENT_PROFILE,
        DURABLE_TRIAGE_SIGNAL,
        DURABLE_EXECUTION_POLICY,
        SYNC_READ_ONLY_TURN,
        SYNC_CHAT_TURN
    }

    public enum PolicySignal {
        EXPLICIT_NAMED_AGENT,
        DEFAULT_AGENT_PROFILE,
        DURABLE_TRIAGE_BUCKET,
        EXPLICIT_DURABLE_REQUEST,
        DURABLE_LIFECYCLE_SIGNAL,
        READ_ONLY_CONTEXT,
        CHAT_TRIAGE_BUCKET
    }

    public record TurnExecutionInput(String agentCode,
                                     TriageBucket triageBucket,
                                     Set<String> allowedReadOnlyTools,
                                     boolean explicitDurableRequest,
                                     boolean requiresApproval,
                                     boolean externalSideEffect,
                                     boolean batch) {

        public TurnExecutionInput {
            allowedReadOnlyTools = allowedReadOnlyTools == null ? Set.of() : Set.copyOf(allowedReadOnlyTools);
        }

        boolean readOnlyContextualAnswer() {
            return triageBucket == TriageBucket.CONTEXTUAL_ANSWER && !allowedReadOnlyTools.isEmpty();
        }

        boolean durablePolicyRequired() {
            return explicitDurableRequest || externalSideEffect || batch;
        }
    }

    public record TurnExecutionPlan(InitialExecutionMode initialMode,
                                    DecisionReason reason,
                                    String normalizedAgentCode,
                                    TriageBucket triageBucket,
                                    Set<PolicySignal> policySignals) {

        public TurnExecutionPlan {
            policySignals = policySignals == null ? Set.of() : Set.copyOf(policySignals);
        }

        public boolean durableLifecycleRequired() {
            return initialMode == InitialExecutionMode.DURABLE_WORKFLOW;
        }

        public boolean namedAgent() {
            return initialMode == InitialExecutionMode.NAMED_AGENT_TURN;
        }
    }

    public TurnExecutionPlan decide(String agentCode, TriageBucket triageBucket) {
        return decide(new TurnExecutionInput(agentCode, triageBucket, Set.of(),
                false, false, false, false));
    }

    public TurnExecutionPlan decide(TurnExecutionInput input) {
        TurnExecutionInput effective = input == null
                ? new TurnExecutionInput(null, null, Set.of(), false, false, false, false)
                : input;
        String normalizedAgentCode = normalizeAgentCode(effective.agentCode());
        if (!isDefaultAgentPath(effective.agentCode())) {
            return new TurnExecutionPlan(
                    InitialExecutionMode.NAMED_AGENT_TURN,
                    DecisionReason.NAMED_AGENT_PROFILE,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.EXPLICIT_NAMED_AGENT));
        }
        if (effective.triageBucket() == TriageBucket.ACP_RUN) {
            return new TurnExecutionPlan(
                    InitialExecutionMode.DURABLE_WORKFLOW,
                    DecisionReason.DURABLE_TRIAGE_SIGNAL,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.DEFAULT_AGENT_PROFILE, PolicySignal.DURABLE_TRIAGE_BUCKET));
        }
        if (effective.durablePolicyRequired()) {
            EnumSet<PolicySignal> signals = EnumSet.of(PolicySignal.DEFAULT_AGENT_PROFILE);
            if (effective.explicitDurableRequest()) {
                signals.add(PolicySignal.EXPLICIT_DURABLE_REQUEST);
            }
            if (effective.requiresApproval() || effective.externalSideEffect() || effective.batch()) {
                signals.add(PolicySignal.DURABLE_LIFECYCLE_SIGNAL);
            }
            return new TurnExecutionPlan(
                    InitialExecutionMode.DURABLE_WORKFLOW,
                    DecisionReason.DURABLE_EXECUTION_POLICY,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    signals);
        }
        if (effective.readOnlyContextualAnswer()) {
            return new TurnExecutionPlan(
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    DecisionReason.SYNC_READ_ONLY_TURN,
                    normalizedAgentCode,
                    effective.triageBucket(),
                    EnumSet.of(PolicySignal.DEFAULT_AGENT_PROFILE, PolicySignal.READ_ONLY_CONTEXT,
                            PolicySignal.CHAT_TRIAGE_BUCKET));
        }
        return new TurnExecutionPlan(
                InitialExecutionMode.SYNC_AGENT_TURN,
                DecisionReason.SYNC_CHAT_TURN,
                normalizedAgentCode,
                effective.triageBucket(),
                EnumSet.of(PolicySignal.DEFAULT_AGENT_PROFILE, PolicySignal.CHAT_TRIAGE_BUCKET));
    }

    /**
     * Channels that are pure knowledge Q&A and must never enter the durable/planner path — a
     * customer-facing widget cannot run execute_sql, loop over tool rounds, or demand human approval.
     * Kept in step with {@code ChatToolResolver.RAG_ONLY_CHANNELS} (the tool-stripping counterpart on
     * the sync path): both must list the same channels for the RAG-only guarantee to hold end to end.
     */
    private static final Set<String> RAG_ONLY_CHANNELS = Set.of("cs_widget");

    public static boolean isRagOnlyChannel(String channel) {
        return channel != null && RAG_ONLY_CHANNELS.contains(channel);
    }

    public static boolean isDefaultAgentPath(String agentCode) {
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
