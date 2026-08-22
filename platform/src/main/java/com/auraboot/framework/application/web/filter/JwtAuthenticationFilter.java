package com.auraboot.framework.application.web.filter;

import com.auraboot.framework.application.security.WhiteList;
import com.auraboot.framework.application.security.ExternalApiKeyAuthenticationFilter;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.party.service.PartyAuthorizationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:}")
    private String activeProfile;

    @Autowired
    private UserService userService;

    @Autowired(required = false)
    private SessionManagementService sessionManagementService;

    @Autowired(required = false)
    private SystemModeService systemModeService;

    @Autowired(required = false)
    private TenantMemberService tenantMemberService;

    @Autowired(required = false)
    private PartyAuthorizationService partyAuthorizationService;

    @Autowired
    private com.auraboot.framework.rbac.service.UserRoleService userRoleService;

    /**
     * Absent when tracing is disabled (the dev profile sets
     * {@code management.tracing.enabled: false}), so this must stay optional.
     */
    @Autowired(required = false)
    private io.micrometer.tracing.Tracer tracer;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String authorizationHeader = request.getHeader("Authorization");

        String userPid = null;
        String jwt = null;

        if (null == authorizationHeader) {
            ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.MissingAuthorizationHeader, request.getRequestURI());
            reject(request, response, apiResponse);
            log.debug("Missing Authorization header, url: {}", request.getRequestURI());
            return;
        }

        if (authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                userPid = jwtUtil.extractIdentifier(jwt);
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.ExpiredAuthorizationHeader, request.getRequestURI());
                log.debug("Expired JWT token, url: {}", request.getRequestURI());
                reject(request, response, apiResponse);
                return;
            } catch (Exception e) {
                logger.error("JWT token validation failed", e);
                ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.Unauthorized, request.getRequestURI());
                reject(request, response, apiResponse);
                return;
            }
        }

        if (userPid != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            CustomUserDetails userDetails;
            try {
                userDetails = (CustomUserDetails) this.userDetailsService.loadUserByUsername(userPid);
            } catch (UsernameNotFoundException e) {
                // A correctly signed, unexpired token whose user no longer exists —
                // deactivated, deleted, or issued against a database that has since been
                // reset. UsernameNotFoundException used to escape this filter uncaught and
                // surface as a raw Spring 500 error page ("Internal Server Error"), which
                // reads as a platform fault instead of what it is: a credential that no
                // longer identifies anyone. Same answer as an expired token — 401, so the
                // client re-authenticates.
                log.debug("Token references a user that no longer exists: {}, url: {}", userPid,
                        request.getRequestURI());
                ApiResponse<?> apiResponse = ApiResponse.errorWithContext(
                        ResponseCode.UserNotLoginInOrAccessTokenInvalid, request.getRequestURI());
                reject(request, response, apiResponse);
                return;
            }

            if (jwtUtil.validateToken(jwt, userDetails)) {
                // Verify security version — invalidate token if password changed
                int tokenSv = jwtUtil.extractSecurityVersion(jwt);
                User user = userService.findByPid(userPid);
                if (user != null) {
                    int dbSv = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
                    if (tokenSv < dbSv) {
                        ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.SecurityVersionMismatch, request.getRequestURI());
                        reject(request, response, apiResponse);
                        return;
                    }
                }

                // Check session validity (if session management is available)
                if (sessionManagementService != null) {
                    try {
                        if (!sessionManagementService.isSessionValid(jwt)) {
                            ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.SecurityVersionMismatch, request.getRequestURI());
                            reject(request, response, apiResponse);
                            return;
                        }
                        // Update last active time (throttled)
                        sessionManagementService.updateLastActive(jwt);
                    } catch (Exception e) {
                        // SECURITY: fail-closed — reject request when session check errors
                        // (e.g. Redis/DB unavailable). Do not silently allow.
                        log.error("Session check failed, rejecting request: {}", e.getMessage());
                        ApiResponse<?> apiResponse = ApiResponse.errorWithContext(ResponseCode.Unauthorized, request.getRequestURI());
                        reject(request, response, apiResponse);
                        return;
                    }
                }

                Long tenantId = jwtUtil.extractTenantId(jwt);

                // SINGLE mode: auto-inject default tenant when JWT has no tenantId
                if (tenantId == null && systemModeService != null && systemModeService.isSingleTenant()) {
                    Long defaultTenantId = systemModeService.getDefaultTenantId();
                    if (defaultTenantId != null && defaultTenantId > 0 && tenantMemberService != null) {
                        if (tenantMemberService.findByTenantIdAndUserId(defaultTenantId, userDetails.getUserId()) != null) {
                            tenantId = defaultTenantId;
                        } else {
                            log.warn("SINGLE mode: user {} is not a member of default tenant {}", userPid, defaultTenantId);
                        }
                    }
                }

                Long memberId = jwtUtil.extractMemberId(jwt);
                String executionScope = jwtUtil.extractExecutionScope(jwt);
                Long actorPartyId = positiveOrNull(jwtUtil.extractActorPartyId(jwt));
                Long partyMembershipId = positiveOrNull(jwtUtil.extractPartyMembershipId(jwt));
                String sessionStage = jwtUtil.extractSessionStage(jwt);
                long contextVersion = jwtUtil.extractContextVersion(jwt);
                if (isRestrictedStage(sessionStage)
                        && !isAllowedForRestrictedStage(request.getRequestURI())) {
                    ApiResponse<?> apiResponse = ApiResponse.errorWithContext(
                            ResponseCode.FORBIDDEN, request.getRequestURI());
                    reject(request, response, apiResponse);
                    return;
                }
                java.util.Set<Long> roleIds = java.util.Set.of();
                if (memberId != null && tenantId != null) {
                    try {
                        java.util.List<Long> ids = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
                        if (ids != null && !ids.isEmpty()) {
                            roleIds = java.util.Set.copyOf(ids);
                        }
                    } catch (RuntimeException e) {
                        // Don't fail the request if RBAC lookup hiccups —
                        // mobile config role layer falls back to tenant defaults.
                        log.warn("Failed to load roles for member {} tenant {}: {}",
                                memberId, tenantId, e.getMessage());
                    }
                }

                if ("party".equals(executionScope)) {
                    if (!"ready".equals(sessionStage) || partyAuthorizationService == null) {
                        ApiResponse<?> apiResponse = ApiResponse.errorWithContext(
                                ResponseCode.FORBIDDEN, request.getRequestURI());
                        reject(request, response, apiResponse);
                        return;
                    }
                    // Party tables deliberately remain behind TenantLineInterceptor. Install the
                    // verified JWT tenant/member context before revalidating the Party membership,
                    // otherwise the interceptor sees no current tenant and fail-closes the lookup.
                    // This provisional context is replaced with the final role/session context below;
                    // rejection paths must clear it before returning to avoid a pooled-thread leak.
                    MetaContext.setContext(tenantId, userDetails.getUserId(), userPid,
                                           userDetails.getUsername(), roleIds);
                    if (memberId != null) {
                        MetaContext.setMemberId(memberId);
                    }
                    try {
                        java.util.Set<Long> partyRoleIds = partyAuthorizationService.resolveActivePartyRoleIds(
                                tenantId, memberId, actorPartyId, partyMembershipId);
                        if (!partyRoleIds.isEmpty()) {
                            java.util.Set<Long> effectiveRoleIds = new java.util.HashSet<>(roleIds);
                            effectiveRoleIds.addAll(partyRoleIds);
                            roleIds = java.util.Set.copyOf(effectiveRoleIds);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Rejected stale Party Actor context: tenant={}, member={}, party={}, membership={}",
                                tenantId, memberId, actorPartyId, partyMembershipId);
                        MetaContext.clear();
                        ApiResponse<?> apiResponse = ApiResponse.errorWithContext(
                                ResponseCode.FORBIDDEN, request.getRequestURI());
                        reject(request, response, apiResponse);
                        return;
                    }
                } else if (actorPartyId != null || partyMembershipId != null) {
                    ApiResponse<?> apiResponse = ApiResponse.errorWithContext(
                            ResponseCode.FORBIDDEN, request.getRequestURI());
                    reject(request, response, apiResponse);
                    return;
                }

                MetaContext.setContext(tenantId, userDetails.getUserId(), userPid,
                                       userDetails.getUsername(), roleIds);
                if (memberId != null) {
                    MetaContext.setMemberId(memberId);
                }
                MetaContext.setSessionContext(
                        jwtUtil.extractApplicationId(jwt),
                        jwtUtil.extractLoginChannelId(jwt),
                        executionScope,
                        actorPartyId,
                        partyMembershipId,
                        sessionStage,
                        contextVersion);

                // Surface tenant/user in every log line for this request (log pattern reads
                // %X{tenantId}/%X{userId}). Cleared in the finally below so a pooled thread
                // never leaks one request's identity into the next.
                if (tenantId != null) {
                    MDC.put("tenantId", String.valueOf(tenantId));
                }
                if (userDetails.getUserId() != null) {
                    MDC.put("userId", String.valueOf(userDetails.getUserId()));
                }

                // Snapshot the request's OTel trace id into MetaContext so work that
                // hops to a pooled thread can still stamp it. TenantAwareTaskDecorator
                // carries MetaContext across @Async boundaries but not the OTel context,
                // so a writer on a worker thread has no span to read — which is why
                // ab_query_audit_log.trace_id and ab_gen_ai_usage.trace_id were NULL for
                // every row (85 and 278 respectively in live databases). Setting it here,
                // once per request, is the only place that sees both the span and every
                // downstream write. Cleared by MetaContext.clear() in the finally below.
                if (tracer != null) {
                    var span = tracer.currentSpan();
                    if (span != null) {
                        MetaContext.setOtelTraceId(span.context().traceId());
                    }
                }

                setAuthenticationContext(request, userDetails);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MetaContext.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }

    private static void setAuthenticationContext(HttpServletRequest request, CustomUserDetails userDetails) {
        UsernamePasswordAuthenticationToken authenticationToken =
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ApiResponse<?> errorResponse) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String s = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(s);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        if (Boolean.TRUE.equals(request.getAttribute(
                ExternalApiKeyAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            return true;
        }
        if ("options".equals(request.getMethod())) {
            return true;
        }

        String requestPath = request.getServletPath();
        // Check both main whitelist and swagger whitelist
        // NOTE: for "/**" patterns, match by path-segment boundary:
        //   "/api/crm/inbound/**" must match "/api/crm/inbound/x" but NOT "/api/crm/inbound-channels"
        java.util.function.Predicate<String> matchesPath = path -> {
            if (path.endsWith("/**")) {
                String base = path.substring(0, path.length() - 3); // strip "/**"
                if (requestPath.equals(base) || requestPath.startsWith(base + "/")) {
                    return true;
                }
                // Mid-segment wildcard patterns (e.g. "/api/ext/*/public/**", gamma-3 plugin public
                // endpoints) can't be expressed by the literal-prefix check above. Fall back to
                // AntPathMatcher ONLY for these — it is segment-boundary safe, so it matches the
                // existing literal patterns identically and merely enables the wildcard case.
                if (base.indexOf('*') >= 0) {
                    return ANT_PATH_MATCHER.match(path, requestPath);
                }
                return false;
            }
            return path.equals(requestPath);
        };
        if (Arrays.stream(WhiteList.whiteList).anyMatch(matchesPath)) {
            return true;
        }
        // Swagger endpoints — only skip auth in dev/test profiles
        if (isDevOrTestProfile() && Arrays.stream(WhiteList.swaggerWhiteList).anyMatch(matchesPath)) {
            return true;
        }
        // Test seed endpoints — skip JWT filter when test profile is active
        if (activeProfile.contains("test") && Arrays.stream(WhiteList.testWhiteList).anyMatch(matchesPath)) {
            return true;
        }
        return false;
    }

    private boolean isDevOrTestProfile() {
        return activeProfile != null && (activeProfile.contains("dev") || activeProfile.contains("local")
                || activeProfile.contains("test") || activeProfile.contains("integration-test"));
    }

    private boolean isRestrictedStage(String sessionStage) {
        return "onboarding".equals(sessionStage) || "actor_selection".equals(sessionStage);
    }

    private Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private boolean isAllowedForRestrictedStage(String path) {
        return "/api/auth/me".equals(path)
                || path.startsWith("/api/tenant-selection")
                || path.startsWith("/api/actors")
                || path.startsWith("/api/user/sessions")
                || path.startsWith("/api/auth/logout");
    }
}
