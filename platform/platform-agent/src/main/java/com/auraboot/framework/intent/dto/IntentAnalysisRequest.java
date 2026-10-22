package com.auraboot.framework.intent.dto;

import lombok.Data;

/**
 * Request DTO for intent analysis endpoint.
 * Accepts requirement document content in text or markdown format.
 */
@Data
public class IntentAnalysisRequest {

    /**
     * The requirement document content.
     */
    private String content;

    /**
     * Content format: "text" or "markdown".
     */
    private String format = "text";
}
