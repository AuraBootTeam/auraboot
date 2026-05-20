package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.PageSchemaVersionService;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Validates PageSchema kind values at the DTO boundary.
 *
 * <p>Uses Mockito standaloneSetup so we can test the {@code @Pattern} constraint on
 * {@link com.auraboot.framework.meta.dto.PageSchemaCreateRequest#kind} without needing
 * a running DB, JWT auth, or permission interceptor. The assertion we care about is
 * purely structural: V3 authoring allows {@code dashboard} and {@code composite}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageSchema kind validation at API boundary")
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
    @DisplayName("PS-VAL-01: kind=dashboard passes DTO validation")
    void ps_val_01_kindDashboard_passesValidation() throws Exception {
        var body = validBody("dashboard");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
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

    @Test
    @DisplayName("PS-VAL-04: kind=composite passes DTO validation")
    void ps_val_04_kindComposite_passesValidation() throws Exception {
        var body = validBody("composite");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("PS-VAL-05: unknown kind returns 400 with validation error on 'kind' field")
    void ps_val_05_unknownKind_returns400() throws Exception {
        var body = validBody("unknown");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(
                        result.getResolvedException() instanceof MethodArgumentNotValidException,
                        "Expected MethodArgumentNotValidException for unknown kind"));
    }

    @Test
    @DisplayName("PS-VAL-06: GET /api/pages/page-key/{pageKey} returns a V3 dashboard page")
    void ps_val_06_findByPageKey_returnsV3Dashboard() throws Exception {
        PageSchemaDTO dto = new PageSchemaDTO();
        dto.setPid("page_pid");
        dto.setPageKey("system_overview");
        dto.setName("system_overview");
        dto.setKind("dashboard");
        dto.setSchemaVersion(3);
        dto.setBlocks(List.of(Map.of("id", "dashboard_system_overview", "blockType", "dashboard")));
        when(pageSchemaService.findAnyByPageKey("system_overview")).thenReturn(dto);

        mockMvc.perform(get("/api/pages/page-key/system_overview")
                        .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pid").value("page_pid"))
                .andExpect(jsonPath("$.data.pageKey").value("system_overview"))
                .andExpect(jsonPath("$.data.kind").value("dashboard"))
                .andExpect(jsonPath("$.data.schemaVersion").value(3));
    }
}
