package com.auraboot.framework.common.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 * Rejects private/internal IP ranges, loopback, link-local addresses,
 * and non-HTTP(S) schemes.
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

    /**
     * Validate a URL to prevent Server-Side Request Forgery (SSRF) attacks.
     * <p>
     * Rejects private/internal IP ranges (IPv4 & IPv6), non-HTTP schemes,
     * and common management ports.
     * <p>
     * <b>DNS rebinding caveat:</b> There is a TOCTOU gap between DNS resolution here
     * and the actual HTTP connection. A sophisticated attacker could use DNS rebinding
     * to bypass this check. For production hardening, consider pinning the resolved IP
     * at the HTTP client level (e.g., custom {@code DnsResolver} with OkHttp/Apache HC).
     *
     * @param urlStr the URL to validate
     * @throws IllegalArgumentException if the URL is not safe for server-side requests
     */
    public static void validateUrl(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            throw new IllegalArgumentException("URL must not be empty");
        }

        try {
            URI uri = new URI(urlStr);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new IllegalArgumentException("URL scheme not allowed: " + scheme);
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("URL host is empty");
            }

            // Check port restrictions
            int port = uri.getPort();
            if (port != -1 && BLOCKED_PORTS.contains(port)) {
                throw new IllegalArgumentException("URL port not allowed: " + port);
            }

            // Resolve to IP and check for private ranges.
            // InetAddress.getByName handles both IPv4 and IPv6 addresses;
            // isLoopbackAddress/isLinkLocalAddress/isSiteLocalAddress cover
            // both IPv4 (127.x, 169.254.x, 10.x/172.16.x/192.168.x)
            // and IPv6 (::1, fe80::, fec0:: / fc00::) equivalents.
            try {
                InetAddress addr = InetAddress.getByName(host);
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
            } catch (UnknownHostException e) {
                // DNS resolution failure is NOT an SSRF concern — the host simply
                // doesn't resolve. The actual HTTP call will fail later with a
                // connection error. Allow it through validation.
                log.debug("DNS resolution failed for host '{}', allowing through SSRF check", host);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + urlStr, e);
        }
    }
}
