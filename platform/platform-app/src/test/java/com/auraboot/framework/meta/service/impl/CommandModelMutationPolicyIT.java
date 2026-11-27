package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.exception.ModelMutationDeniedException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack regression for a model mutation policy refusal. A command-only-create model
 * accepts a record only from an authorized command. A command that declares no permission is
 * not one: the platform lets it through the legacy allow, and the model's policy refuses the
 * write. That refusal is a denial that names the policy, which the command executor hands to
 * the client as a 403. It used to be reported as an unclassified failure: an opaque 500 with a
 * correlation reference (on the CRM stack, "新建群成员流水" failed that way).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandModelMutationPolicyIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private CommandService commandService;
    @Autowired private CommandExecutor commandExecutor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String model = "rmp_m_" + suffix;
    private final String table = "mt_" + model;
    private final String createCmd = "rmptest:create_" + suffix;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(table);
        cleanMeta();
        cleanCommand();

        // the model accepts a record only from an authorized command
        Model m = buildModel(model, "RMP Protected");
        metaModelMapper.insert(m);
        Field name = buildField("m_name");
        metaFieldMapper.insert(name);
        bindingMapper.insert(buildBinding(m.getId(), name.getId(), 1));
        metaModelService.publish(m.getPid(), model + " IT fixture");

        // a create command that declares no permission, so it is not an authorized command
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "create");
        execConfig.put("modelCode", model);
        execConfig.put("inputFields", List.of("m_name"));
        CommandDefinitionCreateRequest req = new CommandDefinitionCreateRequest();
        req.setCode(createCmd);
        req.setDisplayName(createCmd);
        req.setModelCode(model);
        req.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        req.setCmdRiskLevel("L2");
        CommandDefinitionDTO dto = commandService.create(req);
        commandService.publish(dto.getPid());
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            dropIfExists(table);
            cleanMeta();
            cleanCommand();
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
        grantCommittedPermissionToTestRole("model." + model + ".read", "model", model, "read",
                "Read the protected model");
    }

    @Test
    void aCommandWithoutPermissionCannotCreateARecordOfACommandOnlyCreateModelAndIsToldWhy() {
        CommandExecuteRequest exec = new CommandExecuteRequest();
        exec.setPayload(Map.of("m_name", "not authorized"));
        exec.setClientRequestId(UUID.randomUUID().toString());

        var denied = assertThrows(ModelMutationDeniedException.class, () -> commandExecutor.execute(createCmd, exec));

        assertEquals("MODEL_MUTATION_DENIED: Model '" + model + "' can only be created through an authorized command",
                denied.getMessage());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                "the refused create wrote nothing");
        log.info("[model mutation policy] PASS — refused as a denial: {}", denied.getMessage());
    }

    // ------------------------------------------------------------------ fixture helpers

    private void cleanCommand() {
        try {
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", createCmd);
        } catch (Exception ignored) {}
    }

    private void cleanMeta() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE id IN (SELECT field_id FROM ab_meta_model_field_binding b "
                            + "JOIN ab_meta_model m ON m.id = b.model_id WHERE m.code = ?)", model);
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ?)", model);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code = ?", model);
        } catch (Exception e) {
            log.warn("[cleanup] meta cleanup for {} failed: {}", model, e.getMessage());
        }
    }

    private void dropIfExists(String tableName) {
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
        } catch (Exception e) {
            log.debug("[setup] drop {} skipped: {}", tableName, e.getMessage());
        }
    }

    private Model buildModel(String code, String displayName) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.DRAFT.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", displayName);
        extMap.put("description", displayName + " — IT fixture");
        extMap.put("modelType", "entity");
        extMap.put("commandOnlyCreate", true);
        ext.setExtension(extMap);
        m.setExtension(ext);
        return m;
    }

    private Field buildField(String code) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType("string");
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        f.setFeature(feature);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code);
        ext.setExtension(extMap);
        f.setExtension(ext);
        return f;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(getTestTenant().getId());
        b.setModelId(modelId);
        b.setFieldId(fieldId);
        b.setFieldOrder(order);
        return b;
    }
}
