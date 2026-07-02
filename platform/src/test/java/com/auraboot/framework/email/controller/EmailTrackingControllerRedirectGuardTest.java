package com.auraboot.framework.email.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the email click-tracking open-redirect / header-injection guard.
 *
 * <p>Security regression: the public {@code /api/email/tracking/{id}/click} endpoint
 * redirected to a fully attacker-controlled {@code url} param with no validation
 * ({@code setHeader(LOCATION, url)}), enabling phishing redirects, {@code javascript:}
 * redirects, and CR/LF response-header injection. The guard now requires a well-formed
 * absolute http/https URL.
 */
@DisplayName("EmailTrackingController redirect guard")
class EmailTrackingControllerRedirectGuardTest {

    @Test
    @DisplayName("well-formed absolute http/https URLs are allowed")
    void allowsHttpAndHttps() {
        assertTrue(EmailTrackingController.isSafeRedirectUrl("https://example.com/path?q=1&x=2"));
        assertTrue(EmailTrackingController.isSafeRedirectUrl("http://example.com"));
        assertTrue(EmailTrackingController.isSafeRedirectUrl("https://sub.example.com:8443/a/b"));
    }

    @Test
    @DisplayName("dangerous schemes are rejected")
    void rejectsDangerousSchemes() {
        assertFalse(EmailTrackingController.isSafeRedirectUrl("javascript:alert(1)"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("data:text/html,<script>alert(1)</script>"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("file:///etc/passwd"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("mailto:victim@example.com"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("ftp://example.com/x"));
    }

    @Test
    @DisplayName("protocol-relative and non-absolute URLs are rejected")
    void rejectsRelative() {
        assertFalse(EmailTrackingController.isSafeRedirectUrl("//evil.example.com"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("/relative/path"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("example.com/no-scheme"));
    }

    @Test
    @DisplayName("CR/LF and control characters (header injection) are rejected")
    void rejectsHeaderInjection() {
        assertFalse(EmailTrackingController.isSafeRedirectUrl("https://good.com\r\nSet-Cookie: pwn=1"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("https://good.com\nLocation: https://evil.com"));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("https://good.com\tx"));
    }

    @Test
    @DisplayName("null / blank / malformed are rejected")
    void rejectsEmptyAndMalformed() {
        assertFalse(EmailTrackingController.isSafeRedirectUrl(null));
        assertFalse(EmailTrackingController.isSafeRedirectUrl(""));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("   "));
        assertFalse(EmailTrackingController.isSafeRedirectUrl("ht!tp://bad"));
    }
}
