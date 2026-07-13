package com.auraboot.framework.application.security;

import org.springframework.web.cors.CorsConfiguration;

/**
 * Contributes a CORS rule for one public, key-authenticated endpoint.
 *
 * <p>The default {@code /api/**} rule only admits the admin origins and sends credentials — right
 * for the console, wrong for anything embedded in a customer's own site on an origin we have never
 * heard of. Those endpoints authenticate with a public site key in a header, not a cookie, so they
 * need origin {@code *} with credentials off, and tenant authority stays in the application layer
 * (key registry + origin allowlist + rate limit). {@code /api/collect/keyed} is the in-tree example.
 *
 * <p>Exists so a module that owns such an endpoint can declare its own CORS rule instead of the
 * platform hard-coding paths it does not own. Contributed rules are registered ahead of the generic
 * {@code /api/**} rule, so the more specific pattern wins.
 */
public interface PublicCorsContributor {

    /** Exact path or pattern this rule applies to, e.g. {@code /api/public/cs/**}. */
    String pathPattern();

    /** The CORS rule for that path. */
    CorsConfiguration corsConfiguration();
}
