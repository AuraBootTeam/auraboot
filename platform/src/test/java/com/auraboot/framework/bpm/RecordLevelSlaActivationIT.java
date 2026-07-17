package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaSchedulerService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 — record-level SLA activation (platform gap fix, campaign).
 *
 * <p>Before this change, SLA records could only be activated by a BPM {@code task_assigned} event
 * (SlaConfig {@code targetType ∈ {PROCESS, NODE, TASK}}). A plain dynamic-record create — e.g. a CRM
 * complaint that needs a "respond within 2h" SLA the moment it is logged — had NO activation path.
 *
 * <p>This IT pins the new capability: an SLA config with {@code targetType="RECORD"} +
 * {@code targetKey=<modelCode>} must, when a record of that model is created via the normal
 * {@code DynamicDataService.create} pipeline, materialize an {@code ab_sla_record} with the computed
 * deadline (reusing the existing FIXED/RULE/decision deadline engine) — and the scheduler must then
 * mark it overdue and escalate, exactly like the BPM-node path.
 *
 * <p>Self-contained: synthetic model + a RECORD SLA config, real PostgreSQL, no plugin import.
 */
@Slf4j
@DisplayName("F3: record create → RECORD-type SLA activation + escalation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordLevelSlaActivationIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private CommandService commandService;
    @Autowired private CommandExecutor commandExecutor;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private SlaSchedulerService slaSchedulerService;
    @Autowired private SlaRecordMapper slaRecordMapper;
    @Autowired private BpmNotifyRecordMapper notifyRecordMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String complaintModel = "rsla_" + suffix;
    private final String complaintTable = "mt_" + complaintModel;
    private final String subjectField = "c_subject_" + suffix;
    private final String priorityField = "c_priority_" + suffix;
    private final String createCommandCode = "rsla:create_" + suffix;
    private final long escalationRecipientId = 960000000L + Math.floorMod(System.nanoTime(), 1_000_000L);

    private String slaConfigPid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(complaintTable);
        cleanMeta(complaintModel);
        publishModel(complaintModel, "Record-SLA complaint", new String[]{subjectField, priorityField});
        publishCreateCommand();

        // RECORD-level SLA: respond within PT2H of creating any record of this model; escalate at 50%.
        var cfg = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "Complaint response SLA " + suffix, "RECORD", complaintModel, null,
                "FIXED", "PT2H", null,
                List.of(Map.of("threshold", "50%", "action", "escalate",
                        "recipients", "userId:" + escalationRecipientId)),
                null, null, null, null, null));
        slaConfigPid = cfg.getPid();
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            jdbcTemplate.update("DELETE FROM ab_sla_record WHERE node_id = ?", complaintModel);
            if (slaConfigPid != null) jdbcTemplate.update("DELETE FROM ab_sla_config WHERE pid = ?", slaConfigPid);
            jdbcTemplate.update("DELETE FROM ab_bpm_notify_record WHERE recipient_user_id = ?", escalationRecipientId);
            jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", createCommandCode);
            dropIfExists(complaintTable);
            cleanMeta(complaintModel);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @Order(1)
    @DisplayName("creating a record of the SLA-bound model activates an ab_sla_record with deadline")
    void recordCreate_activatesRecordLevelSla() {
        Map<String, Object> rec = MetaContext.runWithoutDataPermission(() ->
                dynamicDataService.create(complaintModel, Map.of(
                        subjectField, "device black screen", priorityField, "high")));
        String recordPid = String.valueOf(rec.get("pid"));

        List<Map<String, Object>> slaRows = jdbcTemplate.queryForList(
                "SELECT pid, process_instance_id, node_id, start_time, deadline_time "
                        + "FROM ab_sla_record WHERE tenant_id = ? AND node_id = ? AND process_instance_id = ?",
                getTestTenant().getId(), complaintModel, recordPid);

        assertThat(slaRows)
                .as("creating a record of a RECORD-SLA-bound model must activate exactly one SLA record")
                .hasSize(1);

        Map<String, Object> sla = slaRows.get(0);
        Timestamp start = (Timestamp) sla.get("start_time");
        Timestamp deadline = (Timestamp) sla.get("deadline_time");
        long minutes = (deadline.getTime() - start.getTime()) / 60000L;
        assertThat(minutes).as("FIXED PT2H deadline ≈ 120 min").isBetween(118L, 122L);
        assertThat(sla.get("process_instance_id"))
                .as("the SLA record links to the source record pid").isEqualTo(recordPid);

        log.info("[F3 record SLA] PASS — record {} → SLA on model {} (deadline {}min)", recordPid, complaintModel, minutes);
    }

    @Test
    @Order(2)
    @DisplayName("command-created record of the SLA-bound model activates an ab_sla_record")
    void commandCreate_activatesRecordLevelSla() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Map.of(subjectField, "command-created issue", priorityField, "urgent"));
        request.setClientRequestId(UUID.randomUUID().toString());
        // Deliberately do not set request.operationType. The command event must
        // derive create semantics from executionConfig.type.

        CommandExecuteResult result = commandExecutor.execute(createCommandCode, request);
        assertThat(result.getData()).containsKey("recordPid");
        String recordPid = String.valueOf(result.getData().get("recordPid"));

        List<Map<String, Object>> slaRows = jdbcTemplate.queryForList(
                "SELECT pid, process_instance_id, node_id, start_time, deadline_time "
                        + "FROM ab_sla_record WHERE tenant_id = ? AND node_id = ? AND process_instance_id = ?",
                getTestTenant().getId(), complaintModel, recordPid);

        assertThat(slaRows)
                .as("command-created records must also activate RECORD-level SLA through the command event bridge")
                .hasSize(1);

        log.info("[F3 command record SLA] PASS — command {} created record {} → SLA on model {}",
                createCommandCode, recordPid, complaintModel);
    }

    @Test
    @Order(3)
    @DisplayName("overdue record-level SLA → scheduler marks overdue + escalates")
    void recordSla_pastDeadline_schedulerMarksOverdueAndEscalates() {
        Long tid = getTestTenant().getId();
        SlaRecordEntity record = slaRecordMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SlaRecordEntity>()
                                .eq("tenant_id", tid).eq("node_id", complaintModel)).stream()
                .findFirst().orElseThrow(() -> new AssertionError("record-level SLA record from test 1 must exist"));

        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE ab_sla_record SET start_time=?, deadline_time=?, updated_at=? WHERE pid=?",
                Timestamp.from(now.minusSeconds(7200)), Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now), record.getPid());

        slaSchedulerService.scanSlaRecords();
        MetaContext.setContext(tid, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        SlaRecordEntity overdue = slaRecordMapper.findByPid(record.getPid(), tid);
        assertThat(overdue.getStatus()).as("past-deadline record SLA must be overdue").isEqualTo("overdue");
        assertThat(overdue.getCurrentWarningLevel()).as("escalation fired once").isEqualTo(1);
        assertThat(overdue.getWarningHistory()).hasSize(1);
        assertThat(overdue.getWarningHistory().get(0)).containsEntry("action", "escalate");

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(tid, escalationRecipientId, "urge");
        assertThat(notifications).as("escalation notifies the configured recipient").isNotEmpty();
        assertThat(notifications.get(0).getContent()).contains("SLA ESCALATION");
        log.info("[F3 record SLA escalation] PASS — SLA {} overdue + escalated", record.getPid());
    }

    // ------------------------------------------------------------------ fixtures

    private void publishCreateCommand() throws Exception {
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "create");
        execConfig.put("inputFields", List.of(subjectField, priorityField));

        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(createCommandCode);
        request.setDisplayName("Record SLA create command " + suffix);
        request.setDescription("IT command that creates record-SLA source records");
        request.setModelCode(complaintModel);
        request.setInputSchema("{\"type\":\"object\"}");
        request.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        request.setCmdRiskLevel("L1");

        var created = commandService.create(request);
        commandService.publish(created.getPid());
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
            Long tenantId = getTestTenant().getId();
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                            + "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE tenant_id = ? AND code IN (?, ?)",
                    tenantId, subjectField, priorityField);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?", modelCode, tenantId);
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
