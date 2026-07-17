package com.auraboot.framework.agent.service;

import com.auraboot.framework.rag.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CAP-01 semantic pre-recall pass in {@link ActiveMemoryService}.
 * The vector SQL itself ({@code AgentMemoryService.searchScopedSemantic}) is verified by
 * construction (its scope predicate is copied verbatim from {@code searchScoped} and its
 * {@code embedding <=> ?::vector} binding mirrors the proven MemoryL1L2Promoter query);
 * these tests pin the blending / dedup / opt-in / graceful-fallback wiring.
 */
class ActiveMemoryServiceSemanticRecallTest {

    private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private ActiveMemoryService service;

    @BeforeEach
    void setup() {
        service = new ActiveMemoryService(memoryService);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "semanticRecallEnabled", true);
        ReflectionTestUtils.setField(service, "semanticLimit", 5);
        ReflectionTestUtils.setField(service, "semanticProvider", "openai");
        when(memoryService.loadScopedByImportance(anyLong(), any(), anyString(), anyInt()))
                .thenReturn(List.of());
    }

    private Map<String, Object> row(String pid) {
        Map<String, Object> m = new HashMap<>();
        m.put("pid", pid);
        m.put("memory_type", "preference");
        m.put("memory_title", "t-" + pid);
        m.put("memory_content", "c-" + pid);
        m.put("importance", 5);
        m.put("scope", "user");
        m.put("shadow_mode", false);
        return m;
    }

    @Test
    void semanticHitsAreBlendedAndDedupedWhenEnabled() {
        when(memoryService.searchScoped(anyLong(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(row("kw1")));
        when(embeddingService.embed(anyLong(), anyString(), anyString()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(memoryService.searchScopedSemantic(anyLong(), any(), anyString(), any(), anyInt()))
                .thenReturn(List.of(row("sem1"), row("kw1")));  // sem1 new, kw1 duplicates the keyword hit

        List<Map<String, Object>> out = service.preRecall(7L, "u1", "aurabot", "上次那个客户");

        List<Object> pids = out.stream().map(m -> m.get("pid")).toList();
        assertThat(pids).contains("kw1", "sem1");
        assertThat(pids).filteredOn("kw1"::equals).as("keyword+semantic overlap deduped").hasSize(1);
        verify(memoryService).searchScopedSemantic(eq(7L), eq("u1"), eq("aurabot"), any(), eq(5));
    }

    @Test
    void semanticSkippedWhenDisabled() {
        ReflectionTestUtils.setField(service, "semanticRecallEnabled", false);
        when(memoryService.searchScoped(anyLong(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        service.preRecall(7L, "u1", "aurabot", "hi");

        verify(memoryService, never()).searchScopedSemantic(anyLong(), any(), anyString(), any(), anyInt());
        verify(embeddingService, never()).embed(anyLong(), anyString(), any());
    }

    @Test
    void semanticSkippedWhenEmbeddingProviderUnavailable() {
        // embeddingService not wired at all
        ReflectionTestUtils.setField(service, "embeddingService", null);
        when(memoryService.searchScoped(anyLong(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        service.preRecall(7L, "u1", "aurabot", "hi");

        verify(memoryService, never()).searchScopedSemantic(anyLong(), any(), anyString(), any(), anyInt());
    }

    @Test
    void semanticSkippedWhenEmbeddingReturnsNull() {
        when(memoryService.searchScoped(anyLong(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(embeddingService.embed(anyLong(), anyString(), anyString())).thenReturn(null);

        service.preRecall(7L, "u1", "aurabot", "hi");

        verify(memoryService, never()).searchScopedSemantic(anyLong(), any(), anyString(), any(), anyInt());
    }
}
