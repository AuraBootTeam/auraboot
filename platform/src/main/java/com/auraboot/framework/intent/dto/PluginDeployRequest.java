package com.auraboot.framework.intent.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request DTO for deploying a generated plugin configuration.
 */
@Data
public class PluginDeployRequest {

    /**
     * Plugin code.
     */
    private String pluginCode;

    /**
     * Plugin name.
     */
    private String pluginName;

    /**
     * Map of config file name -> JSON content (from PluginGenerateResult.configs).
     */
    private Map<String, Object> configs;
}
