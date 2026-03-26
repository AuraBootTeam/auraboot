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


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
public class CommandExecutorImpl implements CommandExecutor {

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
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
        String phaseReached = "init";
        Map<String, Long> phaseTimings = new java.util.LinkedHashMap<>();
        long[] lastPhaseTime = {startTime};

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        try {
            // 1. Load CommandDefinition (tenant_id is automatically added by TenantLineInnerInterceptor)
            phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
            lastPhaseTime[0] = System.currentTimeMillis();
            phaseReached = "load";
            CommandDefinition command = commandDefinitionMapper.findCurrentByCode(commandCode);
            if (command == null) {
                throw new BusinessException(ResponseCode.BadParam, "Command not found: " + commandCode);
            }
            if (!Status.PUBLISHED.getCode().equals(command.getStatus())) {
                throw new BusinessException(ResponseCode.BadParam, "Command is not published: " + commandCode);
            }

            // 2. Schema Validate (basic payload check)
            phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
            lastPhaseTime[0] = System.currentTimeMillis();
            phaseReached = "schema_validate";
            Map<String, Object> payload = request.getPayload() != null ? request.getPayload() : new HashMap<>();

            // 2.1. Temporal Normalization — convert date/datetime strings to typed Java objects
            if (command.getModelCode() != null) {
                metaModelService.getModelDefinition(command.getModelCode())
                        .ifPresent(modelDef -> payloadTemporalNormalizer.normalize(payload, modelDef));
            }

            // 3. Idempotency Check
            phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
            lastPhaseTime[0] = System.currentTimeMillis();
            phaseReached = "idempotency_check";
            if (StringUtils.hasText(request.getClientRequestId())) {
                Map<String, Object> cachedResult = idempotencyService.checkIdempotency(request.getClientRequestId(), tenantId);
                if (cachedResult != null) {
                    log.info("Idempotent replay for command {} with clientRequestId {}", commandCode, request.getClientRequestId());
                    return CommandExecuteResult.builder()
                            .commandCode(commandCode)
                            .phaseReached("completed")
                            .data(cachedResult)
                            .executionTimeMs(System.currentTimeMillis() - startTime)
                            .idempotentReplay(true)
                            .build();
                }
            }

            // 3.5. ENTITLEMENT_CHECK Phase
            if (entitlementChecker.isEnabled()) {
                phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
                lastPhaseTime[0] = System.currentTimeMillis();
                phaseReached = "entitlement_check";
                String modelCode = command.getModelCode();
                if (modelCode != null) {
                    String namespace = modelCode.contains("_")
                            ? modelCode.substring(0, modelCode.indexOf('_'))
                            : modelCode;
                    if (!entitlementChecker.isPluginActive(namespace)) {
                        throw new BusinessException(ResponseCode.FORBIDDEN,
                                "Plugin entitlement required for command: " + commandCode);
                    }
                    if (command.getRequiredFeature() != null && !command.getRequiredFeature().isEmpty()) {
                        if (!entitlementChecker.hasFeature(namespace, command.getRequiredFeature())) {
                            throw new BusinessException(ResponseCode.FORBIDDEN,
                                    "Feature entitlement required: " + command.getRequiredFeature());
                        }
                    }
                }
            }

            // 4. Parse executionConfig once and reuse throughout
            Map<String, Object> execConfig = parseExecutionConfig(command);
            String concurrencyKey = resolveConcurrencyKeyFromConfig(execConfig, payload);
            long lockTimeoutMs = resolveLockTimeoutFromConfig(execConfig);

            // Execute pipeline (optionally wrapped in concurrency guard)
            final CommandDefinition finalCommand = command;
            phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
            final String[] phaseRef = {phaseReached};
            final long[] phaseStartRef = {System.currentTimeMillis()};
            final Map<String, Long> finalPhaseTimings = phaseTimings;
            final Map<String, Object> finalPayload = payload;
            final Map<String, Object> finalExecConfig = execConfig;

            java.util.function.Supplier<CommandExecuteResult> pipeline = () -> {

                // 4.5. SOD_CHECK Phase: Separation of Duties enforcement
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "sod_check");
                if (sodService != null) {
                    String entityType = finalCommand.getModelCode();
                    Long entityId = null;
                    if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
                        try {
                            entityId = Long.parseLong(request.getTargetRecordId());
                        } catch (NumberFormatException e) {
                            // Non-numeric record IDs (e.g. PIDs) — skip entity-level SoD
                        }
                    }
                    String actorName = MetaContext.exists() ? MetaContext.getCurrentUsername() : null;
                    // checkSod will throw SodViolationException for HARD enforcement
                    sodService.checkSod(commandCode, userId, actorName, entityType, entityId);
                }

