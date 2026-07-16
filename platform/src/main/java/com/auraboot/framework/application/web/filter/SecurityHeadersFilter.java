package com.auraboot.framework.application.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds standard security headers to all HTTP responses.
 * Defends against MIME sniffing, clickjacking, and XSS reflection.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * The one path that is INTENTIONALLY embeddable in a third-party iframe: the CS widget's frame
     * host. It does not get the global clickjacking lock here — instead the controller serving it sets
     * a per-site {@code Content-Security-Policy: frame-ancestors <the CS site's own registered origins>}
     * so only that tenant's allowlisted domains can frame it. Everything else stays {@code DENY}.
     */
    static final String CS_FRAME_EMBED_PATH = "/api/public/cs/frame";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        boolean csFrameEmbed = request.getServletPath().startsWith(CS_FRAME_EMBED_PATH);

        // Prevent browsers from MIME-sniffing away from declared Content-Type
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Block page from being embedded in iframe (clickjacking defense). The CS frame-embed path is
        // the deliberate exception — its controller emits a per-site frame-ancestors allowlist instead
        // (X-Frame-Options has no allowlist form, so we must omit it there, not weaken it).
        if (!csFrameEmbed) {
            response.setHeader("X-Frame-Options", "DENY");
        }

        // Enable browser XSS filter (legacy but still useful for older browsers)
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Enforce HTTPS — browsers will refuse plain HTTP for max-age duration
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // Prevent referrer leakage
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Restrict browser features (camera, mic, geolocation) unless explicitly needed
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        // Basic CSP for API responses — prevents script execution if a browser
        // directly opens an API endpoint. Frontend CSP should be set at the
        // BFF/CDN layer with nonce-based script-src.
        if (request.getServletPath().startsWith("/api/") && !csFrameEmbed) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        }

        filterChain.doFilter(request, response);
    }
}
