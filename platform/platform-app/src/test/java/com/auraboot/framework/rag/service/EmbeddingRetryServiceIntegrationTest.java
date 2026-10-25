package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * G6: bounded retry of failed chunk embeddings over the real database.
 */
class EmbeddingRetryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private EmbeddingRetryService retryService;
    @Autowired private KnowledgeBaseService kbService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private EmbeddingService embeddingService;

    private String seedFailedChunk(String kbPid, String content, int retryCount) {
        String docPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_kb_document (pid, tenant_id, kb_id, doc_name, doc_type, file_size, "
                + "char_count, chunk_count, source_type, status, created_at) "
                + "VALUES (?, ?, ?, ?, 'md', 1, 1, 1, 'internal_doc', 'completed', NOW())",
                docPid, getTestTenant().getId(), kbPid, "retry-doc-" + docPid);
        String chunkPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_kb_chunk (pid, tenant_id, kb_id, doc_id, chunk_index, content, "
                + "char_count, token_count, tsv, embedding_status, embedding_retry_count, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, ?, ?, 1, to_tsvector('simple', ?), 'failed', ?, NOW(), NOW())",
                chunkPid, getTestTenant().getId(), kbPid, docPid, content, content.length(), content, retryCount);
        return chunkPid;
    }

    private String createKb() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("retry-kb-" + System.nanoTime());
        KnowledgeBaseDTO kb = kbService.createKnowledgeBase(getTestTenant().getId(), getTestUser().getId(), req);
        return kb.getPid();
    }

    @Test
    @DisplayName("G6: failed chunk recovers to completed when embedding succeeds on retry")
    void retry_recoversFailedChunk() {
        String kbPid = createKb();
        String chunkPid = seedFailedChunk(kbPid, "retry me", 0);
        float[] vec = new float[1536];
        java.util.Arrays.fill(vec, 0.5f);
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenReturn(List.of(vec));

        int recovered = retryService.retryFailedChunks();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT embedding_status FROM ab_kb_chunk WHERE pid = ?", String.class, chunkPid);
        assertThat(status).isEqualTo("completed");
    }

    @Test
    @DisplayName("G6: failing retry increments retry_count; at cap chunk becomes failed_permanent")
    void retry_incrementsCount_andExhausts() {
        String kbPid = createKb();
        String young = seedFailedChunk(kbPid, "young chunk", 0);
        String old = seedFailedChunk(kbPid, "old chunk", EmbeddingRetryService.MAX_RETRIES - 1);
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

        int recovered = retryService.retryFailedChunks();

        assertThat(recovered).isZero();
        Integer youngCount = jdbcTemplate.queryForObject(
                "SELECT embedding_retry_count FROM ab_kb_chunk WHERE pid = ?", Integer.class, young);
        String youngStatus = jdbcTemplate.queryForObject(
                "SELECT embedding_status FROM ab_kb_chunk WHERE pid = ?", String.class, young);
        assertThat(youngCount).isEqualTo(1);
        assertThat(youngStatus).isEqualTo("failed");

        String oldStatus = jdbcTemplate.queryForObject(
                "SELECT embedding_status FROM ab_kb_chunk WHERE pid = ?", String.class, old);
        assertThat(oldStatus).isEqualTo("failed_permanent");
    }

    @Test
    @DisplayName("G6: failed_permanent chunks are not picked up again")
    void retry_skipsExhaustedChunks() {
        String kbPid = createKb();
        String chunkPid = seedFailedChunk(kbPid, "exhausted", 0);
        jdbcTemplate.update(
                "UPDATE ab_kb_chunk SET embedding_status = 'failed_permanent', embedding_retry_count = ? WHERE pid = ?",
                EmbeddingRetryService.MAX_RETRIES, chunkPid);
        List<float[]> never = new ArrayList<>();
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString())).thenReturn(never);

        int recovered = retryService.retryFailedChunks();

        assertThat(recovered).isZero();
        String status = jdbcTemplate.queryForObject(
                "SELECT embedding_status FROM ab_kb_chunk WHERE pid = ?", String.class, chunkPid);
        assertThat(status).isEqualTo("failed_permanent");
    }
}
