package com.auraboot.framework.plugin.dto.uninstall;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request to execute plugin uninstall with user decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UninstallRequest {

    /**
     * Whether to also remove business data created by the plugin.
     * If false, only metadata (models, fields, etc.) will be removed.
     */
    @Builder.Default
    private boolean removeData = false;

    /**
     * User decisions for resources that need explicit handling.
     * Key: resource code
     * Value: decision (DELETE, KEEP_AND_DETACH)
     */
    private Map<String, UninstallDecision> decisions;

    /**
     * Force uninstall even if there are unresolved conflicts.
     * Use with caution - will delete all SHARED resources without user decisions.
     */
    @Builder.Default
    private boolean force = false;
}
