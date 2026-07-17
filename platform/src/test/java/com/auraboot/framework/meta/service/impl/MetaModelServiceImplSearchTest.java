package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.listener.SlaActivationListener;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRiskDTO;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionResult;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionStatus;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.DDLPreviewResult;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelPublishReplayRequest;
import com.auraboot.framework.meta.dto.ModelPublishGovernanceDTO;
import com.auraboot.framework.meta.dto.ModelPublishReplayReportDTO;
import com.auraboot.framework.meta.dto.ModelPublishReplayStepDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldRuleSchemaBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaModelServiceImplSearchTest {

    @Mock
    private MetaModelMapper metaModelMapper;
    @Mock
    private MetaFieldMapper metaFieldMapper;
    @Mock
    private QueryBuilderService queryBuilderService;
    @Mock
    private MetaModelFieldBindingMapper fieldBindingMapper;
    @Mock
    private AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock
    private SchemaManagementService schemaManagementService;
    @Mock
    private DecisionImpactService decisionImpactService;
    @Mock
    private DecisionImpactAckService decisionImpactAckService;
    @Mock
    private DecisionEvaluationService decisionEvaluationService;
    @Mock
    private EventPolicyRuntimeService eventPolicyRuntimeService;
    @Mock
    private AutomationService automationService;
    @Mock
    private ProcessDeploymentService processDeploymentService;
    @Mock
    private BpmRuleBindingRuntimeService bpmRuleBindingRuntimeService;
    @Mock
    private SlaConfigService slaConfigService;
    @Mock
    private SlaActivationListener slaActivationListener;
    @Mock
    private SlaRecordService slaRecordService;
    @Mock
    private PermissionEvaluator permissionEvaluator;
    @Mock
    private RollUpFieldRegistry rollUpFieldRegistry;
    @Mock
    private MetaDefinitionCacheService metaDefinitionCacheService;

    @BeforeEach
    void setUpMetaContext() {
        MetaContext.setContext(1L, 100L, "test-user-pid", "tester");
    }

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void searchModelsLoadsFieldCountsInBatch() {
        Model first = model(11L, "batch_count_one");
        Model second = model(22L, "batch_count_two");
        when(metaModelMapper.countByKeyword(null, null, null, null, true)).thenReturn(2L);
        when(metaModelMapper.searchByKeyword(null, null, null, null, null, null, true, 0L, 20))
                .thenReturn(List.of(first, second));
        when(fieldBindingMapper.countUserFieldsByModelIds(List.of(11L, 22L)))
                .thenReturn(List.of(new MetaModelFieldBindingMapper.ModelFieldCount(11L, 3)));

        MetaModelServiceImpl service = new MetaModelServiceImpl(
                metaModelMapper,
                metaFieldMapper,
                queryBuilderService,
                fieldBindingMapper,
                autoPermissionAssignmentService,
                metaDefinitionCacheService
        );

        PageResult<MetaModelDTO> result = service.searchModels(
                1, 20, null, null, null, null, null, null, null, null, true);

        assertThat(result.getRecords())
                .extracting(MetaModelDTO::getFieldCount)
                .containsExactly(3, 0);
        verify(fieldBindingMapper).countUserFieldsByModelIds(List.of(11L, 22L));
        verify(fieldBindingMapper, never()).countUserFieldsByModelId(11L);
        verify(fieldBindingMapper, never()).countUserFieldsByModelId(22L);
    }

    @Test
    void convertToFieldDefinitionFlattensDynamicExtensionWrapperIntoExtraProps() {
        MetaModelServiceImpl service = new MetaModelServiceImpl(
                metaModelMapper,
                metaFieldMapper,
                queryBuilderService,
                fieldBindingMapper,
                autoPermissionAssignmentService,
                metaDefinitionCacheService
        );
        Map<String, Object> fieldPermission = Map.of(
                "view", List.of("low_field_viewer"),
                "edit", List.of("full_field_editor"));
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("extension", Map.of(
                "displayName", "开始日期",
                "fieldPermission", fieldPermission));
        extension.setDynamicProperty("description", "Dynamic wrapper must be flattened");
        Field field = new Field();
        field.setCode("wd_req_start_date");
        field.setDataType("date");
        field.setExtension(extension);

        FieldDefinition definition = ReflectionTestUtils.invokeMethod(
                service, "convertToFieldDefinition", field, 10);

        assertThat(definition).isNotNull();
        assertThat(definition.getDisplayName()).isEqualTo("开始日期");
        assertThat(definition.getDescription()).isEqualTo("Dynamic wrapper must be flattened");
        assertThat(definition.getExtraProps())
                .containsEntry("fieldPermission", fieldPermission)
                .containsEntry("displayName", "开始日期");
    }

    @Test
    void previewPublishDDLAttachesRuleCenterGovernance() {
        Model draft = draftModel("model_governance_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_governance_case ADD COLUMN amount numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(201L)));
        when(metaFieldMapper.findByIds(List.of(201L))).thenReturn(List.of(field(201L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpact(draft.getCode() + ".amount"));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 2), draft));

        DDLPreviewResult result = service().previewPublishDDL(draft.getPid());

        assertThat(result.getGovernance()).isNotNull();
        assertThat(result.getGovernance().getRequiresAcknowledgement()).isTrue();
        assertThat(result.getGovernance().getSchemaChangeKinds()).containsExactly("ADD_COLUMN");
        assertThat(result.getGovernance().getLatestPublishedVersion()).isEqualTo(2);
        assertThat(result.getGovernance().getFieldImpacts())
                .extracting(DecisionFieldImpactDTO::getFieldRef)
                .containsExactly(draft.getCode() + ".amount");
        assertThat(result.getGovernance().getMigrationPlan())
                .contains("Backfill new columns")
                .contains("Confirm Rule Center blast radius");
    }

    @Test
    void previewPublishDDLMapsLowCodeRecordFieldRefsBackToModelField() {
        Model draft = draftModel("lowcode_order_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("CREATE TABLE mt_lowcode_order_case (amount numeric)"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(204L)));
        when(metaFieldMapper.findByIds(List.of(204L))).thenReturn(List.of(field(204L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithoutReferences(draft.getCode() + ".amount"));
        when(decisionImpactService.getFieldImpact("record.data.amount"))
                .thenReturn(blockingImpact("record.data.amount"));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(draft));

        DDLPreviewResult result = service().previewPublishDDL(draft.getPid());

        assertThat(result.getGovernance()).isNotNull();
        assertThat(result.getGovernance().getRequiresAcknowledgement()).isTrue();
        assertThat(result.getGovernance().getFieldImpacts())
                .extracting(DecisionFieldImpactDTO::getFieldRef)
                .containsExactly(draft.getCode() + ".amount");
        assertThat(result.getGovernance().getFieldImpacts().get(0).getReferences())
                .extracting(DecisionImpactRefDTO::getTargetCode)
                .containsExactly("record.data.amount");
    }

    @Test
    void previewPublishDDLBuildsReplayPlanForKnownRuleConsumers() {
        Model draft = draftModel("model_replay_plan_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_plan_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(205L)));
        when(metaFieldMapper.findByIds(List.of(205L))).thenReturn(List.of(field(205L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("DECISION_VERSION", "approval_decision", "decision-v1", "dv-pid", "record.data.amount", "VERSION_RULES"),
                        ref("BPM_PROCESS", "approval_flow", "7", "bpm-pid", "record.data.amount", "GATEWAY_CONDITION"),
                        ref("SLA_RULE", "approval_sla", null, "sla-pid", "record.data.amount", "DEADLINE_RULE"),
                        ref("AUTOMATION", "notify_manager", null, "auto-pid", "record.data.amount", "RULE_BINDING"),
                        ref("EVENT_POLICY", "case.closed", "2", "event-pid", "record.data.amount", "VERSION_RULES"),
                        ref("PERMISSION_POLICY", "model.order.approve", null, "role-perm-pid", "record.data.amount", "ABAC_CONDITION")
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));

        DDLPreviewResult result = service().previewPublishDDL(draft.getPid());

        assertThat(result.getGovernance()).isNotNull();
        assertThat(result.getGovernance().getReplayPlan())
                .extracting(ModelPublishReplayStepDTO::getConsumerType)
                .containsExactly(
                        "DECISION_VERSION",
                        "BPM_PROCESS",
                        "SLA_RULE",
                        "AUTOMATION",
                        "EVENT_POLICY",
                        "PERMISSION_POLICY");
        assertThat(result.getGovernance().getReplayPlan())
                .extracting(ModelPublishReplayStepDTO::getConsumerLabel)
                .contains("决策版本", "BPM 流程", "SLA 策略", "自动化", "事件策略", "权限策略");
        assertThat(result.getGovernance().getReplayPlan())
                .allSatisfy(step -> {
                    assertThat(step.getRequired()).isTrue();
                    assertThat(step.getFieldRef()).isEqualTo(draft.getCode() + ".amount");
                    assertThat(step.getRecommendedAction()).isNotBlank();
                });
    }

    @Test
    void previewPublishDDLPropagatesFieldGovernanceRiskIntoReplayPlan() {
        Model draft = draftModel("model_replay_field_governance_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_field_governance_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(216L)));
        when(metaFieldMapper.findByIds(List.of(216L)))
                .thenReturn(List.of(nestedExtensionGovernedField(216L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("PERMISSION_POLICY", "model.order.approve", null, "role-perm-pid",
                                "record.data.amount", "ROLE_PERMISSION_CONDITION",
                                Map.of(
                                        "permissionCode", "model.order.approve",
                                        "resourceCode", "order",
                                        "action", "approve"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));

        DDLPreviewResult result = service().previewPublishDDL(draft.getPid());

        ModelPublishReplayStepDTO step = result.getGovernance().getReplayPlan().get(0);
        assertThat(step.getConsumerType()).isEqualTo("PERMISSION_POLICY");
        assertThat(step.getMetadata())
                .containsEntry("fieldMasked", true)
                .containsEntry("fieldPermissionChange", true)
                .containsEntry("fieldPermission", "model.order.amount.view")
                .containsEntry("fieldRiskLevel", "FIELD_PERMISSION_CHANGE")
                .containsEntry("fieldRiskSummary", "MASKED_PERMISSION_CHANGE")
                .containsEntry("requiresLowPermissionSample", true)
                .containsEntry("permissionCode", "model.order.approve")
                .containsEntry("resourceCode", "order")
                .containsEntry("action", "approve");
    }

    @Test
    void publishBlocksSchemaChangingRuleCenterImpactWithoutAcknowledgement() {
        Model draft = draftModel("model_publish_blocked_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_publish_blocked_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(202L)));
        when(metaFieldMapper.findByIds(List.of(202L))).thenReturn(List.of(field(202L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpact(draft.getCode() + ".amount"));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));

        assertThatThrownBy(() -> service().publish(draft.getPid(), "release", false, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("模型发布需要先确认规则中心影响")
                .hasMessageContaining(draft.getCode() + ".amount");

        verify(metaModelMapper, never()).updateById(any(Model.class));
        verify(autoPermissionAssignmentService, never()).autoAssignPermissions(anyString(), any());
        verify(decisionImpactAckService, never()).recordAcknowledgement(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void publishRecordsAcknowledgementAndContinuesPublishing() {
        Model draft = draftModel("model_publish_ack_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_publish_ack_case ADD COLUMN amount numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(203L)));
        when(metaFieldMapper.findByIds(List.of(203L))).thenReturn(List.of(field(203L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpact(draft.getCode() + ".amount"));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(fieldBindingMapper.countUserFieldsByModelId(draft.getId())).thenReturn(1);

        MetaModelDTO result = service().publish(
                draft.getPid(),
                "release with ack",
                true,
                "Reviewed Rule Center blast radius");

        assertThat(result.getStatus()).isEqualTo(StatusConstants.PUBLISHED);
        verify(decisionImpactAckService).recordAcknowledgement(
                eq("MODEL_PUBLISH"),
                eq("MODEL"),
                eq(draft.getCode()),
                eq(draft.getPid()),
                eq(draft.getCode()),
                contains(draft.getCode() + ".amount"),
                any(ModelPublishGovernanceDTO.class),
                eq("Reviewed Rule Center blast radius"));
        verify(metaModelMapper).updateById(draft);
        verify(autoPermissionAssignmentService).autoAssignPermissions(draft.getCode(), null);
        verify(rollUpFieldRegistry).invalidateModel(draft.getCode());
    }

    @Test
    void replayPublishImpactCollectsReadyDecisionAndManualConsumerResults() {
        Model draft = draftModel("model_replay_report_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_report_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(206L)));
        when(metaFieldMapper.findByIds(List.of(206L))).thenReturn(List.of(field(206L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("DECISION_VERSION", "approval_decision", "1", "dv-pid", "record.data.amount", "VERSION_RULES"),
                        ref("BPM_PROCESS", "approval_flow", "7", "bpm-pid", "record.data.amount", "GATEWAY_CONDITION",
                                Map.of("edgeId", "edge_high_amount"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(processDeploymentService.getByPid("bpm-pid")).thenReturn(bpmProcessDefinition("bpm-pid", "approval_flow"));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), null);

        assertThat(report.getTotalCount()).isEqualTo(2);
        assertThat(report.getAutomatedCount()).isEqualTo(2);
        assertThat(report.getManualCount()).isZero();
        assertThat(report.getExecutedCount()).isZero();
        assertThat(report.getResults())
                .extracting(result -> result.getStep().getConsumerType() + ":" + result.getStatus())
                .containsExactly("DECISION_VERSION:READY", "BPM_PROCESS:READY");
        assertThat(report.getResults().get(1).getOutputs())
                .containsEntry("processKey", "approval_flow")
                .containsEntry("edgeId", "edge_high_amount")
                .containsEntry("bindingKind", "CONDITION");
        verifyNoInteractions(decisionEvaluationService, bpmRuleBindingRuntimeService);
    }

    @Test
    void replayPublishImpactExecutesBpmUserTaskRuleBindingWithSampleContext() {
        Model draft = draftModel("model_bpm_replay_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_bpm_replay_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(209L)));
        when(metaFieldMapper.findByIds(List.of(209L))).thenReturn(List.of(field(209L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("BPM_PROCESS", "approval_flow", "7", "bpm-pid", "record.data.amount", "USER_TASK_ASSIGNMENT",
                                Map.of("nodeId", "approve"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(processDeploymentService.getByPid("bpm-pid")).thenReturn(bpmProcessDefinition("bpm-pid", "approval_flow"));
        when(bpmRuleBindingRuntimeService.resolveTaskAssignment(
                any(), eq("approval_flow"), eq("approve"), eq("pi-replay-1"), any()))
                .thenReturn(new BpmRuleBindingRuntimeService.TaskAssignmentResult(
                        List.of("u-manager"),
                        List.of("finance"),
                        false,
                        bpmTrace("trace-bpm-replay", "approve", true, Map.of(
                                "candidateUserIds", List.of("u-manager"),
                                "candidateGroupIds", List.of("finance"),
                                "route", "manager"))));

        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setCorrelationId("corr-bpm-replay");
        request.setSampleContext(Map.of(
                "bpm", Map.of("processInstanceId", "pi-replay-1"),
                "record", Map.of("pid", "REC-1", "data", Map.of("amount", 60000))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("trace-bpm-replay");
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("processKey", "approval_flow")
                .containsEntry("nodeId", "approve")
                .containsEntry("nodeType", "userTask")
                .containsEntry("bindingKind", "DECISION_REF")
                .containsEntry("decisionCode", "approval_route")
                .containsEntry("candidateUserIds", List.of("u-manager"))
                .containsEntry("candidateGroupIds", List.of("finance"));
    }

    @Test
    void replayPublishImpactFailsClosedForBpmUserTaskDecisionError() {
        Model draft = draftModel("model_bpm_replay_fail_closed_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_bpm_replay_fail_closed_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(210L)));
        when(metaFieldMapper.findByIds(List.of(210L))).thenReturn(List.of(field(210L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("BPM_PROCESS", "approval_flow", "7", "bpm-pid", "record.data.amount", "USER_TASK_ASSIGNMENT",
                                Map.of("nodeId", "approve"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(processDeploymentService.getByPid("bpm-pid")).thenReturn(bpmProcessDefinition("bpm-pid", "approval_flow"));
        when(bpmRuleBindingRuntimeService.resolveTaskAssignment(
                any(), eq("approval_flow"), eq("approve"), eq("pi-replay-fail-closed"), any()))
                .thenReturn(new BpmRuleBindingRuntimeService.TaskAssignmentResult(
                        List.of(),
                        List.of(),
                        true,
                        bpmErrorTrace("trace-bpm-fail-closed", "approve")));

        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setCorrelationId("corr-bpm-fail-closed");
        request.setSampleContext(Map.of(
                "bpm", Map.of("processInstanceId", "pi-replay-fail-closed"),
                "record", Map.of("pid", "REC-FAIL", "data", Map.of("amount", 60000))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(report.getResults().get(0).getExecuted()).isFalse();
        assertThat(report.getResults().get(0).getMessage())
                .isEqualTo("BPM rule binding failed closed after decision evaluation error.");
        assertThat(report.getResults().get(0).getErrors())
                .contains("Decision version unavailable", "BPM_RULE_BINDING_FAIL_CLOSED");
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("processKey", "approval_flow")
                .containsEntry("nodeId", "approve")
                .containsEntry("nodeType", "userTask")
                .containsEntry("bindingKind", "DECISION_REF")
                .containsEntry("decisionCode", "approval_route")
                .containsEntry("decisionStatus", "ERROR")
                .containsEntry("fallbackApplied", true)
                .containsEntry("errorCode", "DECISION_EVALUATION_FAILED")
                .containsEntry("candidateUserIds", List.of())
                .containsEntry("candidateGroupIds", List.of())
                .containsEntry("failClosed", true);
    }

    @Test
    void replayPublishImpactExecutesDecisionVersionWithSampleContext() {
        Model draft = draftModel("model_replay_exec_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_exec_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(207L)));
        when(metaFieldMapper.findByIds(List.of(207L))).thenReturn(List.of(field(207L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("DECISION_VERSION", "approval_decision", "1", "dv-pid", "record.data.amount", "VERSION_RULES")
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(decisionEvaluationService.evaluate(any())).thenReturn(DecisionResult.builder("approval_decision")
                .traceId("trace-replay-1")
                .status(DecisionStatus.MATCHED)
                .matched(true)
                .outputs(Map.of("route", "manager"))
                .build());
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setCorrelationId("corr-model-replay");
        request.setSampleContext(Map.of("record", Map.of("amount", 42)));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("trace-replay-1");
        assertThat(report.getResults().get(0).getOutputs()).containsEntry("route", "manager");
        verify(decisionEvaluationService).evaluate(argThat(req ->
                "approval_decision".equals(req.getDecisionCode())
                        && "MODEL_PUBLISH_REPLAY".equals(req.getCallerType())
                        && draft.getPid().equals(req.getCallerRef())
                        && "corr-model-replay".equals(req.getCorrelationId())
                        && req.getContext().containsKey("record")));
    }

    @Test
    void replayPublishImpactExecutesEventPolicyWithSampleContext() {
        Model draft = draftModel("model_replay_event_policy_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_event_policy_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(208L)));
        when(metaFieldMapper.findByIds(List.of(208L))).thenReturn(List.of(field(208L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("EVENT_POLICY", "case_closed_policy", "2", "event-pid", "record.data.amount", "VERSION_RULES",
                                Map.of(
                                        "eventType", "FORM_SUBMITTED",
                                        "targetType", "FORM",
                                        "targetKey", "case_form"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        EventPolicyResult policyResult = new EventPolicyResult(
                "case_closed_policy",
                EventPolicyResult.Status.MATCHED,
                List.of("route-high-value"),
                List.of(),
                List.of(),
                List.of(),
                "corr-event-policy",
                List.of("trace-event-policy-1"));
        PolicyExecutionResult executionResult = new PolicyExecutionResult(
                "case_closed_policy",
                PolicyExecutionResult.OverallStatus.ALL_SUCCESS,
                List.of(ActionExecutionResult.success(
                        "route-high-value",
                        "NOTIFY",
                        "idem-1",
                        Map.of("delivery", "notification"))));
        when(eventPolicyRuntimeService.runAndExecute(eq("FORM_SUBMITTED"), eq("FORM"), eq("case_form"), any()))
                .thenReturn(new EventPolicyExecutionResult(policyResult, executionResult));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setCorrelationId("corr-model-replay");
        request.setSampleContext(Map.of("record", Map.of("amount", 42)));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("trace-event-policy-1");
        assertThat(report.getResults().get(0).getMatched()).isTrue();
        assertThat(report.getResults().get(0).getStep().getMetadata())
                .containsEntry("eventType", "FORM_SUBMITTED")
                .containsEntry("targetType", "FORM")
                .containsEntry("targetKey", "case_form");
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("eventType", "FORM_SUBMITTED")
                .containsEntry("targetType", "FORM")
                .containsEntry("targetKey", "case_form")
                .containsEntry("policyCode", "case_closed_policy")
                .containsEntry("policyStatus", "MATCHED")
                .containsEntry("executionStatus", "ALL_SUCCESS")
                .containsEntry("actionCount", 1);
        verify(eventPolicyRuntimeService).runAndExecute(
                eq("FORM_SUBMITTED"),
                eq("FORM"),
                eq("case_form"),
                argThat(context -> context.containsKey("record")));
        verifyNoInteractions(decisionEvaluationService);
    }

    @Test
    void replayPublishImpactExecutesAutomationWithSampleContextRecordPid() {
        Model draft = draftModel("model_replay_automation_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_automation_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(209L)));
        when(metaFieldMapper.findByIds(List.of(209L))).thenReturn(List.of(field(209L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("AUTOMATION", "notify_manager", null, "auto-pid", "record.data.amount", "RULE_BINDING",
                                Map.of(
                                        "sourceName", "Notify Manager",
                                        "modelCode", "leave_request",
                                        "triggerType", "on_record_create",
                                        "enabled", true))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(automationService.triggerManually(eq("auto-pid"), eq("REQ-LONG-LEAVE-1"), anyMap()))
                .thenReturn(AutomationLogDTO.builder()
                        .pid("auto-log-1")
                        .automationId("auto-pid")
                        .triggerType("on_record_create")
                        .triggerRecordPid("REQ-LONG-LEAVE-1")
                        .status(StatusConstants.SUCCESS)
                        .durationMs(23L)
                        .build());
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setCorrelationId("corr-model-replay");
        request.setSampleContext(Map.of("record", Map.of(
                "pid", "REQ-LONG-LEAVE-1",
                "data", Map.of("amount", 42))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("auto-log-1");
        assertThat(report.getResults().get(0).getMatched()).isTrue();
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("automationPid", "auto-pid")
                .containsEntry("recordPid", "REQ-LONG-LEAVE-1")
                .containsEntry("logPid", "auto-log-1")
                .containsEntry("logStatus", StatusConstants.SUCCESS)
                .containsEntry("modelCode", "leave_request")
                .containsEntry("triggerType", "on_record_create");
        verify(automationService).triggerManually(
                eq("auto-pid"),
                eq("REQ-LONG-LEAVE-1"),
                argThat(context -> context.containsKey("record")));
        verifyNoInteractions(decisionEvaluationService, eventPolicyRuntimeService);
    }

    @Test
    void replayPublishImpactAutomationNeedsRecordPidBeforeExecuting() {
        Model draft = draftModel("model_replay_automation_needs_record_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_automation_needs_record_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(210L)));
        when(metaFieldMapper.findByIds(List.of(210L))).thenReturn(List.of(field(210L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("AUTOMATION", "notify_manager", null, "auto-pid", "record.data.amount", "RULE_BINDING")
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of("record", Map.of("amount", 42)));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isZero();
        assertThat(report.getNeedsInputCount()).isEqualTo(1);
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("NEEDS_SAMPLE_CONTEXT");
        assertThat(report.getResults().get(0).getMessage()).contains("sampleContext.record.pid");
        verifyNoInteractions(automationService, decisionEvaluationService, eventPolicyRuntimeService);
    }

    @Test
    void replayPublishImpactExecutesRecordLevelSlaWithSampleContextRecordPid() {
        Model draft = draftModel("model_replay_sla_record_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_sla_record_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(211L)));
        when(metaFieldMapper.findByIds(List.of(211L))).thenReturn(List.of(field(211L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("SLA_RULE", "approval_sla", null, "sla-pid", "record.data.amount", "RULE_BINDING",
                                Map.of(
                                        "sourceName", "Approval SLA",
                                        "targetType", "RECORD",
                                        "targetKey", "leave_request",
                                        "enabled", true))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid("sla-pid")
                .name("Approval SLA")
                .targetType("RECORD")
                .targetKey("leave_request")
                .deadlineMode("FIXED")
                .deadlineValue("PT2H")
                .enabled(true)
                .actionPolicy(Map.of(
                        "trigger", "SLA_TIMEOUT",
                        "actions", List.of(Map.of("type", "NOTIFY"))))
                .build();
        when(slaConfigService.getByPid("sla-pid")).thenReturn(config);
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid("sla-record-1")
                .slaConfigId("sla-pid")
                .processInstanceId("REQ-SLA-1")
                .nodeId("leave_request")
                .status(StatusConstants.RUNNING)
                .startTime(Instant.parse("2026-01-01T00:00:00Z"))
                .deadlineTime(Instant.parse("2026-01-01T02:00:00Z"))
                .build();
        when(slaRecordService.findByProcessInstance("REQ-SLA-1")).thenReturn(List.of(record));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of("record", Map.of(
                "pid", "REQ-SLA-1",
                "data", Map.of("amount", 42, "priority", "high"))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("sla-record-1");
        assertThat(report.getResults().get(0).getMatched()).isTrue();
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("slaConfigPid", "sla-pid")
                .containsEntry("targetType", "RECORD")
                .containsEntry("targetKey", "leave_request")
                .containsEntry("deadlineMode", "FIXED")
                .containsEntry("deadlineValue", "PT2H")
                .containsEntry("recordPid", "REQ-SLA-1")
                .containsEntry("slaRecordPid", "sla-record-1")
                .containsEntry("slaRecordStatus", StatusConstants.RUNNING)
                .containsEntry("actionCount", 1);
        verify(slaActivationListener).onRecordCreate(
                eq("leave_request"),
                eq("REQ-SLA-1"),
                argThat(data -> "high".equals(data.get("priority"))));
        verifyNoInteractions(automationService, decisionEvaluationService, eventPolicyRuntimeService);
    }

    @Test
    void replayPublishImpactExecutesBpmNodeSlaWithSampleContext() {
        Model draft = draftModel("model_replay_sla_node_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_sla_node_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(212L)));
        when(metaFieldMapper.findByIds(List.of(212L))).thenReturn(List.of(field(212L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("SLA_RULE", "approval_sla", null, "sla-pid", "record.data.amount", "RULE_BINDING",
                                Map.of("targetType", "NODE", "targetKey", "approve_task"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(slaConfigService.getByPid("sla-pid")).thenReturn(SlaConfigEntity.builder()
                .pid("sla-pid")
                .targetType("NODE")
                .targetKey("approve_task")
                .deadlineMode("FIXED")
                .deadlineValue("PT30M")
                .enabled(true)
                .actionPolicy(Map.of(
                        "trigger", "SLA_TIMEOUT",
                        "actions", List.of(Map.of("type", "NOTIFY"))))
                .build());
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid("sla-node-record-1")
                .slaConfigId("sla-pid")
                .processInstanceId("PROC-SLA-2")
                .nodeId("approve_task")
                .status(StatusConstants.RUNNING)
                .startTime(Instant.parse("2026-01-01T00:00:00Z"))
                .deadlineTime(Instant.parse("2026-01-01T00:30:00Z"))
                .build();
        when(slaRecordService.findByProcessInstance("PROC-SLA-2")).thenReturn(List.of(record));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of(
                "bpm", Map.of(
                        "tenantId", 1L,
                        "processKey", "approval_flow",
                        "processInstanceId", "PROC-SLA-2",
                        "taskId", "TASK-SLA-2"),
                "record", Map.of("data", Map.of("amount", 42))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getManualCount()).isZero();
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getTraceId()).isEqualTo("sla-node-record-1");
        assertThat(report.getResults().get(0).getMatched()).isTrue();
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("slaConfigPid", "sla-pid")
                .containsEntry("targetType", "NODE")
                .containsEntry("targetKey", "approve_task")
                .containsEntry("processInstanceId", "PROC-SLA-2")
                .containsEntry("taskId", "TASK-SLA-2")
                .containsEntry("slaRecordPid", "sla-node-record-1")
                .containsEntry("slaRecordStatus", StatusConstants.RUNNING)
                .containsEntry("actionCount", 1)
                .containsEntry("actionPolicyTrigger", "SLA_TIMEOUT");
        assertThat(report.getResults().get(0).getOutputs()).doesNotContainKey("recordPid");
        verify(slaActivationListener).onBpmEvent(argThat((BpmEvent event) ->
                "task_assigned".equals(event.getBpmEventType())
                        && Objects.equals(1L, event.getTenantId())
                        && "approval_flow".equals(event.getProcessKey())
                        && "PROC-SLA-2".equals(event.getInstanceId())
                        && "approve_task".equals(event.getNodeId())
                        && "TASK-SLA-2".equals(event.getPayload().get("taskInstanceId"))));
        verify(slaRecordService).findByProcessInstance("PROC-SLA-2");
        verifyNoInteractions(automationService, decisionEvaluationService, eventPolicyRuntimeService);
    }

    @Test
    void replayPublishImpactExecutesPermissionPolicyWithSampleContext() {
        Model draft = draftModel("model_replay_permission_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_permission_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(213L)));
        when(metaFieldMapper.findByIds(List.of(213L))).thenReturn(List.of(governedField(213L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("PERMISSION_POLICY", "model.order.approve", null, "role-perm-pid",
                                "record.data.amount", "ROLE_PERMISSION_CONDITION",
                                Map.of(
                                        "permissionCode", "model.order.approve",
                                        "resourceCode", "order",
                                        "action", "approve",
                                        "roleId", 700L,
                                        "grantType", "grant",
                                        "status", StatusConstants.ENABLED))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(permissionEvaluator.canOperate(eq(901L), eq("order"), eq("approve"), argThat(record ->
                record instanceof Map<?, ?> map
                        && "ORDER-1".equals(map.get("pid"))
                        && ((Number) map.get("amount")).intValue() == 42
                        && "high".equals(map.get("priority"))
                        && !map.containsKey("data"))))
                .thenReturn(PermissionResult.allow(List.of(
                        new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "User has permission"),
                        new EvaluationStep("Policy", EvaluationVerdict.ALLOW, "Rule Center guard satisfied",
                                Map.of("ruleTraceId", "trace-perm-1",
                                        "permissionContext", Map.of("severity", "warning"))))));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of(
                "permission", Map.of("memberId", 901L),
                "record", Map.of(
                        "pid", "ORDER-1",
                        "data", Map.of("amount", 42, "priority", "high"))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getMatched()).isTrue();
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("permissionPolicyPid", "role-perm-pid")
                .containsEntry("permissionCode", "model.order.approve")
                .containsEntry("resource", "order")
                .containsEntry("action", "approve")
                .containsEntry("memberId", "901")
                .containsEntry("roleId", "700")
                .containsEntry("recordPid", "ORDER-1")
                .containsEntry("granted", true)
                .containsEntry("reason", "Granted")
                .containsEntry("stepCount", 2)
                .containsEntry("affectedFieldRef", draft.getCode() + ".amount")
                .containsEntry("fieldMasked", true)
                .containsEntry("fieldPermissionChange", true)
                .containsEntry("fieldPermission", "model.order.amount.view")
                .containsEntry("fieldRiskLevel", "FIELD_PERMISSION_CHANGE")
                .containsEntry("fieldRiskSummary", "MASKED_PERMISSION_CHANGE")
                .containsEntry("requiresLowPermissionSample", true);
        assertThat((List<?>) report.getResults().get(0).getOutputs().get("steps"))
                .anySatisfy(item -> {
                    Map<?, ?> step = (Map<?, ?>) item;
                    assertThat(step.get("evaluatorName")).isEqualTo("Policy");
                    assertThat(step.get("verdict")).isEqualTo("ALLOW");
                    assertThat(step.get("reason")).isEqualTo("Rule Center guard satisfied");
                });
        verify(permissionEvaluator).canOperate(eq(901L), eq("order"), eq("approve"), any());
        verifyNoInteractions(decisionEvaluationService, eventPolicyRuntimeService, automationService, slaActivationListener);
    }

    @Test
    void replayPublishImpactPermissionDeniedIsExecutedNotInfrastructureFailure() {
        Model draft = draftModel("model_replay_permission_deny_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_permission_deny_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(214L)));
        when(metaFieldMapper.findByIds(List.of(214L))).thenReturn(List.of(field(214L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("PERMISSION_POLICY", "model.invoice.approve", null, "role-perm-deny-pid",
                                "record.data.amount", "ROLE_PERMISSION_CONDITION",
                                Map.of(
                                        "permissionCode", "model.invoice.approve",
                                        "resourceCode", "invoice",
                                        "action", "approve"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        when(permissionEvaluator.canOperate(eq(902L), eq("invoice"), eq("approve"), any()))
                .thenReturn(PermissionResult.deny("Policy condition denied", List.of(
                        new EvaluationStep("Policy", EvaluationVerdict.DENY, "amount is above approval limit"))));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of(
                "permission", Map.of("memberId", 902L),
                "record", Map.of("pid", "INV-9", "data", Map.of("amount", 999999))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isEqualTo(1);
        assertThat(report.getFailedCount()).isZero();
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(report.getResults().get(0).getMatched()).isFalse();
        assertThat(report.getResults().get(0).getErrors()).isEmpty();
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("permissionCode", "model.invoice.approve")
                .containsEntry("resource", "invoice")
                .containsEntry("action", "approve")
                .containsEntry("memberId", "902")
                .containsEntry("recordPid", "INV-9")
                .containsEntry("granted", false)
                .containsEntry("reason", "Policy condition denied");
    }

    @Test
    void replayPublishImpactPermissionNeedsMemberBeforeExecuting() {
        Model draft = draftModel("model_replay_permission_needs_member_case");
        when(metaModelMapper.findByPid(draft.getPid())).thenReturn(draft);
        when(schemaManagementService.previewModelChanges(draft.getCode()))
                .thenReturn(previewWithDdl("ALTER TABLE ab_dyn_model_replay_permission_needs_member_case ALTER COLUMN amount TYPE numeric"));
        when(fieldBindingMapper.findByModelId(draft.getId())).thenReturn(List.of(binding(215L)));
        when(metaFieldMapper.findByIds(List.of(215L))).thenReturn(List.of(field(215L, "amount", "decimal")));
        when(decisionImpactService.getFieldImpact(draft.getCode() + ".amount"))
                .thenReturn(blockingImpactWithReferences(draft.getCode() + ".amount", List.of(
                        ref("PERMISSION_POLICY", "model.order.approve", null, "role-perm-pid",
                                "record.data.amount", "ROLE_PERMISSION_CONDITION",
                                Map.of(
                                        "permissionCode", "model.order.approve",
                                        "resourceCode", "order",
                                        "action", "approve"))
                )));
        when(metaModelMapper.findAllVersionsByCode(draft.getCode()))
                .thenReturn(List.of(publishedVersion(draft.getCode(), 1), draft));
        MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(true);
        request.setSampleContext(Map.of("record", Map.of(
                "pid", "ORDER-2",
                "data", Map.of("amount", 42))));

        ModelPublishReplayReportDTO report = service().replayPublishImpact(draft.getPid(), request);

        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getAutomatedCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isZero();
        assertThat(report.getNeedsInputCount()).isEqualTo(1);
        assertThat(report.getResults().get(0).getStatus()).isEqualTo("NEEDS_SAMPLE_CONTEXT");
        assertThat(report.getResults().get(0).getMessage()).contains("sampleContext.permission.memberId");
        assertThat(report.getResults().get(0).getOutputs())
                .containsEntry("permissionCode", "model.order.approve")
                .containsEntry("resource", "order")
                .containsEntry("action", "approve")
                .containsEntry("recordPid", "ORDER-2");
        verifyNoInteractions(permissionEvaluator, decisionEvaluationService, eventPolicyRuntimeService, automationService);
    }

    private MetaModelServiceImpl service() {
        MetaModelServiceImpl service = new MetaModelServiceImpl(
                metaModelMapper,
                metaFieldMapper,
                queryBuilderService,
                fieldBindingMapper,
                autoPermissionAssignmentService,
                metaDefinitionCacheService
        );
        ReflectionTestUtils.setField(service, "schemaManagementService", schemaManagementService);
        ReflectionTestUtils.setField(service, "decisionImpactService", decisionImpactService);
        ReflectionTestUtils.setField(service, "decisionImpactAckService", decisionImpactAckService);
        ReflectionTestUtils.setField(service, "decisionEvaluationService", decisionEvaluationService);
        ReflectionTestUtils.setField(service, "eventPolicyRuntimeService", eventPolicyRuntimeService);
        ReflectionTestUtils.setField(service, "automationService", automationService);
        ReflectionTestUtils.setField(service, "processDeploymentService", processDeploymentService);
        ReflectionTestUtils.setField(service, "bpmRuleBindingRuntimeService", bpmRuleBindingRuntimeService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "slaConfigService", slaConfigService);
        ReflectionTestUtils.setField(service, "slaActivationListener", slaActivationListener);
        ReflectionTestUtils.setField(service, "slaRecordService", slaRecordService);
        ReflectionTestUtils.setField(service, "permissionEvaluator", permissionEvaluator);
        ReflectionTestUtils.setField(service, "rollUpFieldRegistry", rollUpFieldRegistry);
        return service;
    }

    private static Model model(Long id, String code) {
        Model model = new Model();
        model.setId(id);
        model.setPid("pid-" + code);
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        model.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return model;
    }

    private static Model draftModel(String code) {
        Model model = model(101L, code);
        model.setPid("pid-" + code);
        model.setTenantId(1L);
        model.setStatus(StatusConstants.DRAFT);
        model.setVersion(3);
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("displayName", "Governed Model");
        extension.setDynamicProperty("skipTableCreation", true);
        extension.setDynamicProperty("skipDefaultPages", true);
        model.setExtension(extension);
        return model;
    }

    private static Model publishedVersion(String code, int version) {
        Model model = model(90L + version, code);
        model.setPid("pid-" + code + "-v" + version);
        model.setStatus(StatusConstants.PUBLISHED);
        model.setVersion(version);
        return model;
    }

    private static ModelFieldBinding binding(Long fieldId) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setFieldId(fieldId);
        return binding;
    }

    private static Field field(Long id, String code, String dataType) {
        Field field = new Field();
        field.setId(id);
        field.setCode(code);
        field.setDataType(dataType);
        return field;
    }

    private static Field governedField(Long id, String code, String dataType) {
        Field field = field(id, code, dataType);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of(
                "masked", true,
                "fieldPermissionChange", true,
                "permissionCode", "model.order.amount.view",
                "fieldPermission", Map.of("view", List.of("manager"), "edit", List.of("manager"))));
        field.setExtension(extension);

        FieldRuleSchemaBean ruleSchema = new FieldRuleSchemaBean();
        ruleSchema.setExtensions(Map.of("fieldPermissionChange", true));
        FieldRuleSchemaBean.PermissionRule permissionRule = new FieldRuleSchemaBean.PermissionRule();
        permissionRule.setReadable(false);
        FieldRuleSchemaBean.PermissionRule.FieldLevelSecurity fieldSecurity =
                new FieldRuleSchemaBean.PermissionRule.FieldLevelSecurity();
        fieldSecurity.setMaskSensitive(true);
        permissionRule.setFieldSecurity(fieldSecurity);
        ruleSchema.setPermissionRule(permissionRule);
        field.setRuleSchema(ruleSchema);
        return field;
    }

    private static Field nestedExtensionGovernedField(Long id, String code, String dataType) {
        Field field = field(id, code, dataType);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of("extension", Map.of(
                "masked", true,
                "fieldPermissionChange", true,
                "permissionCode", "model.order.amount.view",
                "fieldPermission", Map.of("view", List.of("manager"), "edit", List.of("manager")))));
        field.setExtension(extension);
        return field;
    }

    private static DDLPreviewResult previewWithDdl(String ddl) {
        return DDLPreviewResult.builder()
                .modelCode("model_governance_case")
                .ddlStatements(List.of(ddl))
                .operationType("SYNC")
                .affectedTables(List.of("ab_dyn_model_governance_case"))
                .build();
    }

    private static DecisionFieldImpactDTO blockingImpact(String fieldRef) {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType("BPM");
        ref.setSourceCode("approval_flow");
        ref.setTargetType("FIELD");
        ref.setTargetCode(fieldRef);
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(true);
        risk.setSummary("BPM consumer references this field");
        risk.setCounts(Map.of("BPM", 1));
        DecisionFieldImpactDTO impact = new DecisionFieldImpactDTO();
        impact.setFieldRef(fieldRef);
        impact.setReferences(List.of(ref));
        impact.setRisk(risk);
        return impact;
    }

    private static DecisionFieldImpactDTO blockingImpactWithReferences(String fieldRef, List<DecisionImpactRefDTO> refs) {
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(true);
        risk.setSummary("Multiple rule consumers reference this field");
        risk.setCounts(Map.of(
                "DECISION_VERSION", 1,
                "BPM_PROCESS", 1,
                "SLA_RULE", 1,
                "AUTOMATION", 1,
                "EVENT_POLICY", 1,
                "PERMISSION_POLICY", 1));
        DecisionFieldImpactDTO impact = new DecisionFieldImpactDTO();
        impact.setFieldRef(fieldRef);
        impact.setReferences(refs);
        impact.setRisk(risk);
        return impact;
    }

    private static DecisionImpactRefDTO ref(
            String sourceType,
            String sourceCode,
            String sourceVersion,
            String sourcePid,
            String targetPath,
            String binding) {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType(sourceType);
        ref.setSourceCode(sourceCode);
        ref.setSourceName(sourceCode);
        ref.setSourceVersion(sourceVersion);
        ref.setSourcePid(sourcePid);
        ref.setTargetType("FIELD");
        ref.setTargetCode(targetPath);
        ref.setTargetPath(targetPath);
        ref.setBinding(binding);
        return ref;
    }

    private static DecisionImpactRefDTO ref(
            String sourceType,
            String sourceCode,
            String sourceVersion,
            String sourcePid,
            String targetPath,
            String binding,
            Map<String, Object> metadata) {
        DecisionImpactRefDTO ref = ref(sourceType, sourceCode, sourceVersion, sourcePid, targetPath, binding);
        ref.setMetadata(metadata);
        return ref;
    }

    private static BpmProcessDefinition bpmProcessDefinition(String pid, String processKey) {
        return BpmProcessDefinition.builder()
                .pid(pid)
                .tenantId(1L)
                .processKey(processKey)
                .processName("Approval Flow")
                .status(StatusConstants.DEPLOYED)
                .version(7)
                .isCurrent(true)
                .extension(Map.of("designerJson", """
                        {
                          "key": "%s",
                          "nodes": [
                            {
                              "id": "gateway_route",
                              "type": "exclusiveGateway",
                              "data": {
                                "config": {
                                  "ruleBinding": {
                                    "consumerType": "BPM",
                                    "consumerCode": "%s",
                                    "consumerNodeId": "gateway_route",
                                    "bindingKind": "DECISION_REF",
                                    "decisionBinding": {
                                      "decisionCode": "approval_route",
                                      "versionPolicy": "LATEST_PUBLISHED",
                                      "inputMappings": [
                                        {
                                          "input": "amount",
                                          "source": { "kind": "FIELD", "scope": "record", "path": "data.amount" }
                                        }
                                      ],
                                      "outputMappings": [
                                        {
                                          "output": "route",
                                          "target": { "kind": "PROCESS_VARIABLE", "path": "approvalRoute" }
                                        }
                                      ],
                                      "fallbackPolicy": { "mode": "FAIL_CLOSED" },
                                      "enabled": true
                                    },
                                    "enabled": true
                                  }
                                }
                              }
                            },
                            {
                              "id": "approve",
                              "type": "userTask",
                              "data": {
                                "config": {
                                  "ruleBinding": {
                                    "consumerType": "BPM",
                                    "consumerCode": "%s",
                                    "consumerNodeId": "approve",
                                    "bindingKind": "DECISION_REF",
                                    "decisionBinding": {
                                      "decisionCode": "approval_route",
                                      "versionPolicy": "LATEST_PUBLISHED",
                                      "inputMappings": [
                                        {
                                          "input": "amount",
                                          "source": { "kind": "FIELD", "scope": "record", "path": "data.amount" }
                                        }
                                      ],
                                      "outputMappings": [
                                        {
                                          "output": "candidateUserIds",
                                          "target": { "kind": "ACTION_PARAM", "path": "candidateUsers" }
                                        },
                                        {
                                          "output": "candidateGroupIds",
                                          "target": { "kind": "ACTION_PARAM", "path": "candidateGroups" }
                                        }
                                      ],
                                      "fallbackPolicy": { "mode": "FAIL_CLOSED" },
                                      "enabled": true
                                    },
                                    "enabled": true
                                  }
                                }
                              }
                            }
                          ],
                          "edges": [
                            {
                              "id": "edge_high_amount",
                              "source": "gateway_route",
                              "target": "approve",
                              "data": {
                                "conditionSpec": {
                                  "root": {
                                    "type": "compare",
                                    "left": {
                                      "type": "path",
                                      "scope": "record",
                                      "path": "data.amount",
                                      "dataType": "decimal"
                                    },
                                    "operator": "GTE",
                                    "right": { "type": "literal", "value": 1000, "dataType": "decimal" }
                                  }
                                }
                              }
                            }
                          ]
                        }
                        """.formatted(processKey, processKey, processKey)))
                .build();
    }

    private static RuleEvaluationTrace bpmTrace(
            String traceId,
            String nodeId,
            boolean matched,
            Map<String, Object> outputs) {
        return new RuleEvaluationTrace(
                traceId,
                "BPM",
                "approval_flow",
                nodeId,
                RuleBindingKind.DECISION_REF,
                "approval_route",
                1,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                matched ? DecisionStatus.MATCHED : DecisionStatus.NOT_MATCHED,
                matched,
                Map.of("amount", 60000),
                outputs,
                false,
                12L,
                null,
                List.of(),
                List.of(),
                List.of("record.data.amount"),
                List.of("approval_route"));
    }

    private static RuleEvaluationTrace bpmErrorTrace(String traceId, String nodeId) {
        return new RuleEvaluationTrace(
                traceId,
                "BPM",
                "approval_flow",
                nodeId,
                RuleBindingKind.DECISION_REF,
                "approval_route",
                1,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                DecisionStatus.ERROR,
                false,
                Map.of("amount", 60000),
                Map.of(),
                true,
                12L,
                "DECISION_EVALUATION_FAILED",
                List.of("Decision version unavailable"),
                List.of(),
                List.of("record.data.amount"),
                List.of("approval_route"));
    }

    private static DecisionFieldImpactDTO blockingImpactWithoutReferences(String fieldRef) {
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(false);
        risk.setSummary("No field consumers");
        risk.setCounts(Map.of());
        DecisionFieldImpactDTO impact = new DecisionFieldImpactDTO();
        impact.setFieldRef(fieldRef);
        impact.setReferences(List.of());
        impact.setRisk(risk);
        return impact;
    }
}
