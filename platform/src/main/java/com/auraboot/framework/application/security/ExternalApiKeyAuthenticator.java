package com.auraboot.framework.application.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Module-owned authentication policy for a narrow external API surface.
 *
 * <p>The platform owns filter ordering and request-context cleanup; a module owns key storage,
 * scope checks, expiry, rate limits and call auditing. Implementations must fail closed.</p>
 */
public interface ExternalApiKeyAuthenticator {

    boolean supports(HttpServletRequest request);

    ExternalApiKeyPrincipal authenticate(HttpServletRequest request);

    default void recordCall(ExternalApiKeyPrincipal principal, HttpServletRequest request,
                            int status, long durationMillis) {
    }

    record ExternalApiKeyPrincipal(Long tenantId, String keyPid, String keyName, Set<String> scopes) {
        public ExternalApiKeyPrincipal {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
