package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Memory L5 flush-before-compression (memory-lifecycle.md §6).
 *
 * Keeps a per-(tenant, agent) memory bucket bounded. When the bucket
 * exceeds {@code maxPerAgent} × {@code thresholdPct}, compress the
 * lowest-importance {@code flushBatchSize} rows into a single "summary"
 * memory and soft-delete the originals.
 *
 * v0 summarization: deterministic concatenation of titles + content
 * excerpts. Replacing with LLM-generated summaries is a later
 * enhancement — the plumbing (which rows to compress, how to mark them,
 * what replaces them) is the fundamentals.
 *
 * scope='user' memories are NOT compressed — user-scoped rows are user-
 * visible preferences / decisions that shouldn't be summarised away
 * without consent. Compression only applies to scope in {'tenant', 'global'}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompactionService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${acp.memory.compaction.max-per-agent:200}")
    private int maxPerAgent = 200;

    @Value("${acp.memory.compaction.threshold-pct:85}")
    private int thresholdPct = 85;

    @Value("${acp.memory.compaction.flush-batch-size:20}")
    private int flushBatchSize = 20;

    public record CompactionResult(int buckets, int compressed, int replaced) {}

    /**
     * Nightly sweep: find all (tenant, agent) buckets above threshold and
     * compress a batch from each. Scheduled at 03:00 UTC — well after the
     * L3 decay (02:30) so we don't compress rows that would have been
     * decay-purged.
     */
    @Scheduled(cron = "${acp.memory.compaction.cron:0 0 3 * * *}")
    public CompactionResult compactOversizedBuckets() {
        int threshold = (int) Math.ceil(maxPerAgent * (thresholdPct / 100.0));
        List<Map<String, Object>> oversized = jdbcTemplate.queryForList(
                "SELECT tenant_id, memory_agent_id, COUNT(*) AS n " +
                        "FROM ab_agent_memory " +
                        "WHERE (deleted_flag IS NULL OR deleted_flag = FALSE) " +
                        "  AND scope IN ('tenant', 'global') " +
                        "GROUP BY tenant_id, memory_agent_id " +
                        "HAVING COUNT(*) >= ?", threshold);

        int buckets = 0;
        int compressed = 0;
        int replaced = 0;
        for (Map<String, Object> b : oversized) {
            Long tenantId = ((Number) b.get("tenant_id")).longValue();
            String agent = (String) b.get("memory_agent_id");
            int r = compactBucket(tenantId, agent);
            if (r > 0) {
                buckets++;
                compressed++;
                replaced += r;
            }
        }
        if (buckets > 0) {
            log.info("Memory L5 compaction: {} buckets compressed, {} source rows flushed into {} summaries",
                    buckets, replaced, compressed);
        }
        return new CompactionResult(buckets, compressed, replaced);
    }

    /**
     * Compress the lowest-importance batch in a specific (tenant, agent)
     * bucket. Returns the number of source rows replaced (0 if the bucket
     * didn't have enough rows to compress).
     */
    public int compactBucket(Long tenantId, String agentCode) {
        List<Map<String, Object>> batch = jdbcTemplate.queryForList(
                "SELECT pid, memory_type, memory_title, memory_content, importance, scope " +
                        "FROM ab_agent_memory " +
                        "WHERE tenant_id = ? AND memory_agent_id = ? " +
                        "  AND (deleted_flag IS NULL OR deleted_flag = FALSE) " +
                        "  AND scope IN ('tenant', 'global') " +
                        "ORDER BY importance ASC NULLS FIRST, " +
                        "         last_accessed ASC NULLS FIRST " +
                        "LIMIT ?",
                tenantId, agentCode, flushBatchSize);
        if (batch.size() < flushBatchSize) {
            // Don't compress under-sized batches — the bucket must be solidly
            // above threshold to justify a summary.
            return 0;
        }

        String summaryPid = UniqueIdGenerator.generate();
        String summaryTitle = "Compressed " + batch.size() + " memories";
        String summaryContent = buildSummary(batch);
        int medianImportance = medianImportance(batch);
        String dominantScope = dominantScope(batch);

        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory " +
                        "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                        " memory_title, memory_content, importance, shareable, scope, " +
                        " metadata, created_at, updated_at, deleted_flag) " +
                        "VALUES (?, ?, ?, 'fact', 'compressed', ?, ?, ?, FALSE, ?, " +
                        " 'source_count=' || ?, NOW(), NOW(), FALSE)",
                summaryPid, tenantId, agentCode, summaryTitle, summaryContent,
                medianImportance, dominantScope, batch.size());

        List<String> pids = new ArrayList<>(batch.size());
        for (Map<String, Object> r : batch) pids.add((String) r.get("pid"));
        String inClause = pids.stream().map(p -> "?").reduce((a, b) -> a + "," + b).orElse("");
        int replaced = jdbcTemplate.update(
                "UPDATE ab_agent_memory " +
                        "SET deleted_flag = TRUE, updated_at = NOW() " +
                        "WHERE pid IN (" + inClause + ")",
                pids.toArray());

        log.debug("Compacted bucket tenant={} agent={} — {} rows → summary {}",
                tenantId, agentCode, replaced, summaryPid);
        return replaced;
    }

    // =========================================================================

    private String buildSummary(List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compressed from ").append(batch.size()).append(" memories (v0 deterministic). Titles:\n");
        int limit = Math.min(batch.size(), 20);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = batch.get(i);
            sb.append("- ").append(r.get("memory_title") != null ? r.get("memory_title") : "(untitled)");
            Object content = r.get("memory_content");
            if (content != null) {
                String c = String.valueOf(content);
                if (c.length() > 80) c = c.substring(0, 80) + "…";
                sb.append(": ").append(c);
            }
            sb.append('\n');
        }
        if (batch.size() > limit) {
            sb.append("(").append(batch.size() - limit).append(" more elided)\n");
        }
        return sb.toString();
    }

    private int medianImportance(List<Map<String, Object>> batch) {
        int[] vals = batch.stream()
                .mapToInt(r -> r.get("importance") == null ? 0
                        : ((Number) r.get("importance")).intValue())
                .sorted()
                .toArray();
        return vals.length == 0 ? 0 : vals[vals.length / 2];
    }

    /** Majority scope in the batch; tie → 'tenant'. */
    private String dominantScope(List<Map<String, Object>> batch) {
        int tenantN = 0, globalN = 0;
        for (Map<String, Object> r : batch) {
            String s = (String) r.get("scope");
            if ("global".equals(s)) globalN++;
            else tenantN++;
        }
        return globalN > tenantN ? "global" : "tenant";
    }

    // Test setters
    public void setMaxPerAgent(int v)    { this.maxPerAgent = v; }
    public void setThresholdPct(int v)   { this.thresholdPct = v; }
    public void setFlushBatchSize(int v) { this.flushBatchSize = v; }
}
