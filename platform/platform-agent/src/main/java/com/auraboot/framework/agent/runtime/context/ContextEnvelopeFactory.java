package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.runtime.policy.RiskScale;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService;
import com.auraboot.framework.agent.util.CanonicalJsonHasher;
import com.auraboot.framework.rag.service.KnowledgeBaseAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single compiler for immutable turn identity/routing envelopes.
 */
@Component
public class ContextEnvelopeFactory {

    private static final Set<String> ROUTE_SIGNAL_ALLOWLIST = Set.of(
            "explicitDurableRequest",
            "durableWorkflow",
            "durable",
            "requiresApproval",
            "externalSideEffect",
            "batch",
            "traceId",
            "deadlineAt",
            "deadlineMs",
            "tokenBudget",
            "costBudgetMicros",
            "maxSteps",
            "idempotencyKey");

    @Autowired(required = false)
    private AgentReleaseDeploymentService releaseDeploymentService;

    @Autowired(required = false)
    private KnowledgeBaseAccessPolicy knowledgeBaseAccessPolicy;

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    public ContextEnvelope compile(CompileRequest request) {
        if (request == null || request.principal() == null) {
            throw new IllegalArgumentException(
                    "ContextEnvelope compile request and principal are required");
        }
        List<String> knowledgeBaseIds =
                normalizeKnowledgeBaseIds(request.requestedKnowledgeBaseIds());
        Map<String, Object> routeSignals = safeRouteSignals(request.routeSignals());
        RuntimeSnapshot runtime = request.resolveRuntimeSnapshot()
                ? resolveRuntimeSnapshot(
                        request.principal(),
                        knowledgeBaseIds,
                        request.allowedReadOnlyTools())
                : fallbackRuntimeSnapshot(
                        request.principal(),
                        knowledgeBaseIds,
                        request.allowedReadOnlyTools());
        Instant deadlineAt = instantSignal(routeSignals, "deadlineAt");
        if (deadlineAt == null) {
            Long deadlineMs = positiveLong(routeSignals.get("deadlineMs"));
            deadlineAt = deadlineMs == null
                    ? null
                    : request.createdAt().plusMillis(deadlineMs);
        }
        ContextEnvelope withoutHash = new ContextEnvelope(
                ContextEnvelope.SCHEMA_VERSION,
                request.turnId(),
                request.principal().tenantId(),
                request.principal(),
                request.principal().agentCode(),
                request.principal().agentReleasePid(),
                request.principal().deploymentPid(),
                request.principal().agentReleaseHash(),
                request.channel(),
                request.profileId(),
                request.channelSessionPid(),
                request.conversationId(),
                request.triageBucket(),
                request.allowedReadOnlyTools(),
                knowledgeBaseIds,
                runtime.capabilityCodes(),
                runtime.eligibleKnowledgeBaseIds(),
                memoryNamespaces(request),
                runtime.policyVersions(),
                runtime.indexReleaseVersions(),
                routeSignals,
                request.locale(),
                request.timezone(),
                textSignal(routeSignals, "traceId"),
                deadlineAt,
                positiveLong(routeSignals.get("tokenBudget")),
                positiveLong(routeSignals.get("costBudgetMicros")),
                positiveInteger(routeSignals.get("maxSteps")),
                textSignal(routeSignals, "idempotencyKey"),
                request.createdAt(),
                "__pending__");
        String hash = CanonicalJsonHasher.sha256Canonical(
                withoutHash.toHashMaterial());
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException("Could not hash ContextEnvelope");
        }
        return new ContextEnvelope(
                withoutHash.schemaVersion(),
                withoutHash.turnId(),
                withoutHash.tenantId(),
                withoutHash.principal(),
                withoutHash.agentCode(),
                withoutHash.agentReleasePid(),
                withoutHash.deploymentPid(),
                withoutHash.agentReleaseHash(),
                withoutHash.channel(),
                withoutHash.profileId(),
                withoutHash.channelSessionPid(),
                withoutHash.conversationId(),
                withoutHash.triageBucket(),
                withoutHash.allowedReadOnlyTools(),
                withoutHash.requestedKnowledgeBaseIds(),
                withoutHash.capabilityCodes(),
                withoutHash.eligibleKnowledgeBaseIds(),
                withoutHash.memoryNamespaces(),
                withoutHash.policyVersions(),
                withoutHash.indexReleaseVersions(),
                withoutHash.routeSignals(),
                withoutHash.locale(),
                withoutHash.timezone(),
                withoutHash.traceId(),
                withoutHash.deadlineAt(),
                withoutHash.tokenBudget(),
                withoutHash.costBudgetMicros(),
                withoutHash.maxSteps(),
                withoutHash.idempotencyKey(),
                withoutHash.createdAt(),
                hash);
    }

    /**
     * Verifies that a deserialized/persisted envelope still matches its
     * canonical secret-free hash material.
     */
    public boolean verify(ContextEnvelope envelope) {
        if (envelope == null || envelope.envelopeHash() == null) {
            return false;
        }
        String expected = CanonicalJsonHasher.sha256Canonical(
                envelope.toHashMaterial());
        return envelope.envelopeHash().equals(expected);
    }

    private List<String> normalizeKnowledgeBaseIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(id.trim());
            }
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private Map<String, Object> safeRouteSignals(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : ROUTE_SIGNAL_ALLOWLIST) {
            if (raw.containsKey(key)) {
                Object value = raw.get(key);
                if (value instanceof Boolean
                        || value instanceof Number
                        || value instanceof String) {
                    safe.put(key, value);
                }
            }
        }
        return Map.copyOf(safe);
    }

    private RuntimeSnapshot resolveRuntimeSnapshot(
            ExecutionPrincipal principal,
            List<String> requestedKnowledgeBaseIds,
            Set<String> allowedReadOnlyTools) {
        Set<String> capabilities = new LinkedHashSet<>();
        if (allowedReadOnlyTools != null) {
            allowedReadOnlyTools.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .map(value -> "tool:" + value)
                    .forEach(capabilities::add);
        }
        List<String> boundKnowledgeBaseIds = requestedKnowledgeBaseIds;
        Map<String, String> policyVersions = new LinkedHashMap<>();
        policyVersions.put("agentRelease", principal.agentReleaseHash());
        policyVersions.put("deployment", principal.deploymentPid());
        policyVersions.put("riskScale", RiskScale.VERSION);
        policyVersions.put("invocationPolicy", "invocation-policy/v1");

        if (releaseDeploymentService != null) {
            Map<String, Object> runtime = releaseDeploymentService.runtimeDefinition(
                    principal.tenantId(),
                    principal.agentCode(),
                    principal.agentReleasePid(),
                    principal.deploymentPid());
            stringList(runtime.get("tools")).stream()
                    .map(value -> "tool:" + value)
                    .forEach(capabilities::add);
            stringList(runtime.get("skills")).stream()
                    .map(value -> "skill:" + value)
                    .forEach(capabilities::add);
            boundKnowledgeBaseIds = normalizeKnowledgeBaseIds(
                    stringList(runtime.get("knowledge_base_ids")));
        }

        List<String> eligible = effectiveKnowledgeBaseIds(
                requestedKnowledgeBaseIds, boundKnowledgeBaseIds);
        if (!eligible.isEmpty() && knowledgeBaseAccessPolicy != null) {
            List<String> requestedEligible = eligible;
            eligible = ExecutionPrincipalContext.callAs(
                    principal,
                    () -> knowledgeBaseAccessPolicy.resolveReadable(
                            principal.tenantId(), requestedEligible));
        }
        eligible.forEach(value -> capabilities.add("knowledge:" + value));
        return new RuntimeSnapshot(
                Set.copyOf(capabilities),
                List.copyOf(eligible),
                Map.copyOf(policyVersions),
                activeIndexReleases(principal.tenantId(), eligible));
    }

    private RuntimeSnapshot fallbackRuntimeSnapshot(
            ExecutionPrincipal principal,
            List<String> knowledgeBaseIds,
            Set<String> allowedReadOnlyTools) {
        Set<String> capabilities = new LinkedHashSet<>();
        if (allowedReadOnlyTools != null) {
            allowedReadOnlyTools.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .map(value -> "tool:" + value)
                    .forEach(capabilities::add);
        }
        knowledgeBaseIds.forEach(value -> capabilities.add("knowledge:" + value));
        return new RuntimeSnapshot(
                Set.copyOf(capabilities),
                List.copyOf(knowledgeBaseIds),
                Map.of(
                        "agentRelease", principal.agentReleaseHash(),
                        "deployment", principal.deploymentPid(),
                        "riskScale", RiskScale.VERSION,
                        "invocationPolicy", "invocation-policy/v1"),
                Map.of());
    }

    private List<String> effectiveKnowledgeBaseIds(
            List<String> requested,
            List<String> bound) {
        List<String> requestedIds = normalizeKnowledgeBaseIds(requested);
        List<String> boundIds = normalizeKnowledgeBaseIds(bound);
        if (requestedIds.isEmpty()) {
            return boundIds;
        }
        if (boundIds.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = Set.copyOf(boundIds);
        return requestedIds.stream().filter(allowed::contains).toList();
    }

    private Map<String, String> activeIndexReleases(long tenantId, List<String> kbIds) {
        if (jdbc == null || kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(
                ",", java.util.Collections.nCopies(kbIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.addAll(kbIds);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pid, active_index_release_pid FROM ab_knowledge_base "
                        + "WHERE tenant_id = ? AND pid IN (" + placeholders + ") "
                        + "AND status = 'active' "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                params.toArray());
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object releasePid = row.get("active_index_release_pid");
            if (releasePid != null && !String.valueOf(releasePid).isBlank()) {
                result.put(String.valueOf(row.get("pid")), String.valueOf(releasePid));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, String> memoryNamespaces(CompileRequest request) {
        ExecutionPrincipal principal = request.principal();
        Map<String, String> namespaces = new LinkedHashMap<>();
        namespaces.put("working", "turn:" + request.turnId());
        namespaces.put("actor", "member:" + principal.actorMemberId());
        namespaces.put("user", "user:" + (
                principal.initiator().userId() != null
                        ? principal.initiator().userId()
                        : principal.actorUserId()));
        namespaces.put(
                "employee",
                principal.actorEmployeeId() != null
                        ? "employee:" + principal.actorEmployeeId()
                        : "agent:" + principal.agentCode());
        if (request.conversationId() != null) {
            namespaces.put("conversation", "conversation:" + request.conversationId());
        }
        if (request.channelSessionPid() != null && !request.channelSessionPid().isBlank()) {
            namespaces.put("session", "session:" + request.channelSessionPid());
        }
        return Map.copyOf(namespaces);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private String textSignal(Map<String, Object> signals, String key) {
        Object value = signals.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.length() <= 256 ? text : text.substring(0, 256);
    }

    private Instant instantSignal(Map<String, Object> signals, String key) {
        String value = textSignal(signals, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 instant");
        }
    }

    private Long positiveLong(Object value) {
        if (value == null) {
            return null;
        }
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("runtime budget must be numeric", e);
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException("runtime budget must be positive");
        }
        return parsed;
    }

    private Integer positiveInteger(Object value) {
        Long parsed = positiveLong(value);
        if (parsed == null) {
            return null;
        }
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxSteps is too large");
        }
        return parsed.intValue();
    }

    private record RuntimeSnapshot(
            Set<String> capabilityCodes,
            List<String> eligibleKnowledgeBaseIds,
            Map<String, String> policyVersions,
            Map<String, String> indexReleaseVersions) {
    }

    public record CompileRequest(
            String turnId,
            ExecutionPrincipal principal,
            String channel,
            String profileId,
            String channelSessionPid,
            Long conversationId,
            String triageBucket,
            Set<String> allowedReadOnlyTools,
            List<String> requestedKnowledgeBaseIds,
            Map<String, Object> routeSignals,
            String locale,
            String timezone,
            Instant createdAt,
            boolean resolveRuntimeSnapshot
    ) {

        public CompileRequest(
                String turnId,
                ExecutionPrincipal principal,
                String channel,
                String profileId,
                String channelSessionPid,
                Long conversationId,
                String triageBucket,
                Set<String> allowedReadOnlyTools,
                List<String> requestedKnowledgeBaseIds,
                Map<String, Object> routeSignals,
                String locale,
                String timezone,
                Instant createdAt) {
            this(
                    turnId,
                    principal,
                    channel,
                    profileId,
                    channelSessionPid,
                    conversationId,
                    triageBucket,
                    allowedReadOnlyTools,
                    requestedKnowledgeBaseIds,
                    routeSignals,
                    locale,
                    timezone,
                    createdAt,
                    false);
        }
    }
}
