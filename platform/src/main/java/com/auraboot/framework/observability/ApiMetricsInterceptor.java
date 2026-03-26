package com.auraboot.framework.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP interceptor that records API request metrics for every request.
 * Normalizes paths to avoid high-cardinality label explosion.
 */
@Component
public class ApiMetricsInterceptor implements HandlerInterceptor {

    private final ObservabilityMetrics metrics;

    public ApiMetricsInterceptor(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        String path = normalizePath(request.getRequestURI());
        String method = request.getMethod();
        String status = String.valueOf(response.getStatus());

        metrics.recordApiRequest(path, method, status);
    }

    /**
     * Normalize request paths to reduce cardinality.
     * Replaces numeric/UUID path segments with placeholders.
     */
    private String normalizePath(String uri) {
        if (uri == null) return "unknown";
        // Replace UUIDs first (before numeric, to avoid partial matches)
        String normalized = uri.replaceAll(
                "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "/{uuid}");
        // Replace ULIDs (26 chars, base32)
        normalized = normalized.replaceAll("/[0-9A-Z]{26}", "/{ulid}");
        // Replace pure numeric IDs (whole path segments only)
        normalized = normalized.replaceAll("/\\d+(?=/|$)", "/{id}");
        return normalized;
    }
}
