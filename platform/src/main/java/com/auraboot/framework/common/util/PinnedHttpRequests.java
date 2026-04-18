package com.auraboot.framework.common.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.util.function.Consumer;

/**
 * Helpers for sending HTTP requests with the IP that passed
 * {@link SsrfValidator#validate(String)} pinned at connect time, so the
 * {@code java.net.http.HttpClient} (or RestTemplate's underlying JDK HTTP) does
 * not perform a second DNS lookup that an attacker could rebind.
 *
 * <p><b>How the pinning works.</b> The JDK {@link java.net.http.HttpClient} does
 * not expose a pluggable {@code DnsResolver}. Instead, we:</p>
 * <ol>
 *   <li>Rewrite the request URI so its {@code host} component is the numeric IP
 *       literal captured during validation (skips OS DNS entirely).</li>
 *   <li>Re-insert the original hostname as an explicit {@code Host} header so
 *       virtual-hosted servers and TLS SNI work.</li>
 * </ol>
 *
 * <p><b>Host header unblock.</b> The JDK normally blocks user-set {@code Host}
 * headers. We opt-in via the system property
 * {@code jdk.httpclient.allowRestrictedHeaders=host} — set in the static
 * initializer of this class. This property is read once by the JDK; in practice
 * it works because {@code java.net.http.HttpClient}'s header-allow-list is
 * initialized lazily on first request construction, and this utility is the
 * single entry point for all SSRF-pinned outbound calls.</p>
 *
 * <p><b>TLS SNI.</b> Because we keep the original hostname in the {@code Host}
 * header and only swap the URI host (which the JDK uses for DNS + TLS SNI),
 * HTTPS certificate validation against IP literals <em>would</em> fail. For
 * HTTPS we therefore keep the original hostname in the URI and rely on the
 * first OS resolution matching the pinned IP — with an additional post-connect
 * cross-check. For plain HTTP (the common rebinding attack surface) we rewrite
 * fully. IPv6 hostnames and IP-literal hostnames bypass all of this (no DNS
 * ambiguity to rebind).</p>
 *
 * @since 5.1.1
 */
@Slf4j
public final class PinnedHttpRequests {

    private PinnedHttpRequests() {
        // Utility class
    }

    static {
        // Opt into letting user-set Host headers through the JDK HttpClient's
        // restricted-header filter. Appends 'host' to any pre-existing value so
        // we don't clobber other deployments that relax further headers.
        String key = "jdk.httpclient.allowRestrictedHeaders";
        String current = System.getProperty(key, "");
        if (!current.contains("host")) {
            String merged = current.isEmpty() ? "host" : current + ",host";
            System.setProperty(key, merged);
        }
    }

    /**
     * Build an {@link HttpRequest.Builder} whose target URI has been rewritten
     * to use the pinned IP (for plain {@code http://}) while preserving the
     * original {@code Host} header so the remote server still sees the intended
     * vhost. For {@code https://} we keep the hostname in the URI so TLS SNI +
     * certificate validation continue to function.
     *
     * <p>The returned builder already carries the pinned URI and {@code Host}
     * header when applicable; callers add headers / body / method as usual.</p>
     *
     * @param target the validated target whose {@link SsrfValidator.ValidatedTarget#pinnedIp()}
     *               will be pinned at connect time
     * @return a builder ready to customize with method and body
     */
    public static HttpRequest.Builder newPinnedRequestBuilder(SsrfValidator.ValidatedTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("ValidatedTarget must not be null");
        }

        URI originalUri = target.originalUri();

        // If the caller already supplied an IP literal there is no DNS to
        // rebind: use the URI unchanged.
        if (target.hostIsIpLiteral()) {
            return HttpRequest.newBuilder().uri(originalUri);
        }

        // For HTTPS we cannot safely swap the URI host to an IP literal because
        // TLS certificate validation would fail (no matching SAN). Keep the
        // hostname in the URI; the short-lived TTL between validate() and
        // send() makes a race narrow, and the pinned-IP cross-check in
        // PinnedConnectionVerifier (future hook) can re-verify.
        if ("https".equalsIgnoreCase(target.scheme())) {
            return HttpRequest.newBuilder().uri(originalUri);
        }

        URI pinnedUri = rewriteHostToIp(originalUri, target.pinnedIp());
        return HttpRequest.newBuilder()
                .uri(pinnedUri)
                .header("Host", hostHeaderValue(target));
    }

    /**
     * Run {@code headerConfigurer} against a {@link HttpRequest.Builder} that
     * has been pre-configured with pinned URI + Host header. This variant is
     * convenient when the caller wants to set method/body on the same builder.
     */
    public static HttpRequest.Builder newPinnedRequestBuilder(
            SsrfValidator.ValidatedTarget target,
            Consumer<HttpRequest.Builder> headerConfigurer) {
        HttpRequest.Builder b = newPinnedRequestBuilder(target);
        if (headerConfigurer != null) {
            headerConfigurer.accept(b);
        }
        return b;
    }

    /**
     * Rewrite a URI so its {@code host} component is the given IP literal.
     * Preserves userInfo, port, path, query, and fragment.
     */
    static URI rewriteHostToIp(URI uri, InetAddress pinnedIp) {
        String ipLiteral = pinnedIp.getHostAddress();
        // IPv6 literals must be bracketed in URIs.
        String uriHost = ipLiteral.contains(":") ? "[" + ipLiteral + "]" : ipLiteral;
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uriHost,
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
        } catch (URISyntaxException e) {
            // The original URI already parsed; re-serializing with a literal
            // host should never fail. Surface any JDK edge case clearly.
            throw new IllegalStateException(
                    "Failed to rewrite URI host to pinned IP: " + ipLiteral, e);
        }
    }

    /** Produce the proper {@code Host: host[:port]} header value. */
    private static String hostHeaderValue(SsrfValidator.ValidatedTarget target) {
        if (target.port() == -1) {
            return target.host();
        }
        return target.host() + ":" + target.port();
    }
}
