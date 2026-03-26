package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolutionInstallResult {
    private boolean success;
    private String solutionCode;
    private int totalPlugins;
    private int installedPlugins;
    private int skippedPlugins;
    private int failedPlugins;
    private List<PluginInstallStatus> pluginResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PluginInstallStatus {
        private String pluginCode;
        private String status; // INSTALLED, SKIPPED, FAILED
        private String message;
    }
}
