package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.RollUpFieldRegistry;
import com.auraboot.framework.meta.service.impl.RollUpSummaryService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Groups side effects, roll-up, governance snapshot, and post-action.
 */
@Slf4j
@Component
@Order(1300)
@RequiredArgsConstructor
public class PostExecutionPhase implements CommandPhase {

    private final CommandSideEffectExecutor sideEffectExecutor;
    private final RollUpFieldRegistry rollUpFieldRegistry;
    private final RollUpSummaryService rollUpSummaryService;
    private final CommandSpelEvaluator spelEvaluator;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final DynamicDataService dynamicDataService;

    @Autowired(required = false)
    private com.auraboot.framework.governance.service.GovernanceSnapshotService governanceSnapshotService;

    @Autowired(required = false)
    private com.auraboot.framework.bpm.service.BpmIntegrationService bpmIntegrationService;

    @Override public String name() { return "post_execution"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        boolean dryRun = ctx.getRequest() != null && ctx.getRequest().isDryRun();

        // PR-62 R2-N2: PostExecutionPhase runs arbitrary side effects
        // declared by `execConfig` (side-effect rules create/update other
        // model records), roll-up summaries, governance snapshots, and
        // postActions (including `start_process` which kicks off a BPM
        // process instance via SmartEngine). While every path today joins
        // the outer transaction through @Transactional(REQUIRED), BPM
        // process start may trigger async task listeners and downstream
        // state persistence that are easy to refactor into non-JDBC
        // emissions. We gate the entire phase under dry-run to make the
        // intent explicit and future-proof — see learning-loop.md
        // "Plugin handler dry-run contract".
        if (dryRun) {
            log.info("Dry-run: skipped PostExecutionPhase (side effects / roll-up / governance / postActions) for command {}",
                    ctx.getCommand() != null ? ctx.getCommand().getCode() : "<unknown>");
            return;
        }

        // Side effects
        sideEffectExecutor.executeSideEffectPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());

        // Roll-up recalculation
        executeRollUpRecalculation(ctx.getCommand().getModelCode(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId());

        // Governance snapshot
        executeGovernanceSnapshot(ctx.getCommand().getModelCode(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId(), ctx.getUserId());

        // Post actions
        executePostActionPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());
    }

    // ==================== Inlined delegate methods ====================

    @SuppressWarnings("unchecked")
    private void executeRollUpRecalculation(String modelCode, Map<String, Object> payload,
                                             Map<String, Object> fieldMapResults, Long tenantId) {
        List<RollUpFieldRegistry.RollUpTarget> targets = rollUpFieldRegistry.getTargets(modelCode);
        if (targets.isEmpty()) return;

        Map<String, Object> context = new HashMap<>();
        if (payload != null) context.putAll(payload);
        if (fieldMapResults != null) context.putAll(fieldMapResults);

        for (RollUpFieldRegistry.RollUpTarget target : targets) {
            try {
                Object parentIdObj = context.get(target.getChildFk());
                if (parentIdObj == null) {
                    String colName = target.getChildFk().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                    parentIdObj = context.get(colName);
                }
                if (parentIdObj == null) {
                    log.debug("RollUp: childFk '{}' not found in payload for target {}.{}, skipping",
                            target.getChildFk(), target.getParentModelCode(), target.getParentFieldCode());
                    continue;
                }

                rollUpSummaryService.recalculate(
                        target.getParentModelCode(),
                        target.getParentFieldCode(),
                        parentIdObj.toString(),
                        modelCode,
                        target.getChildField(),
                        target.getChildFk(),
                        target.getFunction(),
                        target.getChildFilter(),
                        tenantId
                );
            } catch (Exception e) {
                log.warn("RollUp recalculation failed for {}.{}: {}",
                        target.getParentModelCode(), target.getParentFieldCode(), e.getMessage());
            }
        }
    }

