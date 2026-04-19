package com.auraboot.framework.application.web.filter;

import com.auraboot.framework.application.tenant.MetaContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Test-only filter that overrides {@link MetaContext#getCurrentUserId()} with the
 * value of the {@code X-Test-Spoof-User-Id} request header.
 *
 * <p>Rationale — the USP real-backend E2E suite seeds rows under the admin's
 * numeric user id so that {@code MetaContext.getCurrentUserId()} matches when
 * the UI calls the read endpoints. With multiple Playwright workers running
 * the same spec in parallel, every worker seeds under the same user_id and
 * collides on the partial unique index {@code uq_user_soul_profile_active}.
 * The fix is to let each test synthesize a unique user id and pass it via
 * this header; the backend still authenticates the admin JWT normally (so
 * RBAC, tenant resolution, session checks all run) but the effective
 * {@code MetaContext.getCurrentUserId()} is swapped for the spoofed id,
 * making each test row key (tenant_id, user_id) unique.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Gated by {@code @Profile("test")} — the bean is not registered in
 *       any profile that does not contain the {@code test} literal, so the
 *       header is silently ignored in prod/dev/integration-test runs
 *       (ProfileCondition treats {@code integration-test} separately).</li>
 *   <li>Runs <em>after</em> {@link JwtAuthenticationFilter} — requires a
 *       valid admin JWT to populate the {@link MetaContext} first. The
 *       spoof only mutates the user id field; tenant, username, authenticated
 *       principal, and Spring Security authorities stay intact.</li>
 *   <li>No fallback / auto-create. If the header is present but not a valid
 *       {@code Long}, the filter rejects with HTTP 400 rather than silently
 *       continue with the JWT's identity.</li>
 * </ul>
 *
 * @see com.auraboot.framework.application.tenant.MetaContext#setCurrentUserId(Long)
 */
@Slf4j
@Component
@Profile("test")
public class TestUserSpoofFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Test-Spoof-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!MetaContext.exists()) {
            // No authenticated context — nothing to spoof. Either the request
            // is on a whitelisted endpoint, or auth already rejected it. Pass
            // through unchanged.
            filterChain.doFilter(request, response);
            return;
        }

        long spoofed;
        try {
            spoofed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"code\":400,\"message\":\"Invalid " + HEADER + " header — must be a numeric user id\"}"
            );
            return;
        }

        // Preserve tenant/username/userPid; mutate only userId. This keeps
        // the authenticated principal consistent with the admin JWT, so
        // Spring Security authorities and session checks stay aligned.
        log.debug("TestUserSpoofFilter: overriding MetaContext user id with spoofed={} (original admin id={})",
                spoofed, MetaContext.get().getUserId());
        @SuppressWarnings("deprecation") // Test-only override; non-deprecated API expects all 4 fields.
        final Runnable apply = () -> MetaContext.setCurrentUserId(spoofed);
        apply.run();

        filterChain.doFilter(request, response);
    }
}
