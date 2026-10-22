package com.auraboot.framework.integration.bootstrap;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("BootstrapController - Integration Tests")
class BootstrapControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private SystemConfigService systemConfigService;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void status_returns_missing_system_config_when_uninitialized() throws Exception {
        when(systemConfigService.isInitialized()).thenReturn(false);

        mockMvc.perform(get("/api/bootstrap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.initialized").value(false))
                .andExpect(jsonPath("$.data.missingParts").isArray())
                .andExpect(jsonPath("$.data.missingParts.length()").value(1))
                .andExpect(jsonPath("$.data.missingParts[0]").value("system_config"))
                .andExpect(jsonPath("$.data.reason").value("Bootstrap not completed"));
    }

    @Test
    void status_returns_empty_missing_parts_when_initialized() throws Exception {
        when(systemConfigService.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/api/bootstrap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andExpect(jsonPath("$.data.missingParts").isArray())
                .andExpect(jsonPath("$.data.missingParts.length()").value(0));
    }
}
