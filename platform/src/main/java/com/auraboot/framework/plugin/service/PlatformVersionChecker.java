package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.config.PlatformProperties;
import com.auraboot.framework.plugin.util.SemverMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Checks plugin manifest version constraints against the running platform version.
 *
 * <p>Result values:
 * <ul>
 *   <li>{@link CompatibilityStatus#COMPATIBLE} — plugin is compatible with this platform version</li>
 *   <li>{@link CompatibilityStatus#WARN_OLDER} — platform is older than the plugin's minimum requirement</li>
 *   <li>{@link CompatibilityStatus#WARN_NEWER} — platform is newer than the plugin's declared maximum</li>
 *   <li>{@link CompatibilityStatus#INCOMPATIBLE} — hard incompatibility (min > platform version by a major version)</li>
 * </ul>
 *
 * @since 5.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformVersionChecker {

    private final PlatformProperties platformProperties;

    /**
     * Compatibility status returned by {@link #check}.
     */
    public enum CompatibilityStatus {
        /** Plugin is fully compatible with this platform version. */
        COMPATIBLE,
        /**
         * Platform is older than the plugin's minimum required version.
         * Import should warn the user; it is not a hard block unless the major version differs.
         */
        WARN_OLDER,
        /**
         * Platform is newer than the plugin's declared maximum version.
         * The plugin may still work but has not been tested on this platform version.
         */
        WARN_NEWER,
        /**
         * Hard incompatibility — the plugin requires a major platform version upgrade
         * (e.g., plugin requires v2.x but platform is v1.x).
         */
        INCOMPATIBLE,
    }

    /**
     * Result of a version compatibility check.
     */
    public record CompatibilityResult(
            CompatibilityStatus status,
            String platformVersion,
            String minPlatformVersion,
            String maxPlatformVersion,
            String message
    ) {
        /** True when the plugin can be imported (COMPATIBLE or WARN_NEWER). */
        public boolean isAllowed() {
            return status == CompatibilityStatus.COMPATIBLE
                    || status == CompatibilityStatus.WARN_NEWER;
        }

        /** True when the result should be surfaced as a user-visible warning. */
        public boolean hasWarning() {
            return status != CompatibilityStatus.COMPATIBLE;
        }
    }

    /**
     * Check whether the plugin's declared version constraints are satisfied by the
     * current platform version.
     *
     * @param minPlatformVersion minimum required platform version (nullable — skipped when null/blank)
     * @param maxPlatformVersion maximum tested platform version (nullable — skipped when null/blank)
     * @return compatibility result
     */
    public CompatibilityResult check(String minPlatformVersion, String maxPlatformVersion) {
        String currentVersion = platformProperties.getVersion();

        // ── Min version check ──────────────────────────────────────────────────
        if (minPlatformVersion != null && !minPlatformVersion.isBlank()) {
            if (!SemverMatcher.isValid(minPlatformVersion)) {
                log.warn("Plugin has invalid minPlatformVersion: '{}'", minPlatformVersion);
            } else {
                int cmp = SemverMatcher.compare(currentVersion, minPlatformVersion);
                if (cmp < 0) {
                    // Platform is older than required minimum
                    int[] current = parseSafe(currentVersion);
                    int[] min = parseSafe(minPlatformVersion);
                    if (current != null && min != null && current[0] < min[0]) {
                        // Major version mismatch → INCOMPATIBLE
                        return new CompatibilityResult(
                                CompatibilityStatus.INCOMPATIBLE,
                                currentVersion,
                                minPlatformVersion,
                                maxPlatformVersion,
                                String.format(
                                        "Plugin requires platform >= %s (current: %s). "
                                                + "Major version upgrade required — import blocked.",
                                        minPlatformVersion, currentVersion));
                    }
                    // Minor/patch mismatch → WARN_OLDER
                    return new CompatibilityResult(
                            CompatibilityStatus.WARN_OLDER,
                            currentVersion,
                            minPlatformVersion,
                            maxPlatformVersion,
                            String.format(
                                    "Plugin requires platform >= %s (current: %s). "
                                            + "Consider upgrading the platform.",
                                    minPlatformVersion, currentVersion));
                }
            }
        }

        // ── Max version check ──────────────────────────────────────────────────
        if (maxPlatformVersion != null && !maxPlatformVersion.isBlank()) {
            if (!SemverMatcher.isValid(maxPlatformVersion)) {
                log.warn("Plugin has invalid maxPlatformVersion: '{}'", maxPlatformVersion);
            } else {
                int cmp = SemverMatcher.compare(currentVersion, maxPlatformVersion);
                if (cmp > 0) {
                    return new CompatibilityResult(
                            CompatibilityStatus.WARN_NEWER,
                            currentVersion,
                            minPlatformVersion,
                            maxPlatformVersion,
                            String.format(
                                    "Plugin was tested up to platform %s (current: %s). "
                                            + "It may still work but has not been validated on this version.",
                                    maxPlatformVersion, currentVersion));
                }
            }
        }

        return new CompatibilityResult(
                CompatibilityStatus.COMPATIBLE,
                currentVersion,
                minPlatformVersion,
                maxPlatformVersion,
                null);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static int[] parseSafe(String version) {
        if (version == null) return null;
        try {
            return SemverMatcher.parse(version);
        } catch (Exception e) {
            return null;
        }
    }
}
