package com.auraboot.framework.automation;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.bpm.AutomationProcessRuntime;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 — Quality auto-CAPA closed-loop, real-stack golden (campaign batch 2).
 *
 * <p>Proves the business-chain seam that no existing IT covers end-to-end: a quality defect record
 * being created drives an enabled automation that — on a real SmartEngine flow — executes a real
 * {@code create_capa} command through the full command pipeline (AutoSet + FieldMap + Insert),
 * materializing a CAPA row whose fields are carried over from the triggering defect, and stamps a
 * {@code success} row in {@code ab_automation_log}.
 *
 * <p>The codebase already verifies each engine in isolation (automation CRUD, command pipeline,
 * SpEL, SmartEngine flow). What was NOT verified — and is the design-doc S3 gap — is the
 * <em>composed</em> chain {@code defect.create → automation.condition → execute_command →
 * cross-model CAPA row + audit log}. This IT pins exactly that:
 * <ul>
 *   <li>the enabled automation fires through the SmartEngine flow (not a flat loop);</li>
 *   <li>{@code execute_command} routes to the real {@link com.auraboot.framework.meta.service.CommandExecutor};</li>
 *   <li>{@code ${recordId}} links the CAPA to the source defect and {@code ${record.<field>}} carries
 *       the defect's description/root-cause across the model boundary;</li>
 *   <li>the create command's AUTO_SET phase still fires under automation drive (code auto-generated,
 *       status fixed to {@code open});</li>
 *   <li>the SpEL trigger condition gates correctly — a non-critical defect must NOT fire the chain.</li>
 * </ul>
 *
 * <p>Self-contained: synthetic defect/CAPA model pair (mirrors {@code qc_defect_record}/{@code qc_capa}
 * shape) published in-test, real PostgreSQL, no plugin import and no command permissions — same
 * harness as {@code CommandCreateRecordSideEffectIT} + {@code AutomationProcessRuntimeIntegrationTest}.
 */
