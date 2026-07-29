package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable identity and routing snapshot shared by synchronous and durable loops.
 *
 * <p>The envelope pins facts that must not drift during a turn/resume. Retrieved
 * chunks, tool results and model messages are runtime evidence and deliberately
 * live outside this identity envelope.
 */
public record ContextEnvelope(
        String schemaVersion,
        String turnId,
        long tenantId,
        ExecutionPrincipal principal,
        String agentCode,
        String agentReleasePid,
        String deploymentPid,
        String agentReleaseHash,
        String channel,
        String profileId,
        String channelSessionPid,
        Long conversationId,
        String triageBucket,
        Set<String> allowedReadOnlyTools,
        List<String> requestedKnowledgeBaseIds,
        Set<String> capabilityCodes,
        List<String> eligibleKnowledgeBaseIds,
        Map<String, String> memoryNamespaces,
        Map<String, String> policyVersions,
        Map<String, String> indexReleaseVersions,
        Map<String, Object> routeSignals,
        String locale,
        String timezone,
        String traceId,
        Instant deadlineAt,
        Long tokenBudget,
        Long costBudgetMicros,
        Integer maxSteps,
        String idempotencyKey,
        Instant createdAt,
        String envelopeHash
) {

    public static final String SCHEMA_VERSION = "context-envelope/v2";
    public static final String LEGACY_SCHEMA_VERSION = "context-envelope/v1";

    public ContextEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion)
                && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported ContextEnvelope schema: " + schemaVersion);
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId is required");
        }
        if (tenantId <= 0L || principal == null || tenantId != principal.tenantId()) {
            throw new IllegalArgumentException(
                    "principal tenant must match a positive envelope tenant");
        }
        allowedReadOnlyTools = allowedReadOnlyTools == null
                ? Set.of()
                : Set.copyOf(allowedReadOnlyTools);
        requestedKnowledgeBaseIds = requestedKnowledgeBaseIds == null
                ? List.of()
                : List.copyOf(requestedKnowledgeBaseIds);
        capabilityCodes = capabilityCodes == null ? Set.of() : Set.copyOf(capabilityCodes);
        eligibleKnowledgeBaseIds = eligibleKnowledgeBaseIds == null
                ? List.of()
                : List.copyOf(eligibleKnowledgeBaseIds);
        memoryNamespaces = memoryNamespaces == null
                ? Map.of()
                : Map.copyOf(memoryNamespaces);
        policyVersions = policyVersions == null ? Map.of() : Map.copyOf(policyVersions);
        indexReleaseVersions = indexReleaseVersions == null
                ? Map.of()
                : Map.copyOf(indexReleaseVersions);
        routeSignals = routeSignals == null
                ? Map.of()
                : Map.copyOf(routeSignals);
        if (tokenBudget != null && tokenBudget <= 0L) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (costBudgetMicros != null && costBudgetMicros <= 0L) {
            throw new IllegalArgumentException("costBudgetMicros must be positive");
        }
        if (maxSteps != null && maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (envelopeHash == null || envelopeHash.isBlank()) {
            throw new IllegalArgumentException("envelopeHash is required");
        }
    }

    /**
     * Secret-free canonical hash material. Kept public for replay diagnostics
     * and contract tests.
     */
    public Map<String, Object> toHashMaterial() {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", principal.type().name());
        actor.put("tenantId", principal.tenantId());
        actor.put("actorUserId", principal.actorUserId());
        actor.put("actorMemberId", principal.actorMemberId());
        actor.put("actorEmployeeId", principal.actorEmployeeId());
        actor.put("agentCode", principal.agentCode());
        actor.put("agentReleasePid", principal.agentReleasePid());
        actor.put("deploymentPid", principal.deploymentPid());
        actor.put("agentReleaseHash", principal.agentReleaseHash());
        actor.put("roles", principal.roleIds().stream().sorted().toList());

        Map<String, Object> initiator = new LinkedHashMap<>();
        initiator.put("type", principal.initiator().type().name());
        initiator.put("userId", principal.initiator().userId());
        initiator.put("memberId", principal.initiator().memberId());
        initiator.put("channel", principal.initiator().channel());

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("turnId", turnId);
        material.put("tenantId", tenantId);
        material.put("principal", actor);
        material.put("initiator", initiator);
        material.put("delegationMode", principal.delegation().mode().name());
        material.put("delegationGrantPid", principal.delegation().grantPid());
        material.put("agentCode", agentCode);
        material.put("agentReleasePid", agentReleasePid);
        material.put("deploymentPid", deploymentPid);
        material.put("agentReleaseHash", agentReleaseHash);
        material.put("channel", channel);
        material.put("profileId", profileId);
        material.put("channelSessionPid", channelSessionPid);
        material.put("conversationId", conversationId);
        material.put("triageBucket", triageBucket);
        material.put("allowedReadOnlyTools", allowedReadOnlyTools.stream().sorted().toList());
        material.put("requestedKnowledgeBaseIds", requestedKnowledgeBaseIds);
        if (SCHEMA_VERSION.equals(schemaVersion)) {
            material.put("capabilityCodes", capabilityCodes.stream().sorted().toList());
            material.put("eligibleKnowledgeBaseIds", eligibleKnowledgeBaseIds);
            material.put("memoryNamespaces", memoryNamespaces);
            material.put("policyVersions", policyVersions);
            material.put("indexReleaseVersions", indexReleaseVersions);
        }
        material.put("routeSignals", routeSignals);
        material.put("locale", locale);
        material.put("timezone", timezone);
        if (SCHEMA_VERSION.equals(schemaVersion)) {
            material.put("traceId", traceId);
            material.put("deadlineAt", deadlineAt == null ? null : deadlineAt.toString());
            material.put("tokenBudget", tokenBudget);
            material.put("costBudgetMicros", costBudgetMicros);
            material.put("maxSteps", maxSteps);
            material.put("idempotencyKey", idempotencyKey);
        }
        material.put("createdAt", createdAt.toString());
        return material;
    }
}
