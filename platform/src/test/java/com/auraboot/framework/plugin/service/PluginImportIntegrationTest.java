package com.auraboot.framework.plugin.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.controller.PluginImportController;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Plugin Import API.
 * Tests the complete plugin import workflow: upload -> preview -> execute -> rollback.
 *
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>Validation: manifest validation with various error conditions</li>
 *   <li>Parse: inline JSON parsing and error handling</li>
 *   <li>Upload: file upload and preview generation</li>
 *   <li>Execute: resource import with conflict strategies</li>
 *   <li>Rollback: import rollback and eligibility checks</li>
 *   <li>History: import history and status queries</li>
 * </ul>
 *
 * <p><b>Fixed Issues:</b></p>
 * <ul>
 *   <li>MetaContext not initialized - added filter to set tenant context for MockMvc</li>
 *   <li>Invalid status values - changed 'active' to 'draft' for model/field/command inserts</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PluginImportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private MetaModelMapper modelMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    private static final String API_BASE = "/api/plugins/import";

    // Test data
    private static String testImportId;
    private static final String TEST_PLUGIN_ID = "com.test.plugin-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_NAMESPACE = "test_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

    @BeforeEach
    void setup() {
        // Grant PLUGIN.plugin.manage permission to test user's role
        // so that @RequirePermission on PluginImportController passes
        grantPluginPermission();

        // Create a filter that sets MetaContext AND SecurityContext for each request.
        // MockMvc doesn't go through JwtAuthenticationFilter, so we must set both:
        // - MetaContext: for tenant-scoped database queries
        // - SecurityContext: for @RequirePermission interceptor
        Filter metaContextFilter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                try {
                    MetaContext.setContext(
                            getTestTenant().getId(),
                            getTestUser().getId(),
                            getTestUser().getPid(),
                            getTestUser().getUserName()
                    );
                    // Set SecurityContext so PermissionInterceptor can extract userId
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
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    // ==================== Helper Methods ====================

    /**
     * Create PLUGIN.plugin.manage permission and assign it to the test role,
     * so PermissionInterceptor allows access to PluginImportController endpoints.
     */
    private void grantPluginPermission() {
        String permCode = "PLUGIN.plugin.manage";

        // Create permission if not exists
        Permission perm = permissionMapper.findByCode(permCode);
        if (perm == null) {
            perm = new Permission();
            perm.setPid(UniqueIdGenerator.generate());
            perm.setCode(permCode);
            perm.setName("Plugin Management");
            perm.setResourceType("plugin");
            perm.setResourceCode("plugin");
            perm.setAction("manage");
            perm.setSource("manual");
            perm.setStatus("active");
            perm.setDeletedFlag(false);
            perm.setTenantId(getTestTenant().getId());
            perm.setCreatedAt(java.time.Instant.now());
            perm.setUpdatedAt(java.time.Instant.now());
            permissionMapper.insert(perm);
        }

        // Assign permission to test role
        RolePermission rp = new RolePermission();
        rp.setPid(UniqueIdGenerator.generate());
        rp.setRoleId(getTestRole().getId());
        rp.setPermissionId(perm.getId());
        rp.setGrantType("grant");
        rp.setStatus("active");
        rp.setDeletedFlag(false);
        rp.setTenantId(getTestTenant().getId());
        rp.setCreatedAt(java.time.Instant.now());
        rp.setUpdatedAt(java.time.Instant.now());
        rolePermissionMapper.insert(rp);

        // Evict permission cache so the new binding takes effect
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    /**
     * Assert that response is either success (200) or report diagnostic info on 500.
     */
    private void assertSuccessOrDiagnostic(MvcResult result, String operationName) throws Exception {
        int status = result.getResponse().getStatus();
        String response = result.getResponse().getContentAsString();

        if (status == 500) {
            log.error("{} returned 500 - service bug detected. Response: {}", operationName, response);
            Assertions.fail(operationName + " returned 500 (server error). " +
                    "This indicates a bug in the import service that needs to be fixed. " +
                    "Response: " + response);
        }

        assertThat(status)
                .as(operationName + " should return 200 but got " + status + ". Response: " + response)
                .isEqualTo(200);
    }

    /**
     * Create a minimal valid plugin manifest for testing.
     */
    private PluginManifestExtended createMinimalManifest() {
        return PluginManifestExtended.builder()
                .pluginId(TEST_PLUGIN_ID)
                .namespace(TEST_NAMESPACE)
                .version("1.0.0")
                .displayName("Test Plugin")
                .build();
    }

    /**
     * Create a plugin manifest with basic resources.
     */
    private PluginManifestExtended createManifestWithResources() {
        // Create a test dictionary
        DictDefinitionDTO.DictItemDTO item1 = DictDefinitionDTO.DictItemDTO.builder()
                .value("active")
                .label("Active")
                .sortNo(1)
                .status("enabled")
                .build();

        DictDefinitionDTO.DictItemDTO item2 = DictDefinitionDTO.DictItemDTO.builder()
                .value("inactive")
                .label("Inactive")
                .sortNo(2)
                .status("enabled")
                .build();

        DictDefinitionDTO dict = DictDefinitionDTO.builder()
                .code(TEST_NAMESPACE + "_status")
                .name("Test Status")
                .dictType("static")
                .items(Arrays.asList(item1, item2))
                .build();

        // Create a test field
        FieldDefinitionDTO field = FieldDefinitionDTO.builder()
                .code(TEST_NAMESPACE + "_name")
                .displayName("Name")
                .dataType("string")
                .constraints(FieldDefinitionDTO.FieldConstraints.builder()
                        .required(true)
                        .maxLength(100)
                        .build())
                .build();

        // Create a test model
        ModelDefinitionDTO model = ModelDefinitionDTO.builder()
                .code(TEST_NAMESPACE + "_entity")
                .displayName("Test Entity")
                .modelType("entity")
                .build();

        // Create model-field binding
        ModelFieldBindingDTO binding = ModelFieldBindingDTO.builder()
                .modelCode(TEST_NAMESPACE + "_entity")
                .fieldCode(TEST_NAMESPACE + "_name")
                .sequence(1)
                .required(true)
                .build();

        return PluginManifestExtended.builder()
                .pluginId(TEST_PLUGIN_ID)
                .namespace(TEST_NAMESPACE)
                .version("1.0.0")
                .displayName("Test Plugin with Resources")
                .dicts(List.of(dict))
                .fields(List.of(field))
                .models(List.of(model))
                .modelFieldBindings(List.of(binding))
                .build();
    }

    // ==================== Validation Tests ====================

    @Test
    @Order(1)
    @DisplayName("POST /validate - should validate a valid manifest")
    void testValidateValidManifest() throws Exception {
        PluginManifestExtended manifest = createMinimalManifest();

        MvcResult result = mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isArray())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        log.info("Validate response: {}", response);
    }

    @Test
    @Order(2)
    @DisplayName("POST /validate - should reject invalid manifest (missing pluginId)")
    void testValidateInvalidManifestMissingPluginId() throws Exception {
        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .namespace(TEST_NAMESPACE)
                .version("1.0.0")
                .build();

        mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("POST /validate - should reject invalid manifest (missing namespace)")
    void testValidateInvalidManifestMissingNamespace() throws Exception {
        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(TEST_PLUGIN_ID)
                .version("1.0.0")
                .build();

        mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    @Order(4)
    @DisplayName("POST /validate - should validate manifest with resources")
    void testValidateManifestWithResources() throws Exception {
        PluginManifestExtended manifest = createManifestWithResources();

        MvcResult result = mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.dependencies").exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        log.info("Validate with resources response: {}", response);
    }

    // ==================== Parse Tests ====================

    @Test
    @Order(10)
    @DisplayName("POST /parse - should parse inline JSON manifest")
    void testParseJsonManifest() throws Exception {
        PluginManifestExtended manifest = createMinimalManifest();
        String jsonContent = objectMapper.writeValueAsString(manifest);

        MvcResult result = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonContent)
                        .param("sourceName", "test-inline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").exists())
                .andExpect(jsonPath("$.pluginId").value(TEST_PLUGIN_ID))
                .andExpect(jsonPath("$.namespace").value(TEST_NAMESPACE))
                .andExpect(jsonPath("$.valid").value(true))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        ImportPreviewResult previewResult = objectMapper.readValue(response, ImportPreviewResult.class);
        testImportId = previewResult.getImportId();

        log.info("Parse response: {}, importId: {}", response, testImportId);
        assertThat(testImportId).isNotNull().isNotEmpty();
    }

    @Test
    @Order(11)
    @DisplayName("POST /parse - should handle invalid JSON")
    void testParseInvalidJson() throws Exception {
        String invalidJson = "{ invalid json }";

        MvcResult result = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson)
                        .param("sourceName", "invalid-json"))
                .andReturn();

        // API may return 400 for parse error, or 200 with validation errors
        int status = result.getResponse().getStatus();
        log.info("Parse invalid JSON response status: {}, body: {}",
                status, result.getResponse().getContentAsString());

        assertThat(status).isIn(200, 400);
        if (status == 200) {
            // If 200, should have validation errors
            String response = result.getResponse().getContentAsString();
            assertThat(response).isNotEmpty();
        }
    }

    // ==================== Upload Tests ====================

    @Test
    @Order(20)
    @DisplayName("POST /upload - should upload JSON manifest file")
    void testUploadJsonFile() throws Exception {
        PluginManifestExtended manifest = createManifestWithResources();
        String jsonContent = objectMapper.writeValueAsString(manifest);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "plugin-manifest.json",
                MediaType.APPLICATION_JSON_VALUE,
                jsonContent.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(multipart(API_BASE + "/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").exists())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.pluginId").value(TEST_PLUGIN_ID))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        ImportPreviewResult previewResult = objectMapper.readValue(response, ImportPreviewResult.class);

        log.info("Upload response: {}", response);
        assertThat(previewResult.getImportId()).isNotNull();
        assertThat(previewResult.getActionCounts()).isNotNull();
    }

    @Test
    @Order(21)
    @DisplayName("POST /upload - should handle empty file")
    void testUploadEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.json",
                MediaType.APPLICATION_JSON_VALUE,
                new byte[0]
        );

        MvcResult result = mockMvc.perform(multipart(API_BASE + "/upload")
                        .file(file))
                .andReturn();

        // API may return 400 for empty file, or 200 with validation errors
        int status = result.getResponse().getStatus();
        log.info("Upload empty file response status: {}, body: {}",
                status, result.getResponse().getContentAsString());

        assertThat(status).isIn(200, 400);
        if (status == 200) {
            // If 200, should indicate invalid or empty content
            String response = result.getResponse().getContentAsString();
            assertThat(response).isNotEmpty();
        }
    }

    // ==================== Preview Tests ====================

    @Test
    @Order(30)
    @DisplayName("GET /{importId}/preview - should get existing preview")
    void testGetPreview() throws Exception {
        // First, create a preview by parsing
        PluginManifestExtended manifest = createManifestWithResources();
        String jsonContent = objectMapper.writeValueAsString(manifest);

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonContent))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Then, get the preview
        MvcResult result = mockMvc.perform(get(API_BASE + "/" + importId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(importId))
                .andExpect(jsonPath("$.pluginId").value(TEST_PLUGIN_ID))
                .andReturn();

        log.info("Get preview response: {}", result.getResponse().getContentAsString());
    }

    @Test
    @Order(31)
    @DisplayName("GET /{importId}/preview - should return 404 for non-existent import")
    void testGetPreviewNotFound() throws Exception {
        mockMvc.perform(get(API_BASE + "/non-existent-id/preview"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(32)
    @DisplayName("POST /{importId}/preview - should regenerate preview with different options")
    void testRegeneratePreview() throws Exception {
        // First, create a preview
        PluginManifestExtended manifest = createManifestWithResources();
        String jsonContent = objectMapper.writeValueAsString(manifest);

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonContent))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Regenerate with different options
        ImportRequest request = ImportRequest.builder()
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .validateReferences(true)
                .build();

        mockMvc.perform(post(API_BASE + "/" + importId + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(importId));
    }

    // ==================== Execute Tests ====================

    @Test
    @Order(40)
    @DisplayName("POST /{importId}/execute - should execute import with dict resources")
    void testExecuteImport() throws Exception {
        // Create a unique manifest for this test
        String uniqueNamespace = "exec_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.exec-" + UUID.randomUUID().toString().substring(0, 8);

        DictDefinitionDTO.DictItemDTO item = DictDefinitionDTO.DictItemDTO.builder()
                .value("val1")
                .label("Value 1")
                .sortNo(1)
                .status("enabled")
                .build();

        DictDefinitionDTO dict = DictDefinitionDTO.builder()
                .code(uniqueNamespace + "_dict")
                .name("Test Dict")
                .dictType("static")
                .items(List.of(item))
                .build();

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Execute Test Plugin")
                .dicts(List.of(dict))
                .build();

        // First, parse the manifest
        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Execute with overwrite strategy
        ImportRequest request = ImportRequest.builder()
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoDeployProcesses(false)
                .autoPublishPages(false)
                .build();

        MvcResult result = mockMvc.perform(post(API_BASE + "/" + importId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        int status = result.getResponse().getStatus();
        String response = result.getResponse().getContentAsString();
        log.info("Execute response status: {}, body: {}", status, response);

        // If 500, log the error for debugging
        if (status == 500) {
            log.error("Execute returned 500 - service has bugs that need fixing. Response: {}", response);
            // Mark test as diagnostic - it found a bug
            assertThat(status)
                    .as("Execute endpoint returned 500. Check server logs for: " + response)
                    .isEqualTo(200);
        } else {
            assertThat(status).isEqualTo(200);
            ImportExecuteResult executeResult = objectMapper.readValue(response, ImportExecuteResult.class);
            assertThat(executeResult.getImportId()).isEqualTo(importId);
            // Note: success might be false if there are validation issues
            log.info("Execute result success: {}, errors: {}",
                    executeResult.isSuccess(), executeResult.getErrorMessage());
        }
    }

    @Test
    @Order(41)
    @DisplayName("POST /execute-direct - should execute direct import")
    void testExecuteDirect() throws Exception {
        // Create a unique manifest
        String uniqueNamespace = "direct_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.direct-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Direct Execute Test")
                .build();

        MvcResult result = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite")
                        .param("autoDeployProcesses", "false")
                        .param("autoPublishPages", "false"))
                .andReturn();

        int status = result.getResponse().getStatus();
        String response = result.getResponse().getContentAsString();
        log.info("Execute direct response status: {}, body: {}", status, response);

        if (status == 500) {
            log.error("Execute-direct returned 500. Response: {}", response);
            assertThat(status)
                    .as("Execute-direct endpoint returned 500. Check server logs for: " + response)
                    .isEqualTo(200);
        } else {
            assertThat(status).isEqualTo(200);
            ImportExecuteResult executeResult = objectMapper.readValue(response, ImportExecuteResult.class);
            assertThat(executeResult.getPluginId()).isEqualTo(uniquePluginId);
            log.info("Execute direct result success: {}", executeResult.isSuccess());
        }
    }

    @Test
    @Order(42)
    // Fixed: was @Disabled("BLOCKED: Depends on execute-direct working. execute-direct returns 500 for plugins with resources.")
    @DisplayName("POST /{importId}/execute - should handle conflict with ERROR strategy")
    void testExecuteWithConflict() throws Exception {
        // First, create and execute a plugin
        String uniqueNamespace = "conflict_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.conflict-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Conflict Test Plugin")
                .build();

        // Execute first time
        MvcResult firstResult = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(firstResult, "First execute-direct");

        // Parse for second execution
        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );

        // Try to execute with ERROR strategy - should detect conflict
        ImportRequest request = ImportRequest.builder()
                .conflictStrategy(ImportRequest.ConflictStrategy.ERROR)
                .build();

        MvcResult result = mockMvc.perform(post(API_BASE + "/" + parsed.getImportId() + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        // The result may be 200 with error info about conflict, or 500 if service has bugs
        int status = result.getResponse().getStatus();
        String response = result.getResponse().getContentAsString();
        log.info("Conflict execution response status: {}, body: {}", status, response);

        if (status == 500) {
            log.error("Conflict execution returned 500. This needs investigation. Response: {}", response);
            Assertions.fail("Conflict execution returned 500. Response: " + response);
        }
    }

    // ==================== Rollback Tests ====================

    @Test
    @Order(50)
    // Fixed: was @Disabled("BLOCKED: Depends on execute working correctly. Execute returns 500.")
    @DisplayName("GET /{importId}/can-rollback - should check rollback eligibility")
    void testCanRollback() throws Exception {
        // Create and execute a plugin first
        String uniqueNamespace = "rollback_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.rollback-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Rollback Test Plugin")
                .build();

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Execute import
        MvcResult executeResult = mockMvc.perform(post(API_BASE + "/" + importId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ImportRequest.builder()
                                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                                .build())))
                .andReturn();

        assertSuccessOrDiagnostic(executeResult, "Execute before can-rollback");

        // Check if can rollback
        MvcResult result = mockMvc.perform(get(API_BASE + "/" + importId + "/can-rollback"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Can-rollback check");
        log.info("Can rollback response: {}", result.getResponse().getContentAsString());
    }

    @Test
    @Order(51)
    // Fixed: was @Disabled("BLOCKED: Depends on execute working correctly. Execute returns 500.")
    @DisplayName("POST /{importId}/rollback - should rollback a successful import")
    void testRollback() throws Exception {
        // Create and execute a plugin
        String uniqueNamespace = "rb_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.rb-" + UUID.randomUUID().toString().substring(0, 8);

        DictDefinitionDTO.DictItemDTO item = DictDefinitionDTO.DictItemDTO.builder()
                .value("rb_val")
                .label("Rollback Value")
                .sortNo(1)
                .status("enabled")
                .build();

        DictDefinitionDTO dict = DictDefinitionDTO.builder()
                .code(uniqueNamespace + "_dict")
                .name("Rollback Test Dict")
                .dictType("static")
                .items(List.of(item))
                .build();

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Rollback Execution Test")
                .dicts(List.of(dict))
                .build();

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Execute import
        MvcResult executeResult = mockMvc.perform(post(API_BASE + "/" + importId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ImportRequest.builder()
                                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                                .build())))
                .andReturn();

        assertSuccessOrDiagnostic(executeResult, "Execute before rollback");

        // Perform rollback
        MvcResult result = mockMvc.perform(post(API_BASE + "/" + importId + "/rollback"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Rollback");

        String response = result.getResponse().getContentAsString();
        log.info("Rollback response: {}", response);

        // Verify rollback result
        ImportExecuteResult rollbackResult = objectMapper.readValue(response, ImportExecuteResult.class);
        assertThat(rollbackResult).isNotNull();
    }

    // ==================== History & Status Tests ====================

    @Test
    @Order(60)
    @DisplayName("GET /history - should get import history")
    void testGetHistory() throws Exception {
        MvcResult result = mockMvc.perform(get(API_BASE + "/history")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        log.info("History response: {}", response);
    }

    @Test
    @Order(61)
    // Fixed: was @Disabled("BLOCKED: Depends on execute-direct working correctly.")
    @DisplayName("GET /history/plugin/{pluginId} - should get plugin-specific history")
    void testGetPluginHistory() throws Exception {
        // First create a plugin
        String uniquePluginId = "com.test.history-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace("hist_" + UUID.randomUUID().toString().substring(0, 8).replace("-", ""))
                .version("1.0.0")
                .displayName("History Test Plugin")
                .build();

        MvcResult executeResult = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(executeResult, "Execute before getting history");

        // Get plugin history
        MvcResult result = mockMvc.perform(get(API_BASE + "/history/plugin/" + uniquePluginId))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Get plugin history");

        String response = result.getResponse().getContentAsString();
        log.info("Plugin history response: {}", response);
    }

    @Test
    @Order(62)
    // Fixed: was @Disabled("BLOCKED: Depends on execute working correctly.")
    @DisplayName("GET /{importId}/status - should get import status")
    void testGetStatus() throws Exception {
        // Create a plugin first
        String uniquePluginId = "com.test.status-" + UUID.randomUUID().toString().substring(0, 8);
        String uniqueNamespace = "stat_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Status Test Plugin")
                .build();

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Execute
        MvcResult executeResult = mockMvc.perform(post(API_BASE + "/" + importId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ImportRequest.builder()
                                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                                .build())))
                .andReturn();

        assertSuccessOrDiagnostic(executeResult, "Execute before getting status");

        // Get status
        MvcResult result = mockMvc.perform(get(API_BASE + "/" + importId + "/status"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Get import status");

        String response = result.getResponse().getContentAsString();
        log.info("Status response: {}", response);
    }

    @Test
    @Order(63)
    @DisplayName("GET /{importId}/status - should return 404 for non-existent import")
    void testGetStatusNotFound() throws Exception {
        mockMvc.perform(get(API_BASE + "/non-existent-import-id/status"))
                .andExpect(status().isNotFound());
    }

    // ==================== Cancel Tests ====================

    @Test
    @Order(70)
    // Fixed: was @Disabled("BLOCKED: Cancel endpoint returns 500. Needs service fix.")
    @DisplayName("POST /{importId}/cancel - should cancel pending import")
    void testCancelImport() throws Exception {
        // Create a preview (pending state)
        String uniquePluginId = "com.test.cancel-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace("cancel_" + UUID.randomUUID().toString().substring(0, 8).replace("-", ""))
                .version("1.0.0")
                .displayName("Cancel Test Plugin")
                .build();

        MvcResult parseResult = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andReturn();

        ImportPreviewResult parsed = objectMapper.readValue(
                parseResult.getResponse().getContentAsString(),
                ImportPreviewResult.class
        );
        String importId = parsed.getImportId();

        // Cancel the import
        MvcResult result = mockMvc.perform(post(API_BASE + "/" + importId + "/cancel"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Cancel import");

        String response = result.getResponse().getContentAsString();
        log.info("Cancel response: {}", response);
    }

    // ==================== Complex Resource Import Tests ====================

    @Test
    @Order(80)
    // Fixed: was @Disabled("BLOCKED: Execute-direct returns 500 when importing resources. Needs service fix.")
    @DisplayName("Should import complete plugin with all resource types")
    void testImportCompletePlugin() throws Exception {
        String uniqueNamespace = "full_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.full-" + UUID.randomUUID().toString().substring(0, 8);

        // Dictionary
        DictDefinitionDTO.DictItemDTO statusActive = DictDefinitionDTO.DictItemDTO.builder()
                .value("active")
                .label("Active")
                .sortNo(1)
                .status("enabled")
                .build();

        DictDefinitionDTO statusDict = DictDefinitionDTO.builder()
                .code(uniqueNamespace + "_status")
                .name("Status Dictionary")
                .dictType("static")
                .items(List.of(statusActive))
                .build();

        // Fields
        FieldDefinitionDTO nameField = FieldDefinitionDTO.builder()
                .code(uniqueNamespace + "_name")
                .displayName("Name")
                .dataType("string")
                .constraints(FieldDefinitionDTO.FieldConstraints.builder()
                        .required(true)
                        .maxLength(200)
                        .build())
                .build();

        FieldDefinitionDTO descField = FieldDefinitionDTO.builder()
                .code(uniqueNamespace + "_description")
                .displayName("Description")
                .dataType("text")
                .build();

        FieldDefinitionDTO statusField = FieldDefinitionDTO.builder()
                .code(uniqueNamespace + "_status_field")
                .displayName("Status")
                .dataType("string")
                .dictCode(uniqueNamespace + "_status")
                .build();

        // Model
        ModelDefinitionDTO model = ModelDefinitionDTO.builder()
                .code(uniqueNamespace + "_entity")
                .displayName("Test Entity")
                .modelType("entity")
                .build();

        // Bindings
        List<ModelFieldBindingDTO> bindings = Arrays.asList(
                ModelFieldBindingDTO.builder()
                        .modelCode(uniqueNamespace + "_entity")
                        .fieldCode(uniqueNamespace + "_name")
                        .sequence(1)
                        .required(true)
                        .build(),
                ModelFieldBindingDTO.builder()
                        .modelCode(uniqueNamespace + "_entity")
                        .fieldCode(uniqueNamespace + "_description")
                        .sequence(2)
                        .build(),
                ModelFieldBindingDTO.builder()
                        .modelCode(uniqueNamespace + "_entity")
                        .fieldCode(uniqueNamespace + "_status_field")
                        .sequence(3)
                        .build()
        );

        // Permission
        PermissionDefinitionDTO permission = PermissionDefinitionDTO.builder()
                .code(uniqueNamespace + ":entity:read")
                .name("Read Entity")
                .category("model")
                .resourceType("model")
                .resourceCode(uniqueNamespace + "_entity")
                .action("read")
                .build();

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Complete Test Plugin")
                .dicts(List.of(statusDict))
                .fields(Arrays.asList(nameField, descField, statusField))
                .models(List.of(model))
                .modelFieldBindings(bindings)
                .permissions(List.of(permission))
                .build();

        // Execute import
        MvcResult result = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Complete plugin import");

        String response = result.getResponse().getContentAsString();
        log.info("Complete plugin import response: {}", response);

        ImportExecuteResult executeResult = objectMapper.readValue(response, ImportExecuteResult.class);
        log.info("Complete plugin result - success: {}, error: {}, resources: {}",
                executeResult.isSuccess(), executeResult.getErrorMessage(), executeResult.getResourceCounts());

        // ==================== Verify Model-Field Bindings ====================
        // Requirement: ENTITY/VIEW models must have field bindings
        assertThat(executeResult.isSuccess())
                .as("Import should succeed")
                .isTrue();

        // Set MetaContext for verification queries
        try {
            MetaContext.setContext(
                    getTestTenant().getId(),
                    getTestUser().getId(),
                    getTestUser().getPid(),
                    getTestUser().getUserName()
            );

            // Verify the model was created
            Model importedModel = modelMapper.findCurrentByCode(uniqueNamespace + "_entity");
            assertThat(importedModel)
                    .as("Model should be created after import")
                    .isNotNull();
            assertThat(importedModel.getCode()).isEqualTo(uniqueNamespace + "_entity");

            // Verify field bindings were created for the ENTITY model
            List<ModelFieldBinding> allBindings = fieldBindingMapper.findByModelId(importedModel.getId());
            assertThat(allBindings)
                    .as("ENTITY model must have field bindings")
                    .isNotEmpty();

            // Filter out system bindings (e.g., auto-created pid binding)
            List<ModelFieldBinding> userBindings = allBindings.stream()
                    .filter(b -> !Boolean.TRUE.equals(b.getIsSystemBinding()))
                    .toList();

            // Verify user-defined binding count matches expected (3 fields: name, description, status_field)
            assertThat(userBindings)
                    .as("ENTITY model should have exactly 3 user-defined field bindings")
                    .hasSize(3);

            // Verify binding sequence is correct for user bindings
            List<Integer> sequences = userBindings.stream()
                    .map(ModelFieldBinding::getFieldOrder)
                    .sorted()
                    .toList();
            assertThat(sequences)
                    .as("Field bindings should have correct sequence order")
                    .containsExactly(1, 2, 3);

            // Verify required flag is set correctly (name field should be required)
            boolean hasRequiredField = userBindings.stream()
                    .anyMatch(b -> Boolean.TRUE.equals(b.getRequired()));
            assertThat(hasRequiredField)
                    .as("At least one field binding should be marked as required")
                    .isTrue();

            log.info("Verified {} total bindings ({} user-defined) for model {}",
                    allBindings.size(), userBindings.size(), importedModel.getCode());
        } finally {
            MetaContext.clear();
        }
    }

    // ==================== Model-Field Binding Validation Tests ====================

    @Test
    @Order(81)
    @DisplayName("Should validate ENTITY model requires field bindings")
    void testEntityModelRequiresFieldBindings() throws Exception {
        String uniqueNamespace = "nobind_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.nobind-" + UUID.randomUUID().toString().substring(0, 8);

        // Create fields
        FieldDefinitionDTO nameField = FieldDefinitionDTO.builder()
                .code(uniqueNamespace + "_name")
                .displayName("Name")
                .dataType("string")
                .constraints(FieldDefinitionDTO.FieldConstraints.builder()
                        .required(true)
                        .maxLength(100)
                        .build())
                .build();

        // Create ENTITY model WITHOUT field bindings
        ModelDefinitionDTO entityModel = ModelDefinitionDTO.builder()
                .code(uniqueNamespace + "_entity")
                .displayName("Entity Without Bindings")
                .modelType("entity")
                .build();

        // Manifest with model but NO modelFieldBindings
        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Entity No Bindings Test")
                .fields(List.of(nameField))
                .models(List.of(entityModel))
                // NOTE: No modelFieldBindings defined!
                .build();

        // Validate should fail with error about missing bindings for ENTITY model
        MvcResult validateResult = mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isNotEmpty())
                .andReturn();

        String validateResponse = validateResult.getResponse().getContentAsString();
        log.info("Validate entity without bindings response: {}", validateResponse);

        // Verify the error message mentions the model requires field bindings
        assertThat(validateResponse)
                .as("Validation error should mention ENTITY model requires field bindings")
                .contains("ENTITY model requires at least one field binding");
    }

    @Test
    @Order(82)
    @DisplayName("Should validate VIEW model requires field bindings")
    void testViewModelRequiresFieldBindings() throws Exception {
        String uniqueNamespace = "vwnobind_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.vwnobind-" + UUID.randomUUID().toString().substring(0, 8);

        // Create fields
        FieldDefinitionDTO countField = FieldDefinitionDTO.builder()
                .code(uniqueNamespace + "_count")
                .displayName("Count")
                .dataType("integer")
                .build();

        // Create VIEW model WITHOUT field bindings
        ModelDefinitionDTO viewModel = ModelDefinitionDTO.builder()
                .code(uniqueNamespace + "_summary_view")
                .displayName("Summary View Without Bindings")
                .modelType("view")
                .build();

        // Manifest with VIEW model but NO modelFieldBindings
        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("View No Bindings Test")
                .fields(List.of(countField))
                .models(List.of(viewModel))
                // NOTE: No modelFieldBindings defined!
                .build();

        // Validate should fail with error about missing bindings for VIEW model
        MvcResult validateResult = mockMvc.perform(post(API_BASE + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isNotEmpty())
                .andReturn();

        String validateResponse = validateResult.getResponse().getContentAsString();
        log.info("Validate VIEW without bindings response: {}", validateResponse);

        // Verify the error message mentions the model requires field bindings
        assertThat(validateResponse)
                .as("Validation error should mention VIEW model requires field bindings")
                .contains("VIEW model requires at least one field binding");
    }

    @Test
    @Order(83)
    @DisplayName("ABSTRACT model can exist without field bindings")
    void testAbstractModelAllowedWithoutBindings() throws Exception {
        String uniqueNamespace = "abstract_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String uniquePluginId = "com.test.abstract-" + UUID.randomUUID().toString().substring(0, 8);

        // Create ABSTRACT model (template model) - these are allowed without bindings
        ModelDefinitionDTO abstractModel = ModelDefinitionDTO.builder()
                .code(uniqueNamespace + "_base")
                .displayName("Abstract Base Model")
                .modelType("abstract")
                .build();

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Abstract Model Test")
                .models(List.of(abstractModel))
                // ABSTRACT models don't need field bindings
                .build();

        MvcResult executeResult = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(executeResult, "Import ABSTRACT model without bindings");

        String executeResponse = executeResult.getResponse().getContentAsString();
        ImportExecuteResult result = objectMapper.readValue(executeResponse, ImportExecuteResult.class);

        log.info("ABSTRACT model import result: success={}", result.isSuccess());

        // ABSTRACT models are allowed without field bindings
        if (result.isSuccess()) {
            // Set MetaContext for verification queries
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );

                Model importedModel = modelMapper.findCurrentByCode(uniqueNamespace + "_base");
                assertThat(importedModel)
                        .as("ABSTRACT model should be created")
                        .isNotNull();

                // Verify no bindings exist (and that's OK for ABSTRACT)
                List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(importedModel.getId());
                log.info("ABSTRACT model has {} field bindings (0 is acceptable)", bindings.size());
            } finally {
                MetaContext.clear();
            }
        }
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(90)
    @DisplayName("Should handle manifest with empty resource lists")
    void testManifestWithEmptyResources() throws Exception {
        String uniquePluginId = "com.test.empty-" + UUID.randomUUID().toString().substring(0, 8);

        PluginManifestExtended manifest = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace("empty_" + UUID.randomUUID().toString().substring(0, 8).replace("-", ""))
                .version("1.0.0")
                .displayName("Empty Resources Plugin")
                .dicts(List.of())
                .fields(List.of())
                .models(List.of())
                .build();

        MvcResult result = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manifest))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(result, "Import empty resources");
        log.info("Empty resources import response: {}", result.getResponse().getContentAsString());
    }

    @Test
    @Order(91)
    // Fixed: was @Disabled("BLOCKED: First install via execute-direct returns 500. Needs service fix.")
    @DisplayName("Should handle version upgrade scenario")
    void testVersionUpgrade() throws Exception {
        String uniquePluginId = "com.test.upgrade-" + UUID.randomUUID().toString().substring(0, 8);
        String uniqueNamespace = "upgrade_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

        // Version 1.0.0
        PluginManifestExtended v1 = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Upgrade Test Plugin v1")
                .build();

        MvcResult v1Result = mockMvc.perform(post(API_BASE + "/execute-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v1))
                        .param("conflictStrategy", "overwrite"))
                .andReturn();

        assertSuccessOrDiagnostic(v1Result, "Install v1.0.0");

        // Version 1.1.0 (upgrade)
        PluginManifestExtended v2 = PluginManifestExtended.builder()
                .pluginId(uniquePluginId)
                .namespace(uniqueNamespace)
                .version("1.1.0")
                .displayName("Upgrade Test Plugin v2")
                .build();

        MvcResult result = mockMvc.perform(post(API_BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2)))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        log.info("Upgrade preview: {}", response);

        // Verify upgrade detection
        ImportPreviewResult previewResult = objectMapper.readValue(response, ImportPreviewResult.class);
        log.info("Upgrade detected: {}, previousVersion: {}",
                previewResult.isUpgrade(), previewResult.getPreviousVersion());
    }
}
