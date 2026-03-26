package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for package installation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageInstallOptions {

    /**
     * Skip config component installation.
     */
    @Builder.Default
    private boolean skipConfig = false;

    /**
     * Skip backend component installation.
     */
    @Builder.Default
    private boolean skipBackend = false;

    /**
     * Skip frontend component installation.
     */
    @Builder.Default
    private boolean skipFrontend = false;

    /**
     * Force overwrite existing plugin.
     */
    @Builder.Default
    private boolean forceOverwrite = false;

    /**
     * Conflict resolution strategy for config resources.
     * SKIP: Skip conflicting resources
     * OVERWRITE: Overwrite existing resources
     * FAIL: Fail on conflict
     */
    @Builder.Default
    private ConflictStrategy conflictStrategy = ConflictStrategy.FAIL;

    /**
     * Auto-enable plugin after installation.
     */
    @Builder.Default
    private boolean autoEnable = true;

    /**
     * Whether to start backend plugin after loading.
     */
    @Builder.Default
    private boolean startBackend = true;

    /**
     * Whether to broadcast frontend availability to clients.
     */
    @Builder.Default
    private boolean broadcastFrontend = true;

    /**
     * Dry run mode - validate without actually installing.
     */
    @Builder.Default
    private boolean dryRun = false;

    /**
     * Conflict resolution strategy.
     */
    public enum ConflictStrategy {
        SKIP,
        OVERWRITE,
        FAIL;

        @com.fasterxml.jackson.annotation.JsonCreator
        public static ConflictStrategy fromValue(String value) {
            if (value == null) return OVERWRITE;
            return valueOf(value.toUpperCase());
        }
    }
}