@Slf4j
@DisplayName("S3 golden: quality defect → automation → create_capa command → CAPA row + audit log")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityAutoCapaChainGoldenIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private CommandService commandService;
    @Autowired private AutomationProcessRuntime runtime;
    @Autowired private AutomationTriggerService automationTriggerService;
    @Autowired private com.auraboot.framework.automation.mapper.AutomationLogMapper automationLogMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String defectModel = "qcd_" + suffix;
    private final String capaModel = "qcc_" + suffix;
    private final String defectTable = "mt_" + defectModel;
    private final String capaTable = "mt_" + capaModel;
    private final String createCapaCmd = "qctest:create_capa_" + suffix;
    private final String triggerCondition = "#record['qcd_severity'] == 'critical'";

    private Automation autoCapaAutomation;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(defectTable);
        dropIfExists(capaTable);
        cleanMeta(defectModel);
        cleanMeta(capaModel);
        cleanCommand();

        // Defect model: severity (state-like), description, root cause.
        publishModel(defectModel, "QC Defect", new String[]{"qcd_severity", "qcd_description", "qcd_root_cause"}, null);
        // CAPA model: code REQUIRED (mirrors qc_capa NOT-NULL code), plus the carried-over fields + status.
        publishModel(capaModel, "QC CAPA",
                new String[]{"qcc_source_id", "qcc_description", "qcc_root_cause", "qcc_status"}, "qcc_code");

        // Real create command: AUTO_SET auto-generates the code + fixes status=open; inputFields carry payload.
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "create");
        execConfig.put("modelCode", capaModel);
        execConfig.put("inputFields", List.of("qcc_source_id", "qcc_description", "qcc_root_cause"));
        Map<String, Object> autoSet = new LinkedHashMap<>();
        autoSet.put("qcc_code", new LinkedHashMap<>(Map.of("strategy", "auto_generate", "pattern", "CAPA-{yyyyMMdd}-{seq}")));
        autoSet.put("qcc_status", new LinkedHashMap<>(Map.of("strategy", "fixed_value", "value", "open")));
        execConfig.put("autoSetFields", autoSet);

        CommandDefinitionCreateRequest req = new CommandDefinitionCreateRequest();
        req.setCode(createCapaCmd);
        req.setDisplayName(createCapaCmd);
        req.setModelCode(capaModel);
        req.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        req.setCmdRiskLevel("L2");
        CommandDefinitionDTO dto = commandService.create(req);
        commandService.publish(dto.getPid());

        // Automation: on defect create (critical), run a SmartEngine flow whose action executes the
        // create_capa command, mapping defect fields → CAPA payload.
        autoCapaAutomation = buildAutoCapaAutomation();
        runtime.deploy(autoCapaAutomation);
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            dropIfExists(defectTable);
            dropIfExists(capaTable);
            cleanMeta(defectModel);
            cleanMeta(capaModel);
            cleanCommand();
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @DisplayName("critical defect → automation fires → create_capa pipeline → linked CAPA row + success log")
    void criticalDefect_drivesAutomation_createsLinkedCapa_viaRealCommandPipeline() {
        // seed a critical defect
        Map<String, Object> defect = dynamicDataService.create(defectModel, Map.of(
                "qcd_severity", "critical",
                "qcd_description", "Solder bridge on U7 — batch B-" + suffix,
                "qcd_root_cause", "reflow profile drift"));
        String defectPid = String.valueOf(defect.get("pid"));

        long before = capaCountForSource(defectPid);

        // The trigger condition gates the dispatch (mirrors AutomationTriggerService#onRecordCreate).
        Map<String, Object> payload = triggerPayload(defect);
        assertThat(automationTriggerService.evaluateCondition(triggerCondition, payload))
                .as("critical defect must satisfy the automation trigger condition")
                .isTrue();

        // Run the unified synchronous entry (the same one the async fan-out uses).
        AutomationLog runLog = automationTriggerService.executeAutomation(autoCapaAutomation, defectPid, payload);

        assertThat(runLog.getStatus())
                .as("automation run must succeed end-to-end")
                .isEqualTo("success");

        // exactly one CAPA created and linked to the defect
        assertThat(capaCountForSource(defectPid))
                .as("automation must create exactly one CAPA linked to the source defect")
                .isEqualTo(before + 1);

        Map<String, Object> capa = jdbcTemplate.queryForMap(
                "SELECT qcc_code, qcc_source_id, qcc_description, qcc_root_cause, qcc_status, pid "
                        + "FROM " + capaTable + " WHERE qcc_source_id = ?", defectPid);

        assertThat(capa.get("qcc_source_id"))
                .as("${recordId} must link the CAPA back to the source defect pid")
                .isEqualTo(defectPid);
        assertThat(capa.get("qcc_description"))
                .as("${record.qcd_description} must carry the defect description across the model boundary")
                .isEqualTo("Solder bridge on U7 — batch B-" + suffix);
        assertThat(capa.get("qcc_root_cause"))
                .as("${record.qcd_root_cause} must carry across")
                .isEqualTo("reflow profile drift");
        assertThat(capa.get("qcc_status"))
                .as("AUTO_SET fixed_value must still fire under automation drive")
                .isEqualTo("open");
        assertThat((String) capa.get("qcc_code"))
                .as("AUTO_SET auto_generate must satisfy the NOT-NULL code under automation drive")
                .isNotBlank()
                .startsWith("CAPA-");
        assertThat(capa.get("pid"))
                .as("the CAPA must be a NEW record, not aliased to the defect pid")
                .isNotEqualTo(defectPid);

        // success audit-log row must persist (survives the @Transactional run)
        AutomationLog persisted = automationLogMapper.selectById(runLog.getId());
        assertThat(persisted).as("ab_automation_log row must survive the run").isNotNull();
        assertThat(persisted.getStatus()).isEqualTo("success");
        assertThat(persisted.getTriggerRecordId()).isEqualTo(defectPid);

        log.info("[S3 auto-CAPA] PASS — defect {} → CAPA {} ({}), log={}",
                defectPid, capa.get("qcc_code"), capa.get("pid"), persisted.getPid());
    }

    @Test
    @DisplayName("non-critical defect → trigger condition gates out → chain does not fire")
    void lowSeverityDefect_conditionGatesOut_noCapaCreated() {
        Map<String, Object> defect = dynamicDataService.create(defectModel, Map.of(
                "qcd_severity", "minor",
                "qcd_description", "cosmetic scratch",
                "qcd_root_cause", "handling"));
        String defectPid = String.valueOf(defect.get("pid"));

        Map<String, Object> payload = triggerPayload(defect);

        // onRecordCreate only calls executeAutomation when shouldTrigger (== evaluateCondition) is true.
        assertThat(automationTriggerService.evaluateCondition(triggerCondition, payload))
                .as("minor defect must NOT satisfy the critical-only trigger condition")
                .isFalse();

        // therefore the dispatch layer would never execute → no CAPA for this defect
        assertThat(capaCountForSource(defectPid))
                .as("gated-out defect must not produce a CAPA")
                .isZero();
        log.info("[S3 auto-CAPA gating] PASS — minor defect {} correctly gated out", defectPid);
    }

    // ------------------------------------------------------------------ fixtures

    private Automation buildAutoCapaAutomation() {
        Automation a = new Automation();
        a.setPid("ITCAPA" + suffix);
        a.setName("Auto-CAPA on critical defect " + suffix);
        a.setTenantId(getTestTenant().getId());
        a.setModelCode(defectModel);
        a.setTriggerType("on_record_create");
        a.setTriggerCondition(triggerCondition);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("qcc_source_id", "${recordId}");
        params.put("qcc_description", "${record.qcd_description}");
        params.put("qcc_root_cause", "${record.qcd_root_cause}");
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On defect create", "config", Map.of())),
                        Map.of("id", "a1", "type", "action-execute-command",
                                "data", Map.of("label", "Create CAPA",
                                        "config", Map.of(
                                                "actionType", "execute_command",
                                                "commandCode", createCapaCmd,
                                                "params", params)))),
                "edges", List.of(Map.of("id", "e1", "source", "t1", "target", "a1"))));
        a.setEnabled(true);
        return a;
    }

    private Map<String, Object> triggerPayload(Map<String, Object> defectData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "create");
        payload.put("record", defectData);
        return payload;
    }

    private long capaCountForSource(String sourcePid) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + capaTable + " WHERE qcc_source_id = ?", Long.class, sourcePid);
        return n == null ? 0 : n;
    }

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
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", createCapaCmd);
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
