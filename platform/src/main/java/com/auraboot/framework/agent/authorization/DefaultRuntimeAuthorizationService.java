package com.auraboot.framework.agent.authorization;

import com.auraboot.framework.agent.observability.AgentRuntimeObservabilityService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal viable {@link RuntimeAuthorizationService} implementation.
 *
 * <p><b>Scope</b>: this default implementation grants every requested effect
 * for plan and incremental decisions and persists the audit row to
 * {@code ab_agent_authorization_decision}. The 5-input-source synthesis
 * (user permissions / tenant policy / channel ACL / capability default /
 * runtime downgrade) and the principle-of-least-privilege merge are
 * <b>not</b> implemented here; they are deferred to a policy-engine
 * implementation that swaps this bean by Phase B caller emergence.
 *
 * <p>Why this is still useful in Phase B prep:
 * <ul>
 *   <li>Interface contract is exercisable end-to-end (callers can wire up)
 *   <li>Audit table receives every decision, enabling backfill of historical
 *       records once the policy engine arrives
 *   <li>{@link GrantScope#matches(RuntimeAuthorizationService.ToolCallIntent)}
 *       is real and tested, so plan/incremental sharing works
 * </ul>
 *
 * <p>Caller migration to a stricter impl is a single bean-replacement; the
 * SPI is stable.
 */
@Slf4j
@Service
public class DefaultRuntimeAuthorizationService implements RuntimeAuthorizationService {

    private static final String INSERT_DECISION = """
            INSERT INTO ab_agent_authorization_decision (
                pid, tenant_id, run_id, step_index, tool_call_index,
                decision_kind, tool_ref, skill_code, arg_hash, blast_radius,
                requested_effects, granted_effects, rejected_effects,
                plan_hash, grant_scope,
                policy_id, policy_version, decision_reason,
                require_approval, approval_id, session_scope_key,
                decision_at
            )
            VALUES (?, ?, ?, ?, ?,
                    ?::varchar, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb,
                    ?, ?::jsonb,
                    ?, ?, ?,
                    ?, ?, ?,
                    CURRENT_TIMESTAMP)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentRuntimeObservabilityService observabilityService;

    public DefaultRuntimeAuthorizationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlanAuthorization authorizePlan(PlanAuthorizationInput input) {
        List<GrantScope> grants = new ArrayList<>();
        Set<EffectClass> allEffects = new HashSet<>();

        if (input.plannedCalls() != null) {
            for (PlannedCall call : input.plannedCalls()) {
                if (call.requiredEffects() == null) continue;
                for (EffectClass effect : call.requiredEffects()) {
                    allEffects.add(effect);
                    grants.add(new GrantScope(
                            effect,
                            call.toolRef(),
                            call.skillCode(),
                            call.blastRadius(),
                            call.argHashPattern(),
                            EffectLifetime.PER_TURN,
                            "default-policy",
                            1));
                }
            }
        }

        persistDecision(
                input.tenantId(), input.runId(), null, null,
                "plan", null, null, null, null,
                allEffects, allEffects, Map.of(),
                input.planHash(), null,
                "default-policy", 1, "default impl: all effects granted",
                false, null,
                buildSessionScopeKey(input.tenantId(), input.userId(), null, input.channelSessionId()));
        recordAuthorizationDecision("plan", "granted");

        return new PlanAuthorization(
                input.planHash(),
                grants,
                List.of(),
                Set.of(),
                Map.of(),
                EffectLifetime.PER_TURN);
    }

    @Override
    public IncrementalAuthorization authorizeIncremental(ToolCallIntent intent) {
        Set<EffectClass> requested = intent.requiredEffects() != null
                ? intent.requiredEffects()
                : Set.of();

        persistDecision(
                intent.tenantId(), intent.runId(), intent.stepIndex(), intent.toolCallIndex(),
                "incremental", intent.toolRef(), intent.skillCode(),
                intent.argHash(),
                intent.blastRadius() != null ? intent.blastRadius().name() : null,
                requested, requested, Map.of(),
                intent.currentPlanHash(), null,
                "default-policy", 1, "default impl: granted",
                false, null,
                buildSessionScopeKey(intent.tenantId(), null, null, intent.channelSessionId()));
        recordAuthorizationDecision("incremental", "granted");

        return IncrementalAuthorization.grant();
    }

    private void persistDecision(
            long tenantId, String runId, Integer stepIndex, Integer toolCallIndex,
            String decisionKind, String toolRef, String skillCode, String argHash, String blastRadius,
            Set<EffectClass> requestedEffects, Set<EffectClass> grantedEffects,
            Map<EffectClass, String> rejectedEffects,
            String planHash, GrantScope grantScope,
            String policyId, Integer policyVersion, String decisionReason,
            boolean requireApproval, String approvalId, String sessionScopeKey
    ) {
        String pid = UniqueIdGenerator.generate();
        try {
            jdbcTemplate.update(INSERT_DECISION,
                    pid, tenantId, runId, stepIndex, toolCallIndex,
                    decisionKind, toolRef, skillCode, argHash, blastRadius,
                    serializeEffects(requestedEffects),
                    serializeEffects(grantedEffects),
                    serializeRejected(rejectedEffects),
                    planHash,
                    grantScope == null ? null : objectMapper.writeValueAsString(grantScope),
                    policyId, policyVersion, decisionReason,
                    requireApproval, approvalId, sessionScopeKey);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize authorization decision pid={}: {}", pid, e.getMessage());
        }
    }

    private String serializeEffects(Set<EffectClass> effects) {
        if (effects == null || effects.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (EffectClass e : effects) {
            if (!first) sb.append(',');
            sb.append('"').append(e.name()).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }

    private String serializeRejected(Map<EffectClass, String> rejected) {
        if (rejected == null || rejected.isEmpty()) return null;
        Map<String, String> stringKeys = new HashMap<>();
        rejected.forEach((k, v) -> stringKeys.put(k.name(), v));
        try {
            return objectMapper.writeValueAsString(stringKeys);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize rejected effects: {}", e.getMessage());
            return null;
        }
    }

    private String buildSessionScopeKey(long tenantId, Long userId, String profileId, String channelSessionId) {
        if (channelSessionId == null) return null;
        return tenantId + ":" + (userId == null ? "" : userId)
                + ":" + (profileId == null ? "" : profileId)
                + ":" + channelSessionId;
    }

    private void recordAuthorizationDecision(String kind, String decision) {
        if (observabilityService != null) {
            observabilityService.recordAuthorizationDecision(kind, decision);
        }
    }
}
