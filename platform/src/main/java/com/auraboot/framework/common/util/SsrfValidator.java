package com.auraboot.framework.common.util;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 * Rejects private/internal IP ranges, loopback, link-local addresses,
 * and non-HTTP(S) schemes.
 *
 * <p><b>DNS rebinding (P3-E #1):</b> {@link #validate(String)} returns a
 * {@link ValidatedTarget} that captures the exact {@link InetAddress} that
 * passed the block-list check. Callers MUST use the pinned IP at connect
 * time (see {@link PinnedHttpRequests}) instead of letting the OS resolve
 * the hostname a second time; otherwise an attacker controlling the DNS
 * response can flip the answer from a public IP (validation time) to
 * 127.0.0.1 (connect time) — the classic DNS-rebinding TOCTOU.</p>
 *
 * @since 5.1.1
 */
@Slf4j
public final class SsrfValidator {

    private SsrfValidator() {
        // Utility class
    }

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    // Block common management/infrastructure ports to reduce attack surface
    private static final Set<Integer> BLOCKED_PORTS = Set.of(
            6443,  // Platform backend
            8080,  // Common app server
            3306,  // MySQL
            5432,  // PostgreSQL
            6379,  // Redis
            2379,  // etcd
            9200,  // Elasticsearch
            27017  // MongoDB
    );

    private static final Set<String> TEST_PROFILE_PRIVATE_HOST_ALLOWLIST = Set.of(
            "host.docker.internal"
    );

    /**
     * A URL that has passed SSRF validation, with the resolved IP captured so
     * callers can pin it at connect time (defeats DNS rebinding TOCTOU).
     *
     * @param originalUri the URI as supplied by the caller (host is the hostname)
     * @param host        the hostname from the URI (for use as {@code Host} header)
     * @param pinnedIp    the {@link InetAddress} that passed the block-list check;
     *                    connect to this IP directly rather than re-resolving
     * @param port        the explicit port, or {@code -1} if the scheme default applies
     * @param scheme      the URI scheme, lower-cased ({@code http} or {@code https})
     */
    public record ValidatedTarget(URI originalUri, String host, InetAddress pinnedIp, int port, String scheme) {

        /**
         * Whether the original host was given as a numeric IP literal. When {@code true},
         * DNS rebinding is impossible and the original URI can be used as-is.
         */
        public boolean hostIsIpLiteral() {
            String h = host == null ? "" : host;
            if (h.isEmpty()) {
                return false;
            }
            // IPv6 literal appears wrapped in [...] via URI.getHost()
            if (h.startsWith("[") && h.endsWith("]")) {
                return true;
            }
            // IPv4 literal — four dot-separated numeric parts
            String[] parts = h.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3) {
                    return false;
                }
                for (int i = 0; i < p.length(); i++) {
                    if (!Character.isDigit(p.charAt(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * Validate a URL to prevent Server-Side Request Forgery (SSRF) attacks and
     * return the resolved IP so the caller can pin it at connect time.
     *
     * <p>Rejects private/internal IP ranges (IPv4 & IPv6), non-HTTP schemes,
     * and common management ports.</p>
     *
     * @param urlStr the URL to validate
     * @return the {@link ValidatedTarget}, or {@code null} when the hostname could
     *         not be resolved (callers may proceed; the connection will fail
     *         naturally — this path is not an SSRF concern)
     * @throws IllegalArgumentException if the URL is not safe for server-side requests
     */
    public static ValidatedTarget validate(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            throw new IllegalArgumentException("URL must not be empty");
        }

        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + urlStr, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("URL scheme not allowed: " + scheme);
        }
        String lowerScheme = scheme.toLowerCase();

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is empty");
        }

        int port = uri.getPort();
        if (port != -1 && BLOCKED_PORTS.contains(port)) {
            throw new IllegalArgumentException("URL port not allowed: " + port);
        }

        try {
            // Paranoid multi-A-record check (P3-E hardening): if DNS returns
            // multiple addresses, ANY private/loopback/link-local address causes
            // rejection. Otherwise an attacker who controls DNS can answer with
            // [public-A, 127.0.0.1]; the OS may pick either at connect time.
            InetAddress[] all = InetAddress.getAllByName(host);
            boolean allowPrivateTarget = isPrivateTargetAllowed(host);
            for (InetAddress a : all) {
                if (!allowPrivateTarget) {
                    rejectIfPrivate(a);
                }
            }
            // Pin the first resolved address for downstream connect-time use.
            InetAddress addr = all[0];
            return new ValidatedTarget(uri, host, addr, port, lowerScheme);
        } catch (UnknownHostException e) {
            // DNS resolution failure is NOT an SSRF concern — the host simply
            // doesn't resolve. The actual HTTP call will fail later with a
            // connection error. Allow through validation.
            log.debug("DNS resolution failed for host '{}', allowing through SSRF check", host);
            return null;
        }
    }

    /**
     * Backward-compatible void overload; prefer {@link #validate(String)} so the
     * resolved IP can be pinned at connect time.
     *
     * @deprecated Use {@link #validate(String)} and pin the returned
     *             {@link ValidatedTarget#pinnedIp()} at the HTTP client level
     *             (see {@link PinnedHttpRequests}) to close the DNS-rebinding
     *             TOCTOU window.
     */
    @Deprecated
    public static void validateUrl(String urlStr) {
        validate(urlStr);
    }

    private static void rejectIfPrivate(InetAddress addr) {
        // IPv4-mapped IPv6 (::ffff:x.y.z.w) bypass: on many JDKs, the mapped
        // address reports isLoopbackAddress()/isSiteLocalAddress() as FALSE
        // because the check only inspects the IPv6 high bits. Unwrap to the
        // embedded IPv4 and re-run the block-list against that.
        if (addr instanceof Inet6Address v6) {
            byte[] raw = v6.getAddress();
            if (isIpv4Mapped(raw)) {
                try {
                    byte[] v4Raw = new byte[] {raw[12], raw[13], raw[14], raw[15]};
                    InetAddress v4 = InetAddress.getByAddress(v4Raw);
                    rejectIfPrivate(v4);
                } catch (UnknownHostException unreachable) {
                    // getByAddress with a 4-byte array never throws.
                    throw new IllegalStateException(unreachable);
                }
            }
        }
        if (addr.isLoopbackAddress()) {
            throw new IllegalArgumentException("URL resolves to loopback address");
        }
        if (addr.isLinkLocalAddress()) {
            throw new IllegalArgumentException("URL resolves to link-local address");
        }
        if (addr.isSiteLocalAddress()) {
            throw new IllegalArgumentException("URL resolves to private/site-local address");
        }
        if (addr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("URL resolves to wildcard address");
        }
    }

    private static boolean isPrivateTargetAllowed(String host) {
        String normalizedHost = host == null ? "" : host.toLowerCase();
        String activeProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
        if (activeProfiles != null && activeProfiles.contains("test")
                && TEST_PROFILE_PRIVATE_HOST_ALLOWLIST.contains(normalizedHost)) {
            return true;
        }
        // Explicit operator allowlist for self-hosted private inference gateways
        // and fixture servers. Read the environment variable first, then fall back
        // to the JVM system property so the allowlist can also be set
        // programmatically (e.g. a test fronting a loopback mock server, or a
        // deployment that prefers -D over env).
        String configured = System.getenv("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        }
        if (configured == null || configured.isBlank()) {
            return false;
        }
        for (String item : configured.split(",")) {
            if (normalizedHost.equals(item.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Returns true for the ::ffff:0:0/96 prefix (IPv4-mapped IPv6). */
    private static boolean isIpv4Mapped(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff;
    }
}
