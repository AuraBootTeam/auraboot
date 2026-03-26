package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent Memory Service — CRUD operations for {@code ab_agent_memory}.
 *
 * <p>This service provides the core create/read/update/delete API for agent
 * memories.  Higher-level concerns (consolidation lifecycle, prompt assembly)
 * are handled by {@link AgentMemoryConsolidationService} and
 * {@link AgentPromptAssemblyService} respectively.
 *
 * <p>The {@code shareable} flag (G2) is managed here:
 * <ul>
 *   <li>{@link #createMemory} — allows setting {@code shareable} at creation time</li>
 *   <li>{@link #markShareable} / {@link #markPrivate} — toggle shareable on existing memories</li>
 *   <li>{@link #listShareableMemories} — list all shareable memories for a tenant</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // Create
    // =========================================================================

    /**
     * Create a new memory for the specified agent.
     *
     * @param tenantId    tenant scope
     * @param agentCode   agent identifier stored in {@code memory_agent_id}
     * @param memoryType  e.g. "fact", "lesson", "preference", "decision"
     * @param category    lifecycle category: "session", "user", or "agent"
     * @param title       short human-readable title (nullable)
     * @param content     the memory body (required)
     * @param importance  priority weight in [1, 10]
     * @param shareable   whether other agents in the tenant may access this memory
     * @return the generated {@code pid} of the new memory row
     */
    public String createMemory(Long tenantId, String agentCode,
                               String memoryType, String category,
                               String title, String content,
                               int importance, boolean shareable) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                + " memory_title, memory_content, importance, shareable, "
                + " created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), FALSE)",
                pid, tenantId, agentCode, memoryType, category,
                title, content, importance, shareable);

        log.debug("Created memory {} type={} agent={} shareable={}", pid, memoryType, agentCode, shareable);
        return pid;
    }

    // =========================================================================
    // Read
    // =========================================================================

    /**
     * List memories for a given agent, optionally filtered by type.
     *
     * @param tenantId   tenant scope
     * @param agentCode  agent identifier
     * @param memoryType filter by memory type (nullable — returns all types when null)
     * @param limit      maximum rows to return
     * @return list of memory rows
     */
    public List<Map<String, Object>> listMemories(Long tenantId, String agentCode,
                                                   String memoryType, int limit) {
        if (memoryType != null) {
            return jdbcTemplate.queryForList(
                    "SELECT pid, memory_type, category, memory_title, memory_content, "
                    + "  importance, shareable, source_run_id, created_at "
                    + "FROM ab_agent_memory "
                    + "WHERE tenant_id = ? AND memory_agent_id = ? AND memory_type = ? "
                    + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                    + "ORDER BY importance DESC, created_at DESC "
                    + "LIMIT ?",
                    tenantId, agentCode, memoryType, limit);
        }

        return jdbcTemplate.queryForList(
                "SELECT pid, memory_type, category, memory_title, memory_content, "
                + "  importance, shareable, source_run_id, created_at "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, agentCode, limit);
    }

    /**
     * List all shareable memories for the tenant (cross-agent shared pool).
     *
     * @param tenantId tenant scope
     * @param limit    maximum rows to return
     * @return list of shareable memory rows
     */
    public List<Map<String, Object>> listShareableMemories(Long tenantId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT pid, memory_agent_id, memory_type, category, "
                + "  memory_title, memory_content, importance, created_at "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND shareable = TRUE "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, limit);
    }

    // =========================================================================
    // G2 — Shareable flag management
    // =========================================================================

    /**
     * Mark a memory as shareable so other agents in the tenant can see it.
     *
     * @param tenantId  tenant scope (enforced for multi-tenancy safety)
     * @param memoryPid the pid of the memory to update
     * @return true if the memory was updated, false if not found or already shareable
     */
    public boolean markShareable(Long tenantId, String memoryPid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET shareable = TRUE, updated_at = NOW() "
                + "WHERE tenant_id = ? AND pid = ? "
                + "AND shareable = FALSE "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, memoryPid);
        return updated > 0;
    }

    /**
     * Revoke the shareable flag on a memory, making it private again.
     *
     * @param tenantId  tenant scope
     * @param memoryPid the pid of the memory to update
     * @return true if the memory was updated, false if not found or already private
     */
    public boolean markPrivate(Long tenantId, String memoryPid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET shareable = FALSE, updated_at = NOW() "
                + "WHERE tenant_id = ? AND pid = ? "
                + "AND shareable = TRUE "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, memoryPid);
        return updated > 0;
    }

    // =========================================================================
    // Soft delete
    // =========================================================================

    /**
     * Soft-delete a memory.
     *
     * @param tenantId  tenant scope
     * @param memoryPid pid of the memory to delete
     * @return true if deleted, false if not found
     */
    public boolean deleteMemory(Long tenantId, String memoryPid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET deleted_flag = TRUE, updated_at = NOW() "
                + "WHERE tenant_id = ? AND pid = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, memoryPid);
        return updated > 0;
    }

    // =========================================================================
    // Embedding-aware storage (G2 / RAG integration)
    // =========================================================================

    /**
     * Store a memory with an optional pre-computed vector embedding.
     *
     * <p>When {@code embedding} is null the row is inserted without a vector, which
     * is valid — keyword search ({@link #searchSemantic}) falls back to ILIKE matching
     * in that case.
     *
     * @param tenantId    tenant scope
     * @param agentCode   agent identifier
     * @param memoryType  e.g. "fact", "lesson", "preference", "rule"
     * @param title       short title (nullable)
     * @param content     memory body (required)
     * @param importance  priority weight [1, 10]
     * @param sourceRunId optional reference to the agent run that produced this memory
     * @param embedding   optional pre-computed 1536-dim float array; null = no vector stored
     */
    public void storeMemoryWithEmbedding(Long tenantId, String agentCode,
                                          String memoryType, String title, String content,
                                          int importance, String sourceRunId,
                                          float[] embedding) {
        String pid = UniqueIdGenerator.generate();

        if (embedding != null) {
            // Convert float[] → PostgreSQL vector literal "[x,y,...]"
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(embedding[i]);
            }
            sb.append(']');
            String vectorLiteral = sb.toString();

            jdbcTemplate.update(
                    "INSERT INTO ab_agent_memory "
                    + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                    + " memory_title, memory_content, importance, source_run_id, "
                    + " shareable, embedding, created_at, updated_at, deleted_flag) "
                    + "VALUES (?, ?, ?, ?, 'agent', ?, ?, ?, ?, FALSE, ?::vector, NOW(), NOW(), FALSE)",
                    pid, tenantId, agentCode, memoryType,
                    title, content, importance, sourceRunId, vectorLiteral);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO ab_agent_memory "
                    + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                    + " memory_title, memory_content, importance, source_run_id, "
                    + " shareable, created_at, updated_at, deleted_flag) "
                    + "VALUES (?, ?, ?, ?, 'agent', ?, ?, ?, ?, FALSE, NOW(), NOW(), FALSE)",
                    pid, tenantId, agentCode, memoryType,
                    title, content, importance, sourceRunId);
        }

        log.debug("Stored memory {} type={} agent={} hasEmbedding={}", pid, memoryType, agentCode, embedding != null);
    }

    // =========================================================================
    // Importance-ordered retrieval
    // =========================================================================

    /**
     * Load memories for an agent ordered by importance DESC (primary), created_at DESC (secondary).
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier
     * @param limit     maximum rows to return
     * @return list of memory rows; never null
     */
    public List<Map<String, Object>> loadByImportance(Long tenantId, String agentCode, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT pid, memory_type, category, memory_title, memory_content, "
                + "  importance, shareable, source_run_id, access_count, created_at "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, agentCode, limit);
    }

    // =========================================================================
    // Access tracking
    // =========================================================================

    /**
     * Increment the {@code access_count} for all non-deleted memories belonging to the agent.
     * Called when the memory set is loaded into a prompt context.
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier
     */
    public void trackAccess(Long tenantId, String agentCode) {
        jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                + "SET access_count = COALESCE(access_count, 0) + 1, "
                + "    last_accessed = NOW(), "
                + "    updated_at = NOW() "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode);
    }

    // =========================================================================
    // Semantic / keyword search (fallback without vector)
    // =========================================================================

    /**
     * Search memories for an agent using a keyword query.
     *
     * <p>When no vector embeddings are present this performs a case-insensitive
     * keyword search over {@code memory_content} and {@code memory_title} using
     * PostgreSQL {@code ILIKE}.  Results are ordered by importance DESC.
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier
     * @param query     search keyword
     * @param limit     maximum rows to return
     * @return matching memory rows; never null
     */
    public List<Map<String, Object>> searchSemantic(Long tenantId, String agentCode,
                                                     String query, int limit) {
        String pattern = "%" + query + "%";
        return jdbcTemplate.queryForList(
                "SELECT pid, memory_type, category, memory_title, memory_content, "
                + "  importance, shareable, created_at "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND (memory_content ILIKE ? OR memory_title ILIKE ?) "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, agentCode, pattern, pattern, limit);
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    /**
     * Remove lower-importance duplicates that share the same {@code memory_title}.
     *
     * <p>For each group of memories with the same title, the one with the highest
     * importance is kept and all others are soft-deleted.  When two records have
     * equal importance the one with the smaller id (earlier insert) is kept.
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier
     * @return number of memories soft-deleted
     */
    public int deduplicateMemories(Long tenantId, String agentCode) {
        // Identify the id to KEEP for each duplicated title (highest importance, then smallest id)
        // and soft-delete all others.
        int deleted = jdbcTemplate.update(
                "UPDATE ab_agent_memory SET deleted_flag = TRUE, updated_at = NOW() "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "AND memory_title IS NOT NULL "
                + "AND id NOT IN ( "
                + "  SELECT DISTINCT ON (memory_title) id "
                + "  FROM ab_agent_memory "
                + "  WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND memory_title IS NOT NULL "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "  ORDER BY memory_title, importance DESC, id ASC "
                + ") "
                + "AND memory_title IN ( "
                + "  SELECT memory_title FROM ab_agent_memory "
                + "  WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND memory_title IS NOT NULL "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "  GROUP BY memory_title HAVING COUNT(*) > 1 "
                + ")",
                tenantId, agentCode, tenantId, agentCode, tenantId, agentCode);

        if (deleted > 0) {
            log.info("Deduplicated {} memories for agent {} in tenant {}", deleted, agentCode, tenantId);
        }
        return deleted;
    }
}
