package com.auraboot.framework.agent.memory.extraction;

/**
 * One memory candidate produced by the rule-prefilter without LLM. Schema
 * mirrors what {@code RunLifecycleService.saveMemoryEntry} would persist —
 * type / title / content / importance — so the offline replay in Spike-4
 * phase 2 can diff this against historical LLM-extracted rows.
 *
 * <p>Test-only DTO. Not for production wiring (Spike-4 is data-only).
 */
public record ExtractedMemoryCandidate(
        String patternId,
        String memoryType,
        String title,
        String content,
        int importance,
        String rationale
) {

    public ExtractedMemoryCandidate {
        if (title == null) title = "";
        if (content == null) content = "";
        if (rationale == null) rationale = "";
        if (importance < 1) importance = 1;
        if (importance > 10) importance = 10;
    }
}
