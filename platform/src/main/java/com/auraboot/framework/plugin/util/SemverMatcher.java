package com.auraboot.framework.plugin.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight semver version matching utility.
 * <p>
 * Supported patterns:
 * <ul>
 *   <li>{@code "*"} — match any version</li>
 *   <li>{@code "1.0.0"} — exact match</li>
 *   <li>{@code ">=1.0.0"} — greater than or equal</li>
 *   <li>{@code ">1.0.0"} — greater than</li>
 *   <li>{@code "<=1.0.0"} — less than or equal</li>
 *   <li>{@code "<1.0.0"} — less than</li>
 *   <li>{@code "^1.2.0"} — compatible (same major version)</li>
 *   <li>{@code "~1.2.0"} — close to (same major.minor)</li>
 * </ul>
 * <p>
 * Pre-release suffixes (e.g., "-SNAPSHOT", "-alpha.1") are stripped for comparison.
 */
public final class SemverMatcher {

    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-.*)?");
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^(>=?|<=?|\\^|~)?\\s*(\\d+(?:\\.\\d+)?(?:\\.\\d+)?(?:-.*)?)$");

    private SemverMatcher() {
    }

    /**
     * Check if a version satisfies a range constraint.
     *
     * @param version the actual version (e.g., "1.2.3" or "1.0.0-SNAPSHOT")
     * @param range   the constraint (e.g., ">=1.0.0", "^1.2.0", "*")
     * @return true if version satisfies the range
     */
    public static boolean matches(String version, String range) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) {
            return true;
        }
        if (version == null || version.isBlank()) {
            return false;
        }

        int[] ver = parse(version);
        if (ver == null) {
            return false;
        }

        Matcher m = RANGE_PATTERN.matcher(range.trim());
        if (!m.matches()) {
            return false;
        }

        String operator = m.group(1);
        int[] target = parse(m.group(2));
        if (target == null) {
            return false;
        }

        if (operator == null || operator.isEmpty()) {
            // Exact match
            return compare(ver, target) == 0;
        }

        return switch (operator) {
            case ">=" -> compare(ver, target) >= 0;
            case ">" -> compare(ver, target) > 0;
            case "<=" -> compare(ver, target) <= 0;
            case "<" -> compare(ver, target) < 0;
            case "^" -> ver[0] == target[0] && compare(ver, target) >= 0;
            case "~" -> ver[0] == target[0] && ver[1] == target[1] && compare(ver, target) >= 0;
            default -> false;
        };
    }

    /**
     * Compare two semver version strings.
     *
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    public static int compare(String v1, String v2) {
        int[] a = parse(v1);
        int[] b = parse(v2);
        if (a == null || b == null) {
            throw new IllegalArgumentException("Invalid semver: " + (a == null ? v1 : v2));
        }
        return compare(a, b);
    }

    /**
     * Validate that a string looks like a semver version.
     */
    public static boolean isValid(String version) {
        return version != null && parse(version) != null;
    }

    // ==================== Internal ====================

    public static int[] parse(String version) {
        if (version == null) return null;
        Matcher m = SEMVER_PATTERN.matcher(version.trim());
        if (!m.matches()) return null;
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return new int[]{major, minor, patch};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int diff = a[i] - b[i];
            if (diff != 0) return diff;
        }
        return 0;
    }
}
