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
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
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
 * Real-stack regression for AuraQR slice #1's mechanism: a {@code state_transition} command with a
 * {@code create_record} sideEffect must, when executed, transition the source record AND create a
 * linked record in the target model — the way {@code tasset:report_repair} auto-creates a
 * {@code tasset_maintenance} work-order.
 *
 * <p>Exercises the real command pipeline (StateCheck + AutoSet + FieldMap + SideEffect phases) on a
 * synthetic model pair, so it is self-contained (no plugin import, no command permissions). It pins
 * the behavioral contract slice #1 depends on:
 * <ul>
 *   <li>the sideEffect fires and creates exactly one target record;</li>
 *   <li>{@code "${recordId}"} resolves to the source record's pid → the link field;</li>
 *   <li>a payload field (the fault note) flows to the target via {@code "${field}"};</li>
 *   <li>a NOT-NULL target field that the source create-command would auto-generate (the code) is
 *       satisfied because the sideEffect maps it explicitly (the sideEffect path bypasses any
 *       target create-command auto-generation).</li>
 * </ul>
 *
 * <p>NOTE: the production config additionally relies on the import DTO recognizing the
 * {@code action}/{@code fieldMapping} keys (NOT {@code type}/{@code fieldMap}, which are dropped on
 * import → a silent no-op). That import-normalization contract is covered by the host-first
 * verification recorded on the PR; this IT pins the executor/mapping behavior.
 *
 * <p>Harness mirrors {@code QrAssetActionIT}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandCreateRecordSideEffectIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private CommandService commandService;
    @Autowired private CommandExecutor commandExecutor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String assetModel = "rwo_a_" + suffix;
    private final String maintModel = "rwo_m_" + suffix;
    private final String assetTable = "mt_" + assetModel;
    private final String maintTable = "mt_" + maintModel;
    private final String reportCmd = "rwotest:report_" + suffix;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(assetTable);
        dropIfExists(maintTable);
        cleanMeta(assetModel);
        cleanMeta(maintModel);
        cleanCommand();

        // asset model: a_status (state), a_code, a_note
        publishModel(assetModel, "RWO Asset", new String[]{"a_status", "a_code", "a_note"}, null);
        // maintenance model: m_code (REQUIRED → mirrors tasset_mn_code NOT NULL), m_asset, m_type, m_notes
        publishModel(maintModel, "RWO Maint", new String[]{"m_asset", "m_type", "m_notes"}, "m_code");

        // synthetic report command: state_transition available->repair, writes a_note, sideEffect creates a maint record
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "state_transition");
        execConfig.put("modelCode", assetModel);
        execConfig.put("stateField", "a_status");
        execConfig.put("fromStates", List.of("available"));
        execConfig.put("toState", "repair");
        execConfig.put("inputFields", List.of("a_note"));
        execConfig.put("sideEffects", List.of(Map.of(
                "action", "create_record",
                "targetModel", maintModel,
                "fieldMapping", new LinkedHashMap<>(Map.of(
                        "m_code", "${a_code}",
                        "m_asset", "${recordId}",
                        "m_type", "repair",
                        "m_notes", "${a_note}")))));
        CommandDefinitionCreateRequest req = new CommandDefinitionCreateRequest();
        req.setCode(reportCmd);
        req.setDisplayName(reportCmd);
        req.setModelCode(assetModel);
        req.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        req.setCmdRiskLevel("L2");
        CommandDefinitionDTO dto = commandService.create(req);
        commandService.publish(dto.getPid());
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            dropIfExists(assetTable);
            dropIfExists(maintTable);
            cleanMeta(assetModel);
            cleanMeta(maintModel);
            cleanCommand();
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    void stateTransition_createRecordSideEffect_createsLinkedRecord() {
        // seed an available asset with a code
        Map<String, Object> asset = dynamicDataService.create(assetModel,
                Map.of("a_status", "available", "a_code", "A-" + suffix));
        String assetPid = String.valueOf(asset.get("pid"));
        long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + maintTable + " WHERE m_asset = ?", Long.class, assetPid);

        // execute report: transitions to repair + sideEffect creates a linked maint record
        CommandExecuteRequest exec = new CommandExecuteRequest();
        exec.setPayload(Map.of("a_note", "hydraulic leak"));
        exec.setTargetRecordId(assetPid);
        exec.setClientRequestId(UUID.randomUUID().toString());
        commandExecutor.execute(reportCmd, exec);

        // asset transitioned
        assertEquals("repair",
                jdbcTemplate.queryForObject("SELECT a_status FROM " + assetTable + " WHERE pid = ?", String.class, assetPid),
                "source record must transition to repair");

        // exactly one linked record created
        long after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + maintTable + " WHERE m_asset = ?", Long.class, assetPid);
        assertEquals(before + 1, after, "sideEffect must create exactly one linked record");

        Map<String, Object> rec = jdbcTemplate.queryForMap(
                "SELECT m_code, m_asset, m_type, m_notes FROM " + maintTable + " WHERE m_asset = ?", assetPid);
        assertEquals(assetPid, rec.get("m_asset"), "${recordId} must link the new record to the source pid");
        assertEquals("repair", rec.get("m_type"), "literal mapping must pass through");
        assertEquals("hydraulic leak", rec.get("m_notes"), "${a_note} payload field must flow to the target");
        assertEquals("A-" + suffix, rec.get("m_code"), "NOT-NULL code must be satisfied via the explicit mapping");
        log.info("[sideEffect create_record] PASS — transition + linked record {} created", rec.get("m_code"));
    }

    // ------------------------------------------------------------------ fixture helpers

    /** Publish a model with the given string fields; if requiredCode != null, add it as a required (NOT NULL) field. */
    private void publishModel(String code, String name, String[] fields, String requiredCode) {
        Model m = buildModel(code, name);
        metaModelMapper.insert(m);
        int order = 1;
        if (requiredCode != null) {
            Field rc = buildField(requiredCode, true);
            metaFieldMapper.insert(rc);
            bindingMapper.insert(buildBinding(m.getId(), rc.getId(), order++));
        }
        for (String f : fields) {
            Field fld = buildField(f, false);
            metaFieldMapper.insert(fld);
            bindingMapper.insert(buildBinding(m.getId(), fld.getId(), order++));
        }
        metaModelService.publish(m.getPid(), code + " IT fixture");
    }

    private void cleanCommand() {
        try {
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", reportCmd);
        } catch (Exception ignored) {}
    }

    private void cleanMeta(String modelCode) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE id IN (SELECT field_id FROM ab_meta_model_field_binding b "
                            + "JOIN ab_meta_model m ON m.id = b.model_id WHERE m.code = ?)", modelCode);
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ?)", modelCode);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code = ?", modelCode);
        } catch (Exception e) {
            log.warn("[cleanup] meta cleanup for {} failed: {}", modelCode, e.getMessage());
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
        ext.setExtension(extMap);
        m.setExtension(ext);
        return m;
    }

    private Field buildField(String code, boolean required) {
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
        feature.setRequired(required);
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
