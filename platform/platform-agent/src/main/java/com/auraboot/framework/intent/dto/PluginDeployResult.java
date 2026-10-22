package com.auraboot.framework.intent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of plugin deployment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDeployResult {

    private boolean success;
    private String pluginCode;
    private String message;
    private int modelsCreated;
    private int fieldsCreated;
    private int commandsCreated;
    private int pagesCreated;
    private int menusCreated;
}
