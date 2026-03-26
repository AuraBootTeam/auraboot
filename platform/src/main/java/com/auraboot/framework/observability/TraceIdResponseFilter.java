package com.auraboot.framework.observability;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Injects X-Trace-Id header into all HTTP responses for trace correlation.
 * Enables frontend/k6 to look up specific traces in Jaeger UI.
 */
@Component
public class TraceIdResponseFilter implements Filter {

    private final Tracer tracer;

    public TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse && tracer.currentSpan() != null) {
            String traceId = tracer.currentSpan().context().traceId();
            if (traceId != null) {
                httpResponse.setHeader("X-Trace-Id", traceId);
            }
        }
        chain.doFilter(request, response);
    }
}
