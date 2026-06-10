package com.auraboot.framework.rag.d7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class D7ContextAssemblerTest {

    @Test
    @DisplayName("D7-03: AuraBot context places compiled knowledge before raw RAG chunks")
    void buildAuraBotContext_compiledKnowledgeBeforeRawChunks() {
        D7ContextAssembler assembler = new D7ContextAssembler();
        D7CompiledKnowledgePage page = D7CompiledKnowledgePage.builder()
                .id("compiled.decision.d7")
                .type("decision")
                .status("published")
                .staleStatus("stale")
                .visibility("internal")
                .tenantScope("global")
                .title("D7 retrieval decision")
                .summary("AuraBot should use compiled pages before raw chunks.")
                .body("Compiled pages carry reviewed subsystem and decision context.")
                .sourceRefs(List.of(D7SourceRef.builder()
                        .path("docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md")
                        .hash("sha256:test")
                        .build()))
                .build();

        String context = assembler.buildAuraBotContext(
                List.of(new D7CompiledKnowledgeMatch(page, 0.8, true)),
                "## Reference Knowledge\nraw chunk");

        assertThat(context).contains("## Compiled Knowledge");
        assertThat(context).contains("### [Compiled: D7 retrieval decision]");
        assertThat(context).contains("[Source: docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md]");
        assertThat(context).contains("Requires raw evidence: true");
        assertThat(context.indexOf("## Compiled Knowledge"))
                .isLessThan(context.indexOf("## Reference Knowledge"));
    }

    @Test
    @DisplayName("G4: fused context drops items beyond the token budget but always keeps the first")
    void buildFusedContext_respectsTokenBudget() {
        D7ContextAssembler assembler = new D7ContextAssembler();
        String bigContent = "x".repeat(4000); // ~1000 estimated tokens each
        var raws = new java.util.ArrayList<com.auraboot.framework.rag.dto.RetrievalResult>();
        for (int i = 0; i < 5; i++) {
            raws.add(com.auraboot.framework.rag.dto.RetrievalResult.builder()
                    .chunkPid("c" + i).docName("doc" + i).chunkIndex(i).content(bigContent).build());
        }
        var fused = D7RagFusion.fuse(List.of(), raws, 60, 1.5);

        String budgeted = assembler.buildFusedContext(fused, 1500);

        assertThat(budgeted).contains("[Source: doc0, Chunk 0]");
        assertThat(budgeted).doesNotContain("[Source: doc2, Chunk 2]");
        assertThat(budgeted).contains("omitted for context budget");

        String unbounded = assembler.buildFusedContext(fused, 0);
        assertThat(unbounded).contains("[Source: doc4, Chunk 4]");
        assertThat(unbounded).doesNotContain("omitted");
    }
}
