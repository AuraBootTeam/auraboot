package com.auraboot.framework.agent.authorization;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime authorization gate for tool / skill / subagent calls.
 *
 * <p>Decoupled from SkillEngine API per Q10/Q11 of conversation-turn-service-design.
 * Caller responsibility:
 * <ul>
 *   <li>{@code StepLoopService} / {@code ToolLoopService} → {@link #authorizeIncremental}
 *   <li>Stage 4 PLAN of run lifecycle → {@link #authorizePlan} once per turn
 *   <li>{@code SubagentDispatcher} → {@link #authorizeIncremental} for delegated capability
 * </ul>
 *
 * <p>Contract: enterprise/docs/agent/contracts/runtime-authorization.md
 */
public interface RuntimeAuthorizationService {

    /** Plan-time evaluation: pre-batch grants for the whole turn. */
    PlanAuthorization authorizePlan(PlanAuthorizationInput input);

    /** Runtime per-call evaluation; reuses plan grants when {@code planHash} matches. */
    IncrementalAuthorization authorizeIncremental(ToolCallIntent intent);

    record PlanAuthorizationInput(
            long tenantId,
            long userId,
            String runId,
            String bifId,
            String planHash,
            List<PlannedCall> plannedCalls,
            String channelSessionId
    ) {}

    record PlannedCall(
            String skillCode,
            String toolRef,
            Set<EffectClass> requiredEffects,
            BlastRadius blastRadius,
            String argHashPattern,
            Map<String, Object> argPreview
    ) {}

    record PlanAuthorization(
            String planHash,
            List<GrantScope> preAuthorizedGrants,
            List<GrantScope> requiresApprovalGrants,
            Set<EffectClass> forbiddenEffects,
            Map<EffectClass, String> rejectedBy,
            EffectLifetime defaultLifetime
    ) {}

    record ToolCallIntent(
            long tenantId,
            String runId,
            Integer stepIndex,
            Integer toolCallIndex,
            String toolRef,
            String skillCode,
            String currentPlanHash,
            Set<EffectClass> requiredEffects,
            BlastRadius blastRadius,
            String argHash,
            Map<String, Object> argPreview,
            String channelSessionId
    ) {}

    record IncrementalAuthorization(
            boolean granted,
            boolean requireApproval,
            String approvalRequestId,
            String rejectedReason,
            String rejectedBy
    ) {
        public static IncrementalAuthorization grant() {
            return new IncrementalAuthorization(true, false, null, null, null);
        }
        public static IncrementalAuthorization reject(String reason, String by) {
            return new IncrementalAuthorization(false, false, null, reason, by);
        }
        public static IncrementalAuthorization needsApproval(String approvalRequestId) {
            return new IncrementalAuthorization(true, true, approvalRequestId, null, null);
        }
    }
}
