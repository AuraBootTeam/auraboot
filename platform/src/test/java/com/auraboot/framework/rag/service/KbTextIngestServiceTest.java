package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.entity.KbDocument;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 (B): KbTextIngestService — text -> chunk -> embed -> store, idempotent per
 * (sourceType, sourceId), transactional. Collaborators mocked; the TransactionTemplate
 * is driven by a mock PlatformTransactionManager so the callback runs inline.
 */
class KbTextIngestServiceTest {

    private KnowledgeBaseService kbService;
    private KbDocumentMapper docMapper;
    private ChunkingService chunkingService;
    private EmbeddingService embeddingService;
    private JdbcTemplate jdbcTemplate;
    private PlatformTransactionManager txManager;
    private KbTextIngestService svc;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        docMapper = mock(KbDocumentMapper.class);
        chunkingService = mock(ChunkingService.class);
        embeddingService = mock(EmbeddingService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // Real pipeline wired with the same mocks — preserves chunk/embed assertions (G9)
        KbChunkIngestPipeline pipeline =
                new KbChunkIngestPipeline(chunkingService, embeddingService, jdbcTemplate);
        svc = new KbTextIngestService(kbService, docMapper, pipeline, jdbcTemplate, txManager);
        svc.initTx();
    }

    /** Build + register the KB mock OUTSIDE any ongoing when() chain (no nested stubbing). */
    private void stubKb(String kbPid, String provider) {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        when(kb.getEmbeddingProvider()).thenReturn(provider);
        when(kbService.findKbByPid(kbPid)).thenReturn(kb);
    }

    private static ChunkingService.ChunkResult chunk(int idx, String text) {
        return new ChunkingService.ChunkResult(idx, text, text.length(), Math.max(1, text.length() / 4));
    }

    // ---- guards ----

    @Test
    void blankText_returnsNull_noKbLookup() {
        assertNull(svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "   "));
        verify(kbService, never()).findKbByPid(anyString());
    }

    @Test
    void unknownKb_returnsNull() {
        when(kbService.findKbByPid("KB1")).thenReturn(null);
        assertNull(svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "some text"));
        verify(docMapper, never()).insert(any(KbDocument.class));
    }

    // ---- happy path ----

    @Test
    void happyPath_verifyWrites() {
        stubKb("KB1", "zhipu");
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk(0, "hello"), chunk(1, "world")));
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));

        String docPid = svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "hello world");

        org.junit.jupiter.api.Assertions.assertNotNull(docPid);
        verify(docMapper).insert(any(KbDocument.class));
        verify(embeddingService).embedBatch(eq(1L), any(), eq("zhipu"));
        // 2 embedding UPDATEs (one per chunk) — proves vectors are written back
        verify(jdbcTemplate, times(2)).update(contains("UPDATE ab_kb_chunk SET embedding"),
                anyString(), anyString());
        verify(kbService).updateDocumentAfterProcessing(anyString(), eq("completed"), eq(11), eq(2), eq(null));
        verify(kbService).refreshKbCounters("KB1");
    }


    @Test
    void normalizesLogicalSourceType_toInternalDoc_forDbConstraint() {
        // "crawler" is not in ab_kb_document.chk_doc_source {file,entity,internal_doc};
        // it must be persisted as internal_doc so the INSERT does not violate the constraint.
        stubKb("KB1", "openai");
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of(chunk(0, "x")));
        when(embeddingService.embedBatch(anyLong(), any(), anyString())).thenReturn(List.of(new float[]{0.1f}));

        svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "x");

        ArgumentCaptor<KbDocument> cap = ArgumentCaptor.forClass(KbDocument.class);
        verify(docMapper).insert(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals("internal_doc", cap.getValue().getSourceType());
        org.junit.jupiter.api.Assertions.assertEquals("u1", cap.getValue().getSourceEntityId());
        // dedup query also keyed on the normalized type
        verify(docMapper).selectList(any());
    }

    @Test
    void preservesAllowedSourceType_entity() {
        stubKb("KB1", "openai");
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of(chunk(0, "x")));
        when(embeddingService.embedBatch(anyLong(), any(), anyString())).thenReturn(List.of(new float[]{0.1f}));

        svc.ingestText(1L, "KB1", "entity", "rec1", "Doc", "x");

        ArgumentCaptor<KbDocument> cap = ArgumentCaptor.forClass(KbDocument.class);
        verify(docMapper).insert(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals("entity", cap.getValue().getSourceType());
    }

    @Test
    void defaultsProviderToOpenai_whenKbHasNone() {
        stubKb("KB1", null);
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of(chunk(0, "x")));
        when(embeddingService.embedBatch(anyLong(), any(), eq("openai")))
                .thenReturn(List.of(new float[]{0.1f}));

        svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "x");

        verify(embeddingService).embedBatch(eq(1L), any(), eq("openai"));
    }

    // ---- idempotent dedup ----

    @Test
    void reingest_deletesPriorDocAndChunks() {
        KbDocument prior = mock(KbDocument.class);
        when(prior.getPid()).thenReturn("OLD-DOC");
        when(prior.getId()).thenReturn(99L);
        stubKb("KB1", "openai");
        when(docMapper.selectList(any())).thenReturn(List.of(prior));
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of(chunk(0, "x")));
        when(embeddingService.embedBatch(anyLong(), any(), anyString()))
                .thenReturn(List.of(new float[]{0.1f}));

        svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "x");

        verify(jdbcTemplate).update(eq("DELETE FROM ab_kb_chunk WHERE doc_id = ?"), eq("OLD-DOC"));
        verify(docMapper).deleteById(99L);
        verify(docMapper).insert(any(KbDocument.class));
    }

    // ---- edge cases ----

    @Test
    void noChunks_marksFailed() {
        stubKb("KB1", "openai");
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of());

        svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "text");

        verify(kbService).updateDocumentAfterProcessing(anyString(), eq("failed"), eq(0), eq(0), eq("No chunks"));
        verify(embeddingService, never()).embedBatch(anyLong(), any(), anyString());
    }

    @Test
    void embeddingFailure_isSwallowed_docStillCompleted() {
        stubKb("KB1", "openai");
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(chunkingService.chunk(any(), anyInt(), anyInt())).thenReturn(List.of(chunk(0, "x")));
        when(embeddingService.embedBatch(anyLong(), any(), anyString()))
                .thenThrow(new RuntimeException("provider 500"));

        String docPid = svc.ingestText(1L, "KB1", "crawler", "u1", "Doc", "x");

        org.junit.jupiter.api.Assertions.assertNotNull(docPid);
        // no embedding vector written; chunk marked 'failed' (retry pickup state), doc still completed
        verify(jdbcTemplate, never()).update(contains("SET embedding = ?::vector"), anyString(), anyString());
        verify(jdbcTemplate).update(contains("embedding_status = 'failed'"), any(Object.class));
        verify(kbService).updateDocumentAfterProcessing(anyString(), eq("completed"), anyInt(), anyInt(), eq(null));
    }
}
