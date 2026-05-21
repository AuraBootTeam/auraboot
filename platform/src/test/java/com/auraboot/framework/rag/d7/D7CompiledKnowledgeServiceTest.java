package com.auraboot.framework.rag.d7;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class D7CompiledKnowledgeServiceTest {

    @Test
    @DisplayName("D7-01: Rank published fresh pages before stale pages and expose source paths")
    void retrieve_ranksFreshBeforeStaleAndExposesSourcePaths() {
        D7KnowledgeProperties properties = new D7KnowledgeProperties();
        D7CompiledKnowledgeService service = new D7CompiledKnowledgeService(new ObjectMapper(), properties);

        D7CompiledKnowledgePage decision = page("compiled.decision.d7", "fresh",
                "AuraBot compiled pages raw chunks retrieval decision",
                "docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md");
        D7CompiledKnowledgePage subsystem = page("compiled.subsystem.documentation", "fresh",
                "Documentation compiled knowledge",
                "docs/system-reference/subsystems/85-文档与知识管理深度分析.md");
        D7CompiledKnowledgePage stale = page("compiled.stale", "stale",
                "AuraBot compiled pages stale context",
                "docs/system-reference/subsystems/34-文档与知识库系统演进.md");
        D7CompiledKnowledgePage draft = page("compiled.draft", "fresh",
                "AuraBot compiled pages draft", "docs/draft.md");
        draft.setStatus("draft");

        List<D7CompiledKnowledgeMatch> matches = service.rank(
                "How should AuraBot use compiled pages before raw chunks?",
                List.of(stale, subsystem, draft, decision),
                1L,
                3);

        assertThat(matches).extracting(match -> match.getPage().getId())
                .containsExactly("compiled.decision.d7", "compiled.subsystem.documentation", "compiled.stale");
        assertThat(matches.get(0).isRequiresRawEvidence()).isFalse();
        assertThat(matches.get(2).isRequiresRawEvidence()).isTrue();
        assertThat(service.toRankedSourcePaths(matches)).containsExactly(
                "docs/system-reference/subsystems/96-AuraBoot知识系统重设计方案.md",
                "docs/system-reference/subsystems/85-文档与知识管理深度分析.md",
                "docs/system-reference/subsystems/34-文档与知识库系统演进.md");
    }

    @Test
    @DisplayName("D7-02: Exclude orphan, conflict, and tenant-mismatched pages")
    void retrieve_filtersUnavailablePages() {
        D7CompiledKnowledgeService service = new D7CompiledKnowledgeService(
                new ObjectMapper(), new D7KnowledgeProperties());

        D7CompiledKnowledgePage tenantMatch = page("compiled.tenant.match", "fresh",
                "tenant scoped model page", "docs/model.md");
        tenantMatch.setVisibility("tenant");
        tenantMatch.setTenantScope("42");
        D7CompiledKnowledgePage tenantMismatch = page("compiled.tenant.other", "fresh",
                "tenant scoped model page", "docs/other.md");
        tenantMismatch.setVisibility("tenant");
        tenantMismatch.setTenantScope("7");
        D7CompiledKnowledgePage conflict = page("compiled.conflict", "conflict",
                "conflict page", "docs/conflict.md");

        List<D7CompiledKnowledgeMatch> matches = service.rank(
                "tenant model page",
                List.of(tenantMismatch, conflict, tenantMatch),
                42L,
                5);

        assertThat(matches).extracting(match -> match.getPage().getId())
                .containsExactly("compiled.tenant.match");
    }

    @Test
    @DisplayName("D7-09: Match CJK queries with short phrase terms")
    void retrieve_matchesCjkQueriesWithShortPhraseTerms() {
        D7CompiledKnowledgeService service = new D7CompiledKnowledgeService(
                new ObjectMapper(), new D7KnowledgeProperties());

        D7CompiledKnowledgePage ops = page("compiled.ops", "fresh",
                "生产部署 安全配置 备份恢复 容量规划 RTO RPO",
                "docs/system-reference/subsystems/91-生产部署检查清单.md");

        List<D7CompiledKnowledgeMatch> matches = service.rank(
                "生产部署前应该检查哪些安全配置、备份恢复和容量规划项？",
                List.of(ops),
                1L,
                3);

        assertThat(matches).extracting(match -> match.getPage().getId())
                .containsExactly("compiled.ops");
    }

    private static D7CompiledKnowledgePage page(String id, String staleStatus, String text, String sourcePath) {
        return D7CompiledKnowledgePage.builder()
                .id(id)
                .type("decision")
                .status("published")
                .staleStatus(staleStatus)
                .visibility("internal")
                .tenantScope("global")
                .title(text)
                .summary(text)
                .body(text)
                .sourceRefs(List.of(D7SourceRef.builder()
                        .path(sourcePath)
                        .hash("sha256:test")
                        .build()))
                .build();
    }
}
