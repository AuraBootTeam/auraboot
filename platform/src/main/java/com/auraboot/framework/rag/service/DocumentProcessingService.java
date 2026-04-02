package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import com.auraboot.framework.rag.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final FileService fileService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Process a document asynchronously: parse file → chunk text → embed chunks → store vectors.
     */
    @Async("asyncTaskExecutor")
    public void processDocument(String kbPid, String docPid) {
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

            // 2. Chunk: split text into pieces
            List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(
                    text, kb.getChunkSize(), kb.getChunkOverlap());
            if (chunks.isEmpty()) {
                markFailed(docPid, "Chunking produced no results");
                return;
            }

            // 3. Store chunks (without embeddings first)
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
                        chunkPid, doc.getTenantId(), kbPid, docPid,
                        chunk.index(), chunk.content(), chunk.charCount(), chunk.tokenCount(),
                        chunk.content());
            }

            // 4. Embed: batch call embedding API
            int embeddedCount = 0;
            try {
                List<float[]> embeddings = embeddingService.embedBatch(
                        doc.getTenantId(), chunkTexts, kb.getEmbeddingProvider());

                for (int i = 0; i < embeddings.size() && i < chunkPids.size(); i++) {
                    float[] emb = embeddings.get(i);
                    if (emb != null) {
                        jdbcTemplate.update(
                                "UPDATE ab_kb_chunk SET embedding = ?::vector, embedding_status = 'completed', "
                                + "updated_at = NOW() WHERE pid = ?",
                                VectorUtils.toVectorString(emb), chunkPids.get(i));
                        embeddedCount++;
                    } else {
                        jdbcTemplate.update(
                                "UPDATE ab_kb_chunk SET embedding_status = 'failed', updated_at = NOW() WHERE pid = ?",
                                chunkPids.get(i));
                    }
                }
            } catch (Exception e) {
                log.warn("Embedding failed for doc {}, chunks stored without vectors: {}", docPid, e.getMessage());
                // Chunks are still stored — embedding can be retried later
            }

            // 5. Update document and KB counters
            kbService.updateDocumentAfterProcessing(docPid, "completed",
                    text.length(), chunks.size(), null);
            kbService.refreshKbCounters(kbPid);

            log.info("Document processed: doc={}, chars={}, chunks={}, embedded={}",
                    docPid, text.length(), chunks.size(), embeddedCount);

        } catch (Exception e) {
            log.error("Document processing failed: doc={}", docPid, e);
            markFailed(docPid, e.getMessage());
        }
    }

    /**
     * Extract text content from a document.
     * For FILE source: resolve file path and parse.
     * For ENTITY source: content is passed directly (future use).
     */
    private String extractText(KbDocument doc) throws Exception {
        if ("entity".equals(doc.getSourceType())) {
            // Entity content is stored as the doc_name field temporarily
            // (future: resolve from entity table via sourceEntityId)
            return null;
        }

        // FILE source — resolve local file path
        if (doc.getFilePid() == null || doc.getFilePid().isBlank()) {
            throw new IllegalStateException("Document has no file reference");
        }

        FileEntity file = fileService.findByPid(doc.getFilePid());
        if (file == null) {
            throw new IllegalStateException("File not found: " + doc.getFilePid());
        }

        String localPath = file.getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalStateException("File has no local path: " + doc.getFilePid());
        }

        return parserService.parse(localPath, doc.getDocType());
    }

    private void markFailed(String docPid, String error) {
        jdbcTemplate.update(
                "UPDATE ab_kb_document SET status = 'failed', error_message = ?, "
                + "process_completed_at = NOW() WHERE pid = ?",
                error != null && error.length() > 2000 ? error.substring(0, 2000) : error,
                docPid);
    }
}
