package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Memory Consolidation Service — implements OpenClaw-inspired memory lifecycle.
 *
 * <p>Lifecycle tiers (stored in {@code ab_agent_memory.category}):
 * <ul>
 *   <li>{@code session} — ephemeral, created during a single agent run</li>
 *   <li>{@code user}    — promoted from session when importance crosses a threshold</li>
 *   <li>{@code agent}   — long-term knowledge owned by the agent itself</li>
 * </ul>
 *
 * <p>User preferences and context are persisted in {@code ab_agent_user_profile}.
 *
 * <p>Design constraint: DB row is the source of truth.
 * Vector index and prompt cache are derived artifacts, not authoritative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryConsolidationService {

    /** Category value for session-scoped ephemeral memories. */
    public static final String CATEGORY_SESSION = "session";

    /** Category value for user-level promoted memories. */
    public static final String CATEGORY_USER = "user";

    /** Category value for agent-level long-term memories. */
    public static final String CATEGORY_AGENT = "agent";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Session → User promotion
    // =========================================================================

    /**
     * Promote session-scoped memories whose importance meets or exceeds the threshold
     * to user-level by updating {@code category = 'user'}.
     *
     * @param tenantId           tenant scope
     * @param agentCode          agent identifier (maps to {@code memory_agent_id})
     * @param importanceThreshold minimum importance score required for promotion (inclusive)
     * @return number of memories promoted
     */
    public int promoteSessionMemories(Long tenantId, String agentCode, int importanceThreshold) {
        int promoted = jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET category = '" + CATEGORY_USER + "', updated_at = NOW() "
                + "WHERE tenant_id = ? "
                + "  AND memory_agent_id = ? "
                + "  AND category = '" + CATEGORY_SESSION + "' "
                + "  AND importance >= ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode, importanceThreshold);

        if (promoted > 0) {
            log.info("Consolidated {} session memories → user level for agent {} in tenant {}",
                    promoted, agentCode, tenantId);
        }
        return promoted;
    }

    // =========================================================================
    // Session memory decay
    // =========================================================================

    /**
     * Decay session memory importance by {@code decayAmount}.
     * Memories whose importance drops to or below zero are soft-deleted.
     *
     * <p>Two-phase operation:
     * <ol>
     *   <li>Decrement {@code importance} for all active session memories.</li>
     *   <li>Soft-delete memories where {@code importance <= 0}.</li>
     * </ol>
     *
     * @param tenantId    tenant scope
     * @param agentCode   agent identifier
     * @param decayAmount amount to subtract from importance (must be positive)
     * @return total memories soft-deleted due to zero/negative importance
     */
    public int decaySessionMemories(Long tenantId, String agentCode, int decayAmount) {
        if (decayAmount <= 0) {
            throw new IllegalArgumentException("decayAmount must be positive, got: " + decayAmount);
        }

        // Phase 1: reduce importance
        jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET importance = importance - ?, updated_at = NOW() "
                + "WHERE tenant_id = ? "
                + "  AND memory_agent_id = ? "
                + "  AND category = '" + CATEGORY_SESSION + "' "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                decayAmount, tenantId, agentCode);

        // Phase 2: soft-delete exhausted memories
        int deleted = jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET deleted_flag = TRUE, updated_at = NOW() "
                + "WHERE tenant_id = ? "
                + "  AND memory_agent_id = ? "
                + "  AND category = '" + CATEGORY_SESSION + "' "
                + "  AND importance <= 0 "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode);

        if (deleted > 0) {
            log.info("Decayed and archived {} session memories for agent {} in tenant {}",
                    deleted, agentCode, tenantId);
        }
        return deleted;
    }

    // =========================================================================
    // User profile upsert
    // =========================================================================

    /**
     * Create or update the AI-learned user profile in {@code ab_agent_user_profile}.
     *
     * <p>Uses PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE} semantics so the
     * operation is safe to call repeatedly without duplicates.
     *
     * @param tenantId        tenant scope
     * @param userId          the platform user whose profile is being updated
     * @param communication   communication style/preference map (nullable — not overwritten if null)
     * @param roleContext     role and responsibility context map (nullable — not overwritten if null)
     * @param preferences     general preferences map (nullable — not overwritten if null)
     * @param decisionPatterns free-text description of observed decision patterns (nullable)
     */
    public void upsertUserProfile(Long tenantId, Long userId,
                                   Map<String, Object> communication,
                                   Map<String, Object> roleContext,
                                   Map<String, Object> preferences,
                                   String decisionPatterns) {
        String pid = UniqueIdGenerator.generate();
        String communicationJson = toJson(communication);
        String roleContextJson = toJson(roleContext);
        String preferencesJson = toJson(preferences);

        jdbcTemplate.update(
                "INSERT INTO ab_agent_user_profile "
                + "  (pid, tenant_id, user_id, communication, role_context, preferences, decision_patterns, "
                + "   created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, NOW(), NOW(), FALSE) "
                + "ON CONFLICT ON CONSTRAINT uq_ab_agent_user_profile_user DO UPDATE SET "
                + "  communication     = COALESCE(EXCLUDED.communication, ab_agent_user_profile.communication), "
                + "  role_context      = COALESCE(EXCLUDED.role_context, ab_agent_user_profile.role_context), "
                + "  preferences       = COALESCE(EXCLUDED.preferences, ab_agent_user_profile.preferences), "
                + "  decision_patterns = COALESCE(EXCLUDED.decision_patterns, ab_agent_user_profile.decision_patterns), "
                + "  updated_at        = NOW()",
                pid, tenantId, userId,
                communicationJson, roleContextJson, preferencesJson, decisionPatterns);

        log.debug("Upserted user profile for user {} in tenant {}", userId, tenantId);
    }

    /**
     * Convenience overload — updates only communication and role context, leaves other
     * fields unchanged (passes null for preferences and decisionPatterns).
     */
    public void upsertUserProfile(Long tenantId, Long userId,
                                   Map<String, Object> communication,
                                   Map<String, Object> roleContext) {
        upsertUserProfile(tenantId, userId, communication, roleContext, null, null);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize map to JSON: {}", e.getMessage());
            return null;
        }
    }
}
