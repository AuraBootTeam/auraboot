package com.auraboot.framework.application.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileSecurityWarner#isTestProfileActive(String)} — the
 * detector for the unsafe {@code test} Spring profile.
 */
@DisplayName("ProfileSecurityWarner test-profile detection")
class ProfileSecurityWarnerTest {

    @Test
    @DisplayName("detects test profile in comma-separated lists (case-insensitive)")
    void detectsTest() {
        assertTrue(ProfileSecurityWarner.isTestProfileActive("dev,local,test"));
        assertTrue(ProfileSecurityWarner.isTestProfileActive("test"));
        assertTrue(ProfileSecurityWarner.isTestProfileActive("prod, TEST"));
        assertTrue(ProfileSecurityWarner.isTestProfileActive(" test "));
    }

    @Test
    @DisplayName("does not flag safe profiles")
    void ignoresSafe() {
        assertFalse(ProfileSecurityWarner.isTestProfileActive("community"));
        assertFalse(ProfileSecurityWarner.isTestProfileActive("prod"));
        assertFalse(ProfileSecurityWarner.isTestProfileActive("dev,local"));
        assertFalse(ProfileSecurityWarner.isTestProfileActive("testing")); // substring must not match
        assertFalse(ProfileSecurityWarner.isTestProfileActive(""));
        assertFalse(ProfileSecurityWarner.isTestProfileActive(null));
    }
}
