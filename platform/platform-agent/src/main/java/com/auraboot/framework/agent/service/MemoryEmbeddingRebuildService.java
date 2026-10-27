package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.memory.MemoryReadPolicy;
import com.auraboot.framework.agent.memory.MemoryVectorPolicy;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit tenant/agent-scoped maintenance; no scheduler, implicit migration or background repair. */
@Service
public class MemoryEmbeddingRebuildService {
    private static final String INVENTORY_SELECT = "SELECT pid,memory_content,embedding::text AS embedding_text,embedding_space,embedding_content_hash,("
            + MemoryReadPolicy.LIVE + ") AS live FROM ab_agent_memory";
    private final MemoryEmbeddingService writer;
    private final EmbeddingService embedding;
    private final JdbcTemplate jdbc;
    private final AuditTrailService audit;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public MemoryEmbeddingRebuildService(MemoryEmbeddingService writer, EmbeddingService embedding, JdbcTemplate jdbc,
                                        AuditTrailService audit, ObjectMapper json, PlatformTransactionManager transactions) {
        this.writer = writer; this.embedding = embedding; this.jdbc = jdbc; this.audit = audit; this.json = json;
        this.transactions = new TransactionTemplate(transactions);
    }

    public Map<String, Object> inventory(Long tenant, String agent, String after, int limit) {
        scope(tenant, agent);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (after == null) after = "";
        if (after.length() > 128) throw new IllegalArgumentException("invalid cursor");
        String space = embedding.currentSpace(tenant, null, 1536);
        var rows = jdbc.queryForList(INVENTORY_SELECT + " WHERE tenant_id=? AND memory_agent_id=? AND pid>? ORDER BY pid LIMIT ?",
                tenant, agent, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var row : rows.subList(0, Math.min(rows.size(), limit))) {
            String status = classify(row, space);
            counts.merge(status, 1, Integer::sum);
            // Inventory never exposes private memory content or raw vectors.
            records.add(Map.of("pid", row.get("pid"), "status", status));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.toString()); result.put("agentCode", agent); result.put("expectedSpace", space);
        result.put("records", records); result.put("pageCounts", counts); result.put("hasMore", more);
        result.put("nextCursor", more ? records.getLast().get("pid") : null);
        return result;
    }

    public Map<String, Object> inspect(Long tenant, String agent, String pid) {
        scope(tenant, agent);
        if (pid == null || pid.isBlank() || pid.length() > 128) throw new IllegalArgumentException("pid required");
        var rows = jdbc.queryForList(INVENTORY_SELECT + " WHERE tenant_id=? AND memory_agent_id=? AND pid=?", tenant, agent, pid);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        String space = embedding.currentSpace(tenant, null, 1536);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.toString()); result.put("agentCode", agent); result.put("pid", pid);
        result.put("expectedSpace", space); result.put("status", classify(row, space));
        return result;
    }

    static String classify(Map<String, Object> row, String space) {
        if (!Boolean.TRUE.equals(row.get("live"))) return "inactive";
        if (row.get("embedding_text") == null) return "missing";
        if (row.get("embedding_space") == null || row.get("embedding_space").toString().isBlank()) return "unknown_space";
        if (space == null) return "provider_unconfigured";
        if (!space.equals(row.get("embedding_space"))) return "different_space";
        Object content = row.get("memory_content");
        if (content == null || !MemoryVectorPolicy.sha256(content.toString()).equals(row.get("embedding_content_hash"))) return "stale_content";
        return "current";
    }

    @Transactional(propagation = Propagation.NEVER)
    public Map<String, Object> rebuild(Long tenant, Long actor, String agent, String pid, String expectedSpace, String reason) {
        scope(tenant, agent);
        if (actor == null || actor <= 0 || pid == null || pid.isBlank() || pid.length() > 128) throw new IllegalArgumentException("actor and pid required");
        if (expectedSpace == null || !expectedSpace.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("fixed expectedSpace required");
        if (reason == null || reason.isBlank() || reason.length() > 512) throw new IllegalArgumentException("reason required, at most 512 characters");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pid", pid); response.put("agentCode", agent); response.put("expectedSpace", expectedSpace);
        // Model inference occurs before this callback. Only the CAS and audit hold a transaction.
        String outcome = writer.rebuildScoped(pid, tenant, agent, expectedSpace, prepared -> transactions.execute(status -> {
            String applied = writer.storePrepared(prepared);
            if ("rebuilt".equals(applied)) {
                var record = audit.recordInCurrentTransaction(AuditTrailEvent.builder()
                        .tenantId(tenant).actorId(actor).eventType("MEMORY_VECTOR_REBUILT")
                        .entityType("agent_memory").entityPid(pid).operationType("UPDATE")
                        .beforeSnapshot(json.valueToTree(prepared.before()))
                        .afterSnapshot(json.valueToTree(Map.of("embedding_text", prepared.vector(), "embedding_space", prepared.space(),
                                "embedding_content_hash", prepared.contentHash())))
                        .changedFields(new String[]{"embedding", "embedding_space", "embedding_content_hash", "embedding_generated_at"})
                        .metadata(json.valueToTree(Map.of("agentCode", agent, "expectedSpace", expectedSpace, "reason", reason.strip())))
                        .build());
                response.put("auditSequence", String.valueOf(record.getSequenceNo()));
            }
            return applied;
        }));
        response.put("outcome", outcome);
        return response;
    }

    private static void scope(Long tenant, String agent) {
        if (tenant == null) throw new IllegalArgumentException("tenant required");
        if (agent == null || agent.isBlank() || agent.length() > 128) throw new IllegalArgumentException("agentCode required");
    }
}
