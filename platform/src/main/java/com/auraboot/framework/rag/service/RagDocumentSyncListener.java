package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.auraboot.framework.rag.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Listens for CommandCompletedEvent on doc-knowledge models and syncs to RAG.
 * Only PUBLISHED documents are synced. ARCHIVED/DRAFT are removed/skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagDocumentSyncListener {

    private final KnowledgeBaseService kbService;
    private final KbDocumentMapper docMapper;
    private final KbChunkIngestPipeline ingestPipeline;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager txManager;
    /**
     * REQUIRES_NEW template for {@link #syncToRag} atomicity. The
     * {@code @TransactionalEventListener AFTER_COMMIT} above does NOT open
     * a tx for the listener body, and {@code syncToRag} is invoked via
     * self-call (line 199) which bypasses Spring's @Transactional proxy.
     * TransactionTemplate is the only correct way to wrap the 6-write
     * sequence (delete chunks → delete doc → insert doc → insert N
     * chunks → update embeddings → refresh counters) atomically. See
     * deep-review P2-1.
     */
    private TransactionTemplate syncToRagTx;

    @PostConstruct
    void initTx() {
        this.syncToRagTx = new TransactionTemplate(txManager);
        // PROPAGATION_REQUIRED — join existing tx if present, otherwise
        // open new one. The two callers:
        //  (1) @Async @TransactionalEventListener AFTER_COMMIT → runs on a
        //      different thread WITHOUT an existing tx → REQUIRED creates a
        //      new tx, achieving atomicity for the 6 writes (the deep-review
        //      P2-1 fix intent).
        //  (2) Direct test invocation inside BaseIntegrationTest @Transactional
        //      → joins the test tx, sees test's uncommitted seed rows, gets
        //      rolled back as part of @Rollback(true) for clean test isolation.
        // REQUIRES_NEW would break (2) because PG READ COMMITTED hides the
        // test's uncommitted seeds from the nested tx → sync sees no source
        // record and exits early.
        this.syncToRagTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    private static final String RAG_KB_NAME = "Document Knowledge Base";
    private static final String SOURCE_TYPE = "entity";

    private static final Map<String, String[]> SYNCABLE_MODELS = Map.of(
            "dk_document", new String[]{"dk_doc_title", "dk_doc_content", "dk_doc_status"},
            "dk_knowledge_article", new String[]{"dk_ka_title", "dk_ka_content", "dk_ka_status"}
    );

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        String modelCode = event.getModelCode();
        if (!SYNCABLE_MODELS.containsKey(modelCode)) return;

        String opType = event.getOperationType();
        try {
            if (opType == null || opType.isBlank()) {
                String commandCode = event.getCommandCode();
                if (commandCode != null && commandCode.toLowerCase(Locale.ROOT).contains("delete")) {
                    handleDelete(event);
                } else {
                    handleCreateOrUpdate(event);
                }
                return;
            }
            switch (opType.toLowerCase(Locale.ROOT)) {
                case "create", "update" -> handleCreateOrUpdate(event);
                case "delete" -> handleDelete(event);
                default -> {}
            }
        } catch (Exception e) {
            log.error("RAG sync failed for {}:{}: {}", modelCode, event.getRecordId(), e.getMessage(), e);
        }
    }

    /**
     * Sync document content to RAG. Public for direct invocation in tests.
     *
     * <p>Wrapped in {@link #syncToRagTx} (REQUIRED) — the
     * {@code @TransactionalEventListener AFTER_COMMIT} above does NOT open
     * a tx for the listener body, so without explicit wrapping the
     * 6 writes (delete chunks → delete doc → insert doc → insert N
     * chunks → update embeddings → refresh counters) had no atomicity.
     * Partial failure left orphan / inconsistent rows. {@code @Transactional}
     * cannot be used here because this method is self-invoked from
     * {@link #handleCreateOrUpdate} which bypasses the Spring proxy. See
     * deep-review P2-1. See {@link #initTx} for the REQUIRED vs
     * REQUIRES_NEW choice (REQUIRED preserves test rollback semantics).
     */
    public void syncToRag(Long tenantId, String modelCode, String recordId) {
        syncToRagTx.executeWithoutResult(status -> syncToRagInternal(tenantId, modelCode, recordId));
    }

    /** Actual sync logic — runs inside the {@link #syncToRagTx} boundary. */
    private void syncToRagInternal(Long tenantId, String modelCode, String recordId) {
        String[] fieldMapping = SYNCABLE_MODELS.get(modelCode);
        if (fieldMapping == null) return;

        Map<String, Object> record = readRecord(modelCode, recordId, tenantId);
        log.debug("RAG sync readRecord modelCode={} recordId={} tenant={} found={}",
                modelCode, recordId, tenantId,
                record == null ? "null" : (record.size() + " keys"));
        if (record == null) {
            log.debug("Record not found for RAG sync: {}:{}", modelCode, recordId);
            return;
        }

        String status = getString(record, fieldMapping[2]);
        String title = getString(record, fieldMapping[0]);
        String content = getString(record, fieldMapping[1]);

        if (!"published".equalsIgnoreCase(status)) {
            removeFromRag(tenantId, recordId);
            return;
        }

        if (content == null || content.isBlank()) {
            log.debug("Skipping RAG sync for {}:{} — no content", modelCode, recordId);
            return;
        }

        KnowledgeBaseDTO kb = ensureDocKb(tenantId);
        String kbPid = kb.getPid();
        String hash = sha256(content);

        KbDocument existing = findExistingDoc(kbPid, recordId);
        if (existing != null && hash.equals(existing.getContentHash())) {
            return;
        }

        if (existing != null) {
            jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", existing.getPid());
            docMapper.deleteById(existing.getId());
        }

        String docPid = UniqueIdGenerator.generate();
        String docName = (title != null ? title : modelCode + ":" + recordId);

        KbDocument doc = KbDocument.builder()
                .pid(docPid).tenantId(tenantId).kbId(kbPid)
                .docName(docName).docType("md")
                .fileSize((long) content.length()).charCount(content.length())
                .sourceType(SOURCE_TYPE).sourceEntityId(recordId)
                .contentHash(hash).status("processing")
                .build();
        docMapper.insert(doc);

        var kbEntity = kbService.findKbByPid(kbPid);
        String provider = kbEntity != null ? kbEntity.getEmbeddingProvider() : "openai";
        Integer chunkSize = kbEntity != null ? kbEntity.getChunkSize() : null;
        Integer chunkOverlap = kbEntity != null ? kbEntity.getChunkOverlap() : null;
        KbChunkIngestPipeline.IngestOutcome outcome = ingestPipeline.ingestChunks(
                tenantId, kbPid, docPid, content, chunkSize, chunkOverlap, provider, null);
        if (outcome.chunkCount() == 0) {
            kbService.updateDocumentAfterProcessing(docPid, "failed", 0, 0, "No chunks");
            return;
        }

        kbService.updateDocumentAfterProcessing(docPid, "completed", content.length(),
                outcome.chunkCount(), null);
        kbService.refreshKbCounters(kbPid);
        log.info("RAG synced {}:{} — {} chunks, {} embedded",
                modelCode, recordId, outcome.chunkCount(), outcome.embeddedCount());
    }

    /**
     * Remove a record's RAG documents. Public for direct invocation in tests.
     */
    public void removeFromRag(Long tenantId, String recordId) {
        List<KbDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getSourceType, SOURCE_TYPE)
                        .eq(KbDocument::getSourceEntityId, recordId));
        for (KbDocument doc : docs) {
            jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", doc.getPid());
            docMapper.deleteById(doc.getId());
            kbService.refreshKbCounters(doc.getKbId());
        }
    }

    private void handleCreateOrUpdate(CommandCompletedEvent event) {
        syncToRag(event.getTenantId(), event.getModelCode(), event.getRecordId());
    }

    private void handleDelete(CommandCompletedEvent event) {
        removeFromRag(event.getTenantId(), event.getRecordId());
    }

    private Map<String, Object> readRecord(String modelCode, String recordId, Long tenantId) {
        String tableName = "mt_" + modelCode;
        // P2 weak fallback: returns null on DB read failure so the caller
        // (handleCreateOrUpdate / handleDelete) can short-circuit gracefully.
        // log.error provides operator-visible signal; the null return is the
        // substitute path. Not migrated to propagation because the sync
        // listener is an event-driven background process where one bad
        // record should not abort sibling event processing (see
        // handleEvent's top-level P3 boundary at line 67).
        // See Bugfix-0 audit docs/backlog/2026-05-27-rag-catch-exception-audit.md cluster 2.
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM " + tableName + " WHERE pid = ? AND tenant_id = ?",
                    recordId, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.error("Failed to read from {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private KbDocument findExistingDoc(String kbPid, String recordId) {
        return docMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getKbId, kbPid)
                        .eq(KbDocument::getSourceType, SOURCE_TYPE)
                        .eq(KbDocument::getSourceEntityId, recordId));
    }

    private KnowledgeBaseDTO ensureDocKb(Long tenantId) {
        List<KnowledgeBaseDTO> existing = kbService.listKnowledgeBases(tenantId);
        for (KnowledgeBaseDTO kb : existing) {
            if (RAG_KB_NAME.equals(kb.getName())) return kb;
        }
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(RAG_KB_NAME);
        req.setDescription("Auto-synced from doc-knowledge plugin");
        return kbService.createKnowledgeBase(tenantId, 0L, req);
    }

    private String getString(Map<String, Object> record, String fieldCode) {
        Object val = record.get(fieldCode);
        return val instanceof String s ? s : (val != null ? val.toString() : null);
    }

    /**
     * Content hash for chunk-level dedup tracking. P2 weak fallback: when
     * SHA-256 fails (extremely unlikely — SHA-256 is part of JRE), falls
     * back to {@link Objects#hashCode} so the caller still gets a non-null
     * string. {@code log.warn} surfaces the unexpected failure to operators
     * without breaking the ingest path. See Bugfix-0 audit
     * docs/backlog/2026-05-27-rag-catch-exception-audit.md cluster 3.
     */
    private String sha256(String content) {
        if (content == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            log.warn("sha256 hashing failed; falling back to Objects.hashCode "
                    + "(degraded but non-blocking): {}", e.getMessage());
            return String.valueOf(Objects.hashCode(content));
        }
    }
}
