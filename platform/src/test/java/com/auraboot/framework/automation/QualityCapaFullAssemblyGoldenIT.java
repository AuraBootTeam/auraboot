package com.auraboot.framework.automation;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.service.AutomationService;
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
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * S3 — Quality auto-CAPA closed-loop, FULL ASSEMBLY golden (campaign batch 2, slice 3 / capstone).
 *
 * <p>Closes the last leg of the design-doc S3 chain that had no golden: <b>BPMN approval →
 * {@code qc:create_capa}</b>. When the human approval task of a CAPA-review process is completed,
 * the task's {@code task_completed} event ({@code AuraTaskEventPublisher} → {@code EventBusService})
 * is forwarded by {@code BpmEventAutomationBridge} to an enabled <b>on_bpm_event</b> automation,
 * which executes the real {@code create_capa} command through the full pipeline — materializing the
 * CAPA row.
 *
 * <p>This is the seam that completes the assembly with S3-1 ({@code automation → execute_command →
 * CAPA}) and S3-2 ({@code defect → automation → BPMN userTask → SLA}). Here the new, previously
 * untested hop is the <b>real, persisted on_bpm_event automation dispatched by the live BPMN-event
 * bridge on task completion</b> (the bridge queries enabled automations from the DB, so the
 * automation is created+enabled via the real service — not built in-memory like S3-1/S3-2).
 *
 * <p>The completion→CAPA hop runs through {@code @Async} dispatch, so the assertion polls
 * (Awaitility) for the materialized CAPA row instead of assuming synchronous completion.
 *
 * <p>Self-contained: synthetic CAPA model + in-test deployed BPMN, real PostgreSQL, no plugin import.
 */
