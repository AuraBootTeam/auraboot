package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@code POST /api/meta/models} — verifies the virtual-model
 * wizard payload (sourceType / sourceRef / primaryKey / capabilities / extension)
 * is actually persisted rather than silently dropped.
 *
 * <p>Regression guard for P2A-Followup: previously the DTO only declared
 * code/displayName/description/modelType/extension, so Jackson silently ignored
 * the virtual-model fields and {@code createDirectly()} stored everything as a
 * physical model with empty capabilities.
 */
@DisplayName("ModelController POST — virtual model wizard payload persistence")
class ModelControllerCreateVirtualModelIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMvc() {
        Filter contextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true
                );
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("namedQuery virtual model: sourceType/sourceRef/primaryKey/capabilities persisted through saveDefinition")
    void createVirtualModel_persistsSourceAndCapabilities() throws Exception {
        String code = "p2a_fu_vm_" + System.currentTimeMillis();

        Map<String, Object> payload = Map.of(
                "code", code,
                "displayName", "P2A Followup Virtual Model",
                "description", "namedQuery-backed wizard payload",
                "sourceType", "namedQuery",
                "sourceRef", "queries/p2a_followup.sql",
                "primaryKey", "id",
                "fields", List.of(
                        Map.of("code", "id", "dataType", "bigint",
                                "sortable", false, "filterable", false),
                        Map.of("code", "name", "dataType", "string",
                                "sortable", true, "filterable", true)
                ),
                "capabilities", Map.of(
                        "list", true,
                        "detail", true,
                        "sort", true,
                        "filter", true,
                        "paginate", true,
                        "export", true,
                        "create", false,
                        "update", false,
                        "delete", false,
                        "bulkDelete", false
                ),
                "extension", Map.of("endpointAdapter", "rest")
        );

        mockMvc.perform(post("/api/meta/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.pid").isNotEmpty());

        // Reload via service to assert the definition actually carries virtual-model data.
        // The MockMvc filter scope ends after perform(); re-install MetaContext here.
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName()
        );
        ModelDefinition def;
        try {
            def = metaModelService.getDefinitionByCode(code);
        } finally {
            MetaContext.clear();
        }
        assertThat(def).as("definition must be reloadable by code").isNotNull();
        assertThat(def.getSourceType()).isEqualTo("namedQuery");
        assertThat(def.getSourceRef()).isEqualTo("queries/p2a_followup.sql");
        assertThat(def.getPrimaryKey()).isEqualTo("id");
        assertThat(def.getCapabilities()).as("capabilities must be persisted").isNotNull();
        assertThat(def.getCapabilities().isList()).isTrue();
        assertThat(def.getCapabilities().isCreate()).isFalse();
        assertThat(def.getCapabilities().isUpdate()).isFalse();
        assertThat(def.getCapabilities().isSort()).isTrue();
        assertThat(def.getCapabilities().isFilter()).isTrue();
        // normalizeCapabilities() should lift per-field sortable/filterable flags
        // into the capability whitelists.
        assertThat(def.getCapabilities().getSortableFields()).contains("name");
        assertThat(def.getCapabilities().getFilterableFields()).contains("name");
        assertThat(def.getExtension())
                .as("caller-supplied extension keys survive the create path")
                .containsEntry("endpointAdapter", "rest");
    }

    @Test
    @DisplayName("physical model (no virtual payload): legacy create path is unchanged")
    void createPhysicalModel_stillWorks() throws Exception {
        String code = "p2a_fu_phys_" + System.currentTimeMillis();

        Map<String, Object> payload = Map.of(
                "code", code,
                "displayName", "P2A Followup Physical",
                "modelType", "entity"
        );

        mockMvc.perform(post("/api/meta/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.code").value(code));
    }
}