    private void executeGovernanceSnapshot(String modelCode, Map<String, Object> payload,
                                            Map<String, Object> fieldMapResults, Long tenantId, Long userId) {
        if (governanceSnapshotService == null) return;

        try {
            Map<String, Object> context = new HashMap<>();
            if (payload != null) context.putAll(payload);
            if (fieldMapResults != null) context.putAll(fieldMapResults);

            String recordPid = null;
            if (fieldMapResults != null && fieldMapResults.containsKey("pid")) {
                recordPid = String.valueOf(fieldMapResults.get("pid"));
            } else if (payload != null && payload.containsKey("pid")) {
                recordPid = String.valueOf(payload.get("pid"));
            }

            if (recordPid == null || "null".equals(recordPid)) {
                return;
            }

            String userPid = MetaContext.exists() ? MetaContext.getCurrentUserPid() : null;
            governanceSnapshotService.captureSnapshotIfGoverned(
                    modelCode, recordPid, context, tenantId, userPid, "Auto-snapshot from command execution");
        } catch (Exception e) {
            log.warn("Governance snapshot failed for model {}: {}", modelCode, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void executePostActionPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                         Long tenantId, Long userId, CommandDefinition command,
                                         CommandExecuteRequest request,
                                         Map<String, Object> fieldMapResults) {
        if (execConfig == null || !execConfig.containsKey("postActions")) {
            return;
        }

        List<Map<String, Object>> postActions = (List<Map<String, Object>>) execConfig.get("postActions");
        if (postActions == null || postActions.isEmpty()) {
            return;
        }

        String parentRecordId = null;
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            parentRecordId = request.getTargetRecordId();
        } else if (fieldMapResults != null && fieldMapResults.containsKey("recordId")) {
            parentRecordId = String.valueOf(fieldMapResults.get("recordId"));
        }

        for (Map<String, Object> postAction : postActions) {
            String action = (String) postAction.get("type");
            if (action == null) {
                action = (String) postAction.get("action");
            }
            String condition = (String) postAction.get("condition");

            if (condition != null && !condition.isEmpty()) {
                EvaluationContext spelContext = spelEvaluator.buildSpelContext(payload);
                try {
                    Boolean result = spelEvaluator.evaluate(condition, spelContext, Boolean.class);
                    if (result == null || !result) {
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Failed to evaluate postAction condition '{}': {}", condition, e.getMessage());
                    continue;
                }
            }

            switch (action != null ? action : "") {
                case "create_children" -> executePostActionCreateChildren(postAction, parentRecordId,
                        payload, tenantId, userId);
                case "create_record" -> {
                    String targetModel = (String) postAction.get("targetModel");
                    Map<String, Object> fieldMapping = (Map<String, Object>) postAction.get("fieldMapping");
                    Map<String, Object> currentRecord = sideEffectExecutor.buildCurrentRecordContext(payload, tenantId, command, request);
                    sideEffectExecutor.executeSideEffectCreate(targetModel, fieldMapping, currentRecord, tenantId, userId);
                }
                case com.auraboot.framework.meta.service.impl.pipeline.PreActionConstants.POST_TYPE_START_PROCESS -> {
                    executePostActionStartProcess(postAction, parentRecordId, payload, command);
                }
                case "start_approval_chain" -> {
                    String chainProcessKey = (String) postAction.get("chainProcessKey");
                    String businessKeyTemplate = (String) postAction.get("businessKey");
                    String businessKey = businessKeyTemplate;
                    if (businessKeyTemplate != null && businessKeyTemplate.contains("${")) {
                        businessKey = businessKeyTemplate
                                .replace("${modelCode}", command.getModelCode() != null ? command.getModelCode() : "")
                                .replace("${recordId}", parentRecordId != null ? parentRecordId : "");
                    }
                    var chainService = applicationContext.getBean(
                            com.auraboot.framework.bpm.chain.CommandChainService.class);
                    var chainDef = new com.auraboot.framework.bpm.chain.CommandChainDefinition();
                    @SuppressWarnings("unchecked")
                    var chainConfig = (Map<String, Object>) postAction.get("chainDefinition");
                    if (chainConfig != null) {
                        chainDef = objectMapper.convertValue(chainConfig, com.auraboot.framework.bpm.chain.CommandChainDefinition.class);
                    } else if (chainProcessKey != null) {
                        log.warn("Chain definition loading by processKey not yet implemented, chainProcessKey={}", chainProcessKey);
                        break;
                    }
                    Map<String, Object> chainPayload = new java.util.HashMap<>(payload);
                    if (parentRecordId != null) {
                        chainPayload.put("_chain_business_record_id", parentRecordId);
                    }
                    chainService.executeChain(chainDef, businessKey, chainPayload);
                }
                default -> log.warn("Unknown postAction: {}", action);
            }
        }
    }

    /**
     * Execute a {@code start_process} postAction: starts a BPM process via
     * {@link com.auraboot.framework.bpm.service.BpmIntegrationService} and
     * optionally writes the resulting processInstanceId back onto the current
     * record at field {@code storeInstanceIdIn}.
     */
    @SuppressWarnings("unchecked")
    private void executePostActionStartProcess(Map<String, Object> postAction, String parentRecordId,
                                                Map<String, Object> payload, CommandDefinition command) {
        if (bpmIntegrationService == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "start_process postAction requires BpmIntegrationService");
        }
        String processKey = (String) postAction.get("processKey");
        if (processKey == null || processKey.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam,
                    "start_process postAction requires 'processKey'");
        }
        String businessKey = resolveStartProcessTemplate(
                (String) postAction.get("businessKey"), payload, parentRecordId, command);
        String title = resolveStartProcessTemplate(
                (String) postAction.get("title"), payload, parentRecordId, command);

        Map<String, Object> variablesTemplate = (Map<String, Object>) postAction.get("variables");
        Map<String, Object> variables = new HashMap<>();
        if (variablesTemplate != null) {
            for (Map.Entry<String, Object> entry : variablesTemplate.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String strValue) {
                    // Preserve original types when the entire template is a single placeholder
                    // (e.g. "${payload.wd_req_days}" → Integer 2, not String "2"). Drools
                    // constraints like `((Number) this["days"]).doubleValue()` require this.
                    Object typed = resolveTypedPlaceholder(strValue, payload, parentRecordId, command);
                    value = (typed != null) ? typed : resolveStartProcessTemplate(strValue, payload, parentRecordId, command);
                }
                variables.put(entry.getKey(), value);
            }
        }

