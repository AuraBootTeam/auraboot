package com.auraboot.framework.integration.bootstrap;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.bootstrap.BootstrapStatusEvaluator;
import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("BootstrapController - Integration Tests")
class BootstrapControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private BootstrapStatusEvaluator statusEvaluator;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void status_returns_missing_parts_when_uninitialized() throws Exception {
        when(statusEvaluator.evaluate()).thenReturn(new BootstrapStatusEvaluator.Result(
                List.of(BootstrapMissingPart.ADMIN_USER, BootstrapMissingPart.DEFAULT_TENANT),
                "Missing bootstrap data: admin_user, default_tenant"));

        mockMvc.perform(get("/api/bootstrap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.initialized").value(false))
                .andExpect(jsonPath("$.data.missingParts").isArray())
                .andExpect(jsonPath("$.data.missingParts.length()").value(2))
                .andExpect(jsonPath("$.data.missingParts[0]").value("admin_user"))
                .andExpect(jsonPath("$.data.reason").value("Missing bootstrap data: admin_user, default_tenant"));
    }

    @Test
    void status_returns_initialized_when_evaluator_reports_no_missing() throws Exception {
        when(statusEvaluator.evaluate()).thenReturn(new BootstrapStatusEvaluator.Result(
                List.of(), null));

        mockMvc.perform(get("/api/bootstrap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andExpect(jsonPath("$.data.missingParts").isArray())
                .andExpect(jsonPath("$.data.missingParts.length()").value(0));
    }
}
