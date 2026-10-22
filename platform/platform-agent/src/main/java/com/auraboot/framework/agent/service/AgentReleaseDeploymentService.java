package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Publish/read boundary between editable Agent definitions and immutable
 * runtime releases plus tenant deployments.
 */
@Service
@RequiredArgsConstructor
public class AgentReleaseDeploymentService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record RuntimeBinding(
            String deploymentPid,
            String releasePid,
            String releaseHash,
            int releaseNo,
            Map<String, Object> releaseSpec,
            Map<String, Object> capabilityRequirements,
            List<String> knowledgeBaseIds,
            Map<String, Object> channelPolicy,
            Map<String, Object> policySnapshot) {

        public RuntimeBinding {
            releaseSpec = Map.copyOf(releaseSpec);
            capabilityRequirements = Map.copyOf(capabilityRequirements);
            knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
            channelPolicy = Map.copyOf(channelPolicy);
            policySnapshot = Map.copyOf(policySnapshot);
        }
    }

    public record PublishedRelease(
            String deploymentPid,
            String releasePid,
            String releaseHash,
            int releaseNo,
            boolean created) {
    }

    public record DeploymentPolicy(
            String deploymentPid,
            Map<String, Object> channelPolicy,
            Map<String, Object> policySnapshot) {

        public DeploymentPolicy {
            channelPolicy = channelPolicy == null
                    ? Map.of()
                    : Map.copyOf(channelPolicy);
            policySnapshot = policySnapshot == null
                    ? Map.of()
                    : Map.copyOf(policySnapshot);
        }
    }

    /**
     * Resolve the exact release/deployment used by a new turn or run.
     * Missing, suspended and revoked deployments fail closed.
     */
    public RuntimeBinding requireActive(long tenantId, String agentCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT d.pid AS deployment_pid,
                       d.knowledge_base_ids,
                       d.channel_policy,
                       d.policy_snapshot,
                       r.pid AS release_pid,
                       r.release_hash,
                       r.release_no,
                       r.release_spec,
                       r.capability_requirements
                FROM ab_agent_deployment d
                JOIN ab_agent_release r
                  ON r.pid = d.agent_release_pid
                 AND r.tenant_id = d.tenant_id
                 AND r.agent_code = d.agent_code
                JOIN ab_agent_definition a
                  ON a.tenant_id = d.tenant_id
                 AND a.agent_code = d.agent_code
                WHERE d.tenant_id = ?
                  AND d.agent_code = ?
                  AND d.status = 'active'
                  AND r.status = 'published'
                  AND a.status = 'active'
                  AND (a.deleted_flag IS NULL OR a.deleted_flag = FALSE)
                LIMIT 1
                """,
                tenantId,
                agentCode);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Agent has no active immutable release deployment: agentCode=" + agentCode);
        }
        return binding(rows.get(0));
    }

    /**
     * Publish the current draft as a new immutable release and atomically move
     * the deployment pointer. Re-publishing identical content is idempotent.
     */
    @Transactional
    public PublishedRelease publish(
            long tenantId,
            String agentDefinitionPid,
            Long actorUserId) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                tenantId + ":" + agentDefinitionPid);
        List<Map<String, Object>> definitions = jdbc.queryForList(
                """
                SELECT d.*,
                       ab_agent_release_spec(d) AS runtime_spec,
                       encode(
                           digest(ab_agent_release_spec(d)::text, 'sha256'),
                           'hex'
                       ) AS runtime_hash
                FROM ab_agent_definition d
                WHERE d.tenant_id = ?
                  AND d.pid = ?
                  AND d.status = 'active'
                  AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)
                FOR UPDATE
                """,
                tenantId,
                agentDefinitionPid);
        if (definitions.size() != 1) {
            throw new IllegalArgumentException(
                    "Active Agent definition not found: " + agentDefinitionPid);
        }
        Map<String, Object> definition = definitions.get(0);
        String agentCode = text(definition.get("agent_code"));
        Map<String, Object> spec = jsonMap(definition.get("runtime_spec"));
        String hash = text(definition.get("runtime_hash"));

        List<Map<String, Object>> same = jdbc.queryForList(
                """
                SELECT pid, release_no
                FROM ab_agent_release
                WHERE tenant_id = ? AND agent_code = ? AND release_hash = ?
                LIMIT 1
                """,
                tenantId,
                agentCode,
                hash);
        boolean created = same.isEmpty();
        String releasePid;
        int releaseNo;
        if (created) {
            Integer next = jdbc.queryForObject(
                    """
                    SELECT COALESCE(MAX(release_no), 0) + 1
                    FROM ab_agent_release
                    WHERE tenant_id = ? AND agent_code = ?
                    """,
                    Integer.class,
                    tenantId,
                    agentCode);
            releaseNo = next == null ? 1 : next;
            releasePid = UniqueIdGenerator.generate();
            Map<String, Object> requirements = capabilityRequirements(spec);
            jdbc.update(
                    """
                    INSERT INTO ab_agent_release (
                        pid, tenant_id, agent_definition_pid, agent_code,
                        release_no, release_hash, release_spec,
                        capability_requirements, status, source_updated_at,
                        published_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'published',
                            ?, CURRENT_TIMESTAMP, ?)
                    """,
                    releasePid,
                    tenantId,
                    agentDefinitionPid,
                    agentCode,
                    releaseNo,
                    hash,
                    json(spec),
                    json(requirements),
                    definition.get("updated_at"),
                    actorUserId);
        } else {
            releasePid = text(same.get(0).get("pid"));
            releaseNo = ((Number) same.get(0).get("release_no")).intValue();
            jdbc.update(
                    """
                    UPDATE ab_agent_release
                    SET status = 'published'
                    WHERE tenant_id = ? AND agent_code = ? AND pid = ?
                    """,
                    tenantId,
                    agentCode,
                    releasePid);
        }

        jdbc.update(
                """
                UPDATE ab_agent_release
                SET status = 'deprecated'
                WHERE tenant_id = ? AND agent_code = ?
                  AND pid <> ? AND status = 'published'
                """,
                tenantId,
                agentCode,
                releasePid);
        String deploymentPid = moveDeployment(
                tenantId,
                agentCode,
                definition.get("employee_id"),
                releasePid,
                spec,
                actorUserId);
        return new PublishedRelease(
                deploymentPid,
                releasePid,
                hash,
                releaseNo,
                created);
    }

    /**
     * Atomically point the deployment back to an existing immutable release.
     * The release content never changes; only lifecycle status and the stable
     * deployment pointer move.
     */
    @Transactional
    public PublishedRelease deployRelease(
            long tenantId,
            String agentDefinitionPid,
            String releasePid,
            Long actorUserId) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                tenantId + ":" + agentDefinitionPid);
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT d.agent_code, d.employee_id,
                       r.release_no, r.release_hash, r.release_spec
                FROM ab_agent_definition d
                JOIN ab_agent_release r
                  ON r.tenant_id = d.tenant_id
                 AND r.agent_definition_pid = d.pid
                 AND r.agent_code = d.agent_code
                WHERE d.tenant_id = ?
                  AND d.pid = ?
                  AND d.status = 'active'
                  AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)
                  AND r.pid = ?
                FOR UPDATE OF d, r
                """,
                tenantId,
                agentDefinitionPid,
                releasePid);
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "Agent release not found in active definition scope: " + releasePid);
        }
        Map<String, Object> row = rows.get(0);
        String agentCode = text(row.get("agent_code"));
        jdbc.update(
                """
                UPDATE ab_agent_release
                SET status = CASE WHEN pid = ? THEN 'published' ELSE 'deprecated' END
                WHERE tenant_id = ? AND agent_code = ?
                """,
                releasePid,
                tenantId,
                agentCode);
        String deploymentPid = moveDeployment(
                tenantId,
                agentCode,
                row.get("employee_id"),
                releasePid,
                jsonMap(row.get("release_spec")),
                actorUserId);
        return new PublishedRelease(
                deploymentPid,
                releasePid,
                text(row.get("release_hash")),
                ((Number) row.get("release_no")).intValue(),
                false);
    }

    public List<Map<String, Object>> listReleases(
            long tenantId,
            String agentDefinitionPid) {
        return jdbc.queryForList(
                """
                SELECT r.pid, r.release_no, r.release_hash, r.status,
                       r.capability_requirements, r.source_updated_at,
                       r.published_at, r.created_by,
                       EXISTS (
                           SELECT 1
                           FROM ab_agent_deployment d
                           WHERE d.tenant_id = r.tenant_id
                             AND d.agent_release_pid = r.pid
                             AND d.status = 'active'
                       ) AS deployed
                FROM ab_agent_release r
                WHERE r.tenant_id = ? AND r.agent_definition_pid = ?
                ORDER BY r.release_no DESC
                """,
                tenantId,
                agentDefinitionPid);
    }

    public DeploymentPolicy getDeploymentPolicy(
            long tenantId,
            String agentDefinitionPid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT dp.pid AS deployment_pid,
                       dp.channel_policy,
                       dp.policy_snapshot
                FROM ab_agent_definition ad
                JOIN ab_agent_deployment dp
                  ON dp.tenant_id = ad.tenant_id
                 AND dp.agent_code = ad.agent_code
                 AND dp.status = 'active'
                WHERE ad.tenant_id = ?
                  AND ad.pid = ?
                  AND (ad.deleted_flag IS NULL OR ad.deleted_flag = FALSE)
                LIMIT 1
                """,
                tenantId,
                agentDefinitionPid);
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "Active deployment not found for Agent: " + agentDefinitionPid);
        }
        Map<String, Object> row = rows.get(0);
        return new DeploymentPolicy(
                text(row.get("deployment_pid")),
                jsonMap(row.get("channel_policy")),
                jsonMap(row.get("policy_snapshot")));
    }

    @Transactional
    public DeploymentPolicy updateDeploymentPolicy(
            long tenantId,
            String agentDefinitionPid,
            Map<String, Object> requested,
            Long actorUserId) {
        Map<String, Object> channelPolicy =
                normalizeChannelPolicy(requested);
        int updated = jdbc.update(
                """
                UPDATE ab_agent_deployment dp
                SET channel_policy = ?::jsonb,
                    policy_snapshot = jsonb_set(
                        COALESCE(policy_snapshot, '{}'::jsonb),
                        '{invocationPolicyVersion}',
                        to_jsonb('invocation-policy/v1'::text),
                        true),
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = ?
                FROM ab_agent_definition ad
                WHERE ad.tenant_id = ?
                  AND ad.pid = ?
                  AND (ad.deleted_flag IS NULL OR ad.deleted_flag = FALSE)
                  AND dp.tenant_id = ad.tenant_id
                  AND dp.agent_code = ad.agent_code
                  AND dp.status = 'active'
                """,
                json(channelPolicy),
                actorUserId,
                tenantId,
                agentDefinitionPid);
        if (updated != 1) {
            throw new IllegalArgumentException(
                    "Active deployment not found for Agent: " + agentDefinitionPid);
        }
        return getDeploymentPolicy(tenantId, agentDefinitionPid);
    }

    /**
     * Runtime readers consume the pinned release snapshot, never the mutable
     * definition. Internal metadata is prefixed to avoid colliding with Agent fields.
     */
    public Map<String, Object> runtimeDefinition(long tenantId, String agentCode) {
        ExecutionPrincipal principal = ExecutionPrincipalContext.current()
                .filter(current -> current.tenantId() == tenantId)
                .filter(current -> current.agentCode().equals(agentCode))
                .orElse(null);
        if (principal != null) {
            return runtimeDefinition(
                    tenantId,
                    agentCode,
                    principal.agentReleasePid(),
                    principal.deploymentPid());
        }
        RuntimeBinding binding = requireActive(tenantId, agentCode);
        return runtimeDefinition(binding);
    }

    /**
     * Load an already-pinned release, including deprecated historical
     * versions. New work uses {@link #requireActive(long, String)}; only an
     * existing execution principal may keep consuming an older release.
     */
    public Map<String, Object> runtimeDefinition(
            long tenantId,
            String agentCode,
            String releasePid,
            String deploymentPid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT release_hash, release_no, release_spec
                FROM ab_agent_release
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND pid = ?
                  AND status IN ('published', 'deprecated')
                LIMIT 1
                """,
                tenantId,
                agentCode,
                releasePid);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Pinned Agent release is unavailable: releasePid=" + releasePid);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>(
                jsonMap(row.get("release_spec")));
        result.put("_deployment_pid", deploymentPid);
        result.put("_agent_release_pid", releasePid);
        result.put("_agent_release_hash", text(row.get("release_hash")));
        result.put("_agent_release_no", ((Number) row.get("release_no")).intValue());
        return Map.copyOf(result);
    }

    private Map<String, Object> runtimeDefinition(RuntimeBinding binding) {
        Map<String, Object> result = new LinkedHashMap<>(binding.releaseSpec());
        result.put("_deployment_pid", binding.deploymentPid());
        result.put("_agent_release_pid", binding.releasePid());
        result.put("_agent_release_hash", binding.releaseHash());
        result.put("_agent_release_no", binding.releaseNo());
        return Map.copyOf(result);
    }

    @Transactional
    public void bindEmployee(
            long tenantId,
            String agentCode,
            Long employeeId,
            Long actorUserId) {
        int updated = jdbc.update(
                """
                UPDATE ab_agent_deployment
                SET employee_id = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                WHERE tenant_id = ? AND agent_code = ? AND status = 'active'
                """,
                employeeId,
                actorUserId,
                tenantId,
                agentCode);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Active deployment missing while binding employee: agentCode=" + agentCode);
        }
    }

    public void setDeploymentStatus(
            long tenantId,
            String agentCode,
            String fromStatus,
            String toStatus,
            Long actorUserId) {
        int updated = jdbc.update(
                """
                UPDATE ab_agent_deployment
                SET status = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                WHERE tenant_id = ? AND agent_code = ? AND status = ?
                """,
                toStatus,
                actorUserId,
                tenantId,
                agentCode,
                fromStatus);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Deployment lifecycle transition matched no row: agentCode=" + agentCode);
        }
    }

    private String moveDeployment(
            long tenantId,
            String agentCode,
            Object employeeId,
            String releasePid,
            Map<String, Object> spec,
            Long actorUserId) {
        List<String> active = jdbc.queryForList(
                """
                SELECT pid
                FROM ab_agent_deployment
                WHERE tenant_id = ? AND agent_code = ? AND status = 'active'
                FOR UPDATE
                """,
                String.class,
                tenantId,
                agentCode);
        String tools = json(stringList(spec.get("tools")));
        String skills = json(stringList(spec.get("skills")));
        String knowledge = json(stringList(spec.get("knowledge_base_ids")));
        if (!active.isEmpty()) {
            String deploymentPid = active.get(0);
            jdbc.update(
                    """
                    UPDATE ab_agent_deployment
                    SET employee_id = ?, agent_release_pid = ?,
                        tool_grants = ?::jsonb, skill_grants = ?::jsonb,
                        knowledge_base_ids = ?::jsonb,
                        policy_snapshot = ?::jsonb,
                        updated_at = CURRENT_TIMESTAMP, updated_by = ?
                    WHERE tenant_id = ? AND pid = ? AND status = 'active'
                    """,
                    employeeId,
                    releasePid,
                    tools,
                    skills,
                    knowledge,
                    json(Map.of("source", "publish", "version", 1)),
                    actorUserId,
                    tenantId,
                    deploymentPid);
            return deploymentPid;
        }
        String deploymentPid = UniqueIdGenerator.generate();
        jdbc.update(
                """
                INSERT INTO ab_agent_deployment (
                    pid, tenant_id, agent_code, employee_id,
                    agent_release_pid, status, tool_grants, skill_grants,
                    knowledge_base_ids, memory_policy, channel_policy,
                    policy_snapshot, created_by)
                VALUES (?, ?, ?, ?, ?, 'active', ?::jsonb, ?::jsonb,
                        ?::jsonb, '{}'::jsonb, '{}'::jsonb, ?::jsonb, ?)
                """,
                deploymentPid,
                tenantId,
                agentCode,
                employeeId,
                releasePid,
                tools,
                skills,
                knowledge,
                json(Map.of("source", "publish", "version", 1)),
                actorUserId);
        return deploymentPid;
    }

    private RuntimeBinding binding(Map<String, Object> row) {
        return new RuntimeBinding(
                text(row.get("deployment_pid")),
                text(row.get("release_pid")),
                text(row.get("release_hash")),
                ((Number) row.get("release_no")).intValue(),
                jsonMap(row.get("release_spec")),
                jsonMap(row.get("capability_requirements")),
                stringList(row.get("knowledge_base_ids")),
                jsonMap(row.get("channel_policy")),
                jsonMap(row.get("policy_snapshot")));
    }

    private Map<String, Object> capabilityRequirements(Map<String, Object> spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCalling", !stringList(spec.get("tools")).isEmpty());
        result.put("retrieval", !stringList(spec.get("knowledge_base_ids")).isEmpty());
        Map<String, Object> execution = jsonMap(spec.get("execution_config"));
        result.put("thinking", Boolean.TRUE.equals(execution.get("thinking_enabled")));
        return Map.copyOf(result);
    }

    private Map<String, Object> normalizeChannelPolicy(
            Map<String, Object> requested) {
        Map<String, Object> source = requested == null ? Map.of() : requested;
        Set<String> knownChannels = Set.of(
                "web", "im_group", "schedule", "event", "webhook", "api");
        Set<String> knownTypes = Set.of(
                "human", "system", "schedule", "event", "agent_handoff");
        List<String> channels = stringList(source.get("allowedChannels")).stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .filter(knownChannels::contains)
                .distinct()
                .sorted()
                .toList();
        List<String> types = stringList(source.get("allowedInitiatorTypes")).stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .filter(knownTypes::contains)
                .distinct()
                .sorted()
                .toList();
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("version", "invocation-policy/v1");
        normalized.put("allowedChannels", channels);
        normalized.put("allowedInitiatorTypes", types);
        normalized.put("allowedUserIds", positiveLongList(source.get("allowedUserIds")));
        normalized.put("allowedMemberIds", positiveLongList(source.get("allowedMemberIds")));
        normalized.put("allowedRoleIds", positiveLongList(source.get("allowedRoleIds")));
        return Map.copyOf(normalized);
    }

    private List<Long> positiveLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    try {
                        return item instanceof Number number
                                ? number.longValue()
                                : Long.parseLong(String.valueOf(item));
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(item -> item > 0L)
                .distinct()
                .sorted()
                .limit(200)
                .toList();
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Agent release JSON", e);
        }
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) {
            return List.of();
        }
        if (raw.startsWith("[")) {
            try {
                return objectMapper.readValue(
                        raw,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid Agent release list", e);
            }
        }
        List<String> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize Agent release JSON", e);
        }
    }

    private String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Agent release/deployment identifier is missing");
        }
        return String.valueOf(value);
    }
}
