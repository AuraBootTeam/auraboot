package com.auraboot.framework.automation;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.bpm.AutomationProcessRuntime;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleMappingTarget;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.AddFieldRequest;
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
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationSendNotificationIntegrationTest extends BaseIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private AutomationProcessRuntime runtime;
    @Autowired private AutomationTriggerService automationTriggerService;
    @Autowired private AutomationLogMapper automationLogMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DrtDefinitionService drtDefinitionService;
    @Autowired private DecisionVersionService drtVersionService;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;

    @Test
    void roleRecipientNotificationAction_persistsInAppNotification_viaRealAutomationRuntime() {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String title = "Automation role notification " + suffix;
        Automation automation = buildAutomation("ITAUTONOTIFY" + suffix, title);
        runtime.deploy(automation);

        AutomationLog runLog = automationTriggerService.executeAutomation(
                automation,
                "REQ-LONG-LEAVE-SAMPLE",
                Map.of(
                        "event", "manual",
                        "record", Map.of("wd_req_days", 5)));

        assertThat(runLog.getStatus()).isEqualTo("success");
        assertNotificationActionResult(runLog);

        Map<String, Object> notification = jdbcTemplate.queryForMap("""
                select title, content, source_type, source_id
                from ab_notification
                where tenant_id = ? and user_id = ? and title = ?
                order by created_at desc
                limit 1
                """, getTestTenant().getId(), getTestUser().getId(), title);

        assertThat(notification.get("title")).isEqualTo(title);
        assertThat(notification.get("content")).isEqualTo("Long leave request needs manager attention");
        assertThat(notification.get("source_type")).isEqualTo("automation");
        assertThat(notification.get("source_id")).isEqualTo(automation.getPid());

        AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
        assertThat(persistedLog).isNotNull();
        assertNotificationActionResult(persistedLog);

        AutomationLog byPid = automationLogMapper.findByPid(runLog.getPid());
        assertThat(byPid).isNotNull();
        assertNotificationActionResult(byPid);

        List<AutomationLog> byAutomation = automationLogMapper.findByAutomationId(automation.getPid(), 5);
        assertThat(byAutomation).isNotEmpty();
        AutomationLog latest = byAutomation.getFirst();
        assertThat(latest.getPid()).isEqualTo(runLog.getPid());
        assertNotificationActionResult(latest);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addCommentAction_persistsRecordComment_viaRealAutomationRuntime() {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String recordPid = "ORDER-COMMENT-" + suffix;
        String contentTemplate = "Automation comment " + suffix + " for ${recordPid}";
        Automation automation = buildAddCommentAutomation("ITAUTOCOMMENT" + suffix, contentTemplate);
        runtime.deploy(automation);

        AutomationLog runLog = automationTriggerService.executeAutomation(
                automation,
                recordPid,
                Map.of(
                        "event", "manual",
                        "record", Map.of("title", "Comment target order")));

        assertThat(runLog.getStatus()).isEqualTo("success");
        Map<String, Object> actionPayload = assertAddCommentActionResult(runLog, recordPid);
        String commentPid = String.valueOf(actionPayload.get("commentPid"));

        Map<String, Object> comment = jdbcTemplate.queryForMap("""
                select pid, model_code, record_pid, content, mentions, created_by
                from ab_record_comment
                where tenant_id = ? and pid = ?
                  and (deleted_flag = false or deleted_flag is null)
                """, getTestTenant().getId(), commentPid);

        assertThat(comment.get("pid")).isEqualTo(commentPid);
        assertThat(comment.get("model_code")).isEqualTo("e2et_order");
        assertThat(comment.get("record_pid")).isEqualTo(recordPid);
        assertThat(comment.get("content")).isEqualTo("Automation comment " + suffix + " for " + recordPid);
        assertThat(comment.get("mentions")).isEqualTo("ROLE:" + getTestRole().getCode());
        assertThat(comment.get("created_by"))
                .as("SmartEngine automation actions must run as the restricted automation principal, not the triggering admin user")
                .isEqualTo("0");

        AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
        assertThat(persistedLog).isNotNull();
        assertAddCommentActionResult(persistedLog, recordPid);

        AutomationLog byPid = automationLogMapper.findByPid(runLog.getPid());
        assertThat(byPid).isNotNull();
        assertAddCommentActionResult(byPid, recordPid);

        List<AutomationLog> byAutomation = automationLogMapper.findByAutomationId(automation.getPid(), 5);
        assertThat(byAutomation).isNotEmpty();
        assertThat(byAutomation.getFirst().getPid()).isEqualTo(runLog.getPid());
        assertAddCommentActionResult(byAutomation.getFirst(), recordPid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTaskAction_persistsInboxTask_viaRealAutomationRuntime() throws Exception {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String recordPid = "ORDER-TASK-" + suffix;
        String titleTemplate = "Automation task " + suffix + " for ${recordPid}";
        Automation automation = buildCreateTaskAutomation("ITAUTOTASK" + suffix, titleTemplate);
        runtime.deploy(automation);

        AutomationLog runLog = automationTriggerService.executeAutomation(
                automation,
                recordPid,
                Map.of(
                        "event", "manual",
                        "record", Map.of("title", "Task target order")));

        assertThat(runLog.getStatus()).isEqualTo("success");
        Map<String, Object> actionPayload = assertCreateTaskActionResult(runLog, recordPid);
        List<?> inboxItemIds = (List<?>) actionPayload.get("inboxItemIds");
        Long inboxItemId = ((Number) inboxItemIds.getFirst()).longValue();

        Map<String, Object> task = jdbcTemplate.queryForMap("""
                select id, user_id, item_type, title, subtitle, priority, status, source_type,
                       source_id, model_code, record_pid, deep_link, card_payload
                from ab_inbox_item
                where tenant_id = ? and id = ?
                """, getTestTenant().getId(), inboxItemId);

        assertThat(((Number) task.get("id")).longValue()).isEqualTo(inboxItemId);
        assertThat(((Number) task.get("user_id")).longValue()).isEqualTo(getTestUser().getId());
        assertThat(task.get("item_type")).isEqualTo("task");
        assertThat(task.get("title")).isEqualTo("Automation task " + suffix + " for " + recordPid);
        assertThat(task.get("subtitle")).isEqualTo("Created by automation runtime");
        assertThat(task.get("priority")).isEqualTo("high");
        assertThat(task.get("status")).isEqualTo("pending");
        assertThat(task.get("source_type")).isEqualTo("automation");
        assertThat(task.get("source_id")).isEqualTo(automation.getPid());
        assertThat(task.get("model_code")).isEqualTo("e2et_order");
        assertThat(task.get("record_pid")).isEqualTo(recordPid);
        assertThat(task.get("deep_link")).isEqualTo("/p/e2et_order/view/" + recordPid);

        Map<String, Object> cardPayload = JSON.readValue(String.valueOf(task.get("card_payload")), Map.class);
        assertThat(cardPayload)
                .containsEntry("actionType", "create_task")
                .containsEntry("automationPid", automation.getPid())
                .containsEntry("title", "Automation task " + suffix + " for " + recordPid)
                .containsEntry("message", "Created by automation runtime")
                .containsEntry("modelCode", "e2et_order")
                .containsEntry("recordPid", recordPid)
                .containsEntry("dueDate", "2026-07-15T00:00:00Z");

        AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
        assertThat(persistedLog).isNotNull();
        assertCreateTaskActionResult(persistedLog, recordPid);

        AutomationLog byPid = automationLogMapper.findByPid(runLog.getPid());
        assertThat(byPid).isNotNull();
        assertCreateTaskActionResult(byPid, recordPid);

        List<AutomationLog> byAutomation = automationLogMapper.findByAutomationId(automation.getPid(), 5);
        assertThat(byAutomation).isNotEmpty();
        assertThat(byAutomation.getFirst().getPid()).isEqualTo(runLog.getPid());
        assertCreateTaskActionResult(byAutomation.getFirst(), recordPid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ccTaskAction_persistsInboxMention_viaRealAutomationRuntime() throws Exception {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String recordPid = "ORDER-CC-" + suffix;
        String messageTemplate = "Automation CC " + suffix + " for ${recordPid}";
        Automation automation = buildCcTaskAutomation("ITAUTOCC" + suffix, messageTemplate);
        runtime.deploy(automation);

        AutomationLog runLog = automationTriggerService.executeAutomation(
                automation,
                recordPid,
                Map.of(
                        "event", "manual",
                        "record", Map.of("title", "CC target order")));

        assertThat(runLog.getStatus()).isEqualTo("success");
        Map<String, Object> actionPayload = assertCcTaskActionResult(runLog, recordPid);
        List<?> inboxItemIds = (List<?>) actionPayload.get("inboxItemIds");
        Long inboxItemId = ((Number) inboxItemIds.getFirst()).longValue();

        Map<String, Object> mention = jdbcTemplate.queryForMap("""
                select id, user_id, item_type, title, subtitle, priority, status, source_type,
                       source_id, model_code, record_pid, deep_link, card_payload
                from ab_inbox_item
                where tenant_id = ? and id = ?
                """, getTestTenant().getId(), inboxItemId);

        assertThat(((Number) mention.get("id")).longValue()).isEqualTo(inboxItemId);
        assertThat(((Number) mention.get("user_id")).longValue()).isEqualTo(getTestUser().getId());
        assertThat(mention.get("item_type")).isEqualTo("mention");
        assertThat(mention.get("title")).isEqualTo("任务抄送");
        assertThat(mention.get("subtitle")).isEqualTo("Automation CC " + suffix + " for " + recordPid);
        assertThat(mention.get("priority")).isEqualTo("normal");
        assertThat(mention.get("status")).isEqualTo("pending");
        assertThat(mention.get("source_type")).isEqualTo("automation");
        assertThat(mention.get("source_id")).isEqualTo(automation.getPid());
        assertThat(mention.get("model_code")).isEqualTo("e2et_order");
        assertThat(mention.get("record_pid")).isEqualTo(recordPid);
        assertThat(mention.get("deep_link")).isEqualTo("/p/e2et_order/view/" + recordPid);

        Map<String, Object> cardPayload = JSON.readValue(String.valueOf(mention.get("card_payload")), Map.class);
        assertThat(cardPayload)
                .containsEntry("actionType", "cc_task")
                .containsEntry("automationPid", automation.getPid())
                .containsEntry("title", "任务抄送")
                .containsEntry("message", "Automation CC " + suffix + " for " + recordPid)
                .containsEntry("modelCode", "e2et_order")
                .containsEntry("recordPid", recordPid);

        AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
        assertThat(persistedLog).isNotNull();
        assertCcTaskActionResult(persistedLog, recordPid);

        AutomationLog byPid = automationLogMapper.findByPid(runLog.getPid());
        assertThat(byPid).isNotNull();
        assertCcTaskActionResult(byPid, recordPid);

        List<AutomationLog> byAutomation = automationLogMapper.findByAutomationId(automation.getPid(), 5);
        assertThat(byAutomation).isNotEmpty();
        assertThat(byAutomation.getFirst().getPid()).isEqualTo(runLog.getPid());
        assertCcTaskActionResult(byAutomation.getFirst(), recordPid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRecordAction_mutatesTriggerRecord_viaRealAutomationRuntime() {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String modelCode = "it_auto_upd_" + suffix.toLowerCase();
        String tableName = "mt_" + modelCode;
        String seedFieldCode = "seed_" + suffix.toLowerCase();
        String titleFieldCode = "title_" + suffix.toLowerCase();
        String remarkFieldCode = "remark_" + suffix.toLowerCase();
        String updatedTitle = "Automation update " + suffix;
        String updatedRemark = "Updated by automation runtime " + suffix;
        Model model = null;
        Field seedField = null;

        try {
            model = buildTransientModel(modelCode);
            metaModelMapper.insert(model);
            seedField = buildTransientField(seedFieldCode);
            metaFieldMapper.insert(seedField);
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setTenantId(getTestTenant().getId());
            binding.setModelId(model.getId());
            binding.setFieldId(seedField.getId());
            binding.setFieldOrder(1);
            bindingMapper.insert(binding);

            metaModelService.publish(model.getPid(), "automation update_record IT fixture");
            metaFieldService.addToModel(AddFieldRequest.builder()
                    .modelCode(modelCode).code(titleFieldCode).dataType("string")
                    .displayName("Title").tenantId(getTestTenant().getId()).build());
            metaFieldService.addToModel(AddFieldRequest.builder()
                    .modelCode(modelCode).code(remarkFieldCode).dataType("string")
                    .displayName("Remark").tenantId(getTestTenant().getId()).build());
            grantModelRuntimePermissions(modelCode);

            Map<String, Object> created = dynamicDataService.create(modelCode, Map.of(
                    seedFieldCode, "v1",
                    titleFieldCode, "Automation update seed " + suffix,
                    remarkFieldCode, "before"));
            String recordPid = String.valueOf(created.get("pid"));
            assertThat(recordPid).isNotBlank();

            Automation automation = buildUpdateRecordAutomation(
                    "ITAUTOUPDATE" + suffix,
                    modelCode,
                    titleFieldCode,
                    remarkFieldCode,
                    updatedTitle,
                    updatedRemark);
            runtime.deploy(automation);

            AutomationLog runLog = automationTriggerService.executeAutomation(
                    automation,
                    recordPid,
                    Map.of(
                            "event", "manual",
                            "record", Map.of("title", "Update target order")));

            assertThat(runLog.getStatus()).isEqualTo("success");
            assertUpdateRecordActionResult(runLog, recordPid, titleFieldCode, remarkFieldCode);

            Map<String, Object> updated = jdbcTemplate.queryForMap("""
                    select pid, %s, %s
                    from %s
                    where tenant_id = ? and pid = ?
                    """.formatted(titleFieldCode, remarkFieldCode, tableName), getTestTenant().getId(), recordPid);
            assertThat(updated.get("pid")).isEqualTo(recordPid);
            assertThat(updated.get(titleFieldCode)).isEqualTo(updatedTitle);
            assertThat(updated.get(remarkFieldCode)).isEqualTo(updatedRemark);

            AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
            assertThat(persistedLog).isNotNull();
            assertUpdateRecordActionResult(persistedLog, recordPid, titleFieldCode, remarkFieldCode);

            AutomationLog byPid = automationLogMapper.findByPid(runLog.getPid());
            assertThat(byPid).isNotNull();
            assertUpdateRecordActionResult(byPid, recordPid, titleFieldCode, remarkFieldCode);

            List<AutomationLog> byAutomation = automationLogMapper.findByAutomationId(automation.getPid(), 5);
            assertThat(byAutomation).isNotEmpty();
            assertThat(byAutomation.getFirst().getPid()).isEqualTo(runLog.getPid());
            assertUpdateRecordActionResult(byAutomation.getFirst(), recordPid, titleFieldCode, remarkFieldCode);
        } finally {
            try { dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName); } catch (Exception ignore) { }
            try { if (model != null) bindingMapper.deleteByModelId(model.getId()); } catch (Exception ignore) { }
            try { if (seedField != null) metaFieldMapper.deleteById(seedField.getId()); } catch (Exception ignore) { }
            try { if (model != null) metaModelMapper.deleteById(model.getId()); } catch (Exception ignore) { }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleBindingOutputDrivesNotificationContent_andPersistsTraceViaManualRun() throws Exception {
        applyTestMetaContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String decisionCode = "auto_notify_route_" + suffix;
        String title = "Automation decision notification " + suffix;

        publishRoutingDecision(decisionCode);
        Automation automation = buildRuleBindingNotificationAutomation(
                "ITAUTORULE" + suffix,
                title,
                decisionCode);
        runtime.deploy(automation);

        AutomationLog runLog = automationTriggerService.executeAutomation(
                automation,
                "ORDER-RULE-" + suffix,
                Map.of(
                        "event", "manual",
                        "record", Map.of("amount", 25000, "title", "高价值订单")));

        assertThat(runLog.getStatus()).isEqualTo("success");
        assertNotificationActionResult(runLog);

        Map<String, Object> notification = jdbcTemplate.queryForMap("""
                select title, content, source_type, source_id
                from ab_notification
                where tenant_id = ? and user_id = ? and title = ?
                order by created_at desc
                limit 1
                """, getTestTenant().getId(), getTestUser().getId(), title);

        assertThat(notification.get("content"))
                .as("Automation action payload must use decision.outputs from the rule binding")
                .isEqualTo("Route DIRECTOR: High value order");
        assertThat(notification.get("source_id")).isEqualTo(automation.getPid());

        AutomationLog persistedLog = automationLogMapper.selectById(runLog.getId());
        assertThat(persistedLog).isNotNull();
        Map<String, Object> decision = (Map<String, Object>) persistedLog.getTriggerPayload().get("decision");
        assertThat(decision)
                .as("Automation log must expose the decision trace for the UX trace panel")
                .containsEntry("decisionCode", decisionCode)
                .containsEntry("matched", true)
                .containsEntry("status", "MATCHED");
        Map<String, Object> outputs = (Map<String, Object>) decision.get("outputs");
        assertThat(outputs)
                .containsEntry("route", "DIRECTOR")
                .containsEntry("message", "High value order");

        String traceId = String.valueOf(decision.get("traceId"));
        assertThat(traceId).isNotBlank();
        Integer decisionLogCount = jdbcTemplate.queryForObject("""
                select count(*) from ab_drt_log
                where tenant_id = ? and trace_id = ? and decision_code = ?
                """, Integer.class, getTestTenant().getId(), traceId, decisionCode);
        assertThat(decisionLogCount).isEqualTo(1);

        List<Map<String, Object>> nodeRows = jdbcTemplate.queryForList("""
                select node_id, status
                from ab_automation_node_execution
                where tenant_id = ? and automation_log_id = ?
                order by id asc
                """, getTestTenant().getId(), runLog.getId());
        assertThat(nodeRows)
                .as("runtime overlay must show the notification node completed")
                .anySatisfy(row -> {
                    assertThat(row.get("node_id")).isEqualTo("notify");
                    assertThat(row.get("status")).isEqualTo("completed");
                });
    }

    @SuppressWarnings("unchecked")
    private void assertNotificationActionResult(AutomationLog log) {
        assertThat(log.getActionResults())
                .as("automation log must expose per-action results for the UX result panel")
                .hasSize(1);
        AutomationLog.ActionResult result = log.getActionResults().getFirst();
        assertThat(result.getSequence()).isEqualTo(1);
        assertThat(result.getActionType()).isEqualTo("send_notification");
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("type", "in_app")
                .containsEntry("sentCount", 1)
                .containsEntry("recipientCount", 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertAddCommentActionResult(AutomationLog log, String recordPid) {
        assertThat(log.getActionResults())
                .as("automation log must expose add_comment result for the UX result panel")
                .hasSize(1);
        AutomationLog.ActionResult result = log.getActionResults().getFirst();
        assertThat(result.getSequence()).isEqualTo(1);
        assertThat(result.getActionType()).isEqualTo("add_comment");
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("modelCode", "e2et_order")
                .containsEntry("recordPid", recordPid);
        assertThat(payload.get("commentPid")).isInstanceOf(String.class);
        assertThat((String) payload.get("commentPid")).isNotBlank();
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertCreateTaskActionResult(AutomationLog log, String recordPid) {
        assertThat(log.getActionResults())
                .as("automation log must expose create_task result for the UX result panel")
                .hasSize(1);
        AutomationLog.ActionResult result = log.getActionResults().getFirst();
        assertThat(result.getSequence()).isEqualTo(1);
        assertThat(result.getActionType()).isEqualTo("create_task");
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("itemType", "task")
                .containsEntry("createdCount", 1)
                .containsEntry("modelCode", "e2et_order")
                .containsEntry("recordPid", recordPid);
        assertThat((List<Long>) payload.get("assigneeUserIds")).containsExactly(getTestUser().getId());
        assertThat((List<?>) payload.get("inboxItemIds")).hasSize(1);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertCcTaskActionResult(AutomationLog log, String recordPid) {
        assertThat(log.getActionResults())
                .as("automation log must expose cc_task result for the UX result panel")
                .hasSize(1);
        AutomationLog.ActionResult result = log.getActionResults().getFirst();
        assertThat(result.getSequence()).isEqualTo(1);
        assertThat(result.getActionType()).isEqualTo("cc_task");
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "mention")
                .containsEntry("ccCount", 1)
                .containsEntry("modelCode", "e2et_order")
                .containsEntry("recordPid", recordPid);
        assertThat((List<Long>) payload.get("targetUserIds")).containsExactly(getTestUser().getId());
        assertThat((List<?>) payload.get("inboxItemIds")).hasSize(1);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertUpdateRecordActionResult(AutomationLog log, String recordPid,
                                                               String... updatedFieldCodes) {
        assertThat(log.getActionResults())
                .as("automation log must expose update_record result for the UX result panel")
                .hasSize(1);
        AutomationLog.ActionResult result = log.getActionResults().getFirst();
        assertThat(result.getSequence()).isEqualTo(1);
        assertThat(result.getActionType()).isEqualTo("update_record");
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("actionType", "update_record")
                .containsEntry("recordPid", recordPid);
        assertThat((Iterable<Object>) payload.get("updatedFields"))
                .contains((Object[]) updatedFieldCodes);
        return payload;
    }

    private void publishRoutingDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("Automation notification routing decision");
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        drtDefinitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("DECISION_TABLE");
        ver.setRuntimeAdapter("PLATFORM_DECISION_TABLE");
        ver.setContentJson(JSON.readTree("""
                { "hitPolicy":"FIRST",
                  "inputs":[
                    {"id":"amount","label":"Order Amount","expr":{"type":"path","scope":"record","path":"data.amount","dataType":"decimal"}}],
                  "outputs":[
                    {"id":"route","label":"Route","dataType":"string"},
                    {"id":"message","label":"Message","dataType":"string"}],
                  "rules":[
                    {"ruleId":"high-value","priority":10,
                     "when":{"amount":{"operator":"GTE","value":10000}},
                     "then":{"route":"DIRECTOR","message":"High value order"}}],
                  "defaultOutput":{"route":"MANAGER","message":"Normal order"} }
                """));
        DrtVersionDTO draft = drtVersionService.createDraft(code, ver);
        DecisionValidateResult validation = drtVersionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains("record.data.amount");
        drtVersionService.publish(draft.getPid());
    }

    private Automation buildAutomation(String pid, String title) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation role notify " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode("wd_leave_request");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On leave request create", "config", Map.of())),
                        Map.of("id", "notify", "type", "action-send-notification",
                                "data", Map.of(
                                        "label", "Notify manager",
                                        "config", Map.of(
                                                "actionType", "send_notification",
                                                "notificationType", "in_app",
                                                "title", title,
                                                "content", "Long leave request needs manager attention",
                                                "recipients", "ROLE:" + getTestRole().getCode())))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "notify"))));
        return automation;
    }

    private Automation buildAddCommentAutomation(String pid, String contentTemplate) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation add comment " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode("e2et_order");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On order create", "config", Map.of())),
                        Map.of("id", "comment", "type", "action-add-comment",
                                "data", Map.of(
                                        "label", "Add record comment",
                                        "config", Map.of(
                                                "actionType", "add_comment",
                                                "content", contentTemplate,
                                                "mentions", "ROLE:" + getTestRole().getCode())))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "comment"))));
        return automation;
    }

    private Automation buildCreateTaskAutomation(String pid, String titleTemplate) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation create task " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode("e2et_order");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On order create", "config", Map.of())),
                        Map.of("id", "task", "type", "action-create-task",
                                "data", Map.of(
                                        "label", "Create inbox task",
                                        "config", Map.of(
                                                "actionType", "create_task",
                                                "assignee", "USER:" + getTestUser().getId(),
                                                "title", titleTemplate,
                                                "message", "Created by automation runtime",
                                                "priority", "high",
                                                "dueDate", "2026-07-15T00:00:00Z")))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "task"))));
        return automation;
    }

    private Automation buildUpdateRecordAutomation(String pid, String modelCode, String titleFieldCode,
                                                   String remarkFieldCode, String updatedTitle,
                                                   String updatedRemark) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation update record " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode(modelCode);
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On order create", "config", Map.of())),
                        Map.of("id", "update", "type", "action-update-record",
                                "data", Map.of(
                                        "label", "Update trigger record",
                                        "config", Map.of(
                                                "actionType", "update_record",
                                                "modelCode", modelCode,
                                                "recordPid", "${recordPid}",
                                                "fields", Map.of(
                                                        titleFieldCode, updatedTitle,
                                                        remarkFieldCode, updatedRemark))))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "update"))));
        return automation;
    }

    private Model buildTransientModel(String code) {
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.DRAFT.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> values = new HashMap<>();
        values.put("displayName", "Automation update_record IT model");
        values.put("modelType", "entity");
        extension.setExtension(values);
        model.setExtension(extension);
        return model;
    }

    private Field buildTransientField(String code) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType("string");
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        field.setFeature(feature);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> values = new HashMap<>();
        values.put("displayName", code);
        extension.setExtension(values);
        field.setExtension(extension);
        return field;
    }

    private void grantModelRuntimePermissions(String modelCode) {
        for (String action : List.of("read", "create", "update")) {
            grantPermission(modelCode + ":" + action, "model", modelCode, action,
                    "Automation update_record " + action + " permission");
            grantPermission(modelCode + "." + action, "model", modelCode, action,
                    "Automation update_record " + action + " permission");
            grantPermission("model." + modelCode + "." + action, "model", modelCode, action,
                    "Automation update_record model " + action + " permission");
        }
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grantPermission(String code, String resourceType, String resourceCode,
                                 String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("integration_test");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }

        RolePermission rolePermission = new RolePermission();
        rolePermission.setPid(UniqueIdGenerator.generate());
        rolePermission.setRoleId(getTestRole().getId());
        rolePermission.setPermissionId(permission.getId());
        rolePermission.setGrantType("grant");
        rolePermission.setStatus("active");
        rolePermission.setDeletedFlag(false);
        rolePermission.setTenantId(getTestTenant().getId());
        rolePermission.setCreatedAt(Instant.now());
        rolePermission.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(rolePermission);
    }

    private Automation buildCcTaskAutomation(String pid, String messageTemplate) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation cc task " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode("e2et_order");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On order create", "config", Map.of())),
                        Map.of("id", "cc", "type", "action-cc-task",
                                "data", Map.of(
                                        "label", "CC inbox mention",
                                        "config", Map.of(
                                                "actionType", "cc_task",
                                                "target", "USER:" + getTestUser().getId(),
                                                "message", messageTemplate)))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "cc"))));
        return automation;
    }

    private Automation buildRuleBindingNotificationAutomation(String pid, String title, String decisionCode) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setName("Automation rule binding notify " + pid);
        automation.setTenantId(getTestTenant().getId());
        automation.setCreatedBy(getTestUser().getPid());
        automation.setModelCode("e2et_order");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setTriggerConfig(TriggerConfig.builder()
                .modelCode("e2et_order")
                .ruleBinding(new RuleConsumerBinding(
                        "AUTOMATION",
                        pid,
                        "trigger",
                        RuleBindingKind.DECISION_REF,
                        null,
                        new DecisionBinding(
                                decisionCode,
                                DecisionVersionPolicy.LATEST_PUBLISHED,
                                null,
                                null,
                                null,
                                List.of(new DecisionBinding.InputMapping(
                                        "amount",
                                        RuleValueSource.field(Scope.RECORD, "data.amount"))),
                                List.of(
                                        new DecisionBinding.OutputMapping(
                                                "route",
                                                new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "route")),
                                        new DecisionBinding.OutputMapping(
                                                "message",
                                                new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "message"))),
                                DecisionBinding.FallbackPolicy.failClosed(),
                                200,
                                DecisionBinding.TraceMode.ALWAYS,
                                true,
                                null,
                                null),
                        true))
                .build());
        automation.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "trigger-record-create",
                                "data", Map.of("label", "On order create", "config", Map.of())),
                        Map.of("id", "notify", "type", "action-send-notification",
                                "data", Map.of(
                                        "label", "Notify director",
                                        "config", Map.of(
                                                "actionType", "send_notification",
                                                "notificationType", "in_app",
                                                "title", title,
                                                "content", "Route ${decision.outputs.route}: ${decision.outputs.message}",
                                                "recipients", "ROLE:" + getTestRole().getCode())))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "notify"))));
        return automation;
    }
}
