package com.auraboot.framework.rag.d7;

import com.auraboot.framework.rag.dto.RetrievalResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class D7RetrievalTraceWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("D7-06: Trace writer is disabled by default")
    void recordRetrieval_disabledDoesNotCreateOutput() {
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setTraceOutputPath(tempDir.resolve("trace.json").toString());
        properties.setGoldenQueryPath(writeGoldenQueries().toString());
        D7RetrievalTraceWriter writer = new D7RetrievalTraceWriter(objectMapper, properties);

        writer.recordRetrieval(1L, "How should D7 trace retrieval?", List.of(match("docs/a.md")), List.of());

        assertThat(tempDir.resolve("trace.json")).doesNotExist();
    }

    @Test
    @DisplayName("D7-07: Trace writer records golden query source paths and no-answer")
    void recordRetrieval_enabledWritesEvaluationShape() throws Exception {
        Path output = tempDir.resolve("trace.json");
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        properties.setTraceEnabled(true);
        properties.setTraceOutputPath(output.toString());
        properties.setGoldenQueryPath(writeGoldenQueries().toString());
        D7RetrievalTraceWriter writer = new D7RetrievalTraceWriter(objectMapper, properties);

        writer.recordRetrieval(1L, "How should D7 trace retrieval?", List.of(match("docs/a.md", "docs/b.md")),
                List.of(RetrievalResult.builder().chunkPid("chunk-1").content("raw").build()));
        writer.recordRetrieval(1L, "Unknown private contract?", List.of(), List.of());

        JsonNode root = objectMapper.readTree(Files.readString(output));

        assertThat(root.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.get("results")).hasSize(2);
        assertThat(root.at("/results/0/queryId").asText()).isEqualTo("GQ-D7-001");
        List<String> rankedPaths = objectMapper.convertValue(root.at("/results/0/rankedSourcePaths"),
                new TypeReference<>() {
                });
        assertThat(rankedPaths).containsExactly("docs/a.md", "docs/b.md");
        assertThat(root.at("/results/0/noAnswer").asBoolean()).isFalse();
        assertThat(root.at("/results/1/queryId").asText()).isEqualTo("GQ-D7-NOANSWER");
        assertThat(root.at("/results/1/noAnswer").asBoolean()).isTrue();
        assertThat(root.at("/results/1/rankedSourcePaths")).isEmpty();
    }

    private Path writeGoldenQueries() {
        Path path = tempDir.resolve("golden.json");
        String json = """
                {
                  "schemaVersion": 1,
                  "queries": [
                    {
                      "id": "GQ-D7-001",
                      "query": "How should D7 trace retrieval?",
                      "expectedBehavior": "answer_with_citations"
                    },
                    {
                      "id": "GQ-D7-NOANSWER",
                      "query": "Unknown private contract?",
                      "expectedBehavior": "refuse_or_report_no_source"
                    }
                  ]
                }
                """;
        try {
            Files.writeString(path, json);
            return path;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private D7CompiledKnowledgeMatch match(String... sourcePaths) {
        List<D7SourceRef> refs = Arrays.stream(sourcePaths)
                .map(path -> D7SourceRef.builder().path(path).hash("sha256:test").build())
                .toList();
        D7CompiledKnowledgePage page = D7CompiledKnowledgePage.builder()
                .id("compiled.test")
                .status("published")
                .staleStatus("fresh")
                .sourceRefs(refs)
                .build();
        return new D7CompiledKnowledgeMatch(page, 1.0, false);
    }
}
