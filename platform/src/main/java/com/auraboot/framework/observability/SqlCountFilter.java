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
 *   <li>Records the count as a Prometheus distribution summary metric</li>
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
    private final DistributionSummary sqlCountSummary;

    public SqlCountFilter(
            MeterRegistry meterRegistry,
            @Value("${auraboot.performance.sql-count-warn-threshold:10}") int warnThreshold,
            @Value("${auraboot.performance.sql-count-error-threshold:50}") int errorThreshold,
            @Value("${auraboot.performance.sql-count-header-enabled:true}") boolean headerEnabled) {
        this.warnThreshold = warnThreshold;
        this.errorThreshold = errorThreshold;
        this.headerEnabled = headerEnabled;
        this.sqlCountSummary = DistributionSummary.builder(METRIC_NAME)
                .description("Number of SQL statements executed per HTTP request")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SqlCountHolder.reset();
        try {
            filterChain.doFilter(request, response);
        } finally {
            int count = SqlCountHolder.get();

            if (headerEnabled) {
                response.setIntHeader(HEADER_SQL_COUNT, count);
            }

            sqlCountSummary.record(count);

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
}