        var instance = bpmIntegrationService.startBusinessProcess(processKey, businessKey, variables, title);

        String storeInstanceIdIn = (String) postAction.get("storeInstanceIdIn");
        if (storeInstanceIdIn != null && !storeInstanceIdIn.isBlank()
                && instance != null && instance.getInstanceId() != null
                && parentRecordId != null && command != null && command.getModelCode() != null) {
            try {
                Map<String, Object> update = new HashMap<>();
                update.put(storeInstanceIdIn, instance.getInstanceId());
                dynamicDataService.update(command.getModelCode(), parentRecordId, update);
            } catch (Exception e) {
                log.warn("start_process: failed to write processInstanceId to {}.{}: {}",
                        command.getModelCode(), storeInstanceIdIn, e.getMessage());
            }
        }
    }

    /**
     * If the template is a single whole-string placeholder like
     * {@code "${payload.wd_req_days}"} or {@code "${recordId}"}, return the
     * raw typed value (Integer, Boolean, etc.) so downstream components that
     * need typed values (Drools constraints, SmartEngine numeric compare)
     * don't receive a stringified form. Returns null if the template is not a
     * whole-string placeholder or the value cannot be resolved.
     */
    private Object resolveTypedPlaceholder(String template, Map<String, Object> payload,
                                           String parentRecordId, CommandDefinition command) {
        if (template == null || !template.startsWith("${") || !template.endsWith("}")) {
            return null;
        }
        String inner = template.substring(2, template.length() - 1);
        if (inner.contains("${") || inner.contains("}")) {
            return null; // compound templates → fall back to string substitution
        }
        if ("recordId".equals(inner)) {
            return parentRecordId;
        }
        if ("modelCode".equals(inner) && command != null) {
            return command.getModelCode();
        }
        if (inner.startsWith("payload.") && payload != null) {
            return payload.get(inner.substring("payload.".length()));
        }
        return null;
    }

    /**
     * Resolve {@code ${payload.xxx}}, {@code ${recordId}}, {@code ${modelCode}}
     * placeholders. Only whole-string exact-placeholder substitution is
     * supported for typed returns; embedded substitution uses toString.
     */
    private String resolveStartProcessTemplate(String template, Map<String, Object> payload,
                                                String parentRecordId, CommandDefinition command) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        String result = template;
        if (parentRecordId != null) {
            result = result.replace("${recordId}", parentRecordId);
        }
        if (command != null && command.getModelCode() != null) {
            result = result.replace("${modelCode}", command.getModelCode());
        }
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String placeholder = "${payload." + entry.getKey() + "}";
                if (result.contains(placeholder)) {
                    Object v = entry.getValue();
                    result = result.replace(placeholder, v == null ? "" : v.toString());
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void executePostActionCreateChildren(Map<String, Object> postAction, String parentRecordId,
                                                   Map<String, Object> payload,
                                                   Long tenantId, Long userId) {
        String targetModel = (String) postAction.get("targetModel");
        if (targetModel == null) {
            targetModel = (String) postAction.get("childModel");
        }
        String parentField = (String) postAction.get("parentField");
        Map<String, Object> fieldMapping = (Map<String, Object>) postAction.get("fieldMapping");
        List<Map<String, Object>> recordTemplates = (List<Map<String, Object>>) postAction.get("records");
        Integer count = postAction.get("count") != null ? ((Number) postAction.get("count")).intValue() : null;

        if (targetModel == null) {
            log.warn("POST_ACTION CREATE_CHILDREN: targetModel/childModel is null, skipping");
            return;
        }

        if (parentField != null) {
            CommandExecutorUtils.validateSqlIdentifier(parentField, "CREATE_CHILDREN parentField");
        }

        if (recordTemplates != null && !recordTemplates.isEmpty()) {
            int created = 0;
            for (Map<String, Object> template : recordTemplates) {
                Map<String, Object> data = new HashMap<>();
                data.put("tenant_id", tenantId);
                if (parentField != null && parentRecordId != null) {
                    data.put(parentField, parentRecordId);
                }
                for (Map.Entry<String, Object> entry : template.entrySet()) {
                    data.put(entry.getKey(), entry.getValue());
                }
                try {
                    dynamicDataService.create(targetModel, data);
                    created++;
                } catch (Exception e) {
                    log.error("POST_ACTION CREATE_CHILDREN failed for {} (template {}): {}",
                            targetModel, template, e.getMessage());
                    throw new BusinessException(ResponseCode.BadParam,
                            "Post action failed: create " + targetModel + ": " + e.getMessage());
                }
            }
            log.info("POST_ACTION CREATE_CHILDREN: created {} records in {} (from records[])", created, targetModel);
            return;
        }

        if (fieldMapping == null) {
            log.warn("POST_ACTION CREATE_CHILDREN: no fieldMapping or records, skipping for {}", targetModel);
            return;
        }

        int recordCount = (count != null) ? count : 1;

        for (int i = 0; i < recordCount; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("tenant_id", tenantId);

            for (Map.Entry<String, Object> entry : fieldMapping.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String strValue) {
                    if ("$parent.id".equals(strValue) && parentRecordId != null) {
                        var parentIdEntry = CommandExecutorUtils.resolveRecordIdColumn(parentRecordId);
                        value = parentIdEntry.getValue();
                    } else if ("$index".equals(strValue)) {
                        value = i + 1;
                    } else if (strValue.startsWith("$payload.")) {
                        String fieldName = strValue.substring("$payload.".length());
                        value = payload.get(fieldName);
                    }
                }
                data.put(entry.getKey(), value);
            }

            try {
                dynamicDataService.create(targetModel, data);
            } catch (Exception e) {
                log.error("POST_ACTION CREATE_CHILDREN failed for {} (index {}): {}",
                        targetModel, i, e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Post action failed: create " + targetModel + ": " + e.getMessage());
            }
        }

        log.info("POST_ACTION CREATE_CHILDREN: created {} records in {} (from fieldMapping)", recordCount, targetModel);
    }
}
