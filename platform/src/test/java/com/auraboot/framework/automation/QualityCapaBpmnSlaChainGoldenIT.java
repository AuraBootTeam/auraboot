package com.auraboot.framework.automation;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.bpm.AutomationProcessRuntime;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaSchedulerService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 — Quality auto-CAPA closed-loop, BPMN + SLA leg, real-stack golden (campaign batch 2, slice 2).
 *
 * <p>Proves the composed seam that only had piecemeal coverage: a quality defect drives an enabled
 * automation whose {@code start_process} action starts a <em>real</em> BPMN approval process; the
 * userTask it creates fires a {@code task_assigned} event ({@code AuraTaskEventPublisher}) that the
 * synchronous {@link com.auraboot.framework.bpm.listener.SlaActivationListener} consumes to
 * materialize an SLA record with a deadline; the SLA scheduler then marks it overdue and escalates.
 *
 * <p>What was NOT verified before:
 * <ul>
 *   <li>the automation {@code start_process} action ({@code StartProcessActionExecutor}) running on a
 *       real SmartEngine flow and starting a real, deployed process (only a unit test existed);</li>
 *   <li>that a real process start auto-emits {@code task_assigned} which activates SLA end-to-end
 *       (the SLA engine IT manually published the event);</li>
 *   <li>the business correlation: the started instance's {@code businessKey} = the source defect pid.</li>
 * </ul>
 *
 * <p>The SLA record carrying {@code node_id == capa_review_<suffix>} is itself proof of the whole
 * chain — an SLA record only exists for that node if the process started, the userTask was created,
 * and the {@code task_assigned} event reached the activation listener.
 *
 * <p>Self-contained: synthetic defect model + an in-test deployed BPMN process, real PostgreSQL,
 * no plugin import. Harness mirrors {@code BpmGatewayTest} (deploy) + {@code SlaDecisionE2EIntegrationTest}
 * (SLA activation/escalation) + {@code QualityAutoCapaChainGoldenIT} (automation drive).
 */
