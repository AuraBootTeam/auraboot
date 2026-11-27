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
 * Real-stack regression for the {@code document_flow} side effect: a {@code state_transition}
 * command on a source document must create the target document and replicate the source lines
 * into the target line model — the way a released order produces an outbound order.
 *
 * <p>Exercises the real command pipeline and {@code DocumentFlowService} on a synthetic pair of
 * documents, so it is self-contained (no plugin import, no command permissions). It pins:
 * <ul>
 *   <li>the target document is created once, linked to the source by {@code ${recordPid}};</li>
 *   <li>{@code ${record.field}} and {@code 'literal'} resolve on the target document;</li>
 *   <li>every source line is replicated with {@code ${line.field}} and linked to the new document.</li>
 * </ul>
 *
 * <p>Harness mirrors {@code CommandCreateRecordSideEffectIT}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandDocumentFlowSideEffectIT extends BaseIntegrationTest {

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
    private final String sourceModel = "rwf_so_" + suffix;
    private final String sourceLineModel = "rwf_sol_" + suffix;
    private final String targetModel = "rwf_io_" + suffix;
    private final String targetLineModel = "rwf_iol_" + suffix;
    private final String releaseCmd = "rwftest:release_" + suffix;

    private String sourcePid;
    private String sourceCode = "SO-" + suffix;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        for (String model : List.of(sourceModel, sourceLineModel, targetModel, targetLineModel)) {
            dropIfExists(table(model));
            cleanMeta(model);
        }
        cleanCommand();

        publishModel(sourceModel, "RWF Source Order", "so_status", "so_code", "so_customer");
        publishModel(sourceLineModel, "RWF Source Line", "sol_order_pid", "sol_sku", "sol_qty");
        publishModel(targetModel, "RWF Target Order", "io_source_pid", "io_code", "io_status");
        publishModel(targetLineModel, "RWF Target Line", "iol_order_pid", "iol_sku", "iol_qty");

        // synthetic release command: state_transition draft->released, creates the target order and its lines
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "state_transition");
        execConfig.put("modelCode", sourceModel);
        execConfig.put("stateField", "so_status");
        execConfig.put("fromStates", List.of("draft"));
        execConfig.put("toState", "released");
        execConfig.put("inputFields", List.of("so_customer"));
        execConfig.put("sideEffects", List.of(Map.of(
                "action", "document_flow",
                "documentFlow", Map.of(
                        "targetModelCode", targetModel,
                        "fieldMapping", Map.of(
                                "io_source_pid", "${recordPid}",
                                "io_code", "${record.so_code}",
                                "io_status", "'released'"),
                        "lineMapping", Map.of(
                                "sourceLineModel", sourceLineModel,
                                "sourceForeignKey", "sol_order_pid",
                                "targetLineModel", targetLineModel,
                                "targetForeignKey", "iol_order_pid",
                                "fieldMapping", Map.of(
                                        "iol_sku", "${line.sol_sku}",
                                        "iol_qty", "${line.sol_qty}"))))));
        CommandDefinitionCreateRequest req = new CommandDefinitionCreateRequest();
        req.setCode(releaseCmd);
        req.setDisplayName(releaseCmd);
        req.setModelCode(sourceModel);
        req.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        req.setCmdRiskLevel("L2");
        CommandDefinitionDTO dto = commandService.create(req);
        commandService.publish(dto.getPid());
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            for (String model : List.of(targetLineModel, targetModel, sourceLineModel, sourceModel)) {
                dropIfExists(table(model));
                cleanMeta(model);
            }
            cleanCommand();
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
        // Seeds and declared effects are ordinary record writes: each is decided against the
        // actor's complete grant for that model. Reading the source needs its read grant.
        grantCommittedPermissionToTestRole(
                "model." + sourceModel + ".read", "model", sourceModel, "read",
                "Read the source order a document flow starts from");
        grantCommittedPermissionToTestRole(
                "model." + sourceModel + ".create", "model", sourceModel, "create",
                "Seed source orders");
        grantCommittedPermissionToTestRole(
                "model." + sourceLineModel + ".create", "model", sourceLineModel, "create",
                "Seed source order lines");
        grantCommittedPermissionToTestRole(
                "model." + targetModel + ".create", "model", targetModel, "create",
                "Create the target order the release command declares as a document flow");
        grantCommittedPermissionToTestRole(
                "model." + targetLineModel + ".create", "model", targetLineModel, "create",
                "Replicate source lines into the target line model");
    }

    @Test
    void stateTransition_documentFlow_createsTargetDocumentAndLines() {
        // seed a draft order with two lines
        Map<String, Object> source = dynamicDataService.create(sourceModel,
                Map.of("so_status", "draft", "so_code", sourceCode, "so_customer", "Draft customer"));
        sourcePid = String.valueOf(source.get("pid"));
        for (String[] line : new String[][]{{"SKU-1", "2"}, {"SKU-2", "5"}}) {
            dynamicDataService.create(sourceLineModel,
                    Map.of("sol_order_pid", sourcePid, "sol_sku", line[0], "sol_qty", line[1]));
        }

        CommandExecuteRequest exec = new CommandExecuteRequest();
        exec.setPayload(Map.of("so_customer", "Released customer"));
        exec.setTargetRecordId(sourcePid);
        exec.setClientRequestId(UUID.randomUUID().toString());
        commandExecutor.execute(releaseCmd, exec);

        assertEquals("released",
                jdbcTemplate.queryForObject("SELECT so_status FROM " + table(sourceModel) + " WHERE pid = ?", String.class, sourcePid),
                "source order must be released");

        // exactly one target document, linked to the source and mapped from its fields
        List<Map<String, Object>> targets = jdbcTemplate.queryForList(
                "SELECT pid, io_source_pid, io_code, io_status FROM " + table(targetModel) + " WHERE io_source_pid = ?", sourcePid);
        assertEquals(1, targets.size(), "the document flow must create exactly one target document");
        Map<String, Object> target = targets.getFirst();
        assertEquals(sourceCode, target.get("io_code"), "${record.so_code} must flow to the target");
        assertEquals("released", target.get("io_status"), "'released' must resolve as a literal");

        // every source line is replicated and linked to the new target document
        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT iol_sku, iol_qty FROM " + table(targetLineModel) + " WHERE iol_order_pid = ? ORDER BY iol_sku", target.get("pid"));
        assertEquals(2, lines.size(), "both source lines must be replicated");
        assertEquals("SKU-1", lines.get(0).get("iol_sku"));
        assertEquals("SKU-2", lines.get(1).get("iol_sku"));
        assertEquals("2", String.valueOf(lines.get(0).get("iol_qty")), "${line.sol_qty} must flow to the target line");
        assertEquals("5", String.valueOf(lines.get(1).get("iol_qty")));
        log.info("[sideEffect document_flow] PASS — target order {} created with {} line(s)", target.get("pid"), lines.size());
    }

    // ------------------------------------------------------------------ fixture helpers

    private String table(String model) {
        return "mt_" + model;
    }

    /** Publish a model with the given string fields. */
    private void publishModel(String code, String name, String... fields) {
        Model m = buildModel(code, name);
        metaModelMapper.insert(m);
        int order = 1;
        for (String f : fields) {
            Field fld = buildField(f);
            metaFieldMapper.insert(fld);
            bindingMapper.insert(buildBinding(m.getId(), fld.getId(), order++));
        }
        metaModelService.publish(m.getPid(), code + " IT fixture");
    }

    private void cleanCommand() {
        try {
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", releaseCmd);
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
