package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.d7.D7CompiledKnowledgeMatch;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgePage;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeService;
import com.auraboot.framework.rag.d7.D7ContextAssembler;
import com.auraboot.framework.rag.d7.D7KnowledgeProperties;
import com.auraboot.framework.rag.d7.D7RetrievalTraceWriter;
import com.auraboot.framework.rag.d7.D7SourceRef;
import com.auraboot.framework.rag.dto.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagContextProviderImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("hasActiveKnowledgeBases keeps raw RAG as first signal")
    void hasActiveKnowledgeBases_rawRagWins() {
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        D7CompiledKnowledgeService d7Service = mock(D7CompiledKnowledgeService.class);
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setEnabled(true);

        when(ragRetrievalService.hasActiveKnowledgeBases(7L)).thenReturn(true);

        RagContextProviderImpl provider = new RagContextProviderImpl(
                ragRetrievalService,
                d7Service,
                new D7ContextAssembler(),
                mock(D7RetrievalTraceWriter.class),
                properties);

        assertThat(provider.hasActiveKnowledgeBases(7L)).isTrue();
        verifyNoInteractions(d7Service);
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases can use compiled D7 pages when raw RAG is empty")
    void hasActiveKnowledgeBases_d7FallbackWhenEnabled() {
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        D7CompiledKnowledgeService d7Service = mock(D7CompiledKnowledgeService.class);
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setEnabled(true);

        when(ragRetrievalService.hasActiveKnowledgeBases(42L)).thenReturn(false);
        when(d7Service.hasRetrievablePages(42L)).thenReturn(true);

        RagContextProviderImpl provider = new RagContextProviderImpl(
                ragRetrievalService,
                d7Service,
                new D7ContextAssembler(),
                mock(D7RetrievalTraceWriter.class),
                properties);

        assertThat(provider.hasActiveKnowledgeBases(42L)).isTrue();
    }

    @Test
    @DisplayName("D7-05: Feature flag disabled keeps raw RAG behavior")
    void retrieveContext_d7DisabledUsesRawRagOnly() {
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        D7CompiledKnowledgeService d7Service = mock(D7CompiledKnowledgeService.class);
        D7RetrievalTraceWriter traceWriter = mock(D7RetrievalTraceWriter.class);
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setEnabled(false);
        List<RetrievalResult> rawResults = List.of(RetrievalResult.builder()
                .chunkPid("chunk-raw")
                .docName("raw-doc")
                .content("raw only")
                .chunkIndex(0)
                .build());

        when(ragRetrievalService.retrieve(1L, "query", List.of("kb1"), 5, null)).thenReturn(rawResults);

        RagContextProviderImpl provider = new RagContextProviderImpl(
                ragRetrievalService,
                d7Service,
                new D7ContextAssembler(),
                traceWriter,
                properties);

        String context = provider.retrieveContext(1L, "query", List.of("kb1"));

        assertThat(context).contains("## Retrieved Knowledge");
        assertThat(context).contains("[Source: raw-doc, Chunk 0]");
        assertThat(context).contains("raw only");
        verify(ragRetrievalService).retrieve(1L, "query", List.of("kb1"), 5, null);
        verifyNoInteractions(d7Service, traceWriter);
    }

    @Test
    @DisplayName("D7-04: Feature flag retrieves compiled pages before raw chunks")
    void retrieveContext_d7EnabledPrependsCompiledKnowledge() {
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        D7CompiledKnowledgeService d7Service = mock(D7CompiledKnowledgeService.class);
        D7RetrievalTraceWriter traceWriter = mock(D7RetrievalTraceWriter.class);
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setEnabled(true);
        properties.setMaxCompiledPages(2);
        properties.setRawTopK(4);

        D7CompiledKnowledgePage page = D7CompiledKnowledgePage.builder()
                .id("compiled.decision.d7")
                .title("D7 retrieval decision")
                .summary("Use compiled pages before raw chunks.")
                .body("D7 context first.")
                .staleStatus("fresh")
                .sourceRefs(List.of(D7SourceRef.builder()
                        .path("docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md")
                        .build()))
                .build();
        D7CompiledKnowledgeMatch match = new D7CompiledKnowledgeMatch(page, 1.0, false);
        List<RetrievalResult> rawResults = List.of(RetrievalResult.builder()
                .chunkPid("chunk-1")
                .docName("raw-doc")
                .content("raw content")
                .chunkIndex(1)
                .build());

        when(d7Service.retrieve(1L, "query", 2)).thenReturn(List.of(match));
        when(ragRetrievalService.retrieve(1L, "query", List.of("kb1"), 4, null)).thenReturn(rawResults);

        RagContextProviderImpl provider = new RagContextProviderImpl(
                ragRetrievalService,
                d7Service,
                new D7ContextAssembler(),
                traceWriter,
                properties);

        String context = provider.retrieveContext(1L, "query", List.of("kb1"));

        assertThat(context).contains("## Retrieved Knowledge");
        assertThat(context).contains("[Compiled: D7 retrieval decision]");
        assertThat(context).contains("[Source: raw-doc, Chunk 1]");
        // RRF with compiled weight 1.5: top compiled page precedes top raw chunk
        assertThat(context.indexOf("[Compiled: D7 retrieval decision]"))
                .isLessThan(context.indexOf("[Source: raw-doc, Chunk 1]"));
        verify(d7Service).retrieve(1L, "query", 2);
        verify(ragRetrievalService).retrieve(1L, "query", List.of("kb1"), 4, null);
        verify(traceWriter).recordRetrieval(1L, "query", List.of(match), rawResults);
    }

    @Test
    @DisplayName("D7-08: Runtime path captures ranked source paths for golden query evaluation")
    void retrieveContext_d7EnabledWritesRuntimeTrace() throws Exception {
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        D7CompiledKnowledgeService d7Service = mock(D7CompiledKnowledgeService.class);
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setEnabled(true);
        properties.setTraceEnabled(true);
        properties.setTraceOutputPath(tempDir.resolve("trace.json").toString());
        properties.setGoldenQueryPath(writeGoldenQueries().toString());

        D7CompiledKnowledgePage page = D7CompiledKnowledgePage.builder()
                .id("compiled.d7.runtime")
                .title("D7 runtime trace")
                .sourceRefs(List.of(D7SourceRef.builder()
                        .path("docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md")
                        .build()))
                .build();
        D7CompiledKnowledgeMatch match = new D7CompiledKnowledgeMatch(page, 1.0, false);

        when(d7Service.retrieve(1L, "How should D7 trace retrieval?", 3)).thenReturn(List.of(match));
        when(ragRetrievalService.retrieve(1L, "How should D7 trace retrieval?", List.of("kb1"), 5, null))
                .thenReturn(List.of());
        when(ragRetrievalService.buildRagContext(List.of())).thenReturn("");

        RagContextProviderImpl provider = new RagContextProviderImpl(
                ragRetrievalService,
                d7Service,
                new D7ContextAssembler(),
                new D7RetrievalTraceWriter(objectMapper, properties),
                properties);

        provider.retrieveContext(1L, "How should D7 trace retrieval?", List.of("kb1"));

        JsonNode root = objectMapper.readTree(Files.readString(tempDir.resolve("trace.json")));
        assertThat(root.at("/results/0/queryId").asText()).isEqualTo("GQ-D7-001");
        assertThat(root.at("/results/0/rankedSourcePaths/0").asText())
                .isEqualTo("docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md");
        assertThat(root.at("/results/0/noAnswer").asBoolean()).isFalse();
    }

    private Path writeGoldenQueries() throws Exception {
        Path path = tempDir.resolve("golden.json");
        String json = """
                {
                  "schemaVersion": 1,
                  "queries": [
                    {
                      "id": "GQ-D7-001",
                      "query": "How should D7 trace retrieval?",
                      "expectedBehavior": "answer_with_citations"
                    }
                  ]
                }
                """;
        Files.writeString(path, json);
        return path;
    }
}
