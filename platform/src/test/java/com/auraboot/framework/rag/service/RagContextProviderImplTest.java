package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.dto.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagContextProviderImpl")
class RagContextProviderImplTest {

    @Mock
    private RagRetrievalService ragRetrievalService;

    @InjectMocks
    private RagContextProviderImpl provider;

    @Test
    @DisplayName("hasActiveKnowledgeBases delegates and returns true")
    void hasActiveDelegates() {
        when(ragRetrievalService.hasActiveKnowledgeBases(7L)).thenReturn(true);
        assertTrue(provider.hasActiveKnowledgeBases(7L));
        verify(ragRetrievalService).hasActiveKnowledgeBases(7L);
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases returns false when underlying service returns false")
    void hasActiveDelegatesFalse() {
        when(ragRetrievalService.hasActiveKnowledgeBases(8L)).thenReturn(false);
        assertFalse(provider.hasActiveKnowledgeBases(8L));
    }

    @Test
    @DisplayName("retrieveContext calls retrieve with topK=5 then buildRagContext")
    void retrieveContextDelegates() {
        List<RetrievalResult> rs = List.of(RetrievalResult.builder().chunkPid("c1").build());
        when(ragRetrievalService.retrieve(eq(1L), eq("q"), isNull(), eq(5), isNull())).thenReturn(rs);
        when(ragRetrievalService.buildRagContext(rs)).thenReturn("CTX");

        assertEquals("CTX", provider.retrieveContext(1L, "q", null));
        verify(ragRetrievalService).retrieve(1L, "q", null, 5, null);
        verify(ragRetrievalService).buildRagContext(rs);
    }

    @Test
    @DisplayName("retrieveContext passes through kbPids list")
    void retrieveContextWithKbPids() {
        List<String> kbPids = List.of("kb1");
        when(ragRetrievalService.retrieve(eq(2L), eq("hello"), eq(kbPids), eq(5), isNull()))
                .thenReturn(List.of());
        when(ragRetrievalService.buildRagContext(anyList())).thenReturn("");

        assertEquals("", provider.retrieveContext(2L, "hello", kbPids));
    }
}
