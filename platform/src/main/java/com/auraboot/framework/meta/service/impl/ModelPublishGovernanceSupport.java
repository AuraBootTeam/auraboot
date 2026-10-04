package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionResult;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldRuleSchemaBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.auraboot.framework.common.util.LogSanitizer;

/** Builds model publish impact plans and replays their downstream consumers. */
@RequiredArgsConstructor
@Slf4j
final class ModelPublishGovernanceSupport {
    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final DecisionImpactService decisionImpactService;
    private final DecisionImpactAckService decisionImpactAckService;
    private final DecisionEvaluationService decisionEvaluationService;
    private final EventPolicyRuntimeService eventPolicyRuntimeService;
    private final AutomationService automationService;
    private final PermissionEvaluator permissionEvaluator;
    private final ModelPublishWorkflowReplaySupport workflowReplay;
    private final java.util.function.Function<ExtensionBean, Map<String, Object>> extensionFlattener;

    ModelPublishReplayReportDTO replayReport(Model model, ModelPublishGovernanceDTO governance,
            MetaModelPublishReplayRequest request) {
        List<ModelPublishReplayResultDTO> results = governance.getReplayPlan() == null
                ? List.of()
                : governance.getReplayPlan().stream()
                        .map(step -> replayPublishStep(model, step, request))
                        .toList();

        return ModelPublishReplayReportDTO.builder()
                .modelCode(model.getCode())
                .draftVersion(model.getVersion())
                .latestPublishedVersion(governance.getLatestPublishedVersion())
                .generatedAt(java.time.Instant.now())
                .governance(governance)
                .totalCount(results.size())
                .automatedCount((int) results.stream().filter(r -> Boolean.TRUE.equals(r.getAutomated())).count())
                .executedCount((int) results.stream().filter(r -> Boolean.TRUE.equals(r.getExecuted())).count())
                .manualCount((int) results.stream().filter(r -> "MANUAL_REQUIRED".equals(r.getStatus())).count())
                .failedCount((int) results.stream().filter(r -> "FAILED".equals(r.getStatus())).count())
                .needsInputCount((int) results.stream().filter(r -> "NEEDS_SAMPLE_CONTEXT".equals(r.getStatus())).count())
                .results(results)
                .build();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    ModelPublishGovernanceDTO buildPublishGovernance(Model model, DDLPreviewResult ddlPreview) {
        List<String> ddlStatements = ddlPreview != null && ddlPreview.getDdlStatements() != null
                ? ddlPreview.getDdlStatements()
                : List.of();
        List<String> schemaChangeKinds = classifySchemaChangeKinds(ddlStatements);
        boolean schemaChangeDetected = !schemaChangeKinds.isEmpty();
        List<String> warnings = new ArrayList<>();
        List<DecisionFieldImpactDTO> fieldImpacts = List.of();
        if (decisionImpactService == null) {
            warnings.add("Rule Center impact service is unavailable; publish governance can only report DDL risk.");
        } else {
            fieldImpacts = loadRuleCenterFieldImpacts(model, warnings);
        }

        boolean requiresAcknowledgement = schemaChangeDetected && fieldImpacts.stream()
                .anyMatch(impact -> impact.getRisk() != null && Boolean.TRUE.equals(impact.getRisk().getBlocking()));
        List<ModelPublishReplayStepDTO> replayPlan = buildReplayPlan(fieldImpacts);

        return ModelPublishGovernanceDTO.builder()
                .modelCode(model.getCode())
                .draftVersion(model.getVersion())
                .latestPublishedVersion(latestPublishedVersion(model))
                .allowed(!requiresAcknowledgement)
                .blocked(requiresAcknowledgement)
                .requiresAcknowledgement(requiresAcknowledgement)
                .schemaChangeDetected(schemaChangeDetected)
                .schemaChangeKinds(schemaChangeKinds)
                .fieldImpacts(fieldImpacts)
                .replayPlan(replayPlan)
                .migrationPlan(buildMigrationPlan(schemaChangeKinds, fieldImpacts))
                .historicalVersionPolicy(buildHistoricalVersionPolicy(model))
                .warnings(warnings)
                .build();
    }

    private List<DecisionFieldImpactDTO> loadRuleCenterFieldImpacts(Model model, List<String> warnings) {
        if (model == null || model.getId() == null) {
            return List.of();
        }
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fieldIds.isEmpty()) {
            return List.of();
        }
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }

