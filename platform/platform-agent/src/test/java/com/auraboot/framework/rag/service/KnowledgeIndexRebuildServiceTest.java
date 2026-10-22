package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexRebuildServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private JdbcTemplate jdbc;

    private KnowledgeIndexRebuildService service;
    private KnowledgeBase kb;

    @BeforeEach
    void setUp() {
        service = new KnowledgeIndexRebuildService(
                knowledgeBaseService, embeddingService, jdbc);
        kb = KnowledgeBase.builder()
                .tenantId(7L)
                .pid("kb-one")
                .status("active")
                .activeIndexReleasePid("release-active")
                .embeddingProvider("provider-under-test")
                .embeddingModel("embedding-under-test")
                .embeddingDimension(2)
                .chunkStrategy("recursive")
                .chunkSize(500)
                .chunkOverlap(50)
                .build();
        when(knowledgeBaseService.requireActiveKnowledgeBase(7L, "kb-one"))
                .thenReturn(kb);
        when(jdbc.queryForObject(
                contains("SELECT id"),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(1L);
        when(jdbc.queryForObject(
                contains("MAX(release_no)"),
                eq(Integer.class),
                any(Object[].class)))
                .thenReturn(2);
    }

    @Test
    void emptyActiveReleaseIsRecordedFailedAndNeverActivated() {
        when(jdbc.queryForList(
                contains("FROM ab_kb_chunk"),
                any(Object[].class)))
                .thenReturn(List.of());

        KnowledgeIndexRebuildService.RebuildResult result =
                service.rebuildText(7L, 11L, "kb-one");

        assertThat(result.state()).isEqualTo("failed");
        verify(jdbc).update(
                contains("SET state = 'failed'"),
                any(Object[].class));
        verify(jdbc, never()).update(
                contains("UPDATE ab_knowledge_base SET active_index_release_pid"),
                any(Object[].class));
    }

    @Test
    void vectorDimensionMismatchFailsBeforePointerSwitch() {
        when(jdbc.queryForList(
                contains("FROM ab_kb_chunk"),
                any(Object[].class)))
                .thenReturn(List.of(chunk()));
        when(embeddingService.embedBatch(
                7L,
                List.of("knowledge"),
                "provider-under-test"))
                .thenReturn(List.of(new float[]{0.1f}));

        KnowledgeIndexRebuildService.RebuildResult result =
                service.rebuildVector(7L, 11L, "kb-one");

        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.errorMessage()).contains("dimension");
        verify(jdbc, never()).update(
                contains("UPDATE ab_knowledge_base SET active_index_release_pid"),
                any(Object[].class));
    }

    @Test
    void completedTextReleaseSwitchesPointerOnlyAfterChunkCopy() {
        when(jdbc.queryForList(
                contains("FROM ab_kb_chunk"),
                any(Object[].class)))
                .thenReturn(List.of(chunk()));

        KnowledgeIndexRebuildService.RebuildResult result =
                service.rebuildText(7L, 11L, "kb-one");

        assertThat(result.state()).isEqualTo("active");
        assertThat(result.indexedChunks()).isEqualTo(1);
        var order = org.mockito.Mockito.inOrder(jdbc);
        order.verify(jdbc).update(
                contains("INSERT INTO ab_kb_chunk"),
                any(Object[].class));
        order.verify(jdbc).update(
                contains("UPDATE ab_knowledge_base SET active_index_release_pid"),
                any(Object[].class));
    }

    private Map<String, Object> chunk() {
        return Map.ofEntries(
                Map.entry("tenant_id", 7L),
                Map.entry("kb_id", "kb-one"),
                Map.entry("doc_id", "doc-one"),
                Map.entry("document_version_pid", "version-one"),
                Map.entry("chunk_index", 0),
                Map.entry("content", "knowledge"),
                Map.entry("char_count", 9),
                Map.entry("token_count", 3),
                Map.entry("metadata", "{}"),
                Map.entry("tsv_text", "'knowledge':1"),
                Map.entry("embedding_text", "[0.1,0.2]"));
    }
}
