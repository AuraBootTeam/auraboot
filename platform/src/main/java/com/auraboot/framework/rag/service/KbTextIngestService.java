package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.auraboot.framework.rag.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reusable text-document RAG ingest: chunk &rarr; insert pending chunks &rarr; embed
 * &rarr; store vectors &rarr; refresh counters, all in one transaction. Idempotent per
 * {@code (sourceType, sourceId)}.
 *
 * <p>Lives in the {@code rag.service} package so it can reach
 * {@link KnowledgeBaseService#findKbByPid} (package-private). It is the single ingest
 * entry point for callers that have raw TEXT rather than an uploaded file (the
 * file-based path is {@link DocumentProcessingService#processDocument}). Surfaced to
 * plugins via {@code KnowledgeBaseAccessorImpl}.
 *
 * <p>NOTE (DRY-debt): mirrors the chunk/embed sequence in
 * {@link RagDocumentSyncListener#syncToRag} (the doc-knowledge auto-sync path). That
 * listener is intentionally left untouched here to avoid regressing the production
 * dk_document sync; a follow-up may refactor the listener to call this service so the
 * sequence has a single home.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbTextIngestService {

    private final KnowledgeBaseService kbService;
    private final KbDocumentMapper docMapper;
    private final KbChunkIngestPipeline ingestPipeline;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    /**
     * DB-allowed {@code ab_kb_document.source_type} values (chk_doc_source); other logical
     * sources map to internal_doc.
     *
     * <p><b>Must stay in lockstep with the chk_doc_source CHECK constraint.</b> A source type
     * that is legal in the DB but missing here is silently rewritten to {@code internal_doc}
     * by {@link #ingestText}: no exception, the document is still stored, retrieval still
     * recalls it, E2E still passes — but {@code source_type} is never what the caller asked
     * for. Adding a value to the CHECK constraint without adding it here is a no-op.
     */
    private static final Set<String> DB_SOURCE_TYPES =
            Set.of("file", "entity", "internal_doc", "conversation");

    @PostConstruct
    void initTx() {
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @return the KB document pid, or {@code null} when {@code kbPid} is unknown or the
     *         text is blank.
     */
    public String ingestText(long tenantId, String kbPid, String sourceType, String sourceId,
                             String docName, String text) {
        if (kbPid == null || kbPid.isBlank() || text == null || text.isBlank()) {
            return null;
        }
        KnowledgeBase kb = kbService.findKbByPid(kbPid);
        if (kb == null) {
            log.warn("[kb-ingest] unknown kb {} — skip ingest source={}:{}", kbPid, sourceType, sourceId);
            return null;
        }
        String provider = kb.getEmbeddingProvider() != null ? kb.getEmbeddingProvider() : "openai";
        String hash = sha256(text);
        String resolvedName = (docName != null && !docName.isBlank())
                ? docName : sourceType + ":" + sourceId;
        // ab_kb_document.chk_doc_source allows only {file, entity, internal_doc}. A plugin's
        // logical source (e.g. "crawler") maps to internal_doc — a programmatically ingested
        // document that is neither a user file-upload (file) nor an entity-record sync (entity).
        String dbSourceType = DB_SOURCE_TYPES.contains(sourceType) ? sourceType : "internal_doc";

        return tx.execute(status -> {
            // Idempotent: drop this source's prior document + chunks before re-inserting.
            deleteBySource(dbSourceType, sourceId);

            String docPid = UniqueIdGenerator.generate();
            KbDocument doc = KbDocument.builder()
                    .pid(docPid).tenantId(tenantId).kbId(kbPid)
                    .docName(resolvedName).docType("html")
                    .fileSize((long) text.length()).charCount(text.length())
                    .sourceType(dbSourceType).sourceEntityId(sourceId)
                    .contentHash(hash).status("processing")
                    .build();
            docMapper.insert(doc);

            KbChunkIngestPipeline.IngestOutcome outcome = ingestPipeline.ingestChunks(
                    tenantId, kbPid, docPid, text,
                    kb.getChunkSize(), kb.getChunkOverlap(), provider, null);
            if (outcome.chunkCount() == 0) {
                kbService.updateDocumentAfterProcessing(docPid, "failed", 0, 0, "No chunks");
                return docPid;
            }

            kbService.updateDocumentAfterProcessing(docPid, "completed", text.length(),
                    outcome.chunkCount(), null);
            kbService.refreshKbCounters(kbPid);
            log.info("[kb-ingest] ingested {}:{} -> kb {} ({} chunks, {} embedded)",
                    sourceType, sourceId, kbPid, outcome.chunkCount(), outcome.embeddedCount());
            return docPid;
        });
    }

    /**
     * Remove the document (and its chunks) a given source published into a knowledge base, so it
     * stops being retrievable. This is the inverse of {@link #ingestText}: it deletes exactly what a
     * re-ingest would have replaced, using the same {@code (sourceType, sourceId)} key, so an
     * unpublish undoes a publish rather than approximating it.
     *
     * <p>Chunks are hard-deleted, and retrieval reads {@code FROM ab_kb_chunk}, so once they are
     * gone the answer cannot be recalled — a status flag flipped on the candidate would not have
     * been enough, the chunks are what the search actually sees.
     *
     * @return true if a document was removed, false if there was nothing to remove (already gone).
     */
    public boolean remove(long tenantId, String kbPid, String sourceType, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        String dbSourceType = DB_SOURCE_TYPES.contains(sourceType) ? sourceType : "internal_doc";
        Boolean removed = tx.execute(status -> {
            int n = deleteBySource(dbSourceType, sourceId);
            if (n > 0 && kbPid != null && !kbPid.isBlank()) {
                kbService.refreshKbCounters(kbPid);
            }
            return n > 0;
        });
        return Boolean.TRUE.equals(removed);
    }

    /**
     * Delete every document for {@code (dbSourceType, sourceId)} and its chunks. Shared by the
     * idempotent re-ingest path and by {@link #remove}, so both drop exactly the same set — an
     * unpublish can never leave behind a chunk a re-publish would have overwritten.
     */
    private int deleteBySource(String dbSourceType, String sourceId) {
        List<KbDocument> existing = docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSourceType, dbSourceType)
                .eq(KbDocument::getSourceEntityId, sourceId));
        for (KbDocument d : existing) {
            jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", d.getPid());
            docMapper.deleteById(d.getId());
        }
        return existing.size();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
