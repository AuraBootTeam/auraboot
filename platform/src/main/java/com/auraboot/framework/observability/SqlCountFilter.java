package com.auraboot.framework.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;

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
 *
 * <h2>Why the header is stamped lazily instead of buffering the body</h2>
 *
 * <p>A header can only be set before the response commits, but the SQL count is only
 * known after the handler runs — so an earlier version wrapped every response in a
 * {@code ContentCachingResponseWrapper}, buffered the whole body in a
 * {@code ByteArrayOutputStream}, set the header, then copied the body out. That made
 * the header reliable at the cost of holding every response in heap, including file
 * downloads and report exports: nine controllers in this codebase return an
 * {@code InputStreamResource} or streaming body, so an arbitrarily large download was
 * an arbitrarily large heap allocation.
 *
 * <p>It also had to guess, up front, which responses were streams — because buffering
 * an SSE response yields an empty 200 with the events sitting in a buffer nobody
 * flushes. Guessing wrong was silent, so the guess accumulated an {@code Accept} check,
 * a URI check, and a loud error for when it was wrong anyway.
 *
 * <p>Both problems come from buffering, so this no longer buffers. The header is
 * stamped the moment the body is first asked for ({@code getOutputStream()} /
 * {@code getWriter()} / {@code flushBuffer()}): acquiring the stream does not commit the
 * response, so setting a header there is still legal, and for a normal MVC request the
 * handler has already returned by then, so the count is complete. Responses that never
 * write a body are stamped in the {@code finally} block instead. Nothing is copied,
 * nothing is held, and a streaming response passes straight through — no detection
 * heuristic required, and no way to swallow one.
 *
 * <p>The trade-off is explicit: SQL executed <em>after</em> the first byte of the body
 * is written is not reflected in the header. It is still counted in the metric and the
 * threshold logs, which run at request end. For streaming endpoints that is a partial
 * header count instead of the previous behaviour, which was a swallowed response.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SqlCountFilter extends OncePerRequestFilter {

    static final String HEADER_SQL_COUNT = "X-SQL-Count";
    private static final String METRIC_NAME = "auraboot_request_sql_count";

    private final int warnThreshold;
    private final int errorThreshold;
    private final boolean headerEnabled;
    private final MeterRegistry meterRegistry;

    public SqlCountFilter(
            MeterRegistry meterRegistry,
            @Value("${auraboot.performance.sql-count-warn-threshold:50}") int warnThreshold,
            @Value("${auraboot.performance.sql-count-error-threshold:100}") int errorThreshold,
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

        LazySqlCountHeaderResponse stamping =
                headerEnabled ? new LazySqlCountHeaderResponse(response) : null;
        HttpServletResponse effectiveResponse = stamping != null ? stamping : response;

        try {
            filterChain.doFilter(request, effectiveResponse);
        } finally {
            int count = SqlCountHolder.get();

            // Bodyless responses (204, redirects, empty 200) never ask for the stream, so
            // they were never stamped on the way through — and by definition are not
            // committed yet.
            if (stamping != null && !stamping.stamped() && !response.isCommitted()) {
                response.setIntHeader(HEADER_SQL_COUNT, count);
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

            if (!shouldLogSqlCountSeverity(request.getRequestURI())) {
                SqlCountHolder.reset();
                return;
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
     * Sets {@code X-SQL-Count} the first time the body is asked for, then gets out of the
     * way. The wrapped stream and writer are returned untouched, so nothing is buffered
     * and a streaming response is unaffected.
     */
    static final class LazySqlCountHeaderResponse extends HttpServletResponseWrapper {

        private boolean stamped;

        LazySqlCountHeaderResponse(HttpServletResponse response) {
            super(response);
        }

        boolean stamped() {
            return stamped;
        }

        private void stamp() {
            if (stamped) {
                return;
            }
            stamped = true;
            if (!isCommitted()) {
                setIntHeader(HEADER_SQL_COUNT, SqlCountHolder.get());
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            stamp();
            return super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            stamp();
            return super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            stamp();
            super.flushBuffer();
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

    static boolean shouldLogSqlCountSeverity(String uri) {
        return uri == null || !uri.startsWith("/api/test/");
    }
}
