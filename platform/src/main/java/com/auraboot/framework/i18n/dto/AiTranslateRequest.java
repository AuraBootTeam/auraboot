package com.auraboot.framework.i18n.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for AI-assisted batch translation.
 *
 * @author AuraBoot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTranslateRequest {

    /**
     * Target locale to generate translations for (e.g. "ja-JP", "ko-KR").
     */
    private String targetLocale;

    /**
     * Source locale whose values are used as input for the LLM.
     * Defaults to "zh-CN".
     */
    @Builder.Default
    private String sourceLocale = "zh-CN";

    /**
     * Maximum number of missing keys to translate in this batch.
     * Must be between 1 and 200. Defaults to 50.
     */
    @Builder.Default
    private int maxKeys = 50;
}
