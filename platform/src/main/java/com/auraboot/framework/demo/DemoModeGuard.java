package com.auraboot.framework.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guards destructive endpoints when AuraBoot runs in public-demo mode.
 *
 * <p>Activated only when {@code aura.demo.enabled=true}. When active, requests
 * matching any pattern in {@link DemoModeProperties#getDenyPaths()} are short-
 * circuited with HTTP 403 and a JSON body explaining demo mode. Allowlist
 * patterns override the denylist (matched first).
 *
 * <p>This filter runs at high precedence so the rejection happens before
 * permission checks, audit logging, and any other expensive work.
 *
 * <p>Demo mode is intentionally separate from authentication — even authenticated
 * admins on a demo deployment can't reach destructive endpoints, because the
 * deployment is shared and disposable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoModeGuard extends OncePerRequestFilter {

    private final DemoModeProperties properties;
    private final ObjectMapper mapper;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public DemoModeGuard(DemoModeProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // Allowlist wins over denylist
        if (matchesAny(path, properties.getAllowPaths())) {
            chain.doFilter(request, response);
            return;
        }

        if (matchesAny(path, properties.getDenyPaths())) {
            writeForbidden(response, path);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean matchesAny(String path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Auraboot-Demo", "true");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 403);
        body.put("error", "demo_mode_forbidden");
        body.put("message",
                "This action is disabled in public-demo mode. Self-host AuraBoot to use destructive operations.");
        body.put("path", path);
        body.put("docs", "https://docs.auraboot.com/getting-started/quickstart");

        mapper.writeValue(response.getOutputStream(), body);
    }
}

@Configuration
@EnableConfigurationProperties(DemoModeProperties.class)
class DemoModeConfiguration {
    // Marker config to enable the @ConfigurationProperties bean.
}