                // 5. STATE_CHECK Phase (enhanced with multi-branch stateTransitionRules)
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "state_check");
                String targetState = stateCheckExecutor.executeStateCheckPhase(finalCommand, finalPayload, tenantId, request, execConfig);

                // 6. ASSERT Phase (includes preconditions from executionConfig)
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "assert");
                List<BindingRule> assertRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "assert");
                executeAssertPhase(assertRules, finalPayload);
                executePreconditionsPhase(execConfig, finalPayload, tenantId, finalCommand, request);
                executeValidationPhase(execConfig, finalPayload, tenantId, finalCommand, request);

                // 6.5. PRE_INVARIANT Phase
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "pre_invariant");
                String stateFieldForInvariant = stateCheckExecutor.getStateFieldForModel(finalCommand.getModelCode());
                String currentStateForInvariant = (request != null && StringUtils.hasText(request.getTargetRecordId()) && stateFieldForInvariant != null)
                        ? stateCheckExecutor.readCurrentState(tenantId, finalCommand.getModelCode(), request.getTargetRecordId(), stateFieldForInvariant)
                        : null;
                invariantEngine.evaluatePreInvariants(
                        tenantId, finalCommand.getCode(), finalCommand.getModelCode(),
                        finalPayload, request != null ? request.getTargetRecordId() : null,
                        currentStateForInvariant);

                // 6.6. Cross-field validation rules (after invariants)
                executeCrossFieldRules(finalCommand, finalPayload, execConfig);

                boolean hasPluginHandler = hasPluginHandler(finalCommand.getCode());
                boolean pluginRequiresDslPersistence = hasPluginHandler
                        && shouldExecuteDslPersistenceWithPlugin(execConfig, request);

                // 6.8. AUTO_SET Phase: inject auto-generated values into payload
                if (!hasPluginHandler || pluginRequiresDslPersistence) {
                    transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "auto_set");
                    autoSetExecutor.executeAutoSetPhase(execConfig, finalPayload, tenantId, userId, finalCommand);
                    executeCommandFieldValidationPhase(execConfig, finalPayload, finalCommand, request);
                } else {
                    log.info("Skipping AUTO_SET for plugin-handled command: {}", finalCommand.getCode());
                }

                // 7. FIELD_MAP Phase
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "field_map");
                // Capture before-snapshot for change tracking (UPDATE/DELETE)
                Map<String, Object> beforeSnapshot = null;
                if (request != null && StringUtils.hasText(request.getTargetRecordId())
                        && StringUtils.hasText(finalCommand.getModelCode())) {
                    beforeSnapshot = readRecordSnapshot(tenantId, finalCommand.getModelCode(), request.getTargetRecordId());
                }

                // Execute cascade delete before the main delete (if applicable)
                if ("delete".equalsIgnoreCase(request.getOperationType())) {
                    cascadeDeleteExecutor.executeCascadeDeletePhase(execConfig, tenantId, request);
                }

                Map<String, Object> fieldMapResults;
                if (hasPluginHandler && !pluginRequiresDslPersistence) {
                    fieldMapResults = new HashMap<>();
                    log.info("Skipping FIELD_MAP for plugin-handled command: {}", finalCommand.getCode());
                } else {
                    List<BindingRule> fieldMapRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "field_map");
                    boolean noBindingRules = (fieldMapRules == null || fieldMapRules.isEmpty());
                    boolean hasInputFields = (execConfig != null && execConfig.containsKey("inputFields"));
                    boolean hasAutoSetFields = (execConfig != null && execConfig.containsKey("autoSetFields"));
                    boolean isDeleteOp = "delete".equalsIgnoreCase(request.getOperationType());
                    String cmdType = execConfig != null ? (String) execConfig.get("type") : null;
                    boolean isStateTransition = "state_transition".equalsIgnoreCase(cmdType);
                    boolean isCreateOrUpdate = "create".equalsIgnoreCase(cmdType) || "update".equalsIgnoreCase(cmdType);
                    if (noBindingRules && (hasInputFields || hasAutoSetFields || isDeleteOp || isStateTransition || isCreateOrUpdate)) {
                        fieldMapResults = fieldMapExecutor.executeImplicitFieldMapPhase(execConfig, finalPayload, tenantId, request, finalCommand);
                    } else {
                        fieldMapResults = fieldMapExecutor.executeFieldMapPhase(fieldMapRules, finalPayload, tenantId, request);
                    }
                }
                propagateFieldMapRecordId(request, fieldMapResults);

                // 7.3. COMPUTED_FIELDS Phase: calculate SpEL formula fields
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "computed_fields");
                executeComputedFieldsPhase(execConfig, finalPayload, tenantId, finalCommand, request, fieldMapResults);

                // 7.5. Change Tracking: record field-level changes after FIELD_MAP
                recordChangeTracking(finalCommand, request, tenantId, userId, beforeSnapshot);

                // 8. HANDLER Phase
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "handler");
                List<BindingRule> handlerRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "handler");
                Map<String, Object> handlerResults = executeHandlerPhase(handlerRules, finalCommand, finalPayload, fieldMapResults, tenantId, userId, request, finalExecConfig);
                persistHandlerResults(finalCommand.getModelCode(), finalPayload, handlerResults, tenantId, request, fieldMapResults);

                // 8.5. API_CALL Phase: collect rules inside transaction (query DB)
                List<BindingRule> apiCallRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "api_call");

                // 8.7. CONSISTENCY_CHECK Phase: validate cross-document constraints (e.g., shipment qty <= order qty)
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "consistency_check");
                executeConsistencyCheckPhase(finalCommand, finalPayload, fieldMapResults, tenantId, execConfig);

                // 8.8. SIDE_EFFECT Phase: create/update related records based on conditions
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "side_effect");
                sideEffectExecutor.executeSideEffectPhase(execConfig, finalPayload, tenantId, userId, finalCommand, request, fieldMapResults);

                // 8.8.1. ROLL_UP Phase: auto-recalculate parent roll-up fields when child records change
                executeRollUpRecalculation(finalCommand.getModelCode(), finalPayload, fieldMapResults, tenantId);

                // 8.8.2. GOVERNANCE_SNAPSHOT Phase: auto-capture version snapshot for governed models
                executeGovernanceSnapshot(finalCommand.getModelCode(), finalPayload, fieldMapResults, tenantId, userId);

                // 8.9. POST_ACTION Phase: create child records or other post-processing
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "post_action");
                executePostActionPhase(execConfig, finalPayload, tenantId, userId, finalCommand, request, fieldMapResults);

                // 9. EFFECT Phase
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "effect");
                List<BindingRule> effectRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "effect");
                effectExecutor.executeEffectPhase(effectRules, finalCommand, finalPayload, fieldMapResults, tenantId, userId, request, targetState);

                // 9.1. DOMAIN_EVENT Phase: publish for in-process listeners (e.g., finance voucher engine)
                // NOTE: This runs inside the @Transactional boundary — listeners share the same DB transaction.
                // If a listener needs to run after commit, use @TransactionalEventListener(phase = AFTER_COMMIT).
                try {
                    String recordId = request != null ? request.getTargetRecordId() : null;
                    String actorName = MetaContext.exists() ? MetaContext.getCurrentUsername() : null;
                    // Pass beforeSnapshot via extra metadata for field change auditing
                    Map<String, Object> extraMeta = null;
                    if (beforeSnapshot != null) {
                        extraMeta = Map.of("beforeSnapshot", beforeSnapshot);
                    }
                    domainEventPublisher.publishCommandCompleted(
                            finalCommand.getCode(),
                            request != null ? request.getOperationType() : "unknown",
                            tenantId, recordId, finalCommand.getModelCode(), finalPayload,
                            userId, actorName, extraMeta);
                } catch (Exception e) {
                    log.warn("Failed to publish domain event for command {}: {}",
                            finalCommand.getCode(), e.getMessage());
                }

                // 9.2. WEBHOOK Phase: collect rules inside transaction (query DB)
                List<BindingRule> webhookRules = bindingRuleMapper.findByCommandIdAndType(finalCommand.getId(), "webhook");

                // 9.3. Schedule API_CALL and WEBHOOK for after-commit execution
                // External HTTP calls must NOT block the database transaction.
                if ((apiCallRules != null && !apiCallRules.isEmpty()) || (webhookRules != null && !webhookRules.isEmpty())) {
                    final Map<String, Object> afterCommitPayload = new HashMap<>(finalPayload);
                    final Map<String, Object> afterCommitResults = new HashMap<>(handlerResults);
                    final CommandDefinition afterCommitCommand = finalCommand;
                    final Long afterCommitTenantId = tenantId;
                    final List<BindingRule> afterCommitApiCallRules = apiCallRules;
                    final List<BindingRule> afterCommitWebhookRules = webhookRules;

                    TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    if (afterCommitApiCallRules != null && !afterCommitApiCallRules.isEmpty()) {
                                        executeApiCallPhase(afterCommitApiCallRules, afterCommitPayload, afterCommitResults);
                                    }
                                    if (afterCommitWebhookRules != null && !afterCommitWebhookRules.isEmpty()) {
                                        executeWebhookPhase(afterCommitWebhookRules, afterCommitCommand, afterCommitPayload, afterCommitResults, afterCommitTenantId);
                                    }
                                } catch (Exception e) {
                                    log.warn("After-commit API_CALL/WEBHOOK execution failed: {}", e.getMessage());
                                }
                            }
                        }
                    );
                }

                // 9.5. POST_INVARIANT Phase
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "post_invariant");
                invariantEngine.evaluatePostInvariants(
                        tenantId, finalCommand.getCode(), finalCommand.getModelCode(),
                        finalPayload, request != null ? request.getTargetRecordId() : null,
                        targetState);

                // 10. Build result
                transitionPhase(phaseRef, phaseStartRef, finalPhaseTimings, "completed");
                Map<String, Object> resultData = new HashMap<>();
                resultData.putAll(fieldMapResults);
                resultData.putAll(handlerResults);
                mergeEffectiveRecordId(resultData, request);

                // 11. Save idempotency record
                if (StringUtils.hasText(request.getClientRequestId())) {
                    idempotencyService.recordOutcome(request.getClientRequestId(), commandCode, finalPayload, resultData, tenantId);
                }

                // 12. Audit log
                long execTimeMs = System.currentTimeMillis() - startTime;
                finalPhaseTimings.put(phaseRef[0], System.currentTimeMillis() - phaseStartRef[0]);
                effectExecutor.saveAuditLog(tenantId, commandCode, finalCommand.getPid(), userId, finalPayload, resultData, true, null, execTimeMs, phaseRef[0], finalPhaseTimings);

                log.info("Command {} executed successfully in {}ms", commandCode, execTimeMs);

                return CommandExecuteResult.builder()
                        .commandCode(commandCode)
                        .phaseReached(phaseRef[0])
                        .data(resultData)
                        .executionTimeMs(execTimeMs)
                        .idempotentReplay(false)
                        .build();
            };

            CommandExecuteResult result;
            if (concurrencyKey != null) {
                result = concurrencyGuard.executeWithLock(concurrencyKey, lockTimeoutMs, pipeline);
            } else {
                result = pipeline.get();
            }

            // Record success metrics
            if (metricsSample != null && commandMetrics != null) {
                commandMetrics.recordCommandExecution(metricsSample, commandCode, result.getCommandCode(), true);
                metricsSample = null;
            }

            phaseReached = phaseRef[0];
            return result;

        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            log.error("Command {} failed at phase {}: {}", commandCode, phaseReached, e.getMessage());

            // Record failure metrics
            if (metricsSample != null && commandMetrics != null) {
                commandMetrics.recordCommandExecution(metricsSample, "unknown", "unknown", false);
            }

            // Audit log for failure (record current phase timing before saving)
            phaseTimings.put(phaseReached, System.currentTimeMillis() - lastPhaseTime[0]);
            effectExecutor.saveAuditLog(tenantId, commandCode, null, userId,
                    request.getPayload(), null, false, e.getMessage(), executionTimeMs, phaseReached, phaseTimings);

            if (e instanceof BusinessException || e instanceof ValidationException) {
                throw e;
            }
            throw new BusinessException(ResponseCode.BadParam, "Command execution failed: " + e.getMessage());
        }
    }

    // ==================== Phase Implementations ====================

    private void executeAssertPhase(List<BindingRule> assertRules, Map<String, Object> payload) {
        for (BindingRule rule : assertRules) {
            if (!StringUtils.hasText(rule.getExpression())) {
                continue;
            }
            EvaluationContext context = spelEvaluator.buildSpelContext(payload);
            Boolean result = spelEvaluator.evaluate(rule.getExpression(), context, Boolean.class);
            if (result == null || !result) {
                String errorMsg = "Assertion failed: " + rule.getExpression();
                throw new ValidationException(ResponseCode.CommonValidationFailed, errorMsg);
            }
        }
    }

    private Map<String, Object> executeHandlerPhase(List<BindingRule> handlerRules,
                                                     CommandDefinition command,
                                                     Map<String, Object> payload,
                                                     Map<String, Object> fieldMapResults,
                                                     Long tenantId, Long userId,
                                                     CommandExecuteRequest request,
                                                     Map<String, Object> execConfig) {
        Map<String, Object> handlerResults = new HashMap<>();

        // 1. Execute Spring Bean handlers from binding rules
        for (BindingRule rule : handlerRules) {
            if (!StringUtils.hasText(rule.getHandlerClass())) {
                continue;
            }

            try {
                CommandHandler handler = applicationContext.getBean(rule.getHandlerClass(), CommandHandler.class);
                CommandHandlerContext context = CommandHandlerContext.builder()
                        .commandCode(command.getCode())
                        .modelCode(command.getModelCode())
                        .payload(payload)
                        .operationType(request.getOperationType())
                        .targetRecordId(request.getTargetRecordId())
                        .tenantId(tenantId)
                        .userId(userId)
                        .fieldMapResults(fieldMapResults)
                        .ruleConfig(rule.getConfig())
                        .build();

                Map<String, Object> result = handler.execute(context);
                if (result != null) {
                    handlerResults.putAll(result);
                }
            } catch (Exception e) {
                log.error("Handler {} execution failed: {}", rule.getHandlerClass(), e.getMessage());
                throw new BusinessException(ResponseCode.BadParam, "Handler execution failed: " + rule.getHandlerClass());
            }
        }

        // 2. Execute plugin command handlers from ExtensionRegistry
        executePluginCommandHandler(command.getCode(), command.getModelCode(), payload, tenantId, request, fieldMapResults, handlerResults);

        // 3. Declarative BPM trigger: auto-start approval process if configured
        executeBpmTrigger(execConfig, command, payload, request, handlerResults);

        return handlerResults;
    }

    /**
     * Execute declarative BPM trigger from executionConfig.bpmTrigger.
     * Example config: {"bpmTrigger": {"processKey": "so_approval", "titleTemplate": "SO Approval: ${record.code}"}}
     * Starts a BPM process via BpmIntegrationService without requiring explicit handler binding rules.
     */
    @SuppressWarnings("unchecked")
    private void executeBpmTrigger(Map<String, Object> execConfig, CommandDefinition command,
                                    Map<String, Object> payload, CommandExecuteRequest request,
                                    Map<String, Object> handlerResults) {
        if (execConfig == null || bpmIntegrationService == null) return;

        Object triggerObj = execConfig.get("bpmTrigger");
        if (triggerObj == null) return;

        Map<String, Object> trigger;
        if (triggerObj instanceof Map<?, ?> m) {
            trigger = (Map<String, Object>) m;
        } else {
            log.warn("Invalid bpmTrigger config for command {}: expected Map, got {}", command.getCode(), triggerObj.getClass());
            return;
        }

        String processKey = (String) trigger.get("processKey");
        if (processKey == null || processKey.isBlank()) {
            log.warn("bpmTrigger.processKey is required for command {}", command.getCode());
            return;
        }

        // Build business key: modelCode:recordId
        String recordId = request != null ? request.getTargetRecordId() : null;
        String businessKey = command.getModelCode() + ":" + (recordId != null ? recordId : "new");

        // Build title
        String titleTemplate = (String) trigger.getOrDefault("titleTemplate", command.getCode());
        String title = resolveBpmTitle(titleTemplate, payload, command);

        // Build business data
        Map<String, Object> businessData = new HashMap<>();
        businessData.put("modelCode", command.getModelCode());
        businessData.put("recordId", recordId);
        businessData.put("commandCode", command.getCode());
        if (payload != null) {
            businessData.put("payload", payload);
        }

        try {
            log.info("BPM trigger: starting process={} for command={}, businessKey={}",
                    processKey, command.getCode(), businessKey);
            var processInstance = bpmIntegrationService.startBusinessProcess(processKey, businessKey, businessData, title);
            if (processInstance != null) {
                handlerResults.put("bpmProcessInstanceId", processInstance.getInstanceId());
                log.info("BPM process started: processKey={}, instanceId={}", processKey, processInstance.getInstanceId());
            }
        } catch (Exception e) {
            log.error("Failed to start BPM process for command {}: {}", command.getCode(), e.getMessage(), e);
            throw new BusinessException(ResponseCode.BadParam,
                    "Failed to start approval process: " + e.getMessage());
        }
    }

    private String resolveBpmTitle(String template, Map<String, Object> payload, CommandDefinition command) {
        if (template == null || !template.contains("${")) return template;
        // Simple variable substitution for ${payload.fieldCode} patterns
        String result = template;
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                result = result.replace("${payload." + entry.getKey() + "}",
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        result = result.replace("${commandCode}", command.getCode());
        result = result.replace("${modelCode}", command.getModelCode() != null ? command.getModelCode() : "");
        return result;
    }

    /**
     * Execute plugin command handlers registered via PF4J extensions.
     */
    private void executePluginCommandHandler(String commandCode, String modelCode,
                                              Map<String, Object> payload, Long tenantId,
                                              CommandExecuteRequest request,
                                              Map<String, Object> fieldMapResults,
                                              Map<String, Object> handlerResults) {
        if (extensionRegistry == null) {
            return;
        }

        Optional<CommandHandlerExtension> pluginHandler = extensionRegistry.getCommandHandler(commandCode);
        if (pluginHandler.isEmpty()) {
            log.debug("No plugin command handler found for: {}", commandCode);
            return;
        }

        CommandHandlerExtension handler = pluginHandler.get();
        log.info("Executing plugin command handler for: {} (handler: {})", commandCode, handler.getClass().getName());

        try {
            // Build plugin CommandContext with DataAccessor in settings map
            String namespace = commandCode.contains(":") ? commandCode.split(":")[0] : null;
            Map<String, Object> pluginSettings = new HashMap<>();
            pluginSettings.put("__dataAccessor",
                    new com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl(dynamicDataService));
            if (biTemporalService != null) {
                pluginSettings.put("__biTemporalAccessor",
                        new BiTemporalAccessorImpl(biTemporalService, objectMapper));
            }
            CommandHandlerExtension.CommandContext pluginContext = CommandHandlerExtension.CommandContext.builder()
                    .tenantId(tenantId)
                    .namespace(namespace)
                    .commandType(commandCode)
                    .modelCode(modelCode)
                    .recordId(resolveEffectiveRecordId(request, fieldMapResults))
                    .payload(payload)
                    .settings(pluginSettings)
                    .build();

            // Execute plugin handler
            Object result = handler.execute(pluginContext);

            // Merge results
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                handlerResults.putAll(resultMap);
                log.info("Plugin handler returned {} entries", resultMap.size());
            } else if (result != null) {
                handlerResults.put("pluginResult", result);
            }

        } catch (Exception e) {
            log.error("Plugin command handler execution failed for {}: {}", commandCode, e.getMessage(), e);
            throw new BusinessException(ResponseCode.BadParam, "Plugin handler execution failed: " + e.getMessage());
        }
    }

    private boolean hasPluginHandler(String commandCode) {
        return extensionRegistry != null && extensionRegistry.getCommandHandler(commandCode).isPresent();
    }

    private boolean shouldExecuteDslPersistenceWithPlugin(Map<String, Object> execConfig, CommandExecuteRequest request) {
        if (execConfig == null || execConfig.isEmpty()) {
            return false;
        }
        String operationType = request != null ? request.getOperationType() : null;
        if ("delete".equalsIgnoreCase(operationType) || "state_transition".equalsIgnoreCase(operationType)) {
            return true;
        }
        Object type = execConfig.get("type");
        if (type instanceof String typeValue) {
            String normalizedType = typeValue.trim().toLowerCase(Locale.ROOT);
            if (Set.of("create", "update", "delete", "state_transition").contains(normalizedType)) {
                return true;
            }
        }
        return execConfig.containsKey("inputFields") || execConfig.containsKey("autoSetFields");
    }

    private void propagateFieldMapRecordId(CommandExecuteRequest request, Map<String, Object> fieldMapResults) {
        if (request == null || fieldMapResults == null || StringUtils.hasText(request.getTargetRecordId())) {
            return;
        }
        Object recordId = fieldMapResults.get("recordId");
        if (recordId instanceof String recordIdStr && StringUtils.hasText(recordIdStr)) {
            request.setTargetRecordId(recordIdStr);
        }
    }

    private void mergeEffectiveRecordId(Map<String, Object> resultData, CommandExecuteRequest request) {
        if (resultData == null || resultData.containsKey("recordId") || request == null) {
            return;
        }
        String recordId = request.getTargetRecordId();
        if (StringUtils.hasText(recordId)) {
            resultData.put("recordId", recordId);
        }
    }

    private String resolveEffectiveRecordId(CommandExecuteRequest request, Map<String, Object> fieldMapResults) {
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            return request.getTargetRecordId();
        }
        if (fieldMapResults == null) {
            return null;
        }
        Object recordId = fieldMapResults.get("recordId");
        if (recordId instanceof String recordIdStr && StringUtils.hasText(recordIdStr)) {
            return recordIdStr;
        }
        return null;
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

    // ==================== Preconditions & Validation Phase ====================

    @SuppressWarnings("unchecked")
    private void executePreconditionsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                           Long tenantId, CommandDefinition command,
                                           CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("preconditions")) {
            return;
        }

        List<Map<String, Object>> preconditions = (List<Map<String, Object>>) execConfig.get("preconditions");
        if (preconditions == null) return;

        // Build SpEL context once (lazily, only if needed)
        Map<String, Object> spelPayload = null;

        for (Map<String, Object> precond : preconditions) {
            String message = (String) precond.getOrDefault("message:zh-CN",
                    precond.getOrDefault("message:en", "Precondition failed"));

            // SpEL expression mode: { "expression": "status != 'closed' && amount > 0", "message": "..." }
            String expression = (String) precond.get("expression");
            if (StringUtils.hasText(expression)) {
                if (spelPayload == null) {
                    spelPayload = buildPreconditionPayload(payload, tenantId, command, request);
                }
                boolean passed = evaluateSpelPrecondition(expression, spelPayload);
                if (!passed) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed, message);
                }
                continue;
            }

            // Field-operator mode: { "field": "status", "operator": "IN", "value": [...], "message": "..." }
            String field = (String) precond.get("field");
            String operator = (String) precond.get("operator");
            Object expectedValue = precond.get("value");

            if (field == null || operator == null) continue;

            // Read actual value (from payload or from existing record)
            Object actualValue = payload.get(field);
            if (actualValue == null && request != null && StringUtils.hasText(request.getTargetRecordId())) {
                Map<String, Object> record = readRecordSnapshot(tenantId, command.getModelCode(), request.getTargetRecordId());
                if (record != null) {
                    actualValue = record.get(field);
                }
            }

            boolean passed = evaluatePrecondition(operator, actualValue, expectedValue);
            if (!passed) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        }
    }

    private boolean evaluatePrecondition(String operator, Object actual, Object expected) {
        return switch (operator.toUpperCase()) {
            case "EQ" -> Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "neq" -> !Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "IN" -> {
                if (expected instanceof List<?> list) {
                    yield list.stream().anyMatch(v -> Objects.equals(String.valueOf(actual), String.valueOf(v)));
                }
                yield false;
            }
            case "not_in" -> {
                if (expected instanceof List<?> list) {
                    yield list.stream().noneMatch(v -> Objects.equals(String.valueOf(actual), String.valueOf(v)));
                }
                yield true;
            }
            case "not_null" -> actual != null;
            case "null" -> actual == null;
            case "GT", "GE", "LT", "LE" -> compareNumeric(operator.toUpperCase(), actual, expected);
            case "contains" -> actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "not_contains" -> actual == null || !String.valueOf(actual).contains(String.valueOf(expected));
            default -> {
                log.warn("Unknown precondition operator: {}, failing safe", operator);
                yield false;
            }
        };
    }

    private boolean compareNumeric(String op, Object actual, Object expected) {
        if (actual == null || expected == null) return false;
        try {
            double a = Double.parseDouble(String.valueOf(actual));
            double e = Double.parseDouble(String.valueOf(expected));
            return switch (op) {
                case "GT" -> a > e;
                case "GE" -> a >= e;
                case "LT" -> a < e;
                case "LE" -> a <= e;
                default -> false;
            };
        } catch (NumberFormatException ex) {
            // Fall back to string comparison for dates and other comparable strings
            int cmp = String.valueOf(actual).compareTo(String.valueOf(expected));
            return switch (op) {
                case "GT" -> cmp > 0;
                case "GE" -> cmp >= 0;
                case "LT" -> cmp < 0;
                case "LE" -> cmp <= 0;
                default -> false;
            };
        }
    }

    /**
     * Build a merged payload for SpEL precondition evaluation.
     * Merges command payload with existing record data (record fields as base, payload overwrites).
     */
    private Map<String, Object> buildPreconditionPayload(Map<String, Object> payload,
                                                          Long tenantId, CommandDefinition command,
                                                          CommandExecuteRequest request) {
        Map<String, Object> merged = new HashMap<>(payload);
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            Map<String, Object> record = readRecordSnapshot(tenantId, command.getModelCode(), request.getTargetRecordId());
            if (record != null) {
                Map<String, Object> result = new HashMap<>(record);
                result.putAll(payload);
                return result;
            }
        }
        return merged;
    }

    private static final int MAX_PRECONDITION_EXPRESSION_LENGTH = 500;
    private static final java.util.regex.Pattern DANGEROUS_SPEL_PATTERN = java.util.regex.Pattern.compile(
            "T\\s*\\(|new\\s+|getClass|Runtime|exec\\s*\\(|ProcessBuilder|System\\.|Thread\\.", java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean evaluateSpelPrecondition(String expression, Map<String, Object> payload) {
        if (expression.length() > MAX_PRECONDITION_EXPRESSION_LENGTH) {
            log.error("Rejected precondition SpEL expression exceeding max length {}: length={}", MAX_PRECONDITION_EXPRESSION_LENGTH, expression.length());
            return false;
        }
        if (DANGEROUS_SPEL_PATTERN.matcher(expression).find()) {
            log.error("Rejected dangerous precondition SpEL expression: '{}'", expression);
            return false;
        }
        try {
            var context = spelEvaluator.buildSpelContext(payload);
            Boolean result = spelEvaluator.evaluate(expression, context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Failed to evaluate precondition expression '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void executeValidationPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                         Long tenantId, CommandDefinition command,
                                         CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("validation")) {
            return;
        }

        Map<String, Object> validation = (Map<String, Object>) execConfig.get("validation");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) validation.get("rules");
        if (rules == null) return;

        // For UNIQUE_COMPOSITE, merge FIXED_VALUE autoSetFields into a validation payload
        // so that auto-set values (like version_no=1) are considered during uniqueness checks.
        Map<String, Object> validationPayload = new HashMap<>(payload);
        Map<String, Object> autoSetFields = (Map<String, Object>) execConfig.get("autoSetFields");
        if (autoSetFields != null) {
            for (Map.Entry<String, Object> entry : autoSetFields.entrySet()) {
                if (!validationPayload.containsKey(entry.getKey())) {
                    Map<String, Object> config = (Map<String, Object>) entry.getValue();
                    if ("fixed_value".equals(config.get("strategy"))) {
                        validationPayload.put(entry.getKey(), config.get("value"));
                    }
                }
            }
        }

        for (Map<String, Object> rule : rules) {
            String ruleType = (String) rule.get("type");
            switch (ruleType != null ? ruleType : "") {
                case "has_children" -> validateHasChildren(rule, tenantId, request);
                case "unique_composite" -> validateUniqueComposite(rule, validationPayload, tenantId, command, request);
            }
        }
    }

    /**
     * Execute cross-field validation rules (Stage 8, after InvariantEngine).
     * Loads model-level rules and merges with command-level ruleOverrides.
     */
    @SuppressWarnings("unchecked")
    private void executeCrossFieldRules(CommandDefinition command,
                                         Map<String, Object> payload,
                                         Map<String, Object> execConfig) {
        // Load model rules
        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        List<CrossFieldRule> modelRules = (modelDef != null && modelDef.getRules() != null)
                ? modelDef.getRules() : List.of();
        if (modelRules.isEmpty() && (execConfig == null || !execConfig.containsKey("ruleOverrides"))) {
            return; // No rules to evaluate
        }

        // Parse command-level overrides from executionConfig
        List<RuleOverride> overrides = List.of();
        if (execConfig != null && execConfig.containsKey("ruleOverrides")) {
            try {
                Object rawOverrides = execConfig.get("ruleOverrides");
                if (rawOverrides instanceof List) {
                    overrides = objectMapper.convertValue(rawOverrides,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, RuleOverride.class));
                }
            } catch (Exception e) {
                log.warn("Failed to parse ruleOverrides for command {}: {}", command.getCode(), e.getMessage());
            }
        }

        // Build SpEL evaluator function using current payload context
        var spelContext = spelEvaluator.buildSpelContext(payload);
        // Resolve $i18n: keys using en-US as default for API responses
        java.util.function.Function<String, String> i18nResolver = (i18nService != null)
                ? key -> i18nService.getValue("en-US", key, i18nService.getValue("zh-CN", key, key))
                : null;
        CrossFieldRuleEngine engine = new CrossFieldRuleEngine(
                expr -> spelEvaluator.evaluate(expr, spelContext, Boolean.class),
                i18nResolver
        );

        RuleEvaluationResult result = engine.evaluate(modelRules, overrides, payload);

        if (result.hasErrors()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    result.formatErrorMessages());
        }
        // Warnings are logged but don't block execution
        if (result.hasWarnings()) {
            for (var w : result.warnings()) {
                log.info("Cross-field validation warning [{}]: {}", w.ruleId(), w.message());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeCommandFieldValidationPhase(Map<String, Object> execConfig,
                                                    Map<String, Object> payload,
                                                    CommandDefinition command,
                                                    CommandExecuteRequest request) {
        if (execConfig == null || !StringUtils.hasText(command.getModelCode())) {
            return;
        }

        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        if (modelDef == null || modelDef.getFields() == null || modelDef.getFields().isEmpty()) {
            return;
        }

        Set<String> fieldsToValidate = new LinkedHashSet<>();
        Object inputFieldsObj = execConfig.get("inputFields");
        if (inputFieldsObj instanceof List<?> inputFields) {
            for (Object inputField : inputFields) {
                if (inputField instanceof String fieldCode && StringUtils.hasText(fieldCode)) {
                    fieldsToValidate.add(fieldCode);
                }
            }
        }

        // autoSetFields are system-generated values (CURRENT_DATETIME, CURRENT_USER, etc.) — skip validation.
        // They are trusted system values and may not match the user-facing field type (e.g. LocalDateTime into a STRING field).

        String stateField = (String) execConfig.get("stateField");
        if (StringUtils.hasText(stateField)) {
            fieldsToValidate.add(stateField);
        }

        if (fieldsToValidate.isEmpty()) {
            return;
        }

        String operationType = request != null ? request.getOperationType() : null;
        if (!StringUtils.hasText(operationType) && execConfig.get("type") != null) {
            operationType = String.valueOf(execConfig.get("type"));
        }
        boolean isStateTransition = "state_transition".equalsIgnoreCase(operationType)
                || "state_transition".equalsIgnoreCase(String.valueOf(execConfig.get("type")));
        boolean isUpdateLike = "update".equalsIgnoreCase(operationType)
                || "delete".equalsIgnoreCase(operationType)
                || isStateTransition;

        ValidationContext context = isUpdateLike
                ? ValidationContext.UPDATE
                : ValidationContext.CREATE;

        List<String> errors = new ArrayList<>();
        Map<String, FieldDefinition> fieldMap = new HashMap<>();
        for (FieldDefinition field : modelDef.getFields()) {
            fieldMap.put(field.getCode(), field);
        }

        for (String fieldCode : fieldsToValidate) {
            FieldDefinition fieldDefinition = fieldMap.get(fieldCode);
            if (fieldDefinition == null) {
                continue;
            }
            boolean payloadHasField = payload.containsKey(fieldCode);
            Object value = payload.get(fieldCode);

            if (isStateTransition
                    && fieldCode.equals(stateField)
                    && !payloadHasField
                    && execConfig.get("toState") != null) {
                value = execConfig.get("toState");
                payloadHasField = true;
            }

            if (isUpdateLike && !payloadHasField) {
                continue;
            }

            var result = validationService.validateField(fieldDefinition, value, context);
            if (!result.isValid() && result.getErrors() != null) {
                errors.addAll(result.getErrors());
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, String.join("; ", errors));
        }
    }

    private void validateHasChildren(Map<String, Object> rule, Long tenantId, CommandExecuteRequest request) {
        String childModel = (String) rule.get("childModel");
        String parentField = (String) rule.get("parentField");
        Integer minCount = rule.get("minCount") != null ? ((Number) rule.get("minCount")).intValue() : 1;
        String message = (String) rule.getOrDefault("message:zh-CN",
                rule.getOrDefault("message:en", "Validation failed: child records required"));

        if (childModel == null || parentField == null || request == null || request.getTargetRecordId() == null) {
            return;
        }

        // Security: validate parentField to prevent SQL injection
        CommandExecutorUtils.validateSqlIdentifier(parentField, "HAS_CHILDREN parentField");

        try {
            String tableName = metaModelService.getTableName(childModel);
            String recordIdStr = request.getTargetRecordId();

            // Build safe SQL - TenantLineInterceptor auto-adds tenant_id condition
            boolean hasDeletedFlag = hasColumn(tableName, "deleted_flag");
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM " + tableName
                    + " WHERE " + parentField + " = #{params.parentId}");
            if (hasDeletedFlag) {
                sql.append(" AND deleted_flag = FALSE");
            }

            // Always pass parentId as String since the parentField column type
            // may be varchar (e.g., qo_report_id stores numeric IDs as text).
            // PostgreSQL can handle varchar-to-varchar comparisons natively.
            Map<String, Object> params = new HashMap<>();
            params.put("parentId", recordIdStr);

            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql.toString(), params);
            long count = 0;
            if (result != null && !result.isEmpty()) {
                Object cnt = result.get(0).get("cnt");
                if (cnt instanceof Number) {
                    count = ((Number) cnt).longValue();
                }
            }
            if (count < minCount) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HAS_CHILDREN validation failed: {}", e.getMessage());
            // Don't silently swallow — if the query fails, treat validation as failed
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    message + " (validation query error: " + e.getMessage() + ")");
        }
    }

    /**
     * UNIQUE_COMPOSITE validation: check that no existing record has the same combination of field values.
     * For CREATE: checks if any record exists with the same composite key values.
     * For UPDATE: checks if any OTHER record (excluding the current one) has the same values.
     */
    @SuppressWarnings("unchecked")
    private void validateUniqueComposite(Map<String, Object> rule, Map<String, Object> payload,
                                          Long tenantId, CommandDefinition command,
                                          CommandExecuteRequest request) {
        List<String> fields = (List<String>) rule.get("fields");
        String message = (String) rule.getOrDefault("message:zh-CN",
                rule.getOrDefault("message:en", "Duplicate record exists"));

        if (fields == null || fields.isEmpty() || command.getModelCode() == null) return;

        // Security: validate all field codes to prevent SQL injection
        for (String f : fields) {
            CommandExecutorUtils.validateSqlIdentifier(f, "UNIQUE_COMPOSITE field");
        }

        try {
            String tableName = metaModelService.getTableName(command.getModelCode());
            // Note: TenantLineInterceptor automatically adds tenant_id condition,
            // so we do NOT manually add tenant_id to the WHERE clause.
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM " + tableName
                    + " WHERE 1=1");
            Map<String, Object> params = new HashMap<>();

            // Load model definition for type conversion
            ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);

            boolean hasNonNullField = false;
            for (int i = 0; i < fields.size(); i++) {
                String fieldCode = fields.get(i);
                Object value = payload.get(fieldCode);
                String paramKey = "f" + i;
                if (value == null) {
                    sql.append(" AND (").append(fieldCode).append(" IS NULL)");
                } else {
                    // Convert value to correct type for SQL parameter binding
                    if (modelDef != null && modelDef.getFields() != null) {
                        for (FieldDefinition fd : modelDef.getFields()) {
                            if (fieldCode.equals(fd.getCode()) && fd.getDataType() != null) {
                                value = fieldMapExecutor.convertFieldValue(fd.getDataType(), value);
                                break;
                            }
                        }
                    }
                    sql.append(" AND ").append(fieldCode).append(" = #{params.").append(paramKey).append("}");
                    params.put(paramKey, value);
                    hasNonNullField = true;
                }
            }

            // Add soft-delete filter if column exists
            if (hasColumn(tableName, "deleted_flag")) {
                sql.append(" AND deleted_flag = FALSE");
            }

            // For UPDATE, exclude current record
            if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
                var excludeEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                sql.append(" AND ").append(excludeEntry.getKey()).append(" != #{params.excludeId}");
                params.put("excludeId", excludeEntry.getValue());
            }

            // Skip validation if all fields are NULL (no meaningful composite key)
            if (!hasNonNullField) {
                log.debug("UNIQUE_COMPOSITE: all fields are NULL, skipping validation");
                return;
            }

            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql.toString(), params);
            long count = 0;
            if (result != null && !result.isEmpty()) {
                Object cnt = result.get(0).get("cnt");
                if (cnt instanceof Number) {
                    count = ((Number) cnt).longValue();
                }
            }

            if (count > 0) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("UNIQUE_COMPOSITE validation failed unexpectedly: {}", e.getMessage(), e);
            throw new BusinessException(ResponseCode.BadParam,
                    "Uniqueness validation failed: " + e.getMessage());
        }
    }

    // ==================== Computed Fields Phase ====================

    @SuppressWarnings("unchecked")
    private void executeComputedFieldsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                              Long tenantId, CommandDefinition command,
                                              CommandExecuteRequest request,
                                              Map<String, Object> fieldMapResults) {
        if (execConfig == null || !execConfig.containsKey("computedFields")) {
            return;
        }

        Map<String, String> computedFields = (Map<String, String>) execConfig.get("computedFields");
        if (computedFields == null || computedFields.isEmpty()) {
            return;
        }

        // Sort computed fields by dependency order (topological sort)
        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        List<FieldDefinition> fieldDefs = (modelDef != null && modelDef.getFields() != null)
                ? modelDef.getFields() : List.of();
        ComputedFieldDependencyResolver dependencyResolver = new ComputedFieldDependencyResolver();
        List<Map.Entry<String, String>> sortedFields;
        try {
            sortedFields = dependencyResolver.resolveExecutionOrder(computedFields, fieldDefs);
        } catch (Exception e) {
            log.warn("Computed field dependency resolution failed, using insertion order: {}", e.getMessage());
            sortedFields = new ArrayList<>(computedFields.entrySet());
        }

        // Build context with payload + fieldMapResults
        Map<String, Object> combinedContext = new HashMap<>(payload);
        combinedContext.putAll(fieldMapResults);

        EvaluationContext spelContext = spelEvaluator.buildSpelContext(combinedContext);

        Map<String, Object> computedValues = new HashMap<>();
        for (Map.Entry<String, String> entry : sortedFields) {
            String fieldCode = entry.getKey();
            String expression = entry.getValue();

            try {
                Object result = spelEvaluator.evaluate(expression, spelContext);
                if (result != null) {
                    computedValues.put(fieldCode, result);
                    payload.put(fieldCode, result); // Also update payload for downstream phases
                    log.debug("COMPUTED: {} = {} (expr={})", fieldCode, result, expression);
                }
            } catch (Exception e) {
                log.warn("Failed to compute field '{}' with expression '{}': {}",
                        fieldCode, expression, e.getMessage());
            }
        }

        // Write computed values to the record if it exists
        // For UPDATE: use request.getTargetRecordId()
        // For CREATE: use recordId from fieldMapResults (generated during implicit field map)
        String recordIdStr = (request != null && StringUtils.hasText(request.getTargetRecordId()))
                ? request.getTargetRecordId()
                : (String) fieldMapResults.get("recordId");
        if (!computedValues.isEmpty() && StringUtils.hasText(recordIdStr)) {
            try {
                String tableName = metaModelService.getTableName(command.getModelCode());
                CommandExecutorUtils.validateSqlIdentifier(tableName, "computed field tableName");
                // Try pid-based lookup first (for implicit field map CREATE), then id-based
                String sql = "SELECT id FROM " + tableName
                        + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
                Map<String, Object> lookupParams = Map.of("tenantId", tenantId, "pid", recordIdStr);
                List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, lookupParams);
                if (rows != null && !rows.isEmpty()) {
                    Long dbId = ((Number) rows.get(0).get("id")).longValue();
                    Map<String, Object> conditions = Map.of("tenant_id", tenantId, "id", dbId);
                    dynamicDataMapper.update(tableName, computedValues, conditions);
                    log.debug("COMPUTED: wrote {} fields to {} (pid={})", computedValues.size(), tableName, recordIdStr);
                } else {
                    // Fallback: try using recordIdStr directly
                    var fallbackEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdStr);
                    Map<String, Object> conditions = Map.of("tenant_id", tenantId, fallbackEntry.getKey(), fallbackEntry.getValue());
                    dynamicDataMapper.update(tableName, computedValues, conditions);
                }
            } catch (Exception e) {
                log.warn("Failed to write computed fields to record: {}", e.getMessage());
            }
        }
    }

    // ==================== Post Action Phase ====================

    private void persistHandlerResults(String modelCode,
                                       Map<String, Object> payload,
                                       Map<String, Object> handlerResults,
                                       Long tenantId,
                                       CommandExecuteRequest request,
                                       Map<String, Object> fieldMapResults) {
        if (handlerResults == null || handlerResults.isEmpty() || !StringUtils.hasText(modelCode)) {
            return;
        }

        ModelDefinition modelDef = metaModelService.getModelDefinition(modelCode).orElse(null);
        if (modelDef == null || modelDef.getFields() == null || modelDef.getFields().isEmpty()) {
            return;
        }

        Set<String> modelFieldCodes = new HashSet<>();
        for (FieldDefinition fieldDefinition : modelDef.getFields()) {
            if (fieldDefinition != null && StringUtils.hasText(fieldDefinition.getCode())) {
                modelFieldCodes.add(fieldDefinition.getCode());
            }
        }

        Map<String, Object> persistable = new HashMap<>();
        for (Map.Entry<String, Object> entry : handlerResults.entrySet()) {
            String key = entry.getKey();
            if (!StringUtils.hasText(key) || !modelFieldCodes.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            persistable.put(key, value);
        }

        if (persistable.isEmpty()) {
            return;
        }

        String recordIdStr = (request != null && StringUtils.hasText(request.getTargetRecordId()))
                ? request.getTargetRecordId()
                : fieldMapResults != null ? (String) fieldMapResults.get("recordId") : null;
        if (!StringUtils.hasText(recordIdStr)) {
            return;
        }

        try {
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "handler field tableName");

            Map<String, Object> conditions;
            String sql = "SELECT id FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
            Map<String, Object> lookupParams = Map.of("tenantId", tenantId, "pid", recordIdStr);
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, lookupParams);
            if (rows != null && !rows.isEmpty()) {
                Long dbId = ((Number) rows.get(0).get("id")).longValue();
                conditions = Map.of("tenant_id", tenantId, "id", dbId);
            } else {
                var fallbackEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdStr);
                conditions = Map.of("tenant_id", tenantId, fallbackEntry.getKey(), fallbackEntry.getValue());
            }

            dynamicDataMapper.update(tableName, persistable, conditions);
            log.debug("HANDLER: wrote {} fields to {} (pid={})", persistable.size(), tableName, recordIdStr);
        } catch (Exception e) {
            log.warn("Failed to persist handler results for model {}: {}", modelCode, e.getMessage());
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

    // ==================== API Call & Webhook ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeApiCallPhase(List<BindingRule> apiCallRules,
                                                      Map<String, Object> payload,
                                                      Map<String, Object> handlerResults) {
        Map<String, Object> apiResults = new HashMap<>();
        for (BindingRule rule : apiCallRules) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            try {
                // Config format: {"connectorPid":"xxx", "endpointCode":"yyy", "paramMapping":{...}}
                Map<String, Object> config = objectMapper.readValue(rule.getConfig(), Map.class);
                String connectorPid = (String) config.get("connectorPid");
                String endpointCode = (String) config.get("endpointCode");

                // Build params from payload + handlerResults
                Map<String, Object> params = new HashMap<>(payload);
                params.putAll(handlerResults);

                Map<String, Object> result = apiConnectorService.invoke(connectorPid, endpointCode, params);
                if (result != null) {
                    apiResults.putAll(result);
                }
                log.debug("API_CALL rule executed: connector={}, endpoint={}", connectorPid, endpointCode);
            } catch (Exception e) {
                log.warn("API_CALL rule execution failed: {}", e.getMessage());
                // API_CALL failures are non-fatal by default
            }
        }
        return apiResults;
    }

    @SuppressWarnings("unchecked")
    private void executeWebhookPhase(List<BindingRule> webhookRules,
                                      CommandDefinition command,
                                      Map<String, Object> payload,
                                      Map<String, Object> results,
                                      Long tenantId) {
        for (BindingRule rule : webhookRules) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            try {
                // Determine event type from rule config or use command code
                String eventType = StringUtils.hasText(rule.getEventType())
                        ? rule.getEventType()
                        : command.getCode();

                // Build webhook payload
                Map<String, Object> webhookPayload = new HashMap<>();
                webhookPayload.put("commandCode", command.getCode());
                webhookPayload.put("modelCode", command.getModelCode());
                webhookPayload.put("payload", payload);
                webhookPayload.put("result", results);

                webhookDispatcher.dispatch(eventType, webhookPayload, tenantId);
                log.debug("WEBHOOK rule dispatched: eventType={}", eventType);
            } catch (Exception e) {
                log.warn("WEBHOOK rule dispatch failed: {}", e.getMessage());
            }
        }
    }

    // ==================== Change Tracking ====================

    private Map<String, Object> readRecordSnapshot(Long tenantId, String modelCode, String recordId) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "snapshot tableName");
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String sql = "SELECT * FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND " + idEntry.getKey() + " = #{params.recordId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result != null && !result.isEmpty()) {
                return result.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to read record snapshot for change tracking: {}", e.getMessage());
        }
        return null;
    }

    private void recordChangeTracking(CommandDefinition command, CommandExecuteRequest request,
                                       Long tenantId, Long userId, Map<String, Object> beforeSnapshot) {
        try {
            String modelCode = command.getModelCode();
            if (!StringUtils.hasText(modelCode)) {
                return;
            }

            String recordId = request != null ? request.getTargetRecordId() : null;
            String operationType = request != null ? request.getOperationType() : null;

            // Determine operation
            String operation;
            Map<String, Object> afterSnapshot = null;

            if ("delete".equalsIgnoreCase(operationType)) {
                operation = "delete";
            } else if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(recordId)) {
                operation = "update";
                afterSnapshot = readRecordSnapshot(tenantId, modelCode, recordId);
            } else if (beforeSnapshot == null && StringUtils.hasText(recordId)) {
                operation = "create";
                afterSnapshot = readRecordSnapshot(tenantId, modelCode, recordId);
            } else {
                return; // Cannot determine operation, skip
            }

            List<FieldChange> changes = changeTracker.diff(beforeSnapshot, afterSnapshot, modelCode);
            if (changes.isEmpty() && !"delete".equals(operation)) {
                return; // No actual changes detected
            }

            ChangeRecord record = ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordId != null ? recordId : "unknown")
                    .operation(operation)
                    .changedBy(userId)
                    .commandCode(command.getCode())
                    .changes(changes)
                    .snapshotBefore(beforeSnapshot)
                    .snapshotAfter(afterSnapshot)
                    .build();

            changeTracker.recordChange(record);
        } catch (Exception e) {
            log.warn("Change tracking failed for command {}: {}", command.getCode(), e.getMessage());
        }
    }

    /**
     * Auto-recalculate parent roll-up fields when a child model command is executed.
     * Looks up the RollUpFieldRegistry to find parent models with roll-up fields that reference
     * the current command's model as a child, then recalculates each matching roll-up.
     */
    @SuppressWarnings("unchecked")
    private void executeRollUpRecalculation(String modelCode, Map<String, Object> payload,
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
    private void executeGovernanceSnapshot(String modelCode, Map<String, Object> payload,
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
    private void executeConsistencyCheckPhase(
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
