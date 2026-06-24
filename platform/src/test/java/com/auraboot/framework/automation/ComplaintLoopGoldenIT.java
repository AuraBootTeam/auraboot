package com.auraboot.framework.automation;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.bpm.service.SlaConfigService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * S1 — customer-service complaint closed-loop golden (campaign): a complaint logged via the normal
 * record-create pipeline must, with no further action, BOTH (a) get auto-assigned by an enabled
 * automation and (b) start its response SLA. This composes two platform capabilities into the S1
 * business loop the design doc describes ("投诉登记 → 自动指派 → SLA"):
 * <ul>
 *   <li>auto-assign = an {@code on_record_create} automation whose {@code update_record} action sets
 *       the assignee on the just-created complaint (async dispatch → asserted with Awaitility);</li>
 *   <li>response SLA = a {@code targetType="RECORD"} SLA config (the F3 capability) activating an
 *       {@code ab_sla_record} synchronously on create.</li>
 * </ul>
 *
 * <p>Self-contained: synthetic complaint model, real PostgreSQL, no plugin import.
 */
@Slf4j
@DisplayName("S1: complaint create → auto-assign automation + response SLA (record-level)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ComplaintLoopGoldenIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private AutomationService automationService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String complaintModel = "csc_" + suffix;
    private final String complaintTable = "mt_" + complaintModel;
    private String automationPid;
    private String slaConfigPid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(complaintTable);
        cleanMeta(complaintModel);
        publishModel(complaintModel, "CS complaint", new String[]{"c_subject", "c_assignee"});

        // Auto-assign: on complaint create, set the assignee to the CS queue owner.
        AutomationCreateRequest aReq = new AutomationCreateRequest();
        aReq.setName("Auto-assign complaint " + suffix);
        aReq.setModelCode(complaintModel);
        aReq.setTriggerType("on_record_create");
        aReq.setActions(List.of(AutomationAction.builder()
                .type("update_record")
                .config(Map.of("modelCode", complaintModel, "recordPid", "${recordPid}",
                        "fields", Map.of("c_assignee", "cs-team-a")))
                .build()));
        aReq.setEnabled(false);
        AutomationDTO auto = automationService.create(aReq);
        automationService.enable(auto.getPid());
        automationPid = auto.getPid();

        // Response SLA on create (F3 record-level): respond within PT2H.
        var sla = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "Complaint response SLA " + suffix, "RECORD", complaintModel, null,
                "FIXED", "PT2H", null, null, null, null, null, null, null));
        slaConfigPid = sla.getPid();
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            if (automationPid != null) automationService.delete(automationPid);
            jdbcTemplate.update("DELETE FROM ab_sla_record WHERE node_id = ?", complaintModel);
            if (slaConfigPid != null) jdbcTemplate.update("DELETE FROM ab_sla_config WHERE pid = ?", slaConfigPid);
            dropIfExists(complaintTable);
            cleanMeta(complaintModel);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @DisplayName("logging a complaint auto-assigns it and starts its response SLA")
    void complaintCreate_autoAssigns_andStartsResponseSla() {
        Map<String, Object> complaint = dynamicDataService.create(complaintModel, Map.of(
                "c_subject", "device returns black screen on boot"));
        String complaintPid = String.valueOf(complaint.get("pid"));

        // (b) response SLA activated synchronously on create (F3)
        List<Map<String, Object>> slaRows = jdbcTemplate.queryForList(
                "SELECT pid, deadline_time FROM ab_sla_record WHERE tenant_id = ? AND node_id = ? AND process_instance_id = ?",
                getTestTenant().getId(), complaintModel, complaintPid);
        assertThat(slaRows).as("complaint create must start a record-level response SLA").hasSize(1);
        assertThat(slaRows.get(0).get("deadline_time")).as("SLA deadline set").isNotNull();

        // (a) auto-assign automation fires asynchronously → poll for the assignee
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    String assignee = jdbcTemplate.queryForObject(
                            "SELECT c_assignee FROM " + complaintTable + " WHERE pid = ?", String.class, complaintPid);
                    assertThat(assignee).as("auto-assign automation must set the assignee").isEqualTo("cs-team-a");
                });

        // automation run logged success
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long ok = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ab_automation_log WHERE automation_id = ? AND status = 'success'",
                    Long.class, automationPid);
            assertThat(ok).as("a successful automation run must be logged").isGreaterThanOrEqualTo(1L);
        });

        log.info("[S1 complaint loop] PASS — complaint {} auto-assigned + response SLA started", complaintPid);
    }

    // ------------------------------------------------------------------ fixtures

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
