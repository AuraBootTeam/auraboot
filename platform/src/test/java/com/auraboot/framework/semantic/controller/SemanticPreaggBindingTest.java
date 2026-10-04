package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.semantic.service.SemanticPreaggService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hermetic Spring binding contract only; permission interceptors require real-stack API tests. */
class SemanticPreaggBindingTest {
    @Test
    void omittedDimensionsDoNotBecomeALiteralBracketDimension() throws Exception {
        var service = mock(SemanticPreaggService.class);
        MockMvcBuilders.standaloneSetup(new SemanticPreaggController(service)).build()
                .perform(post("/api/semantic/preaggs").param("name", "Orders")
                        .param("semanticModelPid", "model").param("metricCode", "count"))
                .andExpect(status().isOk());
        verify(service).create("Orders", "model", "count", null, 60);
    }

    @Test
    void repeatedDimensionsBindWithoutChangingTheirCodes() throws Exception {
        var service = mock(SemanticPreaggService.class);
        MockMvcBuilders.standaloneSetup(new SemanticPreaggController(service)).build()
                .perform(post("/api/semantic/preaggs").param("name", "Orders")
                        .param("semanticModelPid", "model").param("metricCode", "count")
                        .param("dimensionCodes", "status", "created_day").param("refreshMinutes", "15"))
                .andExpect(status().isOk());
        verify(service).create("Orders", "model", "count", List.of("status", "created_day"), 15);
    }
}
