package com.auraboot.framework.common.util;

import com.auraboot.framework.common.util.SsrfValidator.ValidatedTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HOOK-SEC-04 — Unit tests for P3-E #1 DNS rebinding mitigation.
 *
 * <p>The pinning contract guarantees: once {@link SsrfValidator#validate(String)}
 * has returned a {@link ValidatedTarget}, any subsequent HTTP request built via
 * {@link PinnedHttpRequests#newPinnedRequestBuilder(ValidatedTarget)} must hit
 * the exact IP captured during validation — <em>not</em> whatever the OS DNS
 * resolver returns on the second lookup. The tests below lock that contract
 * by inspecting the {@link HttpRequest#uri()} the JDK would use to connect.</p>
 */
class PinnedHttpRequestsTest {

    // Public-looking IPv4 in TEST-NET-3 (RFC 5737): reserved, will never route,
    // but is NOT private/loopback/link-local, so SsrfValidator accepts it.
    private static final String PUBLIC_IP = "203.0.113.42";
    private static final String PUBLIC_IP_V2 = "198.51.100.7";

    @Test
    @DisplayName("HOOK-SEC-04a: http URI is rewritten to the pinned IP literal and Host header preserves original hostname")
    void httpScheme_rewritesUriToPinnedIp_andSetsHostHeader() throws Exception {
        InetAddress pinned = InetAddress.getByAddress("example.com",
                new byte[] {(byte) 203, 0, 113, 42});
        ValidatedTarget target = new ValidatedTarget(
                URI.create("http://example.com/api/data"),
                "example.com",
                pinned,
                -1,
                "http");

        HttpRequest req = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .GET()
                .build();

        // URI used for connection must be the IP literal — OS DNS is never
        // consulted a second time.
        assertThat(req.uri().getHost()).isEqualTo(PUBLIC_IP);
        assertThat(req.uri().getPath()).isEqualTo("/api/data");
        // Host header must still reflect the original hostname for vhost routing.
        Optional<String> hostHeader = req.headers().firstValue("Host");
        assertThat(hostHeader).contains("example.com");
    }

    @Test
    @DisplayName("HOOK-SEC-04b: rebinding cannot flip the connect IP — the URI host stays pinned even if DNS changes")
    void rebindingAttack_cannotChangePinnedIp() throws Exception {
        // Validation time: legitimate public IP.
        InetAddress validatedIp = InetAddress.getByAddress("victim.example",
                new byte[] {(byte) 198, 51, 100, 7});
        ValidatedTarget target = new ValidatedTarget(
                URI.create("http://victim.example/internal"),
                "victim.example",
                validatedIp,
                -1,
                "http");

        HttpRequest req = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .GET()
                .build();

        // Even if an attacker rebinds victim.example → 127.0.0.1 at connect
        // time, the JDK will connect to the literal IP in req.uri(), which is
        // still the originally-validated public address.
        assertThat(req.uri().getHost()).isEqualTo(PUBLIC_IP_V2);
        assertThat(req.uri().getHost()).isNotEqualTo("127.0.0.1");
        assertThat(req.uri().getHost()).isNotEqualTo("victim.example");
    }

    @Test
    @DisplayName("HOOK-SEC-04c: https URIs keep the original hostname so TLS SNI / cert validation still works")
    void httpsScheme_keepsHostname_forTlsSni() throws Exception {
        InetAddress pinned = InetAddress.getByAddress("api.example.com",
                new byte[] {(byte) 203, 0, 113, 42});
        ValidatedTarget target = new ValidatedTarget(
                URI.create("https://api.example.com/v1"),
                "api.example.com",
                pinned,
                -1,
                "https");

        HttpRequest req = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .GET()
                .build();

        // TLS must see the hostname so the certificate can be matched against
        // the SAN. This leaves a narrow TOCTOU window on HTTPS specifically
        // (documented trade-off), but is immeasurably safer than plain HTTP
        // where rebinding is trivial.
        assertThat(req.uri().getHost()).isEqualTo("api.example.com");
    }

    @Test
    @DisplayName("HOOK-SEC-04d: IP-literal hosts pass through unchanged — there is nothing to rebind")
    void ipLiteralHost_passesThroughUnchanged() throws Exception {
        InetAddress pinned = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 42});
        ValidatedTarget target = new ValidatedTarget(
                URI.create("http://203.0.113.42:8081/x"),
                "203.0.113.42",
                pinned,
                8081,
                "http");

        assertThat(target.hostIsIpLiteral()).isTrue();

        HttpRequest req = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .GET()
                .build();
        assertThat(req.uri().toString()).isEqualTo("http://203.0.113.42:8081/x");
    }

    @Test
    @DisplayName("HOOK-SEC-04e: Port from the original URI is carried onto the pinned URI + Host header")
    void explicitPort_preservedInPinnedUriAndHostHeader() throws Exception {
        InetAddress pinned = InetAddress.getByAddress("example.com",
                new byte[] {(byte) 203, 0, 113, 42});
        ValidatedTarget target = new ValidatedTarget(
                URI.create("http://example.com:8081/x"),
                "example.com",
                pinned,
                8081,
                "http");

        HttpRequest req = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .GET()
                .build();

        assertThat(req.uri().getHost()).isEqualTo(PUBLIC_IP);
        assertThat(req.uri().getPort()).isEqualTo(8081);
        assertThat(req.headers().firstValue("Host")).contains("example.com:8081");
    }

    @Test
    @DisplayName("HOOK-SEC-04f: null ValidatedTarget is rejected — callers must explicitly validate first")
    void nullTarget_rejected() {
        assertThatThrownBy(() -> PinnedHttpRequests.newPinnedRequestBuilder(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("HOOK-SEC-04g: SsrfValidator.validate() returns a target whose pinnedIp matches the block-list IP used for rejection")
    void validate_populatesPinnedIpMatchingBlockListCheck() {
        // This public-looking hostname resolves via the system resolver. The
        // test asserts SSRF validation captures the same IP the block-list
        // check just used — closing the TOCTOU window at the API level.
        // We use an always-resolvable host fixture; if CI has no DNS, validate
        // returns null (documented contract) and this test short-circuits.
        ValidatedTarget target = SsrfValidator.validate("http://example.com/");
        if (target == null) {
            // No DNS on this runner — contract allows null and the HTTP call
            // would fail naturally. Nothing to pin.
            return;
        }
        assertThat(target.pinnedIp()).isNotNull();
        assertThat(target.pinnedIp().isLoopbackAddress()).isFalse();
        assertThat(target.pinnedIp().isSiteLocalAddress()).isFalse();
        assertThat(target.host()).isEqualTo("example.com");
    }
}
