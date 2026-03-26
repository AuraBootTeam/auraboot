package com.auraboot.framework.application.web.filter;

import com.auraboot.framework.application.security.WhiteList;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
            CustomUserDetails userDetails = (CustomUserDetails) this.userDetailsService.loadUserByUsername(userPid);

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
                        log.warn("Session check failed, allowing request: {}", e.getMessage());
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

                MetaContext.setContext(tenantId, userDetails.getUserId(), userPid, userDetails.getUsername());
                setAuthenticationContext(request, userDetails);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MetaContext.clear();
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
                return requestPath.equals(base) ||
                       requestPath.startsWith(base + "/");
            }
            return path.equals(requestPath);
        };
        if (Arrays.stream(WhiteList.whiteList).anyMatch(matchesPath)) {
            return true;
        }
        if (Arrays.stream(WhiteList.swaggerWhiteList).anyMatch(matchesPath)) {
            return true;
        }
        // Test seed endpoints — skip JWT filter when test profile is active
        if (activeProfile.contains("test") && Arrays.stream(WhiteList.testWhiteList).anyMatch(matchesPath)) {
            return true;
        }
        return false;
    }
}
