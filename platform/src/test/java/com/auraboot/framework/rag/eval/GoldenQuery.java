package com.auraboot.framework.rag.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One ground-truth query for RAG + D7 evaluation. See
 * {@code platform/src/test/resources/rag-eval/golden-queries.schema.json} and
 * {@code docs/backlog/2026-05-27-rag-d7-eval-harness-design.md} §3.
 *
 * <p>This is a test-only DTO; not for production wiring.
 */
public record GoldenQuery(
        String id,
        String language,
        @JsonProperty("length_class") String lengthClass,
        @JsonProperty("expected_path") String expectedPath,
        String query,
        @JsonProperty("expected_kb_pages") List<String> expectedKbPages,
        @JsonProperty("expected_d7_pages") List<String> expectedD7Pages,
        List<String> tags,
        String notes
) {

    /**
     * Defensive null handling — Jackson may pass null for missing arrays / strings.
     */
    public GoldenQuery {
        expectedKbPages = expectedKbPages == null ? List.of() : List.copyOf(expectedKbPages);
        expectedD7Pages = expectedD7Pages == null ? List.of() : List.copyOf(expectedD7Pages);
        tags = tags == null ? List.of() : List.copyOf(tags);
        notes = notes == null ? "" : notes;
    }

    public boolean expectsPathA() {
        return "A".equals(expectedPath) || "both".equals(expectedPath);
    }

    public boolean expectsPathB() {
        return "B".equals(expectedPath) || "both".equals(expectedPath);
    }

    public boolean expectsNeither() {
        return "neither".equals(expectedPath);
    }
}
