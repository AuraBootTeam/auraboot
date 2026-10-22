package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.util.CjkBigramSegmenter;
import com.auraboot.framework.rag.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds immutable text/vector index releases and flips the KB pointer only
 * after the complete candidate release is ready.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIndexRebuildService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbc;

    public record RebuildResult(
            String releasePid,
            String releaseType,
            String state,
            int indexedChunks,
            String errorMessage) {
    }

    @Transactional
    public RebuildResult rebuildText(Long tenantId, Long userId, String kbPid) {
        KnowledgeBase kb = knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        ReleaseDraft draft = beginRelease(kb, userId, "text");
        List<Map<String, Object>> chunks = activeChunks(tenantId, kbPid);
        if (chunks.isEmpty()) {
            String error = "The active index contains no chunks to rebuild";
            fail(draft, error);
            return new RebuildResult(draft.pid(), "text", "failed", 0, error);
        }
        try {
            for (Map<String, Object> chunk : chunks) {
                insertReleaseChunk(
                        chunk,
                        draft.pid(),
                        CjkBigramSegmenter.segment((String) chunk.get("content")),
                        text(chunk.get("embedding_text")));
            }
            activate(kb, draft.pid());
            return new RebuildResult(draft.pid(), "text", "active", chunks.size(), null);
        } catch (RuntimeException e) {
            fail(draft, e.getMessage());
            return new RebuildResult(
                    draft.pid(), "text", "failed", 0, safeMessage(e.getMessage()));
        }
    }

    @Transactional
    public RebuildResult rebuildVector(Long tenantId, Long userId, String kbPid) {
        KnowledgeBase kb = knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        ReleaseDraft draft = beginRelease(kb, userId, "vector");
        List<Map<String, Object>> chunks = activeChunks(tenantId, kbPid);
        if (chunks.isEmpty()) {
            String error = "The active index contains no chunks to rebuild";
            fail(draft, error);
            return new RebuildResult(draft.pid(), "vector", "failed", 0, error);
        }
        List<String> contents = chunks.stream()
                .map(row -> (String) row.get("content"))
                .toList();
        List<float[]> embeddings = embeddingService.embedBatch(
                tenantId, contents, kb.getEmbeddingProvider());
        int dimension = kb.getEmbeddingDimension() == null
                ? 1536 : kb.getEmbeddingDimension();
        if (embeddings.size() != chunks.size()
                || embeddings.stream().anyMatch(value ->
                        value == null || value.length != dimension)) {
            String error = "Embedding rebuild returned incomplete or dimension-mismatched vectors";
            fail(draft, error);
            return new RebuildResult(draft.pid(), "vector", "failed", 0, error);
        }
        try {
            for (int i = 0; i < chunks.size(); i++) {
                insertReleaseChunk(
                        chunks.get(i),
                        draft.pid(),
                        text(chunks.get(i).get("tsv_text")),
                        VectorUtils.toVectorString(embeddings.get(i)));
            }
            activate(kb, draft.pid());
            return new RebuildResult(draft.pid(), "vector", "active", chunks.size(), null);
        } catch (RuntimeException e) {
            fail(draft, e.getMessage());
            return new RebuildResult(
                    draft.pid(), "vector", "failed", 0, safeMessage(e.getMessage()));
        }
    }

    public List<Map<String, Object>> listReleases(Long tenantId, String kbPid) {
        knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        return jdbc.queryForList(
                "SELECT pid, release_no, release_type, state, parent_release_pid, "
                        + "embedding_provider, embedding_model, embedding_dimension, "
                        + "chunk_strategy, chunk_size, chunk_overlap, error_message, "
                        + "created_at, activated_at "
                        + "FROM ab_kb_index_release "
                        + "WHERE tenant_id = ? AND kb_pid = ? ORDER BY release_no DESC",
                tenantId, kbPid);
    }

    @Transactional
    public boolean activateExisting(Long tenantId, String kbPid, String releasePid) {
        KnowledgeBase kb = knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_kb_index_release "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND pid = ? "
                        + "AND state IN ('ready', 'retired', 'active')",
                Integer.class, tenantId, kbPid, releasePid);
        if (count == null || count != 1) {
            return false;
        }
        activate(kb, releasePid);
        return true;
    }

    private ReleaseDraft beginRelease(KnowledgeBase kb, Long userId, String type) {
        jdbc.queryForObject(
                "SELECT id FROM ab_knowledge_base WHERE tenant_id = ? AND pid = ? FOR UPDATE",
                Long.class, kb.getTenantId(), kb.getPid());
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(release_no), 0) + 1 FROM ab_kb_index_release "
                        + "WHERE tenant_id = ? AND kb_pid = ?",
                Integer.class, kb.getTenantId(), kb.getPid());
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_index_release ("
                        + "pid, tenant_id, kb_pid, release_no, release_type, state, "
                        + "parent_release_pid, embedding_provider, embedding_model, "
                        + "embedding_dimension, chunk_strategy, chunk_size, chunk_overlap, created_by"
                        + ") VALUES (?, ?, ?, ?, ?, 'building', ?, ?, ?, ?, ?, ?, ?, ?)",
                pid, kb.getTenantId(), kb.getPid(), next == null ? 1 : next, type,
                kb.getActiveIndexReleasePid(), kb.getEmbeddingProvider(), kb.getEmbeddingModel(),
                kb.getEmbeddingDimension(), kb.getChunkStrategy(), kb.getChunkSize(),
                kb.getChunkOverlap(), userId);
        return new ReleaseDraft(pid, kb.getTenantId(), kb.getPid());
    }

    private List<Map<String, Object>> activeChunks(Long tenantId, String kbPid) {
        return jdbc.queryForList(
                "SELECT c.tenant_id, c.kb_id, c.doc_id, c.document_version_pid, "
                        + "c.chunk_index, c.content, c.char_count, c.token_count, "
                        + "c.metadata, c.tsv::text AS tsv_text, c.embedding::text AS embedding_text "
                        + "FROM ab_kb_chunk c "
                        + "JOIN ab_knowledge_base kb ON kb.tenant_id = c.tenant_id AND kb.pid = c.kb_id "
                        + "JOIN ab_kb_document d ON d.tenant_id = c.tenant_id "
                        + "AND d.kb_id = c.kb_id AND d.pid = c.doc_id "
                        + "WHERE c.tenant_id = ? AND c.kb_id = ? "
                        + "AND c.index_release_pid = kb.active_index_release_pid "
                        + "AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE) "
                        + "ORDER BY c.doc_id, c.chunk_index",
                tenantId, kbPid);
    }

    private void insertReleaseChunk(
            Map<String, Object> source,
            String releasePid,
            String tsv,
            String embedding) {
        jdbc.update(
                "INSERT INTO ab_kb_chunk ("
                        + "pid, tenant_id, kb_id, doc_id, document_version_pid, index_release_pid, "
                        + "chunk_index, content, char_count, token_count, metadata, tsv, "
                        + "embedding_status, embedding, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::tsvector, "
                        + "?, ?::vector, NOW(), NOW())",
                UniqueIdGenerator.generate(),
                source.get("tenant_id"), source.get("kb_id"), source.get("doc_id"),
                source.get("document_version_pid"), releasePid, source.get("chunk_index"),
                source.get("content"), source.get("char_count"), source.get("token_count"),
                json(source.get("metadata")), tsv,
                embedding == null ? "failed" : "completed", embedding);
    }

    private void activate(KnowledgeBase kb, String releasePid) {
        jdbc.update(
                "UPDATE ab_kb_index_release SET state = 'retired' "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND state = 'active' AND pid <> ?",
                kb.getTenantId(), kb.getPid(), releasePid);
        jdbc.update(
                "UPDATE ab_kb_index_release SET state = 'active', activated_at = NOW(), "
                        + "error_message = NULL "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND pid = ?",
                kb.getTenantId(), kb.getPid(), releasePid);
        jdbc.update(
                "UPDATE ab_knowledge_base SET active_index_release_pid = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                releasePid, kb.getTenantId(), kb.getPid());
        knowledgeBaseService.refreshKbCounters(kb.getTenantId(), kb.getPid());
    }

    private void fail(ReleaseDraft draft, String error) {
        jdbc.update(
                "UPDATE ab_kb_index_release SET state = 'failed', error_message = ? "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND pid = ?",
                safeMessage(error), draft.tenantId(), draft.kbPid(), draft.pid());
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        return Objects.toString(value);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String string = Objects.toString(value);
        return string.isBlank() ? null : string;
    }

    private String safeMessage(String message) {
        if (message == null) {
            return "Index rebuild failed";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private record ReleaseDraft(String pid, Long tenantId, String kbPid) {
    }
}
