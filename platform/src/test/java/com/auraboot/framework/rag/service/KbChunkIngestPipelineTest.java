package com.auraboot.framework.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the single shared chunk→store→embed pipeline (G9 consolidation).
 *
 * Invariant under test: after {@code ingestChunks} returns, every inserted chunk is
 * either {@code completed} or {@code failed} — never left {@code pending}.
 */
@ExtendWith(MockitoExtension.class)
class KbChunkIngestPipelineTest {

    @Mock private ChunkingService chunkingService;
    @Mock private EmbeddingService embeddingService;
    @Mock private JdbcTemplate jdbcTemplate;

    private KbChunkIngestPipeline pipeline;

    private static ChunkingService.ChunkResult chunk(int index, String content) {
        return new ChunkingService.ChunkResult(index, content, content.length(), content.length() / 4 + 1);
    }

    @BeforeEach
    void setUp() {
        pipeline = new KbChunkIngestPipeline(chunkingService, embeddingService, jdbcTemplate);
    }

    @Test
    void emptyText_returnsZeroOutcome_andInsertsNothing() {
        when(chunkingService.chunk(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        KbChunkIngestPipeline.IngestOutcome outcome =
                pipeline.ingestChunks(1L, "kb1", "doc1", "   ", 500, 50, "openai", null);

        assertThat(outcome.chunkCount()).isZero();
        assertThat(outcome.embeddedCount()).isZero();
        assertThat(outcome.failedCount()).isZero();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void happyPath_insertsPendingChunks_thenMarksCompleted() {
        when(chunkingService.chunk(eq("hello world"), eq(500), eq(50)))
                .thenReturn(List.of(chunk(0, "hello"), chunk(1, "world")));
        when(embeddingService.embedBatch(eq(1L), anyList(), eq("openai")))
                .thenReturn(Arrays.asList(new float[]{0.1f}, new float[]{0.2f}));

        KbChunkIngestPipeline.IngestOutcome outcome =
                pipeline.ingestChunks(1L, "kb1", "doc1", "hello world", 500, 50, "openai", null);

        assertThat(outcome.chunkCount()).isEqualTo(2);
        assertThat(outcome.embeddedCount()).isEqualTo(2);
        assertThat(outcome.failedCount()).isZero();
        // 2 pending inserts + 2 completed updates
        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO ab_kb_chunk"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate, times(2)).update(contains("embedding_status = 'completed'"),
                anyString(), anyString());
    }

    @Test
    void nullEmbedding_marksChunkFailed_notPending() {
        when(chunkingService.chunk(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk(0, "a"), chunk(1, "b")));
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenReturn(Arrays.asList(new float[]{0.1f}, null));

        KbChunkIngestPipeline.IngestOutcome outcome =
                pipeline.ingestChunks(1L, "kb1", "doc1", "ab", 500, 50, "openai", null);

        assertThat(outcome.embeddedCount()).isEqualTo(1);
        assertThat(outcome.failedCount()).isEqualTo(1);
        verify(jdbcTemplate, times(1)).update(contains("embedding_status = 'failed'"), any(Object.class));
    }

    @Test
    void embedBatchThrows_marksAllChunksFailed() {
        when(chunkingService.chunk(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk(0, "a"), chunk(1, "b")));
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("api down"));

        KbChunkIngestPipeline.IngestOutcome outcome =
                pipeline.ingestChunks(1L, "kb1", "doc1", "ab", 500, 50, "openai", null);

        assertThat(outcome.chunkCount()).isEqualTo(2);
        assertThat(outcome.embeddedCount()).isZero();
        assertThat(outcome.failedCount()).isEqualTo(2);
        // batch-failure path marks every stored chunk failed in one statement per chunk
        verify(jdbcTemplate, times(2)).update(contains("embedding_status = 'failed'"), any(Object.class));
    }

    @Test
    void nullChunkSizeAndOverlap_fallBackToDefaults() {
        when(chunkingService.chunk(eq("text"), eq(500), eq(50))).thenReturn(List.of());

        pipeline.ingestChunks(1L, "kb1", "doc1", "text", null, null, "openai", null);

        verify(chunkingService).chunk("text", 500, 50);
    }

    @Test
    void metadataFn_passedThroughAsJsonbParam() {
        when(chunkingService.chunk(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk(0, "a")));
        lenient().when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenReturn(Arrays.asList((float[]) null));

        pipeline.ingestChunks(1L, "kb1", "doc1", "a", 500, 50, "openai",
                c -> "{\"filePath\":\"x.md\",\"chunkIndex\":" + c.index() + "}");

        ArgumentCaptor<Object> metaCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(contains("?::jsonb"),
                any(), any(), any(), any(), any(), any(), any(), any(),
                metaCaptor.capture(), any());
        assertThat(metaCaptor.getValue()).isEqualTo("{\"filePath\":\"x.md\",\"chunkIndex\":0}");
    }
}