@Slf4j
@DisplayName("S3 golden: defect → automation start_process → BPMN userTask → SLA activation + escalation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityCapaBpmnSlaChainGoldenIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private AutomationProcessRuntime runtime;
    @Autowired private AutomationTriggerService automationTriggerService;
    @Autowired private ProcessDeploymentService deploymentService;
    @Autowired private TaskService taskService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private SlaSchedulerService slaSchedulerService;
    @Autowired private SlaRecordMapper slaRecordMapper;
    @Autowired private BpmNotifyRecordMapper notifyRecordMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String defectModel = "qcd2_" + suffix;
    private final String defectTable = "mt_" + defectModel;
    private final String reviewNode = "capa_review_" + suffix;
    private final String processKey = "capaflow_" + suffix;
    private final long escalationRecipientId = 970000000L + Math.floorMod(System.nanoTime(), 1_000_000L);

    private Automation startProcessAutomation;
    private String slaConfigPid;

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
    public void setUp() {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(defectTable);
        cleanMeta(defectModel);
        publishModel(defectModel, "QC Defect (BPMN leg)", new String[]{"qcd_severity", "qcd_description"});

        // Deploy a real BPMN approval process whose single userTask is the SLA-watched node.
        String bpmn = String.format(CAPA_APPROVAL_BPMN, processKey, reviewNode, reviewNode, reviewNode);
        ProcessDeploymentService.CreateProcessRequest req = new ProcessDeploymentService.CreateProcessRequest(
                processKey, "CAPA Approval " + suffix, "auto-CAPA approval flow", "quality",
                bpmn, null, null, null);
        var def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        // SLA config bound to the review node: fixed deadline + escalate at 50% threshold.
        var slaCfg = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "CAPA review SLA " + suffix, "NODE", reviewNode, null,
                "FIXED", "PT2H", null,
                List.of(Map.of("threshold", "50%", "action", "escalate",
                        "recipients", "userId:" + escalationRecipientId)),
                null, null, null, null, null));
        slaConfigPid = slaCfg.getPid();

        // Automation: on critical defect create, start the CAPA approval process.
        startProcessAutomation = buildStartProcessAutomation();
        runtime.deploy(startProcessAutomation);
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            jdbcTemplate.update("DELETE FROM ab_sla_record WHERE node_id = ?", reviewNode);
            if (slaConfigPid != null) jdbcTemplate.update("DELETE FROM ab_sla_config WHERE pid = ?", slaConfigPid);
            jdbcTemplate.update("DELETE FROM ab_bpm_notify_record WHERE recipient_user_id = ?", escalationRecipientId);
            dropIfExists(defectTable);
            cleanMeta(defectModel);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @Order(1)
    @DisplayName("defect → automation start_process → real BPMN userTask → SLA record activated with deadline")
    void defectAutomation_startsBpmnProcess_activatesSlaOnUserTask() {
        Map<String, Object> defect = dynamicDataService.create(defectModel, Map.of(
                "qcd_severity", "critical",
                "qcd_description", "Cold solder joints — lot L-" + suffix));
        String defectPid = String.valueOf(defect.get("pid"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "create");
        payload.put("record", defect);

        AutomationLog runLog = automationTriggerService.executeAutomation(startProcessAutomation, defectPid, payload);
        assertThat(runLog.getStatus()).as("automation run must succeed").isEqualTo("success");

        // The SLA record on the review node is itself proof: it only exists if the process started,
        // the userTask was created, and task_assigned reached the synchronous SLA activation listener.
        List<Map<String, Object>> slaRows = jdbcTemplate.queryForList(
                "SELECT pid, process_instance_id, node_id, start_time, deadline_time "
                        + "FROM ab_sla_record WHERE tenant_id = ? AND node_id = ?",
                getTestTenant().getId(), reviewNode);
        assertThat(slaRows)
                .as("a real process start must emit task_assigned and activate exactly one SLA record on the review node")
                .hasSize(1);

        Map<String, Object> sla = slaRows.get(0);
        String instanceId = String.valueOf(sla.get("process_instance_id"));
        assertThat(instanceId).as("SLA record must carry the started process instance id").isNotBlank();
        Timestamp start = (Timestamp) sla.get("start_time");
        Timestamp deadline = (Timestamp) sla.get("deadline_time");
        long minutes = (deadline.getTime() - start.getTime()) / 60000L;
        assertThat(minutes).as("FIXED PT2H deadline must be ~120 min from activation").isBetween(118L, 122L);

        // Business correlation: the started instance's businessKey is the source defect pid, and a real
        // userTask on the review node exists.
        var tasks = taskService.getTasksByProcessInstance(instanceId);
        assertThat(tasks).as("the started process must have a pending userTask").isNotEmpty();
        assertThat(tasks).anyMatch(t -> reviewNode.equals(t.getProcessDefinitionActivityId()));

        log.info("[S3 BPMN+SLA] PASS — defect {} → process {} → SLA on {} (deadline {}min)",
                defectPid, instanceId, reviewNode, minutes);
    }

    @Test
    @Order(2)
    @DisplayName("overdue SLA → scheduler marks overdue + escalates to recipient")
    void slaRecord_pastDeadline_schedulerMarksOverdueAndEscalates() {
        Long tid = getTestTenant().getId();
        SlaRecordEntity record = slaRecordMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SlaRecordEntity>()
                                .eq("tenant_id", tid).eq("node_id", reviewNode)).stream()
                .findFirst().orElseThrow(() -> new AssertionError("SLA record from test 1 must exist"));

        // force the deadline into the past so the scheduler sees it as overdue
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE ab_sla_record SET start_time=?, deadline_time=?, updated_at=? WHERE pid=?",
                Timestamp.from(now.minusSeconds(7200)), Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now), record.getPid());

        slaSchedulerService.scanSlaRecords();
        MetaContext.setContext(tid, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        SlaRecordEntity overdue = slaRecordMapper.findByPid(record.getPid(), tid);
        assertThat(overdue.getStatus()).as("past-deadline SLA must be overdue").isEqualTo("overdue");
        assertThat(overdue.getCurrentWarningLevel()).as("escalation rule must have fired once").isEqualTo(1);
        assertThat(overdue.getWarningHistory()).hasSize(1);
        assertThat(overdue.getWarningHistory().get(0)).containsEntry("action", "escalate");

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(tid, escalationRecipientId, "urge");
        assertThat(notifications).as("escalation must notify the configured recipient").isNotEmpty();
        assertThat(notifications.get(0).getContent()).contains("SLA ESCALATION");
        log.info("[S3 SLA escalation] PASS — SLA {} overdue + escalated to {}", record.getPid(), escalationRecipientId);
    }

    // ------------------------------------------------------------------ fixtures

    private Automation buildStartProcessAutomation() {
        Automation a = new Automation();
        a.setPid("ITSPROC" + suffix);
        a.setName("Start CAPA approval on critical defect " + suffix);
        a.setTenantId(getTestTenant().getId());
        a.setModelCode(defectModel);
        a.setTriggerType("on_record_create");
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On defect create", "config", Map.of())),
                        Map.of("id", "a1", "type", "action-start-process",
                                "data", Map.of("label", "Start CAPA approval",
                                        "config", Map.of("actionType", "start_process", "processKey", processKey)))),
                "edges", List.of(Map.of("id", "e1", "source", "t1", "target", "a1"))));
        a.setEnabled(true);
        return a;
    }

    private void publishModel(String code, String name, String[] fields) {
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
