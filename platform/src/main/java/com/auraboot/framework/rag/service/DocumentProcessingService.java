package com.auraboot.framework.rag.service;

import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Async document processing pipeline: parse → chunk → embed → store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final KbDocumentMapper docMapper;
    private final KnowledgeBaseService kbService;
    private final DocumentParserService parserService;
    private final KbChunkIngestPipeline ingestPipeline;
    private final FileService fileService;
    private final StorageProvider storageProvider;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Process a document asynchronously: parse file → chunk text → embed chunks → store vectors.
     */
    @Async("asyncTaskExecutor")
    public void processDocument(String kbPid, String docPid) {
        processDocumentNow(kbPid, docPid);
    }

    /**
     * Synchronous pipeline entry. Used by {@link DocumentReconcileService}, which runs on a
     * scheduler thread and needs the outcome to be observable when the call returns (an async
     * hop would make the reconcile pass report success before the work had actually happened).
     */
    public void processDocumentNow(String kbPid, String docPid) {
        log.info("Starting document processing: kb={}, doc={}", kbPid, docPid);

        // Mark as PROCESSING
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'processing', process_started_at = NOW() WHERE pid = ?",
                docPid);

        try {
            KbDocument doc = docMapper.selectOne(
                    new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getPid, docPid));
            if (doc == null) {
                log.error("Document not found: {}", docPid);
                return;
            }

            KnowledgeBase kb = kbService.findKbByPid(kbPid);
            if (kb == null) {
                markFailed(docPid, "Knowledge base not found: " + kbPid);
                return;
            }

            // 1. Parse: extract text from file
            String text = extractText(doc);
            if (text == null || text.isBlank()) {
                markFailed(docPid, "No text content extracted from document");
                return;
            }

            // A run that died mid-ingest can have left chunks behind, and the ingest pipeline only
            // ever INSERTs — so clear them first, or a reconcile / manual reprocess double-inserts
            // every chunk it already wrote.
            int stale = jdbcTemplate.update("DELETE FROM ab_kb_chunk WHERE doc_id = ?", docPid);
            if (stale > 0) {
                log.info("Cleared {} chunk(s) from a previous incomplete run of doc={}", stale, docPid);
            }

            // 2-4. Chunk → store → embed via the shared pipeline (G9)
            KbChunkIngestPipeline.IngestOutcome outcome = ingestPipeline.ingestChunks(
                    doc.getTenantId(), kbPid, docPid, text,
                    kb.getChunkSize(), kb.getChunkOverlap(), kb.getEmbeddingProvider(), null);
            if (outcome.chunkCount() == 0) {
                markFailed(docPid, "Chunking produced no results");
                return;
            }

            // 5. Update document and KB counters
            kbService.updateDocumentAfterProcessing(docPid, "completed",
                    text.length(), outcome.chunkCount(), null);
            kbService.refreshKbCounters(kbPid);

            log.info("Document processed: doc={}, chars={}, chunks={}, embedded={}",
                    docPid, text.length(), outcome.chunkCount(), outcome.embeddedCount());

        } catch (Exception e) {
            log.error("Document processing failed: doc={}", docPid, e);
            markFailed(docPid, e.getMessage());
        }
    }

    /**
     * Extract text content from a document.
     * For FILE source: stream the object out of the configured storage backend and parse it.
     * For ENTITY source: content is passed directly (future use).
     *
     * <p>The bytes are fetched through {@link StorageProvider#download(String)} rather than by
     * opening {@code file.getLocalPath()} as a filesystem path: under {@code aura.storage.type}
     * of minio/s3/oss that "local path" is a remote object key, and opening it locally would
     * throw FileNotFoundException. The storage key is the file entity's generated file name —
     * the same key {@code FileServiceImpl} uploads and deletes with.
     */
    private String extractText(KbDocument doc) throws Exception {
        if ("entity".equals(doc.getSourceType())) {
            // Entity content is stored as the doc_name field temporarily
            // (future: resolve from entity table via sourceEntityId)
            return null;
        }

        if (doc.getFilePid() == null || doc.getFilePid().isBlank()) {
            throw new IllegalStateException("Document has no file reference");
        }

        FileEntity file = fileService.findByPid(doc.getFilePid());
        if (file == null) {
            throw new IllegalStateException("File not found: " + doc.getFilePid());
        }

        String storageKey = file.getFileName();
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalStateException("File has no storage key: " + doc.getFilePid());
        }

        try (InputStream content = storageProvider.download(storageKey)) {
            return parserService.parse(content, doc.getDocType());
        }
    }

    private void markFailed(String docPid, String error) {
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'failed', error_message = ?, "
                + "process_completed_at = NOW() WHERE pid = ?",
                error != null && error.length() > 2000 ? error.substring(0, 2000) : error,
                docPid);
    }
}
