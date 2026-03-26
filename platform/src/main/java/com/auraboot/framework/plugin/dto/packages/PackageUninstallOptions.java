package com.auraboot.framework.plugin.dto.packages;

import com.auraboot.framework.plugin.dto.uninstall.UninstallDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Options for package uninstallation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageUninstallOptions {

    /**
     * Skip config component uninstallation.
     */
    @Builder.Default
    private boolean skipConfig = false;

    /**
     * Skip backend component uninstallation.
     */
    @Builder.Default
    private boolean skipBackend = false;

    /**
     * Skip frontend component uninstallation.
     */
    @Builder.Default
    private boolean skipFrontend = false;

    /**
     * Remove all plugin data including user modifications.
     */
    @Builder.Default
    private boolean removeAllData = false;

    /**
     * Force uninstallation even if there are dependents.
     */
    @Builder.Default
    private boolean force = false;

    /**
     * Resource-specific uninstall decisions.
     * Key: resource PID, Value: decision (DELETE, DETACH, KEEP)
     */
    private Map<String, UninstallDecision> resourceDecisions;

    /**
     * Default decision for resources without specific decision.
     */
    @Builder.Default
    private UninstallDecision defaultDecision = UninstallDecision.DELETE;

    /**
     * Remove deployed frontend assets.
     */
    @Builder.Default
    private boolean removeFrontendAssets = true;

    /**
     * Remove backend JAR file.
     */
    @Builder.Default
    private boolean removeBackendJar = true;
}
