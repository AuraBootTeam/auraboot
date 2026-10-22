package com.auraboot.framework.intent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Result of plugin generation, containing all config JSON files.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginGenerateResult {

    /**
     * Generated plugin code.
     */
    private String pluginCode;

    /**
     * Generated plugin name.
     */
    private String pluginName;

    /**
     * Map of config file name -> JSON content.
     * Keys: models.json, fields.json, bindings.json, commands.json,
     *        pages.json, menus.json, i18n.json, permissions.json
     */
    private Map<String, Object> configs;

    /**
     * Summary of what was generated.
     */
    private String summary;

    /**
     * Number of models generated.
     */
    private int modelCount;

    /**
     * Number of fields generated.
     */
    private int fieldCount;

    /**
     * Number of commands generated.
     */
    private int commandCount;

    /**
     * Number of pages generated.
     */
    private int pageCount;
}
