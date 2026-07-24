package com.auraboot.framework.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import java.time.Instant;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Owner gap 1 — a rerunnable pin for the WRITE half of the digital-employee scenario:
 * a write command an agent proposes must, when executed <em>through the agent's own
 * tool path</em>, go through the real command pipeline and land a row in the system
 * of record — and it must be classified as a write (so the confirm gate fires).
 *
 * <p>This runs the exact seam the confirm-gate resume path executes on approval:
 * {@code AuraBotPendingContinuationService.executeResumeTool} →
 * {@code toolProviderRegistry.execute("cmd:<command>", payload)} →
 * {@code DslToolProvider.executeCommand} → {@code CommandExecutor} → an {@code mt_} insert.
 * Existing tests cover the pieces separately (the resume dispatch in
 * {@code ConversationTurnServiceImplResumeTest}; the command executor directly in
 * {@code CommandExecutorDslEnhancementTest}) but not the integrated <em>agent
 * tool path → DB</em>. The live scenario (create → confirm_required → /execute
 * APPROVED → DB row) was verified end-to-end against real qwen; this fixes it as a
 * deterministic, LLM-free regression guard.
 *
 * <p>Deterministic + self-contained: it seeds its own model + field + physical table
 * + create command in a test tenant (mirroring {@code CommandExecutorDslEnhancementTest}),
 * so it does not depend on a tenant-scoped plugin catalog (the reason a naive attempt
 * hit {@code Command not found}). Everything is torn down after.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("Agent write command → agent tool path → real command pipeline → DB (gap 1)")
class AgentWriteCommandPipelineIT {

    @Autowired private ToolProviderRegistry toolProviderRegistry;
    @Autowired private CommandService commandService;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private UserService userService;
    @Autowired private TenantService tenantService;
    @Autowired private TenantMemberService tenantMemberService;

    private User testUser;
    private Tenant testTenant;
    private final List<String> createdTables = new ArrayList<>();
    private final List<Model> createdModels = new ArrayList<>();
    private final List<Field> createdFields = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ensureTestDataExists();
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            for (String table : createdTables) {
                try { dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + table); } catch (Exception ignore) {}
            }
            createdTables.clear();
            for (Model m : createdModels) {
                try { fieldBindingMapper.deleteByModelId(m.getId()); } catch (Exception ignore) {}
            }
            for (Field f : createdFields) {
                try { metaFieldMapper.deleteById(f.getId()); } catch (Exception ignore) {}
            }
            createdFields.clear();
            for (Model m : createdModels) {
                try { metaModelMapper.deleteById(m.getId()); } catch (Exception ignore) {}
            }
            createdModels.clear();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("an agent write tool executes through the real pipeline and inserts a row; it is confirm-gated")
    void agentWriteTool_executesThroughPipeline_insertsRow_andIsConfirmGated() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String nameField = "name_" + suffix;
        String modelCode = createModel(suffix, nameField + ":STRING");
        String tableName = "mt_" + modelCode.toLowerCase();
        String commandCode = createCommand(modelCode, "{}", Map.of("name", nameField));

        String toolCode = "cmd:" + commandCode;
        String recordName = "gap1-write-" + suffix;

        long before = countRows(tableName, nameField, recordName);

        // Execute via the AGENT tool path (what the confirm-gate resume runs), not the
        // command executor directly.
        ProviderExecutionResult result = toolProviderRegistry.execute(
                testTenant.getId(), toolCode,
                new HashMap<>(Map.of("name", recordName, "operationType", "create")));

        assertNotNull(result, "the agent tool path must resolve and execute the write command");
        assertTrue(result.isSuccess(), "write command must succeed via the agent path: " + result.getErrorMessage());
        assertEquals(before + 1, countRows(tableName, nameField, recordName),
                "the create command must land exactly one row in the system of record");

        // Confirm gate input: the write command must be discovered as a non-read-only
        // tool, so requiresConfirmation fires (a read command would be L0 / read-only).
        ToolDefinition writeTool = discoverCommandTool(modelCode, toolCode);
        assertNotNull(writeTool, "the write command must be discoverable as an agent tool");
        assertNotEquals("L0", writeTool.getRiskLevel(),
                "a create command must NOT be classified read-only (L0) — that is what makes the confirm gate fire");
    }

    // ==================== helpers (mirror CommandExecutorDslEnhancementTest) ====================

    private ToolDefinition discoverCommandTool(String modelCode, String toolCode) {
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .userId(testUser.getId())
                .modelHint(modelCode)
                .maxResults(100)
                .build();
        List<ToolDefinition> tools = toolProviderRegistry.discoverAll(ctx);
        if (tools == null) return null;
        return tools.stream().filter(t -> toolCode.equals(t.getToolCode())).findFirst().orElse(null);
    }

    private long countRows(String tableName, String field, String value) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + tableName + " WHERE " + field + " = ?", Long.class, value);
        return n != null ? n : 0L;
    }

    private void ensureTestDataExists() {
        String testEmail = "agent-write-pipeline-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }
        String tenantName = "agent-write-pipeline-test-tenant";
        testTenant = tenantService.findByName(tenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(tenantName);
            tenant.setDisplayName("Agent Write Pipeline Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@agent-write-pipeline-test.com");
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

    private String createModel(String suffix, String... fieldDefs) {
        String modelCode = "agw_" + suffix;
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
        extMap.put("displayName", "Agent Write Model " + suffix);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);
        metaModelMapper.insert(model);
        createdModels.add(model);

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

    private String createCommand(String modelCode, String executionConfigJson, Map<String, String> fieldMappings) {
        String cmdSuffix = UUID.randomUUID().toString().substring(0, 8);
        String commandCode = "agw_cmd_" + cmdSuffix;
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(commandCode);
        request.setDisplayName("Agent Write Command " + cmdSuffix);
        request.setDescription("Create command for the agent write pipeline pin");
        request.setModelCode(modelCode);
        request.setExecutionConfig(executionConfigJson);
        CommandDefinitionDTO created = commandService.create(request);
        assertNotNull(created, "Command creation should succeed");
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
        commandService.publish(created.getPid());
        return commandCode;
    }
}