        List<DecisionFieldImpactDTO> impacts = new ArrayList<>();
        for (Field field : fields) {
            if (field == null || SystemFieldConstants.isSystemField(field.getCode())) {
                continue;
            }
            String publishFieldRef = model.getCode() + "." + field.getCode();
            try {
                DecisionFieldImpactDTO impact = loadFirstFieldImpact(candidatePublishFieldRefs(model, field));
                if (impact != null && impact.getReferences() != null && !impact.getReferences().isEmpty()) {
                    impact.setFieldRef(publishFieldRef);
                    Map<String, Object> fieldGovernance = fieldGovernanceReplayMetadata(field);
                    if (!fieldGovernance.isEmpty()) {
                        impact.setReferences(impact.getReferences().stream()
                                .map(ref -> withMergedReplayMetadata(ref, fieldGovernance))
                                .toList());
                    }
                    impacts.add(impact);
                }
            } catch (Exception e) {
                warnings.add("Failed to read Rule Center usage for " + publishFieldRef + ": " + e.getMessage());
            }
        }
        return impacts;
    }

    private DecisionFieldImpactDTO loadFirstFieldImpact(List<String> fieldRefs) {
        if (fieldRefs == null || fieldRefs.isEmpty()) {
            return null;
        }
        for (String fieldRef : fieldRefs) {
            DecisionFieldImpactDTO impact = decisionImpactService.getFieldImpact(fieldRef);
            if (impact != null && impact.getReferences() != null && !impact.getReferences().isEmpty()) {
                return impact;
            }
        }
        return null;
    }

    private List<String> candidatePublishFieldRefs(Model model, Field field) {
        if (model == null || field == null || !StringUtils.hasText(field.getCode())) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (StringUtils.hasText(model.getCode())) {
            refs.add(model.getCode() + "." + field.getCode());
        }
        refs.add("record." + recordDataPath(field.getCode()));
        refs.add("record." + field.getCode());
        return List.copyOf(refs);
    }

    private String recordDataPath(String fieldCode) {
        if (!StringUtils.hasText(fieldCode)) {
            return fieldCode;
        }
        return fieldCode.startsWith("data.") ? fieldCode : "data." + fieldCode;
    }

    private List<String> classifySchemaChangeKinds(List<String> ddlStatements) {
        if (ddlStatements == null || ddlStatements.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        for (String ddl : ddlStatements) {
            String normalized = ddl == null ? "" : ddl.toUpperCase(Locale.ROOT);
            if (normalized.contains("CREATE TABLE")) {
                kinds.add("CREATE_TABLE");
            } else if (normalized.contains("ADD COLUMN")) {
                kinds.add("ADD_COLUMN");
            } else if (normalized.contains("DROP COLUMN")) {
                kinds.add("DROP_COLUMN");
            } else if (normalized.contains("ALTER COLUMN") && normalized.contains(" TYPE ")) {
                kinds.add("ALTER_COLUMN_TYPE");
            } else if (normalized.contains("ALTER COLUMN")
                    && (normalized.contains("SET NOT NULL") || normalized.contains("DROP NOT NULL"))) {
                kinds.add("NULLABILITY");
            } else if (normalized.contains("CREATE INDEX") || normalized.contains("CREATE UNIQUE INDEX")) {
                kinds.add("INDEX");
            } else if (StringUtils.hasText(ddl)) {
                kinds.add("OTHER_DDL");
            }
        }
        return List.copyOf(kinds);
    }

    private String buildMigrationPlan(List<String> schemaChangeKinds, List<DecisionFieldImpactDTO> fieldImpacts) {
        if (schemaChangeKinds == null || schemaChangeKinds.isEmpty()) {
            return "No physical schema migration is required. Rebuild the Rule Center usage index if field metadata changed without DDL.";
        }
        List<String> steps = new ArrayList<>();
        if (schemaChangeKinds.contains("CREATE_TABLE")) {
            steps.add("Create the physical table and generated indexes before enabling runtime writes.");
        }
        if (schemaChangeKinds.contains("ADD_COLUMN")) {
            steps.add("Backfill new columns or define defaults before routing rules to the new field.");
        }
        if (schemaChangeKinds.contains("ALTER_COLUMN_TYPE")) {
            steps.add("Validate data casts and replay affected rules against representative records before promotion.");
        }
        if (schemaChangeKinds.contains("DROP_COLUMN")) {
            steps.add("Retire or migrate every affected rule consumer before removing the column.");
        }
        if (schemaChangeKinds.contains("NULLABILITY")) {
            steps.add("Check existing rows against required/nullability changes before publish.");
        }
        if (fieldImpacts != null && !fieldImpacts.isEmpty()) {
            steps.add("Confirm Rule Center blast radius and republish or replay affected BPM, SLA, Automation, EventPolicy and decision versions.");
        }
        if (steps.isEmpty()) {
            steps.add("Review generated DDL and run a post-publish schema sync smoke.");
        }
        return String.join(" ", steps);
    }

    private List<ModelPublishReplayStepDTO> buildReplayPlan(List<DecisionFieldImpactDTO> fieldImpacts) {
        if (fieldImpacts == null || fieldImpacts.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ModelPublishReplayStepDTO> steps = new LinkedHashMap<>();
        for (DecisionFieldImpactDTO impact : fieldImpacts) {
            if (impact == null || impact.getReferences() == null || impact.getReferences().isEmpty()) {
                continue;
            }
            boolean required = impact.getRisk() != null && Boolean.TRUE.equals(impact.getRisk().getBlocking());
            for (DecisionImpactRefDTO ref : impact.getReferences()) {
                if (ref == null || !StringUtils.hasText(ref.getSourceType())) {
                    continue;
                }
                String consumerType = normalizeReplayConsumerType(ref.getSourceType());
                String key = String.join("|",
                        nullToBlank(consumerType),
                        nullToBlank(ref.getSourcePid()),
                        nullToBlank(ref.getSourceCode()),
                        nullToBlank(ref.getTargetPath()),
                        nullToBlank(ref.getBinding()));
                steps.putIfAbsent(key, ModelPublishReplayStepDTO.builder()
                        .consumerType(consumerType)
                        .consumerLabel(replayConsumerLabel(consumerType))
                        .sourceCode(ref.getSourceCode())
                        .sourceName(ref.getSourceName())
                        .sourceVersion(ref.getSourceVersion())
                        .sourcePid(ref.getSourcePid())
                        .fieldRef(impact.getFieldRef())
                        .targetPath(StringUtils.hasText(ref.getTargetPath()) ? ref.getTargetPath() : ref.getTargetCode())
                        .binding(ref.getBinding())
                        .recommendedAction(replayRecommendedAction(consumerType))
                        .required(required)
                        .metadata(replayStepMetadata(impact, ref))
                        .build());
            }
        }
        return List.copyOf(steps.values());
    }

    private Map<String, Object> replayStepMetadata(DecisionFieldImpactDTO impact, DecisionImpactRefDTO ref) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ref != null && ref.getMetadata() != null) {
            metadata.putAll(ref.getMetadata());
        }
        if (impact != null && impact.getRisk() != null && StringUtils.hasText(impact.getRisk().getSummary())) {
            metadata.putIfAbsent("impactRiskSummary", impact.getRisk().getSummary());
        }
        return metadata.isEmpty() ? Map.of() : metadata;
    }

    private DecisionImpactRefDTO withMergedReplayMetadata(
            DecisionImpactRefDTO source,
            Map<String, Object> additionalMetadata) {
        if (source == null || additionalMetadata == null || additionalMetadata.isEmpty()) {
            return source;
        }
        DecisionImpactRefDTO copy = new DecisionImpactRefDTO();
        copy.setSourceType(source.getSourceType());
        copy.setSourceCode(source.getSourceCode());
        copy.setSourceName(source.getSourceName());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setSourcePid(source.getSourcePid());
        copy.setTargetType(source.getTargetType());
        copy.setTargetCode(source.getTargetCode());
        copy.setTargetPath(source.getTargetPath());
        copy.setBinding(source.getBinding());
        Map<String, Object> metadata = new LinkedHashMap<>(additionalMetadata);
        if (source.getMetadata() != null) {
            metadata.putAll(source.getMetadata());
        }
        copy.setMetadata(metadata);
        return copy;
    }

    private Map<String, Object> fieldGovernanceReplayMetadata(Field field) {
        if (field == null) {
            return Map.of();
        }
        List<Map<String, Object>> sources = fieldGovernanceMetadataSources(field);
        boolean masked = Boolean.TRUE.equals(firstFieldGovernanceBoolean(
                sources, "masked", "mask", "masking", "sensitive", "pii"));
        boolean permissionChange = Boolean.TRUE.equals(firstFieldGovernanceBoolean(
                sources, "fieldPermissionChange", "permissionChange", "changePermission", "permissionChanged"))
                || hasPermissionRule(field)
                || hasFeaturePermission(field);
        String permission = firstFieldGovernanceString(
                sources, "permission", "permissionCode", "readPermission", "viewPermission");

        FieldRuleSchemaBean.PermissionRule permissionRule = field.getRuleSchema() != null
                ? field.getRuleSchema().getPermissionRule()
                : null;
        if (permissionRule != null && permissionRule.getFieldSecurity() != null
                && Boolean.TRUE.equals(permissionRule.getFieldSecurity().getMaskSensitive())) {
            masked = true;
        }
        if (StringUtils.hasText(permission)) {
            permissionChange = true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (masked) {
            metadata.put("fieldMasked", true);
        }
        if (permissionChange) {
            metadata.put("fieldPermissionChange", true);
        }
        if (StringUtils.hasText(permission)) {
            metadata.put("fieldPermission", permission);
        }
        if (masked || permissionChange) {
            metadata.put("fieldRiskLevel", permissionChange ? "FIELD_PERMISSION_CHANGE" : "FIELD_MASKED");
            metadata.put("fieldRiskSummary", fieldRiskSummary(masked, permissionChange));
            metadata.put("requiresLowPermissionSample", true);
        }
        return metadata;
    }

    private List<Map<String, Object>> fieldGovernanceMetadataSources(Field field) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (field == null) {
            return sources;
        }
        Map<String, Object> extension = extensionFlattener.apply(field.getExtension());
        if (!extension.isEmpty()) {
            sources.add(extension);
        }
        if (field.getRuleSchema() != null && field.getRuleSchema().getExtensions() != null) {
            sources.add(field.getRuleSchema().getExtensions());
        }
        if (field.getFeature() != null && field.getFeature().getExtensions() != null) {
            sources.add(field.getFeature().getExtensions());
        }
        if (field.getUiSchema() != null && field.getUiSchema().getExtensions() != null) {
            sources.add(field.getUiSchema().getExtensions());
        }
        return sources;
    }

    private boolean hasPermissionRule(Field field) {
        return field != null && field.getRuleSchema() != null && field.getRuleSchema().getPermissionRule() != null;
    }

    private boolean hasFeaturePermission(Field field) {
        return field != null && field.getFeature() != null && field.getFeature().getPermission() != null;
    }

    private String fieldRiskSummary(boolean masked, boolean permissionChange) {
        if (masked && permissionChange) {
            return "MASKED_PERMISSION_CHANGE";
        }
        if (permissionChange) {
            return "PERMISSION_CHANGE";
        }
        return "MASKED_FIELD";
    }

    private Boolean firstFieldGovernanceBoolean(List<Map<String, Object>> sources, String... keys) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (Map<String, Object> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = source.get(key);
                if (value instanceof Boolean booleanValue) {
                    return booleanValue;
                }
                if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
                    if ("true".equalsIgnoreCase(stringValue) || "yes".equalsIgnoreCase(stringValue)) {
                        return true;
                    }
                    if ("false".equalsIgnoreCase(stringValue) || "no".equalsIgnoreCase(stringValue)) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private String firstFieldGovernanceString(List<Map<String, Object>> sources, String... keys) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        for (Map<String, Object> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = source.get(key);
                if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
                    return stringValue;
                }
            }
        }
        return null;
    }

    ModelPublishReplayResultDTO replayPublishStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if ("EVENT_POLICY".equals(nullToBlank(step.getConsumerType()))) {
            return replayEventPolicyStep(model, step, request);
        }
        if ("AUTOMATION".equals(nullToBlank(step.getConsumerType()))) {
            return replayAutomationStep(step, request);
        }
        if ("WORKFLOW_PROCESS".equals(nullToBlank(step.getConsumerType()))) {
            return replayBpmStep(step, request);
        }
        if ("SLA_RULE".equals(nullToBlank(step.getConsumerType()))) {
            return replaySlaStep(step, request);
        }
        if ("PERMISSION_POLICY".equals(nullToBlank(step.getConsumerType()))) {
            return replayPermissionStep(step, request);
        }
        if (!"DECISION_VERSION".equals(nullToBlank(step.getConsumerType()))) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message(step.getRecommendedAction())
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }
        return replayDecisionStep(model, step, request);
    }

    private ModelPublishReplayResultDTO replayBpmStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        return workflowReplay.replay(step, request);
    }

    private ModelPublishReplayResultDTO replayPermissionStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (permissionEvaluator == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("PERMISSION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay service is unavailable in this runtime.")
                    .errors(List.of("PERMISSION_REPLAY_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }

        Map<String, Map<String, Object>> sampleContext = request != null ? request.getSampleContext() : null;
        Long memberId = samplePermissionMemberId(sampleContext);
        String permissionCode = permissionReplayPermissionCode(step, sampleContext);
        String resource = permissionReplayResource(step, sampleContext, permissionCode);
        String action = permissionReplayAction(step, sampleContext, permissionCode);
        String recordPid = sampleRecordPid(sampleContext);
        Map<String, Object> record = permissionReplayRecord(sampleContext, recordPid);

        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay is available. Pass executeAutomated=true with sampleContext.permission.memberId and sampleContext.record data.")
                    .errors(List.of())
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                    .build();
        }
        if (sampleContext == null || sampleContext.isEmpty()) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, null, null,
                    "Permission replay requires representative sampleContext.");
        }
        if (memberId == null) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, null, recordPid,
                    "Permission replay requires sampleContext.permission.memberId or sampleContext.actor.memberId.");
        }
        if (!StringUtils.hasText(resource) || !StringUtils.hasText(action)) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, memberId, recordPid,
                    "Permission replay requires resource/action metadata or sampleContext.permission.resource/action.");
        }

        try {
            PermissionResult result = permissionEvaluator.canOperate(memberId, resource, action, record);
            if (result == null) {
                return ModelPublishReplayResultDTO.builder()
                        .step(step)
                        .status("FAILED")
                        .automated(true)
                        .executed(false)
                        .message("Permission replay returned no result.")
                        .errors(List.of("PERMISSION_REPLAY_RETURNED_NO_RESULT"))
                        .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                        .build();
            }
            String ruleTraceId = permissionRuleTraceId(result);
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("EXECUTED")
                    .automated(true)
                    .executed(true)
                    .message("Permission replay executed with decision " + (result.granted() ? "ALLOW" : "DENY") + ".")
                    .traceId(ruleTraceId)
                    .matched(result.granted())
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, result))
                    .errors(List.of())
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO permissionNeedsSampleContext(
            ModelPublishReplayStepDTO step,
            String permissionCode,
            String resource,
            String action,
            Long memberId,
            String recordPid,
            String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                .build();
    }

    private ModelPublishReplayResultDTO replaySlaStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        return workflowReplay.replay(step, request);
    }

    private ModelPublishReplayResultDTO replayAutomationStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (automationService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Automation service is unavailable in this runtime.")
                    .errors(List.of("AUTOMATION_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay is available. Pass executeAutomated=true with sampleContext.record.pid to run it.")
                    .errors(List.of())
                    .outputs(automationReplayBaseOutputs(step, null, null))
                    .build();
        }
        if (!StringUtils.hasText(step.getSourcePid())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay requires sourcePid.")
                    .errors(List.of("MISSING_AUTOMATION_PID"))
                    .outputs(Map.of())
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return automationNeedsSampleContext(step, "Automation replay requires representative sampleContext.");
        }
        String recordPid = sampleRecordPid(request.getSampleContext());
        if (!StringUtils.hasText(recordPid)) {
            return automationNeedsSampleContext(step,
                    "Automation replay requires sampleContext.record.pid or sampleContext.record.recordPid.");
        }

        Map<String, Object> replayContext = new LinkedHashMap<>();
        replayContext.putAll(request.getSampleContext());
        try {
            AutomationLogDTO log = automationService.triggerManually(step.getSourcePid(), recordPid, replayContext);
            boolean failed = automationReplayFailed(log);
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(failed ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!failed)
                    .message(log == null
                            ? "Automation replay returned no execution log."
                            : "Automation replay executed with status " + log.getStatus() + ".")
                    .traceId(log != null ? log.getPid() : null)
                    .matched(log != null && !failed)
                    .outputs(automationReplayBaseOutputs(step, recordPid, log))
                    .errors(automationReplayErrors(log))
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(automationReplayBaseOutputs(step, recordPid, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO automationNeedsSampleContext(ModelPublishReplayStepDTO step, String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(automationReplayBaseOutputs(step, null, null))
                .build();
    }

    private String sampleRecordPid(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return "";
        }
        String recordPid = firstString(sampleContext.get("record"), "pid", "recordPid", "publicId");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        recordPid = firstString(sampleContext.get("trigger"), "recordPid", "pid");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        recordPid = firstString(sampleContext.get("meta"), "recordPid", "recordPublicId");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        Map<String, Object> record = sampleContext.get("record");
        if (record != null && record.get("data") instanceof Map<?, ?> data) {
            return firstString(data, "pid", "recordPid", "publicId");
        }
        return "";
    }

    private String firstString(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private Long samplePermissionMemberId(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return null;
        }
        Long memberId = firstLong(sampleContext.get("permission"), "memberId", "tenantMemberId", "actorMemberId");
        if (memberId != null) {
            return memberId;
        }
        memberId = firstLong(sampleContext.get("actor"), "memberId", "tenantMemberId", "actorMemberId");
        if (memberId != null) {
            return memberId;
        }
        return firstLong(sampleContext.get("meta"), "memberId", "tenantMemberId", "actorMemberId");
    }

    private Long firstLong(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            Long parsed = parseLong(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String permissionReplayPermissionCode(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null, "permissionCode");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "permissionCode");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return step != null ? nullToBlank(step.getSourceCode()) : "";
    }

    private String permissionReplayResource(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext,
            String permissionCode) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null,
                "resource", "resourceCode", "modelCode");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "resourceCode");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return derivePermissionResource(permissionCode);
    }

    private String permissionReplayAction(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext,
            String permissionCode) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null, "action");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "action");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return derivePermissionAction(permissionCode);
    }

    private String derivePermissionResource(String permissionCode) {
        if (!StringUtils.hasText(permissionCode)) {
            return "";
        }
        String code = permissionCode.trim();
        int colon = code.lastIndexOf(':');
        if (colon > 0) {
            return code.substring(0, colon);
        }
        int lastDot = code.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        String resource = code.substring(0, lastDot);
        return resource.startsWith("model.") ? resource.substring("model.".length()) : resource;
    }

    private String derivePermissionAction(String permissionCode) {
        if (!StringUtils.hasText(permissionCode)) {
            return "";
        }
        String code = permissionCode.trim();
        int colon = code.lastIndexOf(':');
        if (colon >= 0 && colon < code.length() - 1) {
            return code.substring(colon + 1);
        }
        int lastDot = code.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < code.length() - 1) {
            return code.substring(lastDot + 1);
        }
        return "";
    }

    private Map<String, Object> permissionReplayRecord(
            Map<String, Map<String, Object>> sampleContext,
            String recordPid) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> record = new LinkedHashMap<>(sampleRecordData(sampleContext));
        Map<String, Object> rawRecord = sampleContext.get("record");
        if (StringUtils.hasText(recordPid)) {
            record.putIfAbsent("pid", recordPid);
        }
        Long recordId = firstLong(rawRecord, "id", "recordId", "internalId");
        if (recordId != null) {
            record.putIfAbsent("id", recordId);
        }
        if (rawRecord != null) {
            Object meta = rawRecord.get("meta");
            if (meta != null) {
                record.putIfAbsent("meta", meta);
            }
        }
        return record;
    }

    private Map<String, Object> sampleRecordData(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) return Map.of();
        Map<String, Object> record = sampleContext.get("record");
        if (record != null && record.get("data") instanceof Map<?, ?> data) {
            return data.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String)
                    .collect(Collectors.toMap(entry -> (String) entry.getKey(), Map.Entry::getValue,
                            (left, right) -> right, LinkedHashMap::new));
        }
        return record == null ? Map.of() : record.entrySet().stream()
                .filter(entry -> !"pid".equals(entry.getKey())
                        && !"recordPid".equals(entry.getKey())
                        && !"publicId".equals(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right, LinkedHashMap::new));
    }

    private Map<String, Object> permissionReplayOutputs(
            ModelPublishReplayStepDTO step,
            String permissionCode,
            String resource,
            String action,
            Long memberId,
            String recordPid,
            PermissionResult result) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("permissionPolicyPid", step != null ? step.getSourcePid() : null);
        outputs.put("permissionCode", permissionCode);
        outputs.put("resource", resource);
        outputs.put("action", action);
        outputs.put("memberId", idForBrowser(memberId));
        outputs.put("recordPid", recordPid);
        if (step != null && step.getMetadata() != null) {
            outputs.put("roleId", idForBrowser(step.getMetadata().get("roleId")));
            outputs.put("grantType", step.getMetadata().get("grantType"));
            outputs.put("status", step.getMetadata().get("status"));
            if (hasFieldGovernanceReplayMetadata(step)) {
                outputs.put("affectedFieldRef", step.getFieldRef());
                putReplayMetadataOutput(outputs, step, "fieldRiskLevel");
                putReplayMetadataOutput(outputs, step, "fieldRiskSummary");
                putReplayMetadataOutput(outputs, step, "fieldMasked");
                putReplayMetadataOutput(outputs, step, "fieldPermissionChange");
                putReplayMetadataOutput(outputs, step, "fieldPermission");
                putReplayMetadataOutput(outputs, step, "requiresLowPermissionSample");
            }
        }
        if (result != null) {
            outputs.put("ruleTraceId", permissionRuleTraceId(result));
            outputs.put("granted", result.granted());
            outputs.put("reason", result.reason());
            outputs.put("stepCount", result.steps() != null ? result.steps().size() : 0);
            outputs.put("steps", permissionReplayStepOutputs(result.steps()));
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private boolean hasFieldGovernanceReplayMetadata(ModelPublishReplayStepDTO step) {
        if (step == null || step.getMetadata() == null) {
            return false;
        }
        return step.getMetadata().containsKey("fieldRiskLevel")
                || step.getMetadata().containsKey("fieldMasked")
                || step.getMetadata().containsKey("fieldPermissionChange")
                || step.getMetadata().containsKey("fieldPermission");
    }

    private void putReplayMetadataOutput(
            Map<String, Object> outputs,
            ModelPublishReplayStepDTO step,
            String key) {
        Object value = step != null && step.getMetadata() != null ? step.getMetadata().get(key) : null;
        if (value != null) {
            outputs.put(key, value);
        }
    }

    private List<Map<String, Object>> permissionReplayStepOutputs(List<EvaluationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(Objects::nonNull)
                .map(step -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("evaluatorName", step.evaluatorName());
                    item.put("verdict", step.verdict() != null ? step.verdict().name() : null);
                    item.put("reason", step.reason());
                    if (step.details() != null && !step.details().isEmpty()) {
                        item.put("details", step.details());
                    }
                    Map<String, Object> cleaned = item.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (left, right) -> left,
                                    LinkedHashMap::new));
                    return cleaned;
                })
                .toList();
    }

    private String permissionRuleTraceId(PermissionResult result) {
        if (result == null || result.steps() == null) {
            return null;
        }
        for (EvaluationStep step : result.steps()) {
            if (step == null || step.details() == null) {
                continue;
            }
            Object value = step.details().get("ruleTraceId");
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
            value = nestedPermissionRuleTraceId(step.details().get("ruleCenterFailures"));
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String nestedPermissionRuleTraceId(Object failures) {
        if (!(failures instanceof List<?> failureList)) {
            return null;
        }
        for (Object failure : failureList) {
            if (!(failure instanceof Map<?, ?> failureMap)) {
                continue;
            }
            Object value = failureMap.get("ruleTraceId");
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String idForBrowser(Object value) {
        if (value instanceof Number number) {
            return number.toString();
        }
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private boolean automationReplayFailed(AutomationLogDTO log) {
        if (log == null) {
            return true;
        }
        String status = nullToBlank(log.getStatus()).toLowerCase(Locale.ROOT);
        return StatusConstants.FAILED.equals(status)
                || "error".equals(status)
                || StatusConstants.CANCELLED.equals(status);
    }

    private List<String> automationReplayErrors(AutomationLogDTO log) {
        if (log == null) {
            return List.of("AUTOMATION_REPLAY_RETURNED_NO_LOG");
        }
        if (StringUtils.hasText(log.getErrorMessage())) {
            return List.of(log.getErrorMessage());
        }
        return List.of();
    }

    private Map<String, Object> automationReplayBaseOutputs(
            ModelPublishReplayStepDTO step,
            String recordPid,
            AutomationLogDTO log) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("automationPid", step != null ? step.getSourcePid() : null);
        outputs.put("automationCode", step != null ? step.getSourceCode() : null);
        outputs.put("recordPid", recordPid);
        if (step != null && step.getMetadata() != null) {
            outputs.put("modelCode", step.getMetadata().get("modelCode"));
            outputs.put("triggerType", step.getMetadata().get("triggerType"));
            outputs.put("enabled", step.getMetadata().get("enabled"));
        }
        if (log != null) {
            outputs.put("logPid", log.getPid());
            outputs.put("logStatus", log.getStatus());
            outputs.put("triggerRecordPid", log.getTriggerRecordPid());
            outputs.put("triggerType", log.getTriggerType());
            outputs.put("durationMs", log.getDurationMs());
            outputs.put("actionCount", log.getActionResults() != null ? log.getActionResults().size() : 0);
            outputs.put("actionResults", log.getActionResults() != null ? log.getActionResults() : List.of());
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ModelPublishReplayResultDTO replayDecisionStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (decisionEvaluationService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Decision evaluation service is unavailable in this runtime.")
                    .errors(List.of("DECISION_EVALUATION_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay is available. Pass executeAutomated=true with sampleContext to run it.")
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }
        if (!StringUtils.hasText(step.getSourceCode())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay requires sourceCode.")
                    .errors(List.of("MISSING_DECISION_CODE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("NEEDS_SAMPLE_CONTEXT")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay requires representative sampleContext.")
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }

        DrtEvaluateRequest evaluateRequest = new DrtEvaluateRequest();
        evaluateRequest.setDecisionCode(step.getSourceCode());
        evaluateRequest.setCallerType("MODEL_PUBLISH_REPLAY");
        evaluateRequest.setCallerRef(StringUtils.hasText(model.getPid()) ? model.getPid() : model.getCode());
        evaluateRequest.setCorrelationId(StringUtils.hasText(request.getCorrelationId())
                ? request.getCorrelationId()
                : "model-publish-replay-" + nullToBlank(model.getPid()));
        evaluateRequest.setContext(request.getSampleContext());
        try {
            DecisionResult result = decisionEvaluationService.evaluate(evaluateRequest);
            boolean error = result != null && result.errors() != null && !result.errors().isEmpty();
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(error ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!error)
                    .message(result == null
                            ? "Decision replay returned no result."
                            : "Decision replay executed with status " + result.status() + ".")
                    .traceId(result != null ? result.traceId() : null)
                    .matched(result != null ? result.matched() : null)
                    .outputs(result != null && result.outputs() != null ? result.outputs() : Map.of())
                    .errors(result != null && result.errors() != null ? result.errors() : List.of())
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(Map.of())
                    .build();
        }
    }

    private ModelPublishReplayResultDTO replayEventPolicyStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (eventPolicyRuntimeService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy runtime service is unavailable in this runtime.")
                    .errors(List.of("EVENT_POLICY_RUNTIME_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        Map<String, Object> metadata = step.getMetadata() == null ? Map.of() : step.getMetadata();
        String eventType = metadataText(metadata, "eventType");
        String targetType = metadataText(metadata, "targetType");
        String targetKey = metadataText(metadata, "targetKey");
        if (!StringUtils.hasText(eventType) || !StringUtils.hasText(targetType) || !StringUtils.hasText(targetKey)) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message("EventPolicy replay requires eventType, targetType and targetKey metadata. Rebuild the Rule Center usage index and retry.")
                    .errors(List.of("MISSING_EVENT_POLICY_REPLAY_METADATA"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay is available. Pass executeAutomated=true with sampleContext to run it.")
                    .errors(List.of())
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("NEEDS_SAMPLE_CONTEXT")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay requires representative sampleContext.")
                    .errors(List.of())
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }

        try {
            EventPolicyExecutionResult result = eventPolicyRuntimeService.runAndExecute(
                    eventType,
                    targetType,
                    targetKey,
                    request.getSampleContext());
            EventPolicyResult policy = result != null ? result.policy() : null;
            PolicyExecutionResult execution = result != null ? result.execution() : null;
            List<String> errors = eventPolicyReplayErrors(policy, execution);
            boolean failed = eventPolicyReplayFailed(policy, execution, errors);
            Map<String, Object> outputs = eventPolicyReplayOutputs(eventType, targetType, targetKey, policy, execution);
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(failed ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!failed)
                    .message(policy == null
                            ? "EventPolicy replay returned no policy result."
                            : "EventPolicy replay executed with status " + policy.status() + ".")
                    .traceId(policy != null && StringUtils.hasText(policy.primaryDecisionTraceId())
                            ? policy.primaryDecisionTraceId()
                            : policy != null ? policy.correlationId() : null)
                    .matched(policy != null && policy.status() == EventPolicyResult.Status.MATCHED)
                    .outputs(outputs)
                    .errors(errors)
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean eventPolicyReplayFailed(
            EventPolicyResult policy,
            PolicyExecutionResult execution,
            List<String> errors) {
        if (policy == null) {
            return true;
        }
        if (policy.status() == EventPolicyResult.Status.ERROR || policy.status() == EventPolicyResult.Status.CONFLICT) {
            return true;
        }
        if (execution != null && (execution.overallStatus() == PolicyExecutionResult.OverallStatus.FAILED
                || execution.overallStatus() == PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS)) {
            return true;
        }
        return errors != null && !errors.isEmpty()
                && policy.status() != EventPolicyResult.Status.NOT_MATCHED;
    }

    private List<String> eventPolicyReplayErrors(EventPolicyResult policy, PolicyExecutionResult execution) {
        List<String> errors = new ArrayList<>();
        if (policy != null && policy.errors() != null) {
            errors.addAll(policy.errors());
        }
        if (execution != null && execution.actions() != null) {
            for (ActionExecutionResult action : execution.actions()) {
                if (action != null && StringUtils.hasText(action.error())) {
                    errors.add(action.error());
                }
            }
            if (execution.overallStatus() == PolicyExecutionResult.OverallStatus.FAILED
                    || execution.overallStatus() == PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS) {
                errors.add("EVENT_POLICY_EXECUTION_" + execution.overallStatus());
            }
        }
        return List.copyOf(errors);
    }

    private Map<String, Object> eventPolicyReplayOutputs(
            String eventType,
            String targetType,
            String targetKey,
            EventPolicyResult policy,
            PolicyExecutionResult execution) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("eventType", eventType);
        outputs.put("targetType", targetType);
        outputs.put("targetKey", targetKey);
        if (policy != null) {
            outputs.put("policyCode", policy.policyCode());
            outputs.put("policyStatus", policy.status() != null ? policy.status().name() : null);
            outputs.put("correlationId", policy.correlationId());
            outputs.put("primaryDecisionTraceId", policy.primaryDecisionTraceId());
            outputs.put("decisionTraceIds", policy.decisionTraceIds());
            outputs.put("matchedRuleCodes", policy.matchedRuleCodes());
            outputs.put("skippedRuleCodes", policy.skippedRuleCodes());
            outputs.put("actionPlanCount", policy.actionPlans() != null ? policy.actionPlans().size() : 0);
        }
        if (execution != null) {
            outputs.put("executionStatus", execution.overallStatus() != null ? execution.overallStatus().name() : null);
            outputs.put("actionCount", execution.actions() != null ? execution.actions().size() : 0);
            outputs.put("successfulActionCount", execution.successCount());
            outputs.put("actions", eventPolicyActionOutputs(execution.actions()));
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<Map<String, Object>> eventPolicyActionOutputs(List<ActionExecutionResult> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .map(action -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ruleCode", action.ruleCode());
                    item.put("type", action.type());
                    item.put("status", action.status() != null ? action.status().name() : null);
                    item.put("idempotencyKey", action.idempotencyKey());
                    item.put("error", action.error());
                    item.put("resultPayload", action.resultPayload());
                    Map<String, Object> cleaned = item.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (left, right) -> left,
                                    LinkedHashMap::new));
                    return cleaned;
                })
                .toList();
    }

    private String normalizeReplayConsumerType(String sourceType) {
        String normalized = nullToBlank(sourceType).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BPM" -> "WORKFLOW_PROCESS";
            case "SLA" -> "SLA_RULE";
            case "EVENT" -> "EVENT_POLICY";
            case "PERMISSION" -> "PERMISSION_POLICY";
            case "DECISION" -> "DECISION_VERSION";
            default -> normalized;
        };
    }

    private String replayConsumerLabel(String consumerType) {
        return switch (nullToBlank(consumerType)) {
            case "DECISION_VERSION" -> "决策版本";
            case "WORKFLOW_PROCESS" -> "BPM 流程";
            case "SLA_RULE" -> "SLA 策略";
            case "AUTOMATION" -> "自动化";
            case "EVENT_POLICY" -> "事件策略";
            case "PERMISSION_POLICY" -> "权限策略";
            case "NAMED_QUERY" -> "命名查询";
            default -> "规则消费方";
        };
    }

    private String replayRecommendedAction(String consumerType) {
        return switch (nullToBlank(consumerType)) {
            case "DECISION_VERSION" -> "重新校验并发布受影响决策版本，使用代表性记录回放命中结果。";
            case "WORKFLOW_PROCESS" -> "打开 BPMN 设计器校验规则绑定、候选人、网关条件和服务任务参数，重新部署流程。";
            case "SLA_RULE" -> "重新校验 SLA 条件与超时动作，回放 deadline 和升级策略。";
            case "AUTOMATION" -> "重新校验自动化触发条件和动作字段映射，执行一次测试运行。";
            case "EVENT_POLICY" -> "重新校验事件条件、动作 payload 和 Trace 链路，执行一次策略回放。";
            case "PERMISSION_POLICY" -> "重新校验 ABAC 条件与 allow/deny 矩阵，执行权限审计回放。";
            case "NAMED_QUERY" -> "重新校验查询字段、下游可视化和导出消费。";
            default -> "复核字段引用、重新发布或回放该消费方。";
        };
    }

    private String buildHistoricalVersionPolicy(Model model) {
        Integer latestPublishedVersion = latestPublishedVersion(model);
        if (latestPublishedVersion == null) {
            return "Initial publish: no historical published model version exists. Rule consumers should bind to this published schema after publish.";
        }
        return "Latest-compatible policy: publishing this draft makes it the current model metadata. Existing published rule, BPM, SLA, Automation and EventPolicy versions keep their own versioned assets, but consumers using latest model fields must be replayed and republished after acknowledgement.";
    }

    private Integer latestPublishedVersion(Model model) {
        if (model == null || !StringUtils.hasText(model.getCode())) {
            return null;
        }
        List<Model> versions = metaModelMapper.findAllVersionsByCode(model.getCode());
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.stream()
                .filter(version -> StatusConstants.PUBLISHED.equals(version.getStatus()))
                .map(Model::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    String governanceImpactSummary(ModelPublishGovernanceDTO governance) {
        if (governance == null || governance.getFieldImpacts() == null || governance.getFieldImpacts().isEmpty()) {
            return "schema change detected";
        }
        return governance.getFieldImpacts().stream()
                .map(impact -> impact.getFieldRef() + " -> "
                        + (impact.getRisk() != null ? impact.getRisk().getSummary() : "unknown impact"))
                .collect(Collectors.joining("; "));
    }

    void recordModelPublishAcknowledgement(Model model, ModelPublishGovernanceDTO governance, String note) {
        if (decisionImpactAckService == null) {
            log.warn("Decision impact acknowledgement service unavailable; model publish ack not persisted: model={}",
                    LogSanitizer.safe(model.getCode()));
            return;
        }
        decisionImpactAckService.recordAcknowledgement(
                "MODEL_PUBLISH",
                "MODEL",
                model.getCode(),
                model.getPid(),
                model.getCode(),
                governanceImpactSummary(governance),
                governance,
                note);
    }
}
