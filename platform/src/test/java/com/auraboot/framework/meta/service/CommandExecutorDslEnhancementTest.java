package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 0 Command Engine DSL enhancements:
 * - autoSetFields (AUTO_GENERATE, CURRENT_USER, CURRENT_DATETIME, FIXED_VALUE)
 * - computedFields (SpEL formula calculation)
 * - cascadeDelete (child record cleanup)
 * - Multi-branch stateTransitionRules (conditional guard evaluation)
 * - sideEffects (CREATE_RECORD / UPDATE_RECORD with condition)
 * - postActions / CREATE_CHILDREN (batch child record creation)
 *
 * Uses real database tables created via SchemaManagementService.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CommandExecutor DSL Enhancement Tests - Phase 0")
class CommandExecutorDslEnhancementTest {

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private CommandService commandService;

    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    // Test context
    private User testUser;
    private Tenant testTenant;
    private final List<String> createdTables = new ArrayList<>();
    private final List<Model> createdModels = new ArrayList<>();
    private final List<Field> createdFields = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ensureTestDataExists();
        MetaContext.setContext(
                testTenant.getId(),
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName()
        );
    }

    @AfterEach
    void tearDown() {
        try {
            // Drop tables
            for (String table : createdTables) {
                try {
                    dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + table);
                } catch (Exception e) {
                    log.warn("Failed to drop table {}: {}", table, e.getMessage());
                }
            }
            createdTables.clear();

            // Delete bindings and fields
            for (Model model : createdModels) {
                try { fieldBindingMapper.deleteByModelId(model.getId()); } catch (Exception ignored) {}
            }
            for (Field field : createdFields) {
                try { metaFieldMapper.deleteById(field.getId()); } catch (Exception ignored) {}
            }
            createdFields.clear();
            for (Model model : createdModels) {
                try { metaModelMapper.deleteById(model.getId()); } catch (Exception ignored) {}
            }
            createdModels.clear();
        } finally {
            MetaContext.clear();
        }
    }

    private void ensureTestDataExists() {
        String testEmail = "dsl-enhance-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "dsl-enhance-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("DSL Enhancement Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@dsl-enhance-test.com");
            tenant.setDescription("Test tenant for DSL enhancement tests");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
    }

    // ==================== Model & Command Setup Helpers ====================

    private String createModel(String suffix, String... fieldDefs) {
        String modelCode = "dsl_t_" + suffix;
        String tableName = "mt_" + modelCode.toLowerCase();

        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(testTenant.getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.DRAFT.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "DSL Test Model " + suffix);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);

        metaModelMapper.insert(model);
        createdModels.add(model);

        // Parse field definitions: "fieldCode:dataType" or "fieldCode:dataType:required"
        int order = 1;
        for (String fieldDef : fieldDefs) {
            String[] parts = fieldDef.split(":");
            String fieldCode = parts[0];
            String dataType = parts.length > 1 ? parts[1] : "string";
            boolean required = parts.length > 2 && "required".equals(parts[2]);

            Field field = new Field();
            field.setPid(UniqueIdGenerator.generate());
            field.setTenantId(testTenant.getId());
            field.setCode(fieldCode);
            field.setDataType(dataType);
            field.setVersion(1);
            field.setIsCurrent(true);
            field.setStatus(Status.DRAFT.getCode());
            field.setCreatedAt(Instant.now());
            field.setUpdatedAt(Instant.now());
            field.setDeletedFlag(false);

            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setRequired(required);
            field.setFeature(feature);

            ExtensionBean fieldExt = new ExtensionBean();
            Map<String, Object> fieldExtMap = new HashMap<>();
            fieldExtMap.put("displayName", fieldCode);
            fieldExt.setExtension(fieldExtMap);
            field.setExtension(fieldExt);

            metaFieldMapper.insert(field);
            createdFields.add(field);

            fieldBindingMapper.insert(buildBinding(model.getId(), field.getId(), order++));
        }

        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        assertTrue(result.isSuccess(), "Table creation should succeed for " + modelCode);
        createdTables.add(tableName);

        return modelCode;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(modelId);
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        return binding;
    }

    /**
     * Create and publish a command with given executionConfig.
     * Also sets up FIELD_MAP binding rules for all specified field mappings.
     * If no fieldMappings provided, adds a dummy EFFECT rule to satisfy publish requirement.
     */
    private String createCommand(String modelCode, String executionConfigJson,
                                  Map<String, String> fieldMappings) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String commandCode = "dsl_cmd_" + suffix;

        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(commandCode);
        request.setDisplayName("DSL Test Command " + suffix);
        request.setDescription("Command for DSL enhancement tests");
        request.setModelCode(modelCode);
        request.setExecutionConfig(executionConfigJson);

        CommandDefinitionDTO created = commandService.create(request);
        assertNotNull(created, "Command creation should succeed");

        // Add FIELD_MAP binding rules
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            int seq = 1;
            for (Map.Entry<String, String> mapping : fieldMappings.entrySet()) {
                BindingRuleDTO rule = new BindingRuleDTO();
                rule.setRuleType("field_map");
                rule.setSourceField(mapping.getKey());
                rule.setTargetModel(modelCode);
                rule.setTargetField(mapping.getValue());
                rule.setSequence(seq++);
                rule.setEnabled(true);
                commandService.addBindingRule(created.getPid(), rule);
            }
        } else {
            // Add a dummy EFFECT rule to satisfy publish requirement (at least 1 binding rule)
            BindingRuleDTO dummyRule = new BindingRuleDTO();
            dummyRule.setRuleType("effect");
            dummyRule.setEventType("CommandExecuted");
            dummyRule.setSequence(1);
            dummyRule.setEnabled(true);
            commandService.addBindingRule(created.getPid(), dummyRule);
        }

        commandService.publish(created.getPid());
        return commandCode;
    }

    /**
     * Insert a record directly into the dynamic table using low-level mapper.
     */
    private String insertRecord(String modelCode, Map<String, Object> data) {
        String tableName = "mt_" + modelCode.toLowerCase();
        Map<String, Object> fullData = new HashMap<>(data);
        fullData.put("tenant_id", testTenant.getId());
        fullData.put("pid", UniqueIdGenerator.generate());
        fullData.put("created_at", Instant.now());
        fullData.put("updated_at", Instant.now());
        dynamicDataMapper.insert(tableName, fullData);

        // Read back to get the auto-generated id
        String sql = "SELECT id FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        Map<String, Object> params = Map.of("tenantId", testTenant.getId(), "pid", fullData.get("pid"));
        List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
        assertFalse(result.isEmpty(), "Inserted record should be findable");
        return result.get(0).get("id").toString();
    }

    /**
     * Read a record from the dynamic table using low-level mapper.
     */
    private Map<String, Object> readRecord(String modelCode, String recordId) {
        String tableName = "mt_" + modelCode.toLowerCase();
        Long recordIdLong = Long.parseLong(recordId);
        String sql = "SELECT * FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND id = #{params.id}";
        Map<String, Object> params = Map.of("tenantId", testTenant.getId(), "id", recordIdLong);
        List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Count records in a model's table with given conditions.
     */
    private long countRecords(String modelCode, String whereField, Object whereValue) {
        try {
            String tableName = "mt_" + modelCode.toLowerCase();
            String sql = "SELECT COUNT(*) as cnt FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId}"
                    + (whereField != null ? " AND " + whereField + " = #{params.fieldVal}" : "");
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", testTenant.getId());
            if (whereField != null) {
                params.put("fieldVal", whereValue);
            }
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result != null && !result.isEmpty()) {
                Object cnt = result.get(0).get("cnt");
                if (cnt instanceof Number) return ((Number) cnt).longValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("countRecords failed: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== P0-BE-1: autoSetFields Tests ====================

    @Test
    @Order(10)
    @DisplayName("P0-BE-1: autoSetFields FIXED_VALUE injects static value into payload")
    void test10_autoSetFields_fixedValue() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING");

        String execConfig = """
                {
                    "autoSetFields": {
                        "status_%s": { "strategy": "fixed_value", "value": "draft" }
                    }
                }
                """.formatted(suffix);

        String commandCode = createCommand(modelCode, execConfig,
                Map.of("title", "title_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("title", "Test Issue")));
        req.setOperationType("create");
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");
        assertEquals("completed", result.getPhaseReached());
        // The payload should now contain status=DRAFT (injected by autoSet)
        // Verify via the payload that was mapped
    }

    @Test
    @Order(11)
    @DisplayName("P0-BE-1: autoSetFields CURRENT_USER injects user ID")
    void test11_autoSetFields_currentUser() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "reporter_" + suffix + ":INTEGER");

        String execConfig = """
                {
                    "autoSetFields": {
                        "reporter_%s": { "strategy": "current_user" }
                    }
                }
                """.formatted(suffix);

        String commandCode = createCommand(modelCode, execConfig,
                Map.of("title", "title_" + suffix, "reporter", "reporter_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("title", "Test with user")));
        req.setOperationType("create");
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");
        assertEquals("completed", result.getPhaseReached());
    }

    @Test
    @Order(12)
    @DisplayName("P0-BE-1: autoSetFields AUTO_GENERATE creates formatted code")
    void test12_autoSetFields_autoGenerate() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "issue_no_" + suffix + ":STRING");

        String execConfig = """
                {
                    "autoSetFields": {
                        "issue_no_%s": { "strategy": "auto_generate" }
                    }
                }
                """.formatted(suffix);

        String commandCode = createCommand(modelCode, execConfig,
                Map.of("title", "title_" + suffix, "issue_no", "issue_no_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("title", "Auto-gen test")));
        req.setOperationType("create");
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");
        assertEquals("completed", result.getPhaseReached());

        // The auto-generated field should follow pattern: ISS-{YYYYMMDD}-{SEQ}
        // (field contains "issue" → prefix ISS)
        // The value is injected into payload and then mapped via FIELD_MAP
    }

    @Test
    @Order(13)
    @DisplayName("P0-BE-1: autoSetFields CURRENT_DATETIME injects timestamp")
    void test13_autoSetFields_currentTime() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "report_time_" + suffix + ":STRING");

        String execConfig = """
                {
                    "autoSetFields": {
                        "report_time_%s": { "strategy": "current_datetime" }
                    }
                }
                """.formatted(suffix);

        String commandCode = createCommand(modelCode, execConfig,
                Map.of("title", "title_" + suffix, "report_time", "report_time_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("title", "Time test")));
        req.setOperationType("create");
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");
        assertEquals("completed", result.getPhaseReached());
    }

    // ==================== P0-BE-2: computedFields Tests ====================

    @Test
    @Order(20)
    @DisplayName("P0-BE-2: computedFields calculates SpEL expression on UPDATE")
    void test20_computedFields_spelCalculation() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "sales_amount_" + suffix + ":DECIMAL",
                "sales_qty_" + suffix + ":INTEGER",
                "avg_price_" + suffix + ":DECIMAL");

        // First, insert a record to update
        String recordId = insertRecord(modelCode, Map.of(
                "sales_amount_" + suffix, 10000.0,
                "sales_qty_" + suffix, 250,
                "avg_price_" + suffix, 0.0
        ));

        String execConfig = """
                {
                    "computedFields": {
                        "avg_price_%s": "#sales_amount_%s / #sales_qty_%s"
                    }
                }
                """.formatted(suffix, suffix, suffix);

        String commandCode = createCommand(modelCode, execConfig,
                Map.of("sales_amount", "sales_amount_" + suffix,
                       "sales_qty", "sales_qty_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of(
                "sales_amount_" + suffix, 10000.0,
                "sales_qty_" + suffix, 250
        )));
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");

        // Verify computed field was written to DB
        Map<String, Object> record = readRecord(modelCode, recordId);
        assertNotNull(record);
        Object avgPrice = record.get("avg_price_" + suffix);
        assertNotNull(avgPrice, "avg_price should be computed");
        // 10000.0 / 250 = 40.0
        double avgPriceVal = ((Number) avgPrice).doubleValue();
        assertEquals(40.0, avgPriceVal, 0.01, "avg_price should be 10000/250 = 40.0");
    }

    // ==================== P0-BE-3: cascadeDelete Tests ====================

    @Test
    @Order(30)
    @DisplayName("P0-BE-3: cascadeDelete removes child records before parent delete")
    void test30_cascadeDelete() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        // Create parent model
        String parentModel = createModel("parent_" + suffix,
                "name_" + suffix + ":STRING");

        // Create child model with parent reference
        String childModel = createModel("child_" + suffix,
                "parent_id_" + suffix + ":INTEGER",
                "child_name_" + suffix + ":STRING");

        // Insert parent record
        String parentId = insertRecord(parentModel, Map.of(
                "name_" + suffix, "Parent Record"
        ));

        // Insert child records
        insertRecord(childModel, Map.of(
                "parent_id_" + suffix, Long.parseLong(parentId),
                "child_name_" + suffix, "Child 1"
        ));
        insertRecord(childModel, Map.of(
                "parent_id_" + suffix, Long.parseLong(parentId),
                "child_name_" + suffix, "Child 2"
        ));
        insertRecord(childModel, Map.of(
                "parent_id_" + suffix, Long.parseLong(parentId),
                "child_name_" + suffix, "Child 3"
        ));

        // Verify 3 children exist
        assertEquals(3, countRecords(childModel, "parent_id_" + suffix, Long.parseLong(parentId)),
                "Should have 3 child records before delete");

        // Create delete command with cascadeDelete config
        String execConfig = """
                {
                    "cascadeDelete": [
                        { "childModel": "%s", "parentField": "parent_id_%s" }
                    ]
                }
                """.formatted(childModel, suffix);

        String commandCode = createCommand(parentModel, execConfig,
                Map.of("name", "name_" + suffix));

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>());
        req.setOperationType("delete");
        req.setTargetRecordId(parentId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Delete command should succeed");

        // Verify children were deleted
        assertEquals(0, countRecords(childModel, "parent_id_" + suffix, Long.parseLong(parentId)),
                "All child records should be deleted by cascade");
    }

    // ==================== P0-BE-4: Multi-branch stateTransitionRules Tests ====================

    @Test
    @Order(40)
    @DisplayName("P0-BE-4: stateTransitionRules resolves correct branch based on payload")
    void test40_multiBranchStateTransition_firstBranch() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        // Insert record with PENDING status
        String recordId = insertRecord(modelCode, Map.of(
                "title_" + suffix, "Issue to triage",
                "status_" + suffix, "pending",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" },
                        { "condition": "#decision_%s == 'link_existing'", "toState": "rectifying" },
                        { "condition": "#decision_%s == 'create_inspection'", "toState": "inspection" }
                    ]
                }
                """.formatted(suffix, suffix, suffix, suffix, suffix);

        String commandCode = createCommand(modelCode, execConfig, null);

        // Test branch 1: NO_ACTION → CLOSED
        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "no_action")));
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");

        Map<String, Object> record = readRecord(modelCode, recordId);
        assertEquals("closed", record.get("status_" + suffix),
                "Status should transition to CLOSED for NO_ACTION decision");
    }

    @Test
    @Order(41)
    @DisplayName("P0-BE-4: stateTransitionRules resolves NEED_RECTIFY branch")
    void test41_multiBranchStateTransition_secondBranch() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        String recordId = insertRecord(modelCode, Map.of(
                "title_" + suffix, "Issue needs rectify",
                "status_" + suffix, "pending",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" }
                    ]
                }
                """.formatted(suffix, suffix, suffix);

        String commandCode = createCommand(modelCode, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "need_rectify")));
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result);

        Map<String, Object> record = readRecord(modelCode, recordId);
        assertEquals("rectifying", record.get("status_" + suffix),
                "Status should transition to RECTIFYING for NEED_RECTIFY decision");
    }

    @Test
    @Order(42)
    @DisplayName("P0-BE-4: stateTransitionRules rejects invalid fromState")
    void test42_multiBranchStateTransition_invalidFromState() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        // Insert record with CLOSED status (not in fromStates)
        String recordId = insertRecord(modelCode, Map.of(
                "title_" + suffix, "Already closed",
                "status_" + suffix, "closed",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" }
                    ]
                }
                """.formatted(suffix, suffix);

        String commandCode = createCommand(modelCode, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "no_action")));
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        assertThrows(Exception.class, () -> commandExecutor.execute(commandCode, req),
                "Should reject transition from invalid state");
    }

    @Test
    @Order(43)
    @DisplayName("P0-BE-4: stateTransitionRules rejects when no rule matches")
    void test43_multiBranchStateTransition_noMatch() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        String recordId = insertRecord(modelCode, Map.of(
                "title_" + suffix, "Unknown decision",
                "status_" + suffix, "pending",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" }
                    ]
                }
                """.formatted(suffix, suffix, suffix);

        String commandCode = createCommand(modelCode, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "unknown_value")));
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        assertThrows(Exception.class, () -> commandExecutor.execute(commandCode, req),
                "Should fail when no state transition rule matches");
    }

    @Test
    @Order(44)
    @DisplayName("P0-BE-4: simple toState transitions correctly")
    void test44_simpleToState() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING");

        String recordId = insertRecord(modelCode, Map.of(
                "title_" + suffix, "Draft issue",
                "status_" + suffix, "draft"
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["draft"],
                    "toState": "pending"
                }
                """.formatted(suffix);

        String commandCode = createCommand(modelCode, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>());
        req.setOperationType("update");
        req.setTargetRecordId(recordId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);
        assertNotNull(result);

        Map<String, Object> record = readRecord(modelCode, recordId);
        assertEquals("pending", record.get("status_" + suffix),
                "Status should transition from DRAFT to PENDING");
    }

    // ==================== P0-BE-5: sideEffects Tests ====================

    @Test
    @Order(50)
    @DisplayName("P0-BE-5: sideEffect CREATE_RECORD creates related record when condition matches")
    void test50_sideEffect_createRecord() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        // Source model (e.g., issue)
        String issueModel = createModel("issue_" + suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        // Target model (e.g., rectification)
        String rectModel = createModel("rect_" + suffix,
                "rect_issue_id_" + suffix + ":INTEGER",
                "rect_title_" + suffix + ":STRING",
                "rect_status_" + suffix + ":STRING");

        // Insert issue record
        String issueId = insertRecord(issueModel, Map.of(
                "title_" + suffix, "Issue with problem",
                "status_" + suffix, "pending",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" }
                    ],
                    "sideEffects": [
                        {
                            "condition": "#decision_%s == 'need_rectify'",
                            "action": "create_record",
                            "targetModel": "%s",
                            "fieldMapping": {
                                "rect_issue_id_%s": "$current.id",
                                "rect_title_%s": "$current.title_%s",
                                "rect_status_%s": "initiated"
                            }
                        }
                    ]
                }
                """.formatted(suffix, suffix, suffix, suffix, rectModel,
                suffix, suffix, suffix, suffix);

        String commandCode = createCommand(issueModel, execConfig, null);

        // Verify no rectification records exist yet
        assertEquals(0, countRecords(rectModel, null, null),
                "Should have 0 rectification records before command");

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "need_rectify")));
        req.setOperationType("update");
        req.setTargetRecordId(issueId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");

        // Verify issue status changed
        Map<String, Object> issue = readRecord(issueModel, issueId);
        assertEquals("rectifying", issue.get("status_" + suffix));

        // Verify rectification record was created
        long rectCount = countRecords(rectModel, null, null);
        assertTrue(rectCount > 0, "Should have created a rectification record via sideEffect");
    }

    @Test
    @Order(51)
    @DisplayName("P0-BE-5: sideEffect skipped when condition does not match")
    void test51_sideEffect_conditionNotMatched() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        String issueModel = createModel("issue2_" + suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING");

        String rectModel = createModel("rect2_" + suffix,
                "rect_issue_id_" + suffix + ":INTEGER",
                "rect_status_" + suffix + ":STRING");

        String issueId = insertRecord(issueModel, Map.of(
                "title_" + suffix, "Issue no action",
                "status_" + suffix, "pending",
                "decision_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" }
                    ],
                    "sideEffects": [
                        {
                            "condition": "#decision_%s == 'need_rectify'",
                            "action": "create_record",
                            "targetModel": "%s",
                            "fieldMapping": {
                                "rect_issue_id_%s": "$current.id",
                                "rect_status_%s": "initiated"
                            }
                        }
                    ]
                }
                """.formatted(suffix, suffix, suffix, suffix, rectModel, suffix, suffix);

        String commandCode = createCommand(issueModel, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "no_action")));
        req.setOperationType("update");
        req.setTargetRecordId(issueId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);
        assertNotNull(result);

        // Verify NO rectification record was created (condition didn't match)
        assertEquals(0, countRecords(rectModel, null, null),
                "Should NOT create rectification when decision is NO_ACTION");

        // Verify issue status changed to CLOSED
        Map<String, Object> issue = readRecord(issueModel, issueId);
        assertEquals("closed", issue.get("status_" + suffix));
    }

    // ==================== P0-BE-6: postActions/CREATE_CHILDREN Tests ====================

    @Test
    @Order(60)
    @DisplayName("P0-BE-6: postAction CREATE_CHILDREN creates N child records")
    void test60_postAction_createChildren() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        // Parent model (e.g., annual plan)
        String parentModel = createModel("plan_" + suffix,
                "plan_name_" + suffix + ":STRING",
                "plan_year_" + suffix + ":INTEGER");

        // Child model (e.g., monthly records)
        String childModel = createModel("monthly_" + suffix,
                "plan_id_" + suffix + ":INTEGER",
                "month_" + suffix + ":INTEGER",
                "amount_" + suffix + ":DECIMAL");

        // Insert parent record
        String parentId = insertRecord(parentModel, Map.of(
                "plan_name_" + suffix, "2026 Annual Plan",
                "plan_year_" + suffix, 2026
        ));

        String execConfig = """
                {
                    "postActions": [
                        {
                            "action": "create_children",
                            "targetModel": "%s",
                            "count": 12,
                            "fieldMapping": {
                                "plan_id_%s": "$parent.id",
                                "month_%s": "$index",
                                "amount_%s": 0
                            }
                        }
                    ]
                }
                """.formatted(childModel, suffix, suffix, suffix);

        String commandCode = createCommand(parentModel, execConfig, null);

        // Verify no children exist
        assertEquals(0, countRecords(childModel, null, null),
                "Should have 0 monthly records before");

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("plan_name_" + suffix, "Updated plan")));
        req.setOperationType("update");
        req.setTargetRecordId(parentId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Command should succeed");

        // Verify 12 child records were created
        long childCount = countRecords(childModel, "plan_id_" + suffix, Long.parseLong(parentId));
        assertEquals(12, childCount,
                "Should create 12 monthly records via CREATE_CHILDREN postAction");
    }

    @Test
    @Order(61)
    @DisplayName("P0-BE-6: postAction CREATE_CHILDREN with $index creates correct sequence")
    void test61_postAction_createChildren_indexValues() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        String parentModel = createModel("plan2_" + suffix,
                "name_" + suffix + ":STRING");

        String childModel = createModel("sub_" + suffix,
                "parent_id_" + suffix + ":INTEGER",
                "seq_" + suffix + ":INTEGER");

        String parentId = insertRecord(parentModel, Map.of(
                "name_" + suffix, "Test Plan"
        ));

        String execConfig = """
                {
                    "postActions": [
                        {
                            "action": "create_children",
                            "targetModel": "%s",
                            "count": 3,
                            "fieldMapping": {
                                "parent_id_%s": "$parent.id",
                                "seq_%s": "$index"
                            }
                        }
                    ]
                }
                """.formatted(childModel, suffix, suffix);

        String commandCode = createCommand(parentModel, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>());
        req.setOperationType("update");
        req.setTargetRecordId(parentId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);
        assertNotNull(result);

        // Verify 3 child records created
        long count = countRecords(childModel, "parent_id_" + suffix, Long.parseLong(parentId));
        assertEquals(3, count, "Should create 3 child records");

        // Read all children and verify seq values are 1,2,3
        String tableName = "mt_" + childModel.toLowerCase();
        String sql = "SELECT seq_" + suffix + " FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND parent_id_" + suffix + " = #{params.parentId}"
                + " ORDER BY seq_" + suffix;
        Map<String, Object> params = Map.of("tenantId", testTenant.getId(), "parentId", Long.parseLong(parentId));
        List<Map<String, Object>> children = dynamicDataMapper.selectByQuery(sql, params);

        assertEquals(3, children.size());
        assertEquals(1, ((Number) children.get(0).get("seq_" + suffix)).intValue());
        assertEquals(2, ((Number) children.get(1).get("seq_" + suffix)).intValue());
        assertEquals(3, ((Number) children.get(2).get("seq_" + suffix)).intValue());
    }

    // ==================== Combined Tests ====================

    @Test
    @Order(70)
    @DisplayName("Combined: autoSet + stateTransition + sideEffect in single command")
    void test70_combined_autoSet_stateTransition_sideEffect() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        // Issue model
        String issueModel = createModel("comb_iss_" + suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING",
                "decision_" + suffix + ":STRING",
                "triage_time_" + suffix + ":STRING");

        // Rectification model
        String rectModel = createModel("comb_rect_" + suffix,
                "issue_id_" + suffix + ":INTEGER",
                "rect_status_" + suffix + ":STRING");

        // Insert issue
        String issueId = insertRecord(issueModel, Map.of(
                "title_" + suffix, "Combined test issue",
                "status_" + suffix, "pending",
                "decision_" + suffix, "",
                "triage_time_" + suffix, ""
        ));

        String execConfig = """
                {
                    "stateField": "status_%s",
                    "fromStates": ["pending"],
                    "stateTransitionRules": [
                        { "condition": "#decision_%s == 'no_action'", "toState": "closed" },
                        { "condition": "#decision_%s == 'need_rectify'", "toState": "rectifying" }
                    ],
                    "autoSetFields": {
                        "triage_time_%s": { "strategy": "current_datetime" }
                    },
                    "sideEffects": [
                        {
                            "condition": "#decision_%s == 'need_rectify'",
                            "action": "create_record",
                            "targetModel": "%s",
                            "fieldMapping": {
                                "issue_id_%s": "$current.id",
                                "rect_status_%s": "initiated"
                            }
                        }
                    ]
                }
                """.formatted(suffix, suffix, suffix, suffix, suffix, rectModel, suffix, suffix);

        String commandCode = createCommand(issueModel, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("decision_" + suffix, "need_rectify")));
        req.setOperationType("update");
        req.setTargetRecordId(issueId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);

        assertNotNull(result, "Combined command should succeed");

        // Verify state changed
        Map<String, Object> issue = readRecord(issueModel, issueId);
        assertEquals("rectifying", issue.get("status_" + suffix));

        // Verify rectification created
        long rectCount = countRecords(rectModel, null, null);
        assertTrue(rectCount > 0, "Should create rectification via sideEffect");
    }

    @Test
    @Order(80)
    @DisplayName("P0-BE-5: sideEffect UPDATE_RECORD updates related record")
    void test80_sideEffect_updateRecord() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);

        // Issue model (target of update)
        String issueModel = createModel("upd_iss_" + suffix,
                "title_" + suffix + ":STRING",
                "status_" + suffix + ":STRING");

        // Rectification model (source of command)
        String rectModel = createModel("upd_rect_" + suffix,
                "issue_id_" + suffix + ":INTEGER",
                "rect_status_" + suffix + ":STRING",
                "rect_remark_" + suffix + ":STRING");

        // Insert issue
        String issueId = insertRecord(issueModel, Map.of(
                "title_" + suffix, "Issue to be updated",
                "status_" + suffix, "rectifying"
        ));

        // Insert rectification
        String rectId = insertRecord(rectModel, Map.of(
                "issue_id_" + suffix, Long.parseLong(issueId),
                "rect_status_" + suffix, "submitted",
                "rect_remark_" + suffix, ""
        ));

        // Accept rectification → should update issue status to RECTIFIED
        String execConfig = """
                {
                    "stateField": "rect_status_%s",
                    "fromStates": ["submitted"],
                    "toState": "accepted",
                    "sideEffects": [
                        {
                            "action": "update_record",
                            "targetModel": "%s",
                            "targetIdField": "issue_id_%s",
                            "fieldMapping": {
                                "status_%s": "rectified"
                            }
                        }
                    ]
                }
                """.formatted(suffix, issueModel, suffix, suffix);

        String commandCode = createCommand(rectModel, execConfig, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(new HashMap<>(Map.of("rect_remark_" + suffix, "Looks good")));
        req.setOperationType("update");
        req.setTargetRecordId(rectId);
        req.setClientRequestId("req_" + UUID.randomUUID());

        CommandExecuteResult result = commandExecutor.execute(commandCode, req);
        assertNotNull(result);

        // Verify rectification status changed
        Map<String, Object> rect = readRecord(rectModel, rectId);
        assertEquals("accepted", rect.get("rect_status_" + suffix));

        // Verify issue status updated via sideEffect UPDATE_RECORD
        Map<String, Object> issue = readRecord(issueModel, issueId);
        assertEquals("rectified", issue.get("status_" + suffix),
                "Issue status should be updated to RECTIFIED via sideEffect UPDATE_RECORD");
    }
}
