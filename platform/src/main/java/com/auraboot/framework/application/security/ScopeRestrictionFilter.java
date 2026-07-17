package com.auraboot.framework.application.security;

import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Confines scoped JWTs to the paths their {@link TokenScopePolicy} allows, and rejects everything
 * else with a 401 before the request can reach {@code JwtAuthenticationFilter}.
 *
 * <p><b>Why this runs first.</b> {@code JwtAuthenticationFilter} resolves the token subject through
 * {@code UnifiedUserDetailsService}, which throws {@code UsernameNotFoundException} for an unknown
 * identifier. That call sits outside its try/catch and the filter is installed ahead of Spring
 * Security's {@code ExceptionTranslationFilter}, so the exception escapes to the container: a
 * visitor token aimed at a business endpoint answers <b>500</b>, not 401 (measured, 2026-07-13:
 * {@code /api/dynamic/**}, {@code /api/im/conversations}, {@code /api/meta/models},
 * {@code /api/user/profile} — all 500). A scope subject is never a platform user, so a scoped token
 * must be turned away before that lookup happens. Rejecting here converts an error into a policy
 * decision.
 *
 * <p>Ordinary user tokens carry no {@code scope} claim and pass through untouched.
 */
@Slf4j
@Component
public class ScopeRestrictionFilter extends OncePerRequestFilter {

    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    private final JwtUtil jwtUtil;
    private final List<TokenScopePolicy> policies;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScopeRestrictionFilter(JwtUtil jwtUtil, List<TokenScopePolicy> policies) {
        this.jwtUtil = jwtUtil;
        this.policies = policies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String scope;
        try {
            scope = jwtUtil.extractScope(header.substring(7));
        } catch (Exception e) {
            // Unparseable, expired or badly signed. Not this filter's job to say so — fall through
            // and let JwtAuthenticationFilter produce the canonical rejection, so a malformed token
            // gets one consistent answer regardless of which path it was aimed at.
            filterChain.doFilter(request, response);
            return;
        }

        if (scope == null) {
            filterChain.doFilter(request, response);
            return;
        }

        TokenScopePolicy policy = policies.stream()
                .filter(p -> scope.equals(p.scope()))
                .findFirst()
                .orElse(null);

        if (policy == null) {
            log.warn("Rejected token with unknown scope '{}' for {}", scope, request.getRequestURI());
            reject(request, response);
            return;
        }

        String path = request.getServletPath();
        boolean allowed = false;
        for (String pattern : policy.allowedPathPatterns()) {
            if (ANT_PATH_MATCHER.match(pattern, path)) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            log.debug("Scope '{}' is not allowed to reach {}", scope, path);
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponse<?> body = ApiResponse.errorWithContext(ResponseCode.Unauthorized, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Deliberately does NOT skip the whitelist: a scoped token aimed at /api/auth/login or any
        // other public path must still be confined to its policy. Only CORS preflight is exempt.
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }
}
