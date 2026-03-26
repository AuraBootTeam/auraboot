package com.auraboot.framework.plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemverMatcherTest {

    // ==================== matches() ====================

    @Test
    void wildcard_matches_anything() {
        assertTrue(SemverMatcher.matches("1.0.0", "*"));
        assertTrue(SemverMatcher.matches("99.99.99", "*"));
        assertTrue(SemverMatcher.matches("0.0.1", null));
        assertTrue(SemverMatcher.matches("0.0.1", ""));
        assertTrue(SemverMatcher.matches("0.0.1", "  "));
    }

    @Test
    void exact_match() {
        assertTrue(SemverMatcher.matches("1.0.0", "1.0.0"));
        assertTrue(SemverMatcher.matches("2.3.4", "2.3.4"));
        assertFalse(SemverMatcher.matches("1.0.1", "1.0.0"));
    }

    @Test
    void greater_than_or_equal() {
        assertTrue(SemverMatcher.matches("1.0.0", ">=1.0.0"));
        assertTrue(SemverMatcher.matches("1.0.1", ">=1.0.0"));
        assertTrue(SemverMatcher.matches("2.0.0", ">=1.0.0"));
        assertFalse(SemverMatcher.matches("0.9.9", ">=1.0.0"));
    }

    @Test
    void greater_than() {
        assertTrue(SemverMatcher.matches("1.0.1", ">1.0.0"));
        assertFalse(SemverMatcher.matches("1.0.0", ">1.0.0"));
        assertFalse(SemverMatcher.matches("0.9.0", ">1.0.0"));
    }

    @Test
    void less_than_or_equal() {
        assertTrue(SemverMatcher.matches("1.0.0", "<=1.0.0"));
        assertTrue(SemverMatcher.matches("0.9.0", "<=1.0.0"));
        assertFalse(SemverMatcher.matches("1.0.1", "<=1.0.0"));
    }

    @Test
    void less_than() {
        assertTrue(SemverMatcher.matches("0.9.9", "<1.0.0"));
        assertFalse(SemverMatcher.matches("1.0.0", "<1.0.0"));
    }

    @Test
    void caret_compatible_same_major() {
        assertTrue(SemverMatcher.matches("1.2.0", "^1.2.0"));
        assertTrue(SemverMatcher.matches("1.3.0", "^1.2.0"));
        assertTrue(SemverMatcher.matches("1.99.99", "^1.2.0"));
        assertFalse(SemverMatcher.matches("2.0.0", "^1.2.0"));
        assertFalse(SemverMatcher.matches("1.1.9", "^1.2.0"));
    }

    @Test
    void tilde_close_to_same_minor() {
        assertTrue(SemverMatcher.matches("1.2.0", "~1.2.0"));
        assertTrue(SemverMatcher.matches("1.2.5", "~1.2.0"));
        assertFalse(SemverMatcher.matches("1.3.0", "~1.2.0"));
        assertFalse(SemverMatcher.matches("2.2.0", "~1.2.0"));
    }

    @Test
    void snapshot_versions_stripped() {
        assertTrue(SemverMatcher.matches("1.0.0-SNAPSHOT", ">=1.0.0"));
        assertTrue(SemverMatcher.matches("1.0.0-SNAPSHOT", "1.0.0"));
        assertTrue(SemverMatcher.matches("1.0.0", ">=1.0.0-SNAPSHOT"));
    }

    @Test
    void partial_versions_default_to_zero() {
        assertTrue(SemverMatcher.matches("1", ">=1.0.0"));
        assertTrue(SemverMatcher.matches("1.2", ">=1.2.0"));
        assertTrue(SemverMatcher.matches("1.0.0", ">=1"));
    }

    @Test
    void null_version_returns_false() {
        assertFalse(SemverMatcher.matches(null, ">=1.0.0"));
        assertFalse(SemverMatcher.matches("", ">=1.0.0"));
    }

    // ==================== compare() ====================

    @Test
    void compare_versions() {
        assertTrue(SemverMatcher.compare("1.0.0", "1.0.0") == 0);
        assertTrue(SemverMatcher.compare("1.0.1", "1.0.0") > 0);
        assertTrue(SemverMatcher.compare("1.0.0", "1.0.1") < 0);
        assertTrue(SemverMatcher.compare("2.0.0", "1.99.99") > 0);
        assertTrue(SemverMatcher.compare("1.0.0-SNAPSHOT", "1.0.0") == 0);
    }

    // ==================== isValid() ====================

    @Test
    void isValid() {
        assertTrue(SemverMatcher.isValid("1.0.0"));
        assertTrue(SemverMatcher.isValid("1.2.3-SNAPSHOT"));
        assertTrue(SemverMatcher.isValid("0.0.1"));
        assertTrue(SemverMatcher.isValid("1"));
        assertTrue(SemverMatcher.isValid("1.2"));
        assertFalse(SemverMatcher.isValid(null));
        assertFalse(SemverMatcher.isValid(""));
        assertFalse(SemverMatcher.isValid("abc"));
        assertFalse(SemverMatcher.isValid("v1.0.0"));
    }
}
