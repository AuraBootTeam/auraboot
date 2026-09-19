package com.auraboot.framework.application.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Module-owned authentication policy for a narrow external API surface.
 *
 * <p>The platform owns filter ordering and request-context cleanup; an authenticator owns
 * credential validation, scope checks, expiry, rate limits and call auditing. Implementations
 * must fail closed.</p>
 */
public interface ExternalMachineAuthenticator {

    boolean supports(HttpServletRequest request);

    MachinePrincipal authenticate(HttpServletRequest request);

    default void recordCall(MachinePrincipal principal, HttpServletRequest request,
                            int status, long durationMillis) {
    }

    record MachinePrincipal(Long tenantId, String subjectPid, String subjectName, Set<String> scopes,
                            String applicationPid, String installationPid,
                            String environment, String tokenPid) {
        public MachinePrincipal {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }

        public MachinePrincipal(Long tenantId, String subjectPid, String subjectName, Set<String> scopes) {
            this(tenantId, subjectPid, subjectName, scopes, null, null, null, null);
        }
    }
}
