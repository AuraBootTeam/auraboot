package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleOverride;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.module.meta.event.DomainEventPublisher;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.meta.dto.ChangeRecord;
import com.auraboot.framework.meta.dto.FieldChange;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.validation.ComputedFieldDependencyResolver;
import com.auraboot.framework.meta.validation.CrossFieldRuleEngine;
import com.auraboot.framework.meta.validation.RuleEvaluationResult;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.BiTemporalAccessorImpl;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;


import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Command Executor implementation.
 * Executes commands through the pipeline:
 * Load → Schema Validate → Idempotency → STATE_CHECK → ASSERT → PRE_INVARIANT → FIELD_MAP → HANDLER → EFFECT → POST_INVARIANT → Audit
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandExecutorImpl implements CommandExecutor, CommandExecutorDelegate {

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final CommandMetadataCacheService commandMetadataCache;
    private final DynamicDataMapper dynamicDataMapper;
    private final DynamicDataService dynamicDataService;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final MetaModelService metaModelService;
    private final IdempotencyService idempotencyService;
    private final ConcurrencyGuard concurrencyGuard;
    private final InvariantEngine invariantEngine;
    private final ChangeTracker changeTracker;
    private final WebhookDispatcher webhookDispatcher;
    private final ApiConnectorService apiConnectorService;
    private final ExtensionRegistry extensionRegistry;
    private final CommandSpelEvaluator spelEvaluator;
    private final CommandCascadeDeleteExecutor cascadeDeleteExecutor;
    private final CommandSideEffectExecutor sideEffectExecutor;
    private final DomainEventPublisher domainEventPublisher;
    private final CommandFieldMapExecutor fieldMapExecutor;
    private final CommandStateCheckExecutor stateCheckExecutor;
    private final CommandEffectExecutor effectExecutor;
    private final CommandAutoSetExecutor autoSetExecutor;
    private final ValidationService validationService;
    private final EntitlementChecker entitlementChecker;
    private final RollUpFieldRegistry rollUpFieldRegistry;
    private final RollUpSummaryService rollUpSummaryService;
    private final PayloadTemporalNormalizer payloadTemporalNormalizer;
    private final com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader recordSnapshotReader;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private CommandPipeline commandPipeline;

    @Autowired(required = false)
    private I18nService i18nService;

    @Autowired(required = false)
    private SodService sodService;

    @Autowired(required = false)
    private BiTemporalService biTemporalService;

    @Autowired(required = false)
    private com.auraboot.framework.infrastructure.metrics.CommandMetrics commandMetrics;

    @Autowired(required = false)
    private com.auraboot.framework.bpm.service.BpmIntegrationService bpmIntegrationService;

    @Autowired(required = false)
    private com.auraboot.framework.governance.service.GovernanceSnapshotService governanceSnapshotService;

    @Autowired(required = false)
    private com.auraboot.framework.consistency.service.ConsistencyRuleService consistencyRuleService;

    /** Cache for column existence checks — table structure rarely changes. Bounded to prevent OOM. */
    private static final int COLUMN_CACHE_MAX_SIZE = 1024;
    private final ConcurrentHashMap<String, Boolean> columnExistsCache = new ConcurrentHashMap<>();

    @Override
    @Transactional
    @Observed(name = "command.execute", contextualName = "command-pipeline")
    public CommandExecuteResult execute(String commandCode, CommandExecuteRequest request) {
        log.info("Executing command: {}", commandCode);
        long startTime = System.currentTimeMillis();
        io.micrometer.core.instrument.Timer.Sample metricsSample = commandMetrics != null ? commandMetrics.startTimer() : null;

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode(commandCode)
                .request(request)
                .tenantId(tenantId)
                .userId(userId)
                .startTime(startTime)
                .build();

        try {
            // Pre-guard phases: Load, SchemaValidate, Idempotency, Entitlement
            CommandExecuteResult shortCircuit = commandPipeline.executePreGuardPhases(ctx);
            if (shortCircuit != null) {
                return shortCircuit;
            }

            // Execute guarded phases (optionally wrapped in concurrency guard)
            java.util.function.Supplier<CommandExecuteResult> guardedPipeline =
                    () -> commandPipeline.executeGuardedPhases(ctx);

            CommandExecuteResult result;
            if (ctx.getConcurrencyKey() != null) {
                result = concurrencyGuard.executeWithLock(ctx.getConcurrencyKey(), ctx.getLockTimeoutMs(), guardedPipeline);
            } else {
                result = guardedPipeline.get();
            }

            // Record success metrics
            if (metricsSample != null && commandMetrics != null) {
                commandMetrics.recordCommandExecution(metricsSample, commandCode, result.getCommandCode(), true);
            }

            return result;

        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            String phaseReached = ctx.getCurrentPhase() != null ? ctx.getCurrentPhase() : "init";
            log.error("Command {} failed at phase {}: {}", commandCode, phaseReached, e.getMessage());

            // Record failure metrics
            if (metricsSample != null && commandMetrics != null) {
                commandMetrics.recordCommandExecution(metricsSample, "unknown", "unknown", false);
            }

            // Audit log for failure
            Map<String, Long> phaseTimings = ctx.getPhaseTimings();
            if (ctx.getCurrentPhase() != null) {
                phaseTimings.put(ctx.getCurrentPhase(), System.currentTimeMillis() - ctx.getCurrentPhaseStart());
            }
            effectExecutor.saveAuditLog(tenantId, commandCode, null, userId,
                    request.getPayload(), null, false, e.getMessage(), executionTimeMs, phaseReached, phaseTimings);

            if (e instanceof BusinessException || e instanceof ValidationException) {
                throw e;
            }
            throw new BusinessException(ResponseCode.BadParam, "Command execution failed: " + e.getMessage());
        }
    }

    // ==================== ExecutionConfig Parsing ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExecutionConfig(CommandDefinition command) {
        if (command.getExecutionConfig() == null || command.getExecutionConfig().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(command.getExecutionConfig(),
                    new TypeReference<Map<String, Object>>() {});
            return result != null ? result : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to parse executionConfig for command {}: {}", command.getCode(), e.getMessage());
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Invalid executionConfig for command " + command.getCode() + ": " + e.getMessage());
        }
    }


    @SuppressWarnings("unchecked")
    @Override
    public void executePostActionPhase(Map<String, Object> execConfig, Map<String, Object> payload,
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

        // Determine the parent record ID (for newly created records, get from fieldMapResults)
        String parentRecordId = null;
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            parentRecordId = request.getTargetRecordId();
        } else if (fieldMapResults != null && fieldMapResults.containsKey("recordId")) {
            // For CREATE commands, the new record's PID is in fieldMapResults
            parentRecordId = String.valueOf(fieldMapResults.get("recordId"));
        }

        for (Map<String, Object> postAction : postActions) {
            String action = (String) postAction.get("type");
            if (action == null) {
                action = (String) postAction.get("action"); // legacy fallback
            }
            String condition = (String) postAction.get("condition");

            // Evaluate condition if present
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
                    // Load chain definition from plugin processes.json or ab_command_chain
                    // For now, the chain definition should be provided directly in postAction config
                    @SuppressWarnings("unchecked")
                    var chainConfig = (Map<String, Object>) postAction.get("chainDefinition");
                    if (chainConfig != null) {
                        chainDef = objectMapper.convertValue(chainConfig, com.auraboot.framework.bpm.chain.CommandChainDefinition.class);
                    } else if (chainProcessKey != null) {
                        // Load from plugin config (future: dedicated chain definition repository)
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

    @SuppressWarnings("unchecked")
    private void executePostActionCreateChildren(Map<String, Object> postAction, String parentRecordId,
                                                   Map<String, Object> payload,
                                                   Long tenantId, Long userId) {
        // Support both DSL naming conventions:
        // Convention A: targetModel + fieldMapping + count
        // Convention B: childModel + parentField + records[]
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

        // Security: validate parentField to prevent SQL injection via dynamic data service
        if (parentField != null) {
            CommandExecutorUtils.validateSqlIdentifier(parentField, "CREATE_CHILDREN parentField");
        }

        // Convention B: records[] with parentField - each entry in records becomes a child record
        if (recordTemplates != null && !recordTemplates.isEmpty()) {
            int created = 0;
            for (Map<String, Object> template : recordTemplates) {
                Map<String, Object> data = new HashMap<>();
                data.put("tenant_id", tenantId);
                // Set parent FK
                if (parentField != null && parentRecordId != null) {
                    data.put(parentField, parentRecordId);
                }
                // Copy template fields
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

        // Convention A: fieldMapping + count
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
                        value = i + 1; // 1-based index
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

    // ==================== Private Helpers ====================

    /**
     * Check if a table has a specific column using cached JDBC metadata.
     * Uses @InterceptorIgnore on the mapper to bypass TenantLineInterceptor.
     * Result is cached since table structure rarely changes at runtime.
     */
    private boolean hasColumn(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return false;
        }
        String cacheKey = tableName + ":" + columnName;
        // Evict oldest entries when cache exceeds max size to prevent unbounded growth
        if (columnExistsCache.size() >= COLUMN_CACHE_MAX_SIZE) {
            var it = columnExistsCache.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        return columnExistsCache.computeIfAbsent(cacheKey, k -> {
            try {
                return dynamicDataMapper.checkColumnExists(tableName, columnName) > 0;
            } catch (Exception e) {
                log.debug("Failed to check column existence for {}.{}, assuming absent: {}",
                        tableName, columnName, e.getMessage());
                return false;
            }
        });
    }

    /**
     * Resolve concurrency key from pre-parsed execution config.
     */
    private String resolveConcurrencyKeyFromConfig(Map<String, Object> config, Map<String, Object> payload) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        String keyTemplate = (String) config.get("concurrencyKey");
        if (keyTemplate == null || keyTemplate.isEmpty()) {
            return null;
        }
        // Resolve ${payload.xxx} placeholders
        String resolved = keyTemplate;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String placeholder = "${payload." + entry.getKey() + "}";
            if (resolved.contains(placeholder) && entry.getValue() != null) {
                resolved = resolved.replace(placeholder, entry.getValue().toString());
            }
        }
        return resolved;
    }

    /**
     * Resolve lock timeout from pre-parsed execution config.
     */
    private long resolveLockTimeoutFromConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return 5000L; // default 5s
        }
        Object timeout = config.get("lockTimeoutMs");
        if (timeout instanceof Number) {
            return ((Number) timeout).longValue();
        }
        return 5000L;
    }



    /**
     * Auto-recalculate parent roll-up fields when a child model command is executed.
     * Looks up the RollUpFieldRegistry to find parent models with roll-up fields that reference
     * the current command's model as a child, then recalculates each matching roll-up.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void executeRollUpRecalculation(String modelCode, Map<String, Object> payload,
                                             Map<String, Object> fieldMapResults, Long tenantId) {
        List<RollUpFieldRegistry.RollUpTarget> targets = rollUpFieldRegistry.getTargets(modelCode);
        if (targets.isEmpty()) return;

        // Build a merged context to find parent FK values
        Map<String, Object> context = new HashMap<>();
        if (payload != null) context.putAll(payload);
        if (fieldMapResults != null) context.putAll(fieldMapResults);

        for (RollUpFieldRegistry.RollUpTarget target : targets) {
            try {
                // Find the parent record ID from the child's FK field value
                Object parentIdObj = context.get(target.getChildFk());
                if (parentIdObj == null) {
                    // Try column name variant
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

    /**
     * Auto-capture a governance version snapshot after successful command execution
     * on models with autoSnapshot policy enabled.
     */
    @Override
    public void executeGovernanceSnapshot(String modelCode, Map<String, Object> payload,
                                            Map<String, Object> fieldMapResults, Long tenantId, Long userId) {
        if (governanceSnapshotService == null) return;

        try {
            // Build merged data context
            Map<String, Object> context = new HashMap<>();
            if (payload != null) context.putAll(payload);
            if (fieldMapResults != null) context.putAll(fieldMapResults);

            // Try to extract the record PID from fieldMapResults or payload
            String recordPid = null;
            if (fieldMapResults != null && fieldMapResults.containsKey("pid")) {
                recordPid = String.valueOf(fieldMapResults.get("pid"));
            } else if (payload != null && payload.containsKey("pid")) {
                recordPid = String.valueOf(payload.get("pid"));
            }

            if (recordPid == null || "null".equals(recordPid)) {
                return; // Can't snapshot without a record PID
            }

            String userPid = MetaContext.exists() ? MetaContext.getCurrentUserPid() : null;
            governanceSnapshotService.captureSnapshotIfGoverned(
                    modelCode, recordPid, context, tenantId, userPid, "Auto-snapshot from command execution");
        } catch (Exception e) {
            log.warn("Governance snapshot failed for model {}: {}", modelCode, e.getMessage());
        }
    }

    /**
     * Evaluate cross-document consistency rules before committing changes.
     * Runs all enabled CROSS_DOCUMENT rules for the source model;
     * if any ERROR-severity violations are found the transaction is aborted.
     * WARNING-severity violations are logged but don't block.
     * Only active when the command type is CREATE or UPDATE.
     */
    @Override
    public void executeConsistencyCheckPhase(
            com.auraboot.framework.meta.entity.CommandDefinition command,
            Map<String, Object> payload,
            Map<String, Object> fieldMapResults,
            Long tenantId,
            Map<String, Object> execConfig) {

        if (consistencyRuleService == null) return;

        String cmdType = execConfig != null ? (String) execConfig.get("type") : null;
        boolean isCreateOrUpdate = "create".equalsIgnoreCase(cmdType) || "update".equalsIgnoreCase(cmdType);
        if (!isCreateOrUpdate) return;

        String modelCode = command.getModelCode();
        if (!StringUtils.hasText(modelCode)) return;

        try {
            // Extract recordId from payload or fieldMapResults for post-save validation
            Object recordIdRaw = payload != null ? payload.get("id") : null;
            if (recordIdRaw == null && fieldMapResults != null) recordIdRaw = fieldMapResults.get("id");
            if (recordIdRaw == null) return; // no record ID available (e.g. pre-save CREATE), skip
            String recordId = String.valueOf(recordIdRaw);
            var violations = consistencyRuleService.validate(modelCode, recordId);

            if (violations == null || violations.isEmpty()) return;

            // Separate ERROR vs WARNING violations
            var errorViolations = violations.stream()
                    .filter(v -> "error".equals(v.getSeverity()))
                    .collect(java.util.stream.Collectors.toList());

            var warningViolations = violations.stream()
                    .filter(v -> !"error".equals(v.getSeverity()))
                    .collect(java.util.stream.Collectors.toList());

            // Log warnings
            for (var w : warningViolations) {
                log.warn("Consistency warning [{}]: {}", w.getRuleCode(), w.getMessage());
            }

            // Throw on ERROR violations
            if (!errorViolations.isEmpty()) {
                throw new com.auraboot.framework.consistency.exception.ConsistencyViolationException(errorViolations);
            }
        } catch (com.auraboot.framework.consistency.exception.ConsistencyViolationException ex) {
            throw ex; // rethrow — violates a hard constraint, abort the transaction
        } catch (Exception e) {
            log.warn("Consistency check failed for model {} (non-fatal): {}", modelCode, e.getMessage());
        }
    }

    /**
     * Transitions to a new pipeline phase, recording timing for the current phase.
     * Stores elapsed time (ms) for the current phase in phaseTimings, then advances to the new phase.
     */
    private void transitionPhase(String[] phaseRef, long[] phaseStartRef,
                                  Map<String, Long> phaseTimings, String newPhase) {
        long now = System.currentTimeMillis();
        phaseTimings.put(phaseRef[0], now - phaseStartRef[0]);
        phaseStartRef[0] = now;
        phaseRef[0] = newPhase;
    }

}
