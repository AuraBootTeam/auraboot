package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.service.AgentSkillService;
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
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deterministic settle for the Gap 3 root cause: a bound skill whose {@code skill_tools}
 * declares a {@code cmd:} write command must resolve that command as a governed tool via
 * {@link AgentSkillService#resolveSkillTools} — exactly what a skill-based WRITE colleague
 * (小奥 bound to a create skill) relies on, mirroring the READ colleague that already works.
 *
 * <p>When the skill-based write colleague was driven live, it reached for {@code list_agent_skill}
 * instead of the create command, which could mean either (a) the skill did not resolve the
 * {@code cmd:} tool, or (b) the model just chose badly. This IT removes the LLM from the loop and
 * answers (a) directly: seed a real model + create command + a skill bound to {@code cmd:<cmd>},
 * then assert the skill contributes it. If this is GREEN, cmd: skill resolution is sound and the
 * remaining Gap-3 blocker is purely model behaviour; if RED, it is a fixable resolution bug.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("A bound skill resolves its cmd: write command as a governed tool")
class SkillCmdResolutionIT {

    @Autowired private AgentSkillService agentSkillService;
    @Autowired private CommandService commandService;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserService userService;
    @Autowired private TenantService tenantService;
    @Autowired private TenantMemberService tenantMemberService;

    private User testUser;
    private Tenant testTenant;
    private final List<String> createdTables = new ArrayList<>();
    private final List<Model> createdModels = new ArrayList<>();
    private final List<Field> createdFields = new ArrayList<>();
    private final Set<String> seededSkills = new HashSet<>();

    @BeforeEach
    void setUp() {
        ensureTestDataExists();
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            for (String code : seededSkills) {
                try { jdbcTemplate.update("DELETE FROM ab_agent_skill WHERE skill_code = ?", code); } catch (Exception ignore) {}
            }
            for (String t : createdTables) {
                try { dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + t); } catch (Exception ignore) {}
            }
            for (Model m : createdModels) { try { fieldBindingMapper.deleteByModelId(m.getId()); } catch (Exception ignore) {} }
            for (Field f : createdFields) { try { metaFieldMapper.deleteById(f.getId()); } catch (Exception ignore) {} }
            for (Model m : createdModels) { try { metaModelMapper.deleteById(m.getId()); } catch (Exception ignore) {} }
            createdTables.clear(); createdModels.clear(); createdFields.clear(); seededSkills.clear();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("resolveSkillTools contributes a cmd: create command declared in skill_tools")
    void boundSkill_resolvesCmdWriteCommand() {
        String suffix = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        String modelCode = createModel(suffix, "name_" + suffix + ":STRING");
        String commandCode = createCommand(modelCode, Map.of("name", "name_" + suffix));
        String cmdTool = "cmd:" + commandCode;

        // A SYSTEM-tenant builtin skill whose only tool is the cmd: write command.
        String skillCode = seedSkill("[\"" + cmdTool + "\"]");

        List<AgentToolDefinition> tools = agentSkillService.resolveSkillTools(testTenant.getId(), skillCode);

        assertThat(tools)
                .as("a skill bound to %s must contribute it as a governed tool (what a skill-based write colleague needs)", cmdTool)
                .extracting(AgentToolDefinition::getName)
                .contains(cmdTool);
    }

    // ==================== helpers ====================

    private String seedSkill(String toolsJson) {
        String code = "skcmd_" + UniqueIdGenerator.generate().toLowerCase();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_skill (pid, tenant_id, skill_code, skill_name, skill_level, skill_category, "
                        + " skill_tools, execution_mode, actionability, declared_effects, is_builtin, skill_status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'workflow', 'test', ?::jsonb, 'sequential', 'execute', "
                        + " '[\"WRITE_PLATFORM_STATE\"]'::jsonb, TRUE, 'active', FALSE)",
                UniqueIdGenerator.generate(), SystemTenantContextExecutor.SYSTEM_TENANT_ID, code, "Skill Cmd " + code, toolsJson);
        seededSkills.add(code);
        return code;
    }

    private void ensureTestDataExists() {
        String email = "skill-cmd-resolution-test@auraboot.com";
        testUser = userService.findByEmail(email);
        if (testUser == null) testUser = userService.signUp(email, "test-password-123");
        String tenantName = "skill-cmd-resolution-test-tenant";
        testTenant = tenantService.findByName(tenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(tenantName);
            tenant.setDisplayName("Skill Cmd Resolution Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@skill-cmd-resolution-test.com");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
    }

    private String createModel(String suffix, String... fieldDefs) {
        String modelCode = "skc_" + suffix;
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
        extMap.put("displayName", "Skill Cmd Model " + suffix);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);
        metaModelMapper.insert(model);
        createdModels.add(model);
        int order = 1;
        for (String fieldDef : fieldDefs) {
            String[] parts = fieldDef.split(":");
            Field field = new Field();
            field.setPid(UniqueIdGenerator.generate());
            field.setTenantId(testTenant.getId());
            field.setCode(parts[0]);
            field.setDataType(parts.length > 1 ? parts[1] : "string");
            field.setVersion(1);
            field.setIsCurrent(true);
            field.setStatus(Status.DRAFT.getCode());
            field.setCreatedAt(Instant.now());
            field.setUpdatedAt(Instant.now());
            field.setDeletedFlag(false);
            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setRequired(false);
            field.setFeature(feature);
            ExtensionBean fieldExt = new ExtensionBean();
            Map<String, Object> fieldExtMap = new HashMap<>();
            fieldExtMap.put("displayName", parts[0]);
            fieldExt.setExtension(fieldExtMap);
            field.setExtension(fieldExt);
            metaFieldMapper.insert(field);
            createdFields.add(field);
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setTenantId(testTenant.getId());
            binding.setModelId(model.getId());
            binding.setFieldId(field.getId());
            binding.setFieldOrder(order++);
            fieldBindingMapper.insert(binding);
        }
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        assertThat(result.isSuccess()).as("table creation for %s", modelCode).isTrue();
        createdTables.add(tableName);
        return modelCode;
    }

    private String createCommand(String modelCode, Map<String, String> fieldMappings) {
        String cmdSuffix = UUID.randomUUID().toString().substring(0, 8);
        String commandCode = "skc_cmd_" + cmdSuffix;
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(commandCode);
        request.setDisplayName("Skill Cmd Command " + cmdSuffix);
        request.setDescription("Create command for skill cmd resolution");
        request.setModelCode(modelCode);
        request.setExecutionConfig("{}");
        CommandDefinitionDTO created = commandService.create(request);
        assertThat(created).isNotNull();
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
