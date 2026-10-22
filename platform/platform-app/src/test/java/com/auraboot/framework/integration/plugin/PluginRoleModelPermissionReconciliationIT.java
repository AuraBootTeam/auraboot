package com.auraboot.framework.integration.plugin;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A plugin role declaring generated model actions must end up holding them after a
 * SINGLE plugin import.
 *
 * Baseline CRUD actions (model.&lt;code&gt;.create/update/...) are created at model-create
 * time, so the ROLE import stage can resolve them. Command-verb actions
 * (model.&lt;code&gt;.&lt;verb&gt;, e.g. a state_transition command) are only derived during
 * post-import processing, AFTER the ROLE stage — the binding was silently skipped
 * ("Permission not found for role binding") and plugin business roles ended up without
 * the verbs their own roles.json declares. The post-import reconciliation must bind
 * every declared code once it exists.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles({"integration-test", "test"})
@DisplayName("Plugin role model-action permissions resolve on first import")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginRoleModelPermissionReconciliationIT extends BaseIntegrationTest {

    private static final String MODEL_CODE = "pfx_widget";
    private static final String ROLE_CODE = "pfx_clerk";
    private static final String PLUGIN_DIR_NAME = "pfx-plugin";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PluginImportService pluginImportService;

    private MockMvc mockMvc;
    private Long tenantId;
    private Long userId;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        MvcResult seed = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        // The seed enforces showcase-plugin preconditions irrelevant to this IT; all we
        // need is the tenant/user pair it guarantees.
        tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_tenant WHERE name = 'e2e_test' ORDER BY id DESC LIMIT 1", Long.class);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_user WHERE email = 'e2e@test.local' ORDER BY id DESC LIMIT 1", Long.class);
        Assertions.assertTrue(seed.getResponse().getStatus() == 200 || tenantId != null,
                "seed must succeed or an e2e_test tenant must already exist");
    }

    @Test
    @Order(1)
    @DisplayName("single import binds baseline CRUD and command-verb actions to the plugin role")
    void singleImportBindsAllDeclaredModelActions() throws Exception {
        importPlugin(PLUGIN_DIR_NAME);

        List<String> bound = rolePermissionCodes();
        assertThat(bound)
                .as("plugin role %s must hold baseline model actions after first import", ROLE_CODE)
                .contains(
                        "model." + MODEL_CODE + ".read",
                        "model." + MODEL_CODE + ".create",
                        "model." + MODEL_CODE + ".update",
                        "model." + MODEL_CODE + ".delete",
                        "model." + MODEL_CODE + ".export",
                        // state_transition command pfx:archive_widget derives this verb
                        // during post-import processing, after the ROLE stage ran.
                        "model." + MODEL_CODE + ".archive");
    }

    @Test
    @Order(2)
    @DisplayName("re-import keeps the binding set stable (idempotent reconciliation)")
    void reImportKeepsBindingsStable() throws Exception {
        importPlugin(PLUGIN_DIR_NAME);
        List<String> afterFirst = rolePermissionCodes();

        importPlugin(PLUGIN_DIR_NAME);
        List<String> afterSecond = rolePermissionCodes();

        assertThat(afterSecond).containsExactlyInAnyOrderElementsOf(afterFirst);
    }

    private List<String> rolePermissionCodes() {
        return jdbcTemplate.queryForList("""
                SELECT p.code
                FROM ab_role_permission rp
                JOIN ab_role r ON r.id = rp.role_id
                JOIN ab_permission p ON p.id = rp.permission_id
                WHERE r.code = ? AND rp.deleted_flag = false
                """, String.class, ROLE_CODE);
    }

    /** Materialize a minimal plugin whose role declares baseline + verb model actions. */
    private void importPlugin(String dirName) throws Exception {
        Path pluginDir = Path.of(System.getProperty("java.io.tmpdir"), "pfx-it", dirName);
        Files.createDirectories(pluginDir.resolve("config/commands"));

        write(pluginDir, "plugin.json", """
                {
                  "pluginId": "com.auraboot.pfx",
                  "namespace": "pfxw",
                  "version": "1.0.0",
                  "dslVersion": 1,
                  "pluginType": "config",
                  "displayName": "Permission Reconciliation Fixture",
                  "minPlatformVersion": "1.0.0",
                  "dependencies": [],
                  "provides": [],
                  "requires": [],
                  "resourceDirs": {
                    "models": "config/models.json",
                    "fields": "config/fields",
                    "modelFieldBindings": "config/bindings",
                    "commands": "config/commands",
                    "permissions": "config/permissions.json",
                    "roles": "config/roles.json"
                  }
                }
                """);

        write(pluginDir, "config/models.json", """
                [
                  {
                    "code": "%s",
                    "displayName:zh-CN": "组件",
                    "displayName:en": "Widget",
                    "modelType": "entity",
                    "modelCategory": "entity"
                  }
                ]
                """.formatted(MODEL_CODE));

        write(pluginDir, "config/fields/%s.json".formatted(MODEL_CODE), """
                [
                  {
                    "code": "%s_name",
                    "displayName:en": "Widget name",
                    "dataType": "string",
                    "constraints": {"required": true, "maxLength": 100}
                  }
                ]
                """.formatted(MODEL_CODE));

        write(pluginDir, "config/bindings/%s.json".formatted(MODEL_CODE), """
                [
                  {
                    "modelCode": "%s",
                    "fieldCode": "%s_name",
                    "sequence": 1,
                    "required": true,
                    "visible": true,
                    "editable": true
                  }
                ]
                """.formatted(MODEL_CODE, MODEL_CODE));

        write(pluginDir, "config/commands/commands.json", """
                [
                  {"code": "pfxw:create_%s", "displayName:en": "Create", "type": "create", "modelCode": "%s"},
                  {"code": "pfxw:archive_%s", "displayName:en": "Archive", "type": "state_transition", "modelCode": "%s"}
                ]
                """.formatted(MODEL_CODE, MODEL_CODE, MODEL_CODE, MODEL_CODE));

        write(pluginDir, "config/permissions.json", """
                [
                  {
                    "code": "model.%s.read",
                    "name:en": "Read widgets",
                    "resourceType": "model",
                    "resourceCode": "%s",
                    "action": "read",
                    "module": "pfxw"
                  }
                ]
                """.formatted(MODEL_CODE, MODEL_CODE));

        write(pluginDir, "config/roles.json", """
                [
                  {
                    "code": "%s",
                    "name:en": "Widget Clerk",
                    "description": "Runs the full widget lifecycle",
                    "permissions": [
                      "model.%s.read",
                      "model.%s.create",
                      "model.%s.update",
                      "model.%s.delete",
                      "model.%s.export",
                      "model.%s.archive"
                    ]
                  }
                ]
                """.formatted(ROLE_CODE, MODEL_CODE, MODEL_CODE, MODEL_CODE, MODEL_CODE, MODEL_CODE, MODEL_CODE));

        MetaContext.setContext(tenantId, userId, "e2e-test-user", "e2e@test.local");
        try {
            ImportPreviewResult preview = pluginImportService.parseDirectory(pluginDir.toString());
            Assertions.assertTrue(preview.isValid(),
                    "synthetic plugin parse must be valid: " + preview.getErrors());
            ImportRequest request = ImportRequest.builder()
                    .importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                    .autoPublishModels(true)
                    .autoPublishFields(true)
                    .autoPublishCommands(true)
                    .autoPublishPages(true)
                    .autoDeployProcesses(true)
                    .build();
            var result = pluginImportService.execute(preview.getImportId(), request);
            Assertions.assertTrue(result.isSuccess(),
                    "synthetic plugin import must succeed: " + result.getErrorMessage());
        } finally {
            MetaContext.clear();
        }
    }

    private void write(Path pluginDir, String relative, String content) throws Exception {
        Path target = pluginDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
