package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns immutable document-version and index-release lineage for RAG ingestion.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeLineageService {

    private final JdbcTemplate jdbc;

    public record IngestLineage(
            String documentVersionPid,
            String indexReleasePid,
            int embeddingDimension) {
    }

    public String createInitialRelease(KnowledgeBase kb) {
        String releasePid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_index_release ("
                        + "pid, tenant_id, kb_pid, release_no, release_type, state, "
                        + "embedding_provider, embedding_model, embedding_dimension, "
                        + "chunk_strategy, chunk_size, chunk_overlap, activated_at, created_by"
                        + ") VALUES (?, ?, ?, 1, 'full', 'active', ?, ?, ?, ?, ?, ?, NOW(), ?)",
                releasePid, kb.getTenantId(), kb.getPid(),
                kb.getEmbeddingProvider(), kb.getEmbeddingModel(), kb.getEmbeddingDimension(),
                kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap(), kb.getCreatedBy());
        jdbc.update(
                "UPDATE ab_knowledge_base SET active_index_release_pid = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                releasePid, kb.getTenantId(), kb.getPid());
        return releasePid;
    }

    @Transactional
    public IngestLineage beginIngest(long tenantId, String kbPid, String documentPid) {
        Map<String, Object> kb = requireOne(
                "SELECT active_index_release_pid, embedding_provider, embedding_model, "
                        + "embedding_dimension, chunk_strategy, chunk_size, chunk_overlap, created_by "
                        + "FROM ab_knowledge_base "
                        + "WHERE tenant_id = ? AND pid = ? AND status = 'active' "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) FOR UPDATE",
                tenantId, kbPid);

        String parentReleasePid = ensureActiveRelease(tenantId, kbPid, kb);
        String releasePid = beginFullSnapshot(
                tenantId, kbPid, documentPid, parentReleasePid, kb, kb.get("created_by"));

        Map<String, Object> document = requireOne(
                "SELECT content_hash, file_pid, source_type, source_entity_id, created_by "
                        + "FROM ab_kb_document "
                        + "WHERE tenant_id = ? AND kb_id = ? AND pid = ? "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, kbPid, documentPid);
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 "
                        + "FROM ab_kb_document_version "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND document_pid = ?",
                Integer.class, tenantId, kbPid, documentPid);
        int versionNo = nextVersion == null ? 1 : nextVersion;
        String versionPid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_document_version ("
                        + "pid, tenant_id, kb_pid, document_pid, version_no, content_hash, "
                        + "file_pid, source_type, source_entity_id, state, created_by"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'processing', ?)",
                versionPid, tenantId, kbPid, documentPid, versionNo,
                document.get("content_hash"), document.get("file_pid"),
                document.get("source_type"), document.get("source_entity_id"),
                document.get("created_by"));

        int dimension = kb.get("embedding_dimension") instanceof Number number
                ? number.intValue()
                : 1536;
        return new IngestLineage(versionPid, releasePid, dimension);
    }

    @Transactional
    public void activateIngest(
            long tenantId,
            String kbPid,
            String documentPid,
            String versionPid,
            String releasePid) {
        jdbc.update(
                "UPDATE ab_kb_document_version SET state = 'superseded' "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND document_pid = ? "
                        + "AND state = 'active' AND pid <> ?",
                tenantId, kbPid, documentPid, versionPid);
        jdbc.update(
                "UPDATE ab_kb_document_version SET state = 'active' "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND document_pid = ? AND pid = ?",
                tenantId, kbPid, documentPid, versionPid);
        jdbc.update(
                "UPDATE ab_kb_document d SET active_version_pid = ?, "
                        + "version_no = (SELECT version_no FROM ab_kb_document_version "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND document_pid = ? AND pid = ?) "
                        + "WHERE tenant_id = ? AND kb_id = ? AND pid = ?",
                versionPid, tenantId, kbPid, documentPid, versionPid,
                tenantId, kbPid, documentPid);
        activateRelease(tenantId, kbPid, releasePid);
    }

    /**
     * Publish a new full snapshot without {@code documentPid}. Historical
     * releases and their chunks remain immutable for evidence replay.
     */
    @Transactional
    public String removeDocumentFromActiveRelease(
            long tenantId,
            String kbPid,
            String documentPid,
            Long userId) {
        Map<String, Object> kb = requireOne(
                "SELECT active_index_release_pid, embedding_provider, embedding_model, "
                        + "embedding_dimension, chunk_strategy, chunk_size, chunk_overlap, created_by "
                        + "FROM ab_knowledge_base "
                        + "WHERE tenant_id = ? AND pid = ? AND status = 'active' "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) FOR UPDATE",
                tenantId, kbPid);
        String parentReleasePid = ensureActiveRelease(tenantId, kbPid, kb);
        String releasePid = beginFullSnapshot(
                tenantId, kbPid, documentPid, parentReleasePid, kb,
                userId != null ? userId : kb.get("created_by"));
        activateRelease(tenantId, kbPid, releasePid);
        return releasePid;
    }

    public void failDocumentVersion(long tenantId, String versionPid) {
        jdbc.update(
                "UPDATE ab_kb_document_version SET state = 'failed' "
                        + "WHERE tenant_id = ? AND pid = ? AND state = 'processing'",
                tenantId, versionPid);
    }

    private String ensureActiveRelease(
            long tenantId,
            String kbPid,
            Map<String, Object> kb) {
        String releasePid = text(kb.get("active_index_release_pid"));
        if (releasePid != null) {
            return releasePid;
        }
        releasePid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_index_release ("
                        + "pid, tenant_id, kb_pid, release_no, release_type, state, "
                        + "embedding_provider, embedding_model, embedding_dimension, "
                        + "chunk_strategy, chunk_size, chunk_overlap, activated_at, created_by"
                        + ") VALUES (?, ?, ?, 1, 'full', 'active', ?, ?, ?, ?, ?, ?, NOW(), ?)",
                releasePid, tenantId, kbPid,
                kb.get("embedding_provider"), kb.get("embedding_model"),
                kb.get("embedding_dimension"), kb.get("chunk_strategy"),
                kb.get("chunk_size"), kb.get("chunk_overlap"), kb.get("created_by"));
        jdbc.update(
                "UPDATE ab_knowledge_base SET active_index_release_pid = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ? AND active_index_release_pid IS NULL",
                releasePid, tenantId, kbPid);
        return releasePid;
    }

    private String beginFullSnapshot(
            long tenantId,
            String kbPid,
            String replacedDocumentPid,
            String parentReleasePid,
            Map<String, Object> kb,
            Object createdBy) {
        Integer nextRelease = jdbc.queryForObject(
                "SELECT COALESCE(MAX(release_no), 0) + 1 "
                        + "FROM ab_kb_index_release WHERE tenant_id = ? AND kb_pid = ?",
                Integer.class, tenantId, kbPid);
        String releasePid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_index_release ("
                        + "pid, tenant_id, kb_pid, release_no, release_type, state, "
                        + "parent_release_pid, embedding_provider, embedding_model, "
                        + "embedding_dimension, chunk_strategy, chunk_size, chunk_overlap, created_by"
                        + ") VALUES (?, ?, ?, ?, 'full', 'building', ?, ?, ?, ?, ?, ?, ?, ?)",
                releasePid, tenantId, kbPid, nextRelease == null ? 1 : nextRelease,
                parentReleasePid, kb.get("embedding_provider"), kb.get("embedding_model"),
                kb.get("embedding_dimension"), kb.get("chunk_strategy"),
                kb.get("chunk_size"), kb.get("chunk_overlap"), createdBy);

        List<Map<String, Object>> retained = jdbc.queryForList(
                "SELECT c.tenant_id, c.kb_id, c.doc_id, c.document_version_pid, "
                        + "c.chunk_index, c.content, c.char_count, c.token_count, c.metadata, "
                        + "c.tsv::text AS tsv_text, c.embedding_status, "
                        + "c.embedding::text AS embedding_text "
                        + "FROM ab_kb_chunk c "
                        + "JOIN ab_kb_document d ON d.tenant_id = c.tenant_id "
                        + "AND d.kb_id = c.kb_id AND d.pid = c.doc_id "
                        + "WHERE c.tenant_id = ? AND c.kb_id = ? "
                        + "AND c.index_release_pid = ? AND c.doc_id <> ? "
                        + "AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE) "
                        + "ORDER BY c.doc_id, c.chunk_index",
                tenantId, kbPid, parentReleasePid, replacedDocumentPid);
        for (Map<String, Object> source : retained) {
            jdbc.update(
                    "INSERT INTO ab_kb_chunk ("
                            + "pid, tenant_id, kb_id, doc_id, document_version_pid, "
                            + "index_release_pid, chunk_index, content, char_count, token_count, "
                            + "metadata, tsv, embedding_status, embedding, created_at, updated_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::tsvector, "
                            + "?, ?::vector, NOW(), NOW())",
                    UniqueIdGenerator.generate(),
                    source.get("tenant_id"), source.get("kb_id"), source.get("doc_id"),
                    source.get("document_version_pid"), releasePid, source.get("chunk_index"),
                    source.get("content"), source.get("char_count"), source.get("token_count"),
                    nullableText(source.get("metadata")), nullableText(source.get("tsv_text")),
                    source.get("embedding_status"), nullableText(source.get("embedding_text")));
        }
        return releasePid;
    }

    private void activateRelease(long tenantId, String kbPid, String releasePid) {
        jdbc.update(
                "UPDATE ab_kb_index_release SET state = 'retired' "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND state = 'active' AND pid <> ?",
                tenantId, kbPid, releasePid);
        jdbc.update(
                "UPDATE ab_kb_index_release SET state = 'active', activated_at = NOW(), "
                        + "error_message = NULL "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND pid = ? AND state = 'building'",
                tenantId, kbPid, releasePid);
        jdbc.update(
                "UPDATE ab_knowledge_base SET active_index_release_pid = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                releasePid, tenantId, kbPid);
    }

    private Map<String, Object> requireOne(String sql, Object... params) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Knowledge lineage target is missing, inactive, or outside the tenant");
        }
        return rows.get(0);
    }

    private String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string;
    }

    private String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String string = Objects.toString(value);
        return string.isBlank() ? null : string;
    }
}
