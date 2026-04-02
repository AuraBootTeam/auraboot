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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

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
            switch (opType) {
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
     */
    public void syncToRag(Long tenantId, String modelCode, String recordId) {
        String[] fieldMapping = SYNCABLE_MODELS.get(modelCode);
        if (fieldMapping == null) return;

        Map<String, Object> record = readRecord(modelCode, recordId, tenantId);
        System.out.println("[RAG-SYNC-DEBUG] readRecord result for " + modelCode + ":" + recordId + " tenant=" + tenantId + " => " + (record != null ? "found(" + record.size() + " keys)" : "null"));
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

        List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(content, 500, 50);
        if (chunks.isEmpty()) {
            kbService.updateDocumentAfterProcessing(docPid, "failed", 0, 0, "No chunks");
            return;
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
                    chunkPid, tenantId, kbPid, docPid,
                    chunk.index(), chunk.content(), chunk.charCount(), chunk.tokenCount(), chunk.content());
        }

        try {
            var kbEntity = kbService.findKbByPid(kbPid);
            String provider = kbEntity != null ? kbEntity.getEmbeddingProvider() : "openai";
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
            log.warn("Embedding failed: {}", e.getMessage());
        }

        kbService.updateDocumentAfterProcessing(docPid, "completed", content.length(), chunks.size(), null);
        kbService.refreshKbCounters(kbPid);
        log.info("RAG synced {}:{} — {} chunks", modelCode, recordId, chunks.size());
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

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }
}
