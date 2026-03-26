package com.auraboot.framework.i18n.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of an AI-assisted translation batch job.
 *
 * @author AuraBoot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTranslationResult {

    /** Number of DRAFT translation entries successfully generated and saved. */
    private int generated;

    /** Number of keys skipped (already have an existing translation entry). */
    private int skipped;

    /** Number of keys that could not be translated due to LLM or parse errors. */
    private int errors;

    /** Target locale that was translated into. */
    private String targetLocale;

    /** Source locale that was used as the translation source. */
    private String sourceLocale;

    /**
     * Whether a real LLM was used.
     * {@code false} means the en-US fallback strategy was used instead.
     */
    private boolean llmUsed;
}
