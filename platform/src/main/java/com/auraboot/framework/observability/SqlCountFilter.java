package com.auraboot.framework.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * Servlet filter that tracks the number of SQL statements executed per HTTP request.
 *
 * <p>At request start, resets the thread-local counter. After the request completes,
 * reads the count and:
 * <ul>
 *   <li>Sets the {@code X-SQL-Count} response header (configurable)</li>
 *   <li>Logs a warning if count exceeds the warn threshold</li>
 *   <li>Logs an error if count exceeds the error threshold</li>
 *   <li>Records the count as a Prometheus distribution summary metric with endpoint tags</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SqlCountFilter extends OncePerRequestFilter {

    private static final String HEADER_SQL_COUNT = "X-SQL-Count";
    private static final String METRIC_NAME = "auraboot_request_sql_count";

    private final int warnThreshold;
    private final int errorThreshold;
    private final boolean headerEnabled;
    private final MeterRegistry meterRegistry;

    public SqlCountFilter(
            MeterRegistry meterRegistry,
            @Value("${auraboot.performance.sql-count-warn-threshold:10}") int warnThreshold,
            @Value("${auraboot.performance.sql-count-error-threshold:50}") int errorThreshold,
            @Value("${auraboot.performance.sql-count-header-enabled:true}") boolean headerEnabled) {
        this.warnThreshold = warnThreshold;
        this.errorThreshold = errorThreshold;
        this.headerEnabled = headerEnabled;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SqlCountHolder.reset();
        // Wrap response to buffer output, allowing header injection after chain completes
        ContentCachingResponseWrapper wrappedResponse = headerEnabled
                ? new ContentCachingResponseWrapper(response)
                : null;
        HttpServletResponse effectiveResponse = wrappedResponse != null ? wrappedResponse : response;
        try {
            filterChain.doFilter(request, effectiveResponse);
        } finally {
            int count = SqlCountHolder.get();

            if (wrappedResponse != null) {
                wrappedResponse.setIntHeader(HEADER_SQL_COUNT, count);
                wrappedResponse.copyBodyToResponse();
            }

            if (count > 0) {
                String method = request.getMethod();
                String path = normalizePath(request.getRequestURI());

                DistributionSummary.builder(METRIC_NAME)
                        .description("Number of SQL statements executed per HTTP request")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .tag("method", method)
                        .tag("path", path)
                        .register(meterRegistry)
                        .record(count);
            }

            if (count >= errorThreshold) {
                log.error("Excessive SQL count: {} queries for {} {}",
                        count, request.getMethod(), request.getRequestURI());
            } else if (count >= warnThreshold) {
                log.warn("High SQL count: {} queries for {} {}",
                        count, request.getMethod(), request.getRequestURI());
            }

            SqlCountHolder.reset();
        }
    }

    /**
     * Normalize request paths to reduce cardinality.
     * Replaces numeric/UUID/ULID path segments with placeholders.
     */
    static String normalizePath(String uri) {
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
