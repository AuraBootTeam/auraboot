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
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    /** DB-allowed ab_kb_document.source_type values (chk_doc_source); other logical sources map to internal_doc. */
    private static final Set<String> DB_SOURCE_TYPES = Set.of("file", "entity", "internal_doc");

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
            List<KbDocument> existing = docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                    .eq(KbDocument::getSourceType, dbSourceType)
                    .eq(KbDocument::getSourceEntityId, sourceId));
            for (KbDocument d : existing) {
                jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", d.getPid());
                docMapper.deleteById(d.getId());
            }

            String docPid = UniqueIdGenerator.generate();
            KbDocument doc = KbDocument.builder()
                    .pid(docPid).tenantId(tenantId).kbId(kbPid)
                    .docName(resolvedName).docType("html")
                    .fileSize((long) text.length()).charCount(text.length())
                    .sourceType(dbSourceType).sourceEntityId(sourceId)
                    .contentHash(hash).status("processing")
                    .build();
            docMapper.insert(doc);

            List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(text, 500, 50);
            if (chunks.isEmpty()) {
                kbService.updateDocumentAfterProcessing(docPid, "failed", 0, 0, "No chunks");
                return docPid;
            }

            List<String> chunkPids = new ArrayList<>();
            List<String> chunkTexts = new ArrayList<>();
            for (ChunkingService.ChunkResult chunk : chunks) {
                String chunkPid = UniqueIdGenerator.generate();
                chunkPids.add(chunkPid);
                chunkTexts.add(chunk.content());
                jdbcTemplate.update(
                        "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                        + "content, char_count, token_count, tsv, embedding_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), 'pending', NOW(), NOW())",
                        chunkPid, tenantId, kbPid, docPid, chunk.index(), chunk.content(),
                        chunk.charCount(), chunk.tokenCount(), chunk.content());
            }

            try {
                List<float[]> embeddings = embeddingService.embedBatch(tenantId, chunkTexts, provider);
                for (int i = 0; i < embeddings.size() && i < chunkPids.size(); i++) {
                    float[] emb = embeddings.get(i);
                    if (emb != null) {
                        jdbcTemplate.update(
                                "UPDATE ab_kb_chunk SET embedding = ?::vector, embedding_status = 'completed', "
                                + "updated_at = NOW() WHERE pid = ?",
                                VectorUtils.toVectorString(emb), chunkPids.get(i));
                    }
                }
            } catch (Exception e) {
                log.warn("[kb-ingest] embedding failed kb={} source={}:{}: {}",
                        kbPid, sourceType, sourceId, e.getMessage());
            }

            kbService.updateDocumentAfterProcessing(docPid, "completed", text.length(), chunks.size(), null);
            kbService.refreshKbCounters(kbPid);
            log.info("[kb-ingest] ingested {}:{} -> kb {} ({} chunks)",
                    sourceType, sourceId, kbPid, chunks.size());
            return docPid;
        });
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
