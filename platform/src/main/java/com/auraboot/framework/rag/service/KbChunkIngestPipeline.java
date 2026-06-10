package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.util.CjkBigramSegmenter;
import com.auraboot.framework.rag.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Single shared chunk → store → embed sequence for every KB ingest path
 * (file upload, raw-text SPI, doc-knowledge entity sync, internal-doc import).
 *
 * <p>Consolidates the four previously mirrored copies (G9). Callers own the
 * {@code ab_kb_document} lifecycle (create/idempotency/status/counters); this
 * pipeline owns only the chunk rows.
 *
 * <p>Exit invariant: every inserted chunk ends {@code completed} or {@code failed}
 * — never a permanently {@code pending} row. A {@code null} embedding and a
 * thrown batch-embedding failure both mark the affected chunks {@code failed},
 * which is the pickup state for the embedding retry task (G6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbChunkIngestPipeline {

    static final int DEFAULT_CHUNK_SIZE = 500;
    static final int DEFAULT_CHUNK_OVERLAP = 50;

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    /** Result of one ingest run. {@code chunkCount == embeddedCount + failedCount}. */
    public record IngestOutcome(int chunkCount, int embeddedCount, int failedCount) {
        public static final IngestOutcome EMPTY = new IngestOutcome(0, 0, 0);
    }

    /**
     * Chunk {@code text}, insert pending chunk rows, embed them, and finalize each
     * row to {@code completed}/{@code failed}.
     *
     * @param chunkSize    nullable; falls back to {@value #DEFAULT_CHUNK_SIZE}
     * @param chunkOverlap nullable; falls back to {@value #DEFAULT_CHUNK_OVERLAP}
     * @param metadataFn   nullable per-chunk JSON metadata supplier
     */
    public IngestOutcome ingestChunks(long tenantId, String kbPid, String docPid, String text,
                                      Integer chunkSize, Integer chunkOverlap, String embeddingProvider,
                                      Function<ChunkingService.ChunkResult, String> metadataFn) {
        int size = chunkSize != null && chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        int overlap = chunkOverlap != null && chunkOverlap >= 0 ? chunkOverlap : DEFAULT_CHUNK_OVERLAP;

        List<ChunkingService.ChunkResult> chunks = chunkingService.chunk(text, size, overlap);
        if (chunks.isEmpty()) {
            return IngestOutcome.EMPTY;
        }

        List<String> chunkPids = new ArrayList<>(chunks.size());
        List<String> chunkTexts = new ArrayList<>(chunks.size());
        for (ChunkingService.ChunkResult chunk : chunks) {
            String chunkPid = UniqueIdGenerator.generate();
            chunkPids.add(chunkPid);
            chunkTexts.add(chunk.content());
            String metadata = metadataFn != null ? metadataFn.apply(chunk) : null;
            jdbcTemplate.update(
                    "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, "
                    + "content, char_count, token_count, metadata, tsv, embedding_status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, to_tsvector('simple', ?), 'pending', NOW(), NOW())",
                    chunkPid, tenantId, kbPid, docPid,
                    chunk.index(), chunk.content(), chunk.charCount(), chunk.tokenCount(),
                    metadata, CjkBigramSegmenter.segment(chunk.content()));
        }

        int embedded = 0;
        int failed = 0;
        try {
            List<float[]> embeddings = embeddingService.embedBatch(tenantId, chunkTexts, embeddingProvider);
            for (int i = 0; i < chunkPids.size(); i++) {
                float[] emb = i < embeddings.size() ? embeddings.get(i) : null;
                if (emb != null) {
                    jdbcTemplate.update(
                            "UPDATE ab_kb_chunk SET embedding = ?::vector, embedding_status = 'completed', "
                            + "updated_at = NOW() WHERE pid = ?",
                            VectorUtils.toVectorString(emb), chunkPids.get(i));
                    embedded++;
                } else {
                    markFailed(chunkPids.get(i));
                    failed++;
                }
            }
        } catch (Exception e) {
            log.warn("Embedding failed for doc {} ({} chunks marked failed for retry): {}",
                    docPid, chunkPids.size() - embedded, e.getMessage());
            for (int i = embedded; i < chunkPids.size(); i++) {
                markFailed(chunkPids.get(i));
                failed++;
            }
        }

        return new IngestOutcome(chunks.size(), embedded, failed);
    }

    private void markFailed(String chunkPid) {
        jdbcTemplate.update(
                "UPDATE ab_kb_chunk SET embedding_status = 'failed', updated_at = NOW() WHERE pid = ?",
                chunkPid);
    }
}
