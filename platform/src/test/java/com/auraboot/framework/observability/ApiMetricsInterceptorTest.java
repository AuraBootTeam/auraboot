package com.auraboot.framework.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for ApiMetricsInterceptor path normalization and metric recording.
 */
class ApiMetricsInterceptorTest {

    private ObservabilityMetrics metrics;
    private ApiMetricsInterceptor interceptor;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(registry);
        interceptor = new ApiMetricsInterceptor(metrics);
    }

    @Test
    void afterCompletion_recordsMetricWithNormalizedPath() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getRequestURI()).thenReturn("/api/view/list/12345");
        when(request.getMethod()).thenReturn("get");
        when(response.getStatus()).thenReturn(200);

        interceptor.afterCompletion(request, response, null, null);

        double count = registry.get("auraboot_api_requests_total")
                .tag("path", "/api/view/list/{id}")
                .tag("method", "get")
                .tag("status", "200")
                .counter().count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void normalizePath_replacesUuids() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getRequestURI()).thenReturn("/api/tenant/550e8400-e29b-41d4-a716-446655440000/members");
        when(request.getMethod()).thenReturn("get");
        when(response.getStatus()).thenReturn(200);

        interceptor.afterCompletion(request, response, null, null);

        double count = registry.get("auraboot_api_requests_total")
                .tag("path", "/api/tenant/{uuid}/members")
                .tag("method", "get")
                .tag("status", "200")
                .counter().count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void afterCompletion_records5xxStatus() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getMethod()).thenReturn("post");
        when(response.getStatus()).thenReturn(500);

        interceptor.afterCompletion(request, response, null, null);

        double count = registry.get("auraboot_api_requests_total")
                .tag("path", "/api/auth/login")
                .tag("method", "post")
                .tag("status", "500")
                .counter().count();

        assertThat(count).isEqualTo(1.0);
    }
}
