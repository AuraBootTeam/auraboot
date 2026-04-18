package com.auraboot.framework.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SsrfValidator.
 */
class SsrfValidatorTest {

    // =========================================================
    // Null / blank
    // =========================================================

    @Test
    void validate_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void validate_blank_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    // =========================================================
    // Scheme validation
    // =========================================================

    @Test
    void validate_httpScheme_passes() {
        // external hostname that won't resolve to a private IP
        assertThatNoException().isThrownBy(
                () -> SsrfValidator.validateUrl("http://this-host-does-not-exist-aura.invalid/api"));
    }

    @Test
    void validate_httpsScheme_passes() {
        assertThatNoException().isThrownBy(
                () -> SsrfValidator.validateUrl("https://this-host-does-not-exist-aura.invalid/api"));
    }

    @Test
    void validate_ftpScheme_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("ftp://example.com/file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void validate_fileScheme_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void validate_gopherScheme_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("gopher://evil.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    // =========================================================
    // Blocked ports
    // =========================================================

    @Test
    void validate_blockedPort6443_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://example.com:6443/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validate_blockedPort6379_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://example.com:6379/cmd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validate_blockedPort5432_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://example.com:5432/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validate_blockedPort3306_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://example.com:3306/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validate_blockedPort27017_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://example.com:27017/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validate_allowedPort443_passes() {
        assertThatNoException().isThrownBy(
                () -> SsrfValidator.validateUrl("https://this-host-does-not-exist-aura.invalid:443/"));
    }

    @Test
    void validate_allowedPort8443_passes() {
        assertThatNoException().isThrownBy(
                () -> SsrfValidator.validateUrl("https://this-host-does-not-exist-aura.invalid:8443/"));
    }

    // =========================================================
    // Private / loopback addresses
    // =========================================================

    @Test
    void validate_loopback127_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://127.0.0.1/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void validate_localhost_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://localhost/api"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_privateIp192_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://192.168.1.1/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    void validate_privateIp10_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://10.0.0.1/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    void validate_privateIp172_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://172.16.0.1/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    // =========================================================
    // DNS unresolvable host — allowed through
    // =========================================================

    @Test
    void validate_unresolvableHost_passesValidation() {
        // DNS fail → not SSRF concern → allowed through
        assertThatNoException().isThrownBy(
                () -> SsrfValidator.validateUrl("https://this-host-does-not-exist-aura.invalid/test"));
    }

    // =========================================================
    // IPv6 loopback / IPv4-mapped IPv6 (P3-E hardening)
    // =========================================================

    @Test
    void validate_ipv6LoopbackBracketed_throwsIllegalArgument() {
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://[::1]/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void validate_ipv4MappedIpv6Loopback_throwsIllegalArgument() {
        // ::ffff:127.0.0.1 — JDK's Inet6Address.isLoopbackAddress() may return
        // false without the IPv4-mapped unwrap. The validator must reject.
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://[::ffff:127.0.0.1]/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void validate_ipv4MappedIpv6Private_throwsIllegalArgument() {
        // ::ffff:10.0.0.1 — mapped RFC1918 private address.
        assertThatThrownBy(() -> SsrfValidator.validateUrl("http://[::ffff:10.0.0.1]/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    // =========================================================
    // Multi-A-record paranoid check (P3-E hardening)
    // =========================================================

    @Test
    void validate_multiARecord_anyPrivate_throwsIllegalArgument() throws Exception {
        // Simulate "attacker DNS returns [203.0.113.1, 127.0.0.1]" via a direct
        // call to the per-address rejection path. SsrfValidator.validate()
        // iterates every address returned by getAllByName() and rejects if ANY
        // is private — this test asserts the contract (each address is checked
        // in isolation through the same rejection predicate).
        java.lang.reflect.Method m = SsrfValidator.class.getDeclaredMethod(
                "rejectIfPrivate", java.net.InetAddress.class);
        m.setAccessible(true);

        java.net.InetAddress publicIp = java.net.InetAddress.getByAddress(
                "mixed.example", new byte[]{(byte) 203, 0, 113, 1});
        java.net.InetAddress loopback = java.net.InetAddress.getByAddress(
                "mixed.example", new byte[]{127, 0, 0, 1});

        // Public address passes.
        assertThatNoException().isThrownBy(() -> m.invoke(null, publicIp));

        // Loopback in the same answer set is rejected.
        assertThatThrownBy(() -> {
            try {
                m.invoke(null, loopback);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                throw ite.getCause();
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }
}
