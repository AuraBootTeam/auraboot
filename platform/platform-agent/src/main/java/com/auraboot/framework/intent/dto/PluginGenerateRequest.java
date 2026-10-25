package com.auraboot.framework.intent.dto;

import lombok.Data;

/**
 * Request DTO for plugin generation from an analysis result.
 */
@Data
public class PluginGenerateRequest {

    /**
     * The intent analysis result to generate a plugin from.
     */
    private IntentAnalysisResult analysis;

    /**
     * Plugin code (lowercase, hyphenated). e.g. "order-management"
     */
    private String pluginCode;

    /**
     * Plugin display name. e.g. "Order Management"
     */
    private String pluginName;
}