@Slf4j
@DisplayName("S3 golden (capstone): BPMN approval (task complete) → on_bpm_event automation → create_capa")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityCapaFullAssemblyGoldenIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private CommandService commandService;
    @Autowired private AutomationService automationService;
    @Autowired private ProcessDeploymentService deploymentService;
    @Autowired private ProcessEngineService processEngineService;
    @Autowired private TaskService taskService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String defectModel = "qcd3_" + suffix;
    private final String capaModel = "qcc3_" + suffix;
    private final String defectTable = "mt_" + defectModel;
    private final String capaTable = "mt_" + capaModel;
    private final String createCapaCmd = "qctest:create_capa3_" + suffix;
    private final String reviewNode = "capa_review3_" + suffix;
    private final String processKey = "capaflow3_" + suffix;

    private String onApprovalAutomationPid;

    private static final String CAPA_APPROVAL_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="CAPA Approval" isExecutable="true">
                <startEvent id="start" name="Start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="CAPA Review" smart:assigneeType="starter"/>
                <sequenceFlow id="f2" sourceRef="%s" targetRef="end"/>
                <endEvent id="end" name="End"/>
              </process>
            </definitions>
            """;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(defectTable);
        dropIfExists(capaTable);
        cleanMeta(defectModel);
        cleanMeta(capaModel);
        jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", createCapaCmd);

        publishModel(defectModel, "QC Defect (assembly)", new String[]{"qcd_severity", "qcd_description"}, null);
        publishModel(capaModel, "QC CAPA (assembly)", new String[]{"qcc_source_id", "qcc_description", "qcc_status"}, "qcc_code");

        // create_capa command (same shape as S3-1)
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "create");
        execConfig.put("modelCode", capaModel);
        execConfig.put("inputFields", List.of("qcc_source_id", "qcc_description"));
        Map<String, Object> autoSet = new LinkedHashMap<>();
        autoSet.put("qcc_code", new LinkedHashMap<>(Map.of("strategy", "auto_generate", "pattern", "CAPA-{yyyyMMdd}-{seq}")));
        autoSet.put("qcc_status", new LinkedHashMap<>(Map.of("strategy", "fixed_value", "value", "open")));
        execConfig.put("autoSetFields", autoSet);
        CommandDefinitionCreateRequest cmd = new CommandDefinitionCreateRequest();
        cmd.setCode(createCapaCmd);
        cmd.setDisplayName(createCapaCmd);
        cmd.setModelCode(capaModel);
        cmd.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        cmd.setCmdRiskLevel("L2");
        CommandDefinitionDTO cmdDto = commandService.create(cmd);
        commandService.publish(cmdDto.getPid());

        // Deploy the CAPA approval BPMN process
        String bpmn = String.format(CAPA_APPROVAL_BPMN, processKey, reviewNode, reviewNode, reviewNode);
        ProcessDeploymentService.CreateProcessRequest pReq = new ProcessDeploymentService.CreateProcessRequest(
                processKey, "CAPA Approval assembly " + suffix, "approval → CAPA", "quality",
                bpmn, null, null, null);
        var def = deploymentService.create(pReq);
        deploymentService.deploy(def.getPid());

        // Persisted + enabled on_bpm_event automation: on task_completed of this process, create the CAPA.
        // The bridge looks automations up from the DB, so this MUST be a real persisted+enabled rule.
        AutomationCreateRequest aReq = new AutomationCreateRequest();
        aReq.setName("Create CAPA on approval " + suffix);
        aReq.setModelCode(processKey);
        aReq.setTriggerType("on_bpm_event");
        aReq.setTriggerConfig(TriggerConfig.builder()
                .processKey(processKey)
                .eventTypes(List.of("task_completed"))
                .build());
        aReq.setActions(List.of(AutomationAction.builder()
                .type("execute_command")
                .config(Map.of("commandCode", createCapaCmd, "params", Map.of(
                        "qcc_source_id", "${instanceId}",
                        "qcc_description", "Auto CAPA from approved quality review")))
                .build()));
        aReq.setEnabled(false);
        AutomationDTO auto = automationService.create(aReq);
        automationService.enable(auto.getPid());
        onApprovalAutomationPid = auto.getPid();
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            if (onApprovalAutomationPid != null) automationService.delete(onApprovalAutomationPid);
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", createCapaCmd);
            dropIfExists(defectTable);
            dropIfExists(capaTable);
            cleanMeta(defectModel);
            cleanMeta(capaModel);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @DisplayName("approving the CAPA-review task auto-creates the CAPA via the on_bpm_event automation")
    void approvingReviewTask_createsCapa_viaOnBpmEventAutomation() {
        // a defect gives the process a real business key
        Map<String, Object> defect = dynamicDataService.create(defectModel, Map.of(
                "qcd_severity", "critical", "qcd_description", "Misaligned BGA — lot L-" + suffix));
        String defectPid = String.valueOf(defect.get("pid"));

        // start the CAPA approval process (defect→automation→start hop is covered by S3-2)
        Map<String, Object> vars = new HashMap<>();
        vars.put("_startUserId", String.valueOf(MetaContext.getCurrentUserId()));
        ProcessInstance instance = processEngineService.startProcess(processKey, defectPid, vars);
        String instanceId = instance.getInstanceId();
        assertThat(instanceId).as("process must start").isNotBlank();

        long before = capaCountForSource(instanceId);

        // find + approve the review task
        List<TaskInstance> tasks = taskService.getTasksByProcessInstance(instanceId);
        TaskInstance review = tasks.stream()
                .filter(t -> reviewNode.equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow(() -> new AssertionError("review userTask must be pending"));

        Map<String, Object> approval = new HashMap<>();
        approval.put("approved", true);
        taskService.completeTask(review.getInstanceId(), approval);

        // the completion event drives the on_bpm_event automation asynchronously → poll for the CAPA
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(capaCountForSource(instanceId))
                        .as("approving the review must create exactly one CAPA via on_bpm_event automation")
                        .isEqualTo(before + 1));

        Map<String, Object> capa = jdbcTemplate.queryForMap(
                "SELECT qcc_code, qcc_source_id, qcc_description, qcc_status FROM " + capaTable
                        + " WHERE qcc_source_id = ?", instanceId);
        assertThat(capa.get("qcc_source_id"))
                .as("CAPA must link back to the approved process instance").isEqualTo(instanceId);
        assertThat(capa.get("qcc_status")).as("AUTO_SET status").isEqualTo("open");
        assertThat((String) capa.get("qcc_code")).as("AUTO_SET code").isNotBlank().startsWith("CAPA-");
        assertThat(capa.get("qcc_description")).isEqualTo("Auto CAPA from approved quality review");

        log.info("[S3 full assembly] PASS — defect {} → process {} → approval → CAPA {} ({})",
                defectPid, instanceId, capa.get("qcc_code"), capa.get("qcc_source_id"));
    }

    // ------------------------------------------------------------------ fixtures

    private long capaCountForSource(String sourceId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + capaTable + " WHERE qcc_source_id = ?", Long.class, sourceId);
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
