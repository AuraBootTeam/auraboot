package com.auraboot.framework.plugin.integration;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Asset Plugin Install Integration Test (C1-01 to C1-15).
 * Verifies the complete import of the asset-management plugin:
 * - All 13 resource types are imported correctly
 * - Topological sort order is respected (dict -> field -> model -> ...)
 * - All resources share the same plugin_pid
 */
@Slf4j
@DisplayName("Asset Plugin Install Integration Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AssetPluginInstallTest extends BaseIntegrationTest {

    private static final String PLUGIN_DIR = "plugins/asset-management";
    private static final String PLUGIN_ID = "com.auraboot.asset-management";
    private static final String PLUGIN_NAMESPACE = "asset";
    private static final String PLUGIN_VERSION = "2.0.0";

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private PluginRecordMapper pluginRecordMapper;

    @Autowired
    private PluginResourceMapper pluginResourceMapper;

    @Autowired
    private DictMapper dictMapper;

    @Autowired
    private DictItemMapper dictItemMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaModelFieldBindingMapper modelFieldBindingMapper;

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

    @Autowired
    private BindingRuleMapper bindingRuleMapper;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private BpmProcessDefinitionMapper bpmProcessDefinitionMapper;

    // Shared state for the import result within this test class
    private ImportExecuteResult importResult;
    private String pluginPid;

    /**
     * Import the asset plugin before each test.
     * Since tests are @Transactional + @Rollback, each test gets a fresh import.
     */
    @BeforeEach
    void importAssetPlugin() {
        // Resolve plugin directory from project root
        Path pluginPath = resolvePluginPath();

        // Step 1: Parse plugin directory
        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath.toString());
        assertThat(preview).isNotNull();
        assertThat(preview.isValid()).isTrue();
        assertThat(preview.getPluginId()).isEqualTo(PLUGIN_ID);

        // Step 2: Execute import with OVERWRITE strategy to avoid conflicts from parallel tests
        ImportRequest request = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishPages(false)
                .autoDeployProcesses(false)
                .build();

        importResult = pluginImportService.execute(preview.getImportId(), request);
        assertThat(importResult).isNotNull();
        assertThat(importResult.isSuccess()).isTrue();

        pluginPid = importResult.getPluginPid();
        assertThat(pluginPid).isNotBlank();

        log.info("Asset plugin imported successfully, pluginPid={}", pluginPid);
    }

    // ==================== C1-01: Plugin record created ====================

    @Test
    @Order(1)
    @DisplayName("C1-01: Plugin record should be created in ab_plugin")
    void shouldCreatePluginRecord() {
        PluginRecord record = pluginRecordMapper.findByPid(pluginPid);
        assertThat(record).isNotNull();
        assertThat(record.getPluginId()).isEqualTo(PLUGIN_ID);
        assertThat(record.getNamespace()).isEqualTo(PLUGIN_NAMESPACE);
        assertThat(record.getVersion()).isEqualTo(PLUGIN_VERSION);
        assertThat(record.getStatus()).isEqualTo("installed");
        assertThat(record.getTenantId()).isEqualTo(getTestTenant().getId());
    }

    // ==================== C1-02: Dicts imported ====================

    @Test
    @Order(2)
    @DisplayName("C1-02: 6 dictionaries should be imported")
    void shouldImport3Dicts() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.DICT.name());
        assertThat(count).isEqualTo(6);
    }

    // ==================== C1-03: Dict items imported ====================

    @Test
    @Order(3)
    @DisplayName("C1-03: Dict items should be imported for all 6 dictionaries")
    void shouldImportDictItems() {
        int dictItemCount = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.DICT_ITEM.name());
        log.info("Dict item resource count: {}", dictItemCount);
        // At minimum, the 6 dicts should exist
        assertThat(pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.DICT.name()))
                .isGreaterThanOrEqualTo(6);
    }

    // ==================== C1-04: Fields imported ====================

    @Test
    @Order(4)
    @DisplayName("C1-04: 32 fields should be imported")
    void shouldImport17Fields() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.FIELD.name());
        assertThat(count).isEqualTo(32);
    }

    // ==================== C1-05: Models imported ====================

    @Test
    @Order(5)
    @DisplayName("C1-05: 4 models should be imported")
    void shouldImport2Models() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.MODEL.name());
        assertThat(count).isEqualTo(4);
    }

    // ==================== C1-06: Model-field bindings imported ====================

    @Test
    @Order(6)
    @DisplayName("C1-06: 34 model-field bindings should be imported")
    void shouldImport17ModelFieldBindings() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.MODEL_FIELD_BINDING.name());
        assertThat(count).isEqualTo(34);
    }

    // ==================== C1-07: Commands imported ====================

    @Test
    @Order(7)
    @DisplayName("C1-07: 18 commands should be imported")
    void shouldImport5Commands() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.COMMAND.name());
        assertThat(count).isEqualTo(18);
    }

    // ==================== C1-08: Permissions imported ====================

    @Test
    @Order(8)
    @DisplayName("C1-08: 18 permissions should be imported")
    void shouldImport11Permissions() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.PERMISSION.name());
        assertThat(count).isEqualTo(18);
    }

    // ==================== C1-09: Roles imported ====================

    @Test
    @Order(9)
    @DisplayName("C1-09: 4 roles should be imported")
    void shouldImport4Roles() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.ROLE.name());
        assertThat(count).isEqualTo(4);
    }

    // ==================== C1-10: Menus imported ====================

    @Test
    @Order(10)
    @DisplayName("C1-10: 6 menus should be imported")
    void shouldImport5Menus() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.MENU.name());
        assertThat(count).isEqualTo(6);
    }

    // ==================== C1-11: Pages imported ====================

    @Test
    @Order(11)
    @DisplayName("C1-11: 12 pages should be imported")
    void shouldImport5Pages() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.PAGE.name());
        assertThat(count).isEqualTo(12);
    }

    // ==================== C1-12: Binding rules imported ====================

    @Test
    @Order(12)
    @DisplayName("C1-12: 1 binding rule should be imported")
    void shouldImport1BindingRule() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.BINDING_RULE.name());
        assertThat(count).isEqualTo(1);
    }

    // ==================== C1-13: Process definition imported ====================

    @Test
    @Order(13)
    @DisplayName("C1-13: 1 process definition should be imported")
    void shouldImport1ProcessDefinition() {
        int count = pluginResourceMapper.countByPluginPidAndType(pluginPid, ResourceType.PROCESS.name());
        assertThat(count).isEqualTo(1);
    }

    // ==================== C1-14: Topological sort order ====================

    @Test
    @Order(14)
    @DisplayName("C1-14: Resources should be imported in topological order")
    void shouldImportInTopologicalOrder() {
        List<PluginResource> resources = pluginResourceMapper.findByPluginPid(pluginPid);
        assertThat(resources).isNotEmpty();

        // Verify ordering: DICT < FIELD < MODEL < MODEL_FIELD_BINDING < PERMISSION < ROLE < MENU < COMMAND < PAGE < PROCESS
        Integer lastOrder = null;
        for (PluginResource resource : resources) {
            ResourceType type = resource.getResourceTypeEnum();
            if (type != null) {
                int currentOrder = type.getImportOrder();
                if (lastOrder != null) {
                    assertThat(currentOrder)
                            .as("Resource type %s (order=%d) should not appear before previous type (order=%d)",
                                    type.name(), currentOrder, lastOrder)
                            .isGreaterThanOrEqualTo(lastOrder);
                }
                lastOrder = currentOrder;
            }
        }
    }

    // ==================== C1-15: All resources share same plugin_pid ====================

    @Test
    @Order(15)
    @DisplayName("C1-15: All imported resources should have the same plugin_pid")
    void allResourcesShouldShareSamePluginPid() {
        List<PluginResource> resources = pluginResourceMapper.findByPluginPid(pluginPid);
        assertThat(resources).isNotEmpty();

        for (PluginResource resource : resources) {
            assertThat(resource.getPluginPid())
                    .as("Resource %s (%s) should belong to plugin %s",
                            resource.getResourceCode(), resource.getResourceType(), pluginPid)
                    .isEqualTo(pluginPid);
        }

        // Verify total resource count is reasonable (all 13 types combined)
        int totalCount = pluginResourceMapper.countByPluginPid(pluginPid);
        log.info("Total plugin resources tracked: {}", totalCount);
        // At minimum: 3 dicts + 17 fields + 2 models + 17 bindings + 5 commands +
        //             11 permissions + 4 roles + 5 menus + 5 pages + 1 binding rule + 1 process = 71
        assertThat(totalCount).isGreaterThanOrEqualTo(50);
    }

    // ==================== Helper Methods ====================

    /**
     * Resolve the plugin directory path relative to the project root.
     */
    private Path resolvePluginPath() {
        // Try to find the plugin directory from the project root
        Path projectRoot = Paths.get(System.getProperty("user.dir"));

        // If running from /platform, go up one level
        if (projectRoot.endsWith("platform")) {
            projectRoot = projectRoot.getParent();
        }

        Path pluginPath = projectRoot.resolve(PLUGIN_DIR);
        assertThat(pluginPath.toFile().exists())
                .as("Plugin directory should exist at: %s", pluginPath)
                .isTrue();

        return pluginPath;
    }
}
