package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ChartDataController.
 * Tests the POST /api/meta/chart-data endpoint.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
class ChartDataControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        // Create a filter that sets MetaContext for each request
        // This is necessary because MockMvc doesn't go through the JWT filter
        Filter metaContextFilter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                try {
                    // Set MetaContext with test user/tenant info from BaseIntegrationTest
                    MetaContext.setContext(
                            getTestTenant().getId(),
                            getTestUser().getId(),
                            getTestUser().getPid(),
                            getTestUser().getUserName()
                    );
                    chain.doFilter(request, response);
                } finally {
                    MetaContext.clear();
                }
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @Test
    void shouldReturnChartData() throws Exception {
        MetricConfig metric = new MetricConfig();
        metric.setField("id");
        metric.setAggregation("count");
        metric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        // Use ab_tenant table which has deleted_flag column
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(metric));

        mockMvc.perform(post("/api/meta/chart-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.rows").isArray());
    }

    @Test
    void shouldReturnChartDataWithDimensions() throws Exception {
        MetricConfig metric = new MetricConfig();
        metric.setField("id");
        metric.setAggregation("count");
        metric.setAlias("count");

        AggregateQueryRequest request = new AggregateQueryRequest();
        // Use ab_tenant table which has deleted_flag and status columns
        request.setModelCode("ab_tenant");
        request.setMetrics(List.of(metric));
        request.setDimensions(List.of("status"));

        mockMvc.perform(post("/api/meta/chart-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.rows").isArray());
    }
}
