package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.module.meta.event.DomainEventPublisher;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;

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

            // Shadow Mode dry-run (learning-loop.md §6): the pipeline ran to
            // completion producing a realistic result object, but every DB
            // write it made must be undone. Force rollback at the JTA layer.
            if (request.isDryRun()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport
                        .currentTransactionStatus().setRollbackOnly();
                log.info("Command {} executed in dry-run mode — transaction marked for rollback",
                        commandCode);
                result.setPhaseReached("completed_dry_run");
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

    // ==================== Private Helpers ====================

    /**
     * Check if a table has a specific column using cached JDBC metadata.
     */
    private boolean hasColumn(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return false;
        }
        String cacheKey = tableName + ":" + columnName;
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
            return 5000L;
        }
        Object timeout = config.get("lockTimeoutMs");
        if (timeout instanceof Number) {
            return ((Number) timeout).longValue();
        }
        return 5000L;
    }

    /**
     * Transitions to a new pipeline phase, recording timing for the current phase.
     */
    private void transitionPhase(String[] phaseRef, long[] phaseStartRef,
                                  Map<String, Long> phaseTimings, String newPhase) {
        long now = System.currentTimeMillis();
        phaseTimings.put(phaseRef[0], now - phaseStartRef[0]);
        phaseStartRef[0] = now;
        phaseRef[0] = newPhase;
    }

}
