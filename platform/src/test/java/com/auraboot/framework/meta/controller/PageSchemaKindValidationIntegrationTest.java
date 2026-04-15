package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.PageSchemaVersionService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Validates that {@code kind=dashboard} is rejected at the DTO boundary.
 *
 * <p>Uses Mockito standaloneSetup so we can test the {@code @Pattern} constraint on
 * {@link com.auraboot.framework.meta.dto.PageSchemaCreateRequest#kind} without needing
 * a running DB, JWT auth, or permission interceptor.  The assertion we care about is
 * purely structural: the regex {@code ^(list|form|detail|composite)$} must not include
 * {@code dashboard}.
 *
 * <p>Plan 3a – Task 7.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageSchema kind=dashboard rejected at API boundary (Plan 3a T7)")
class PageSchemaKindValidationIntegrationTest {

    @Mock
    private PageSchemaService pageSchemaService;

    @Mock
    private PageSchemaVersionService pageSchemaVersionService;

    @Mock
    private PluginResourceTracker pluginResourceTracker;

    @InjectMocks
    private PageSchemaController pageSchemaController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(pageSchemaController)
                .build();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> validBody(String kind) {
        return Map.of(
                "pageKey", "test_page_" + System.currentTimeMillis(),
                "name", "Test Page",
                "title", "Test Page Title",
                "kind", kind,
                "blocks", List.of()
        );
    }

    // ── PS-VAL-01 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PS-VAL-01: kind=dashboard returns 400 with validation error on 'kind' field")
    void ps_val_01_kindDashboard_returns400() throws Exception {
        var body = validBody("dashboard");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(
                        result.getResolvedException() instanceof MethodArgumentNotValidException,
                        "Expected MethodArgumentNotValidException for kind=dashboard"));
    }

    // ── PS-VAL-02 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PS-VAL-02: kind=list passes DTO validation (reaches controller layer)")
    void ps_val_02_kindList_passesValidation() throws Exception {
        var body = validBody("list");

        // Service is mocked → returns null, controller wraps in ApiResponse → 200.
        // We only assert that validation does NOT reject the request (no 400).
        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    // ── PS-VAL-03 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PS-VAL-03: kind=form passes DTO validation")
    void ps_val_03_kindForm_passesValidation() throws Exception {
        var body = validBody("form");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }
}
