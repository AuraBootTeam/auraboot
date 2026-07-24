package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DryRunSafe;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.auraboot.framework.meta.service.impl.CommandHandlerAsyncTaskExecutor;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.DynamicDataQueryScope;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.BiTemporalAccessorImpl;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.AsyncTaskAccessorImpl;
import com.auraboot.framework.plugin.pf4j.FileAccessorImpl;
import com.auraboot.framework.plugin.pf4j.LlmProviderAccessorImpl;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Component
@Order(1200)
@RequiredArgsConstructor
public class HandlerPhase implements CommandPhase {

    private final ApplicationContext applicationContext;
    private final ExtensionRegistry extensionRegistry;
    private final ObjectMapper objectMapper;
    private final DynamicDataService dynamicDataService;
    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final RecordSnapshotReader snapshotReader;
    private final DataPermissionEngine dataPermissionEngine;
    private final DataDomainService dataDomainService;

    @Autowired(required = false)
    private BiTemporalService biTemporalService;

    @Autowired(required = false)
    private com.auraboot.framework.bpm.service.BpmIntegrationService bpmIntegrationService;

    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;

    @Autowired(required = false)
    private FileService fileService;

    @Autowired(required = false)
    private AsyncTaskServiceImpl asyncTaskService;

    @Autowired(required = false)
    private StorageProvider storageProvider;

    /**
     * Whether a handler inherits the authority its command boundary granted (DDR-2026-07-22 step 3).
     * Off until observe mode shows no command would newly reach a target its caller cannot read.
     */
    @org.springframework.beans.factory.annotation.Value("${aura.command.data-authority.enabled:false}")
    private boolean commandDataAuthorityEnabled;

    @Override public String name() { return "handler"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        var handlerRules = ctx.getRulesByType().getOrDefault("handler", Collections.emptyList());
        Map<String, Object> handlerResults = withCommandAggregate(ctx, () ->
                withCommandAuthority(ctx, () -> executeHandlerPhase(
                        handlerRules, ctx.getCommand(), ctx.getPayload(), ctx.getFieldMapResults(),
                        ctx.getTenantId(), ctx.getUserId(), ctx.getRequest(), ctx.getExecConfig())));
        ctx.setHandlerResults(handlerResults);
        persistHandlerResults(ctx.getCommand().getModelCode(), ctx.getPayload(),
                handlerResults, ctx.getTenantId(), ctx.getRequest(), ctx.getFieldMapResults());
    }

    // ==================== Inlined delegate methods ====================

    private Map<String, Object> executeHandlerPhase(List<BindingRule> handlerRules,
                                                     CommandDefinition command,
                                                     Map<String, Object> payload,
                                                     Map<String, Object> fieldMapResults,
                                                     Long tenantId, Long userId,
                                                     CommandExecuteRequest request,
                                                     Map<String, Object> execConfig) {
        Map<String, Object> handlerResults = new HashMap<>();
        boolean dryRun = request.isDryRun();

        // 1. Execute Spring Bean handlers from binding rules.
        //    Under dry-run (PR-56) we SKIP every handler whose implementation
        //    class is NOT annotated with @DryRunSafe. The CommandPipeline
        //    transaction rollback only undoes JDBC writes; external side
        //    effects (HTTP / email / MQ / S3 / Redis / files) escape the
        //    envelope, so honour-system logging is not enough.
        for (BindingRule rule : handlerRules) {
            if (!StringUtils.hasText(rule.getHandlerClass())) {
                continue;
            }

            try {
                CommandHandler handler = resolveCommandHandler(rule.getHandlerClass());
                if (dryRun && !handler.getClass().isAnnotationPresent(DryRunSafe.class)) {
                    log.info("Dry-run: skipping handler {} (class not marked @DryRunSafe)",
                            handler.getClass().getName());
                    continue;
                }
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
                        .dryRun(request.isDryRun())
                        .build();

                Map<String, Object> result = handler.execute(context);
                if (result != null) {
                    handlerResults.putAll(result);
                }
            } catch (Exception e) {
                log.error("Handler {} execution failed: {}", rule.getHandlerClass(), e.getMessage(), e);
                throw new BusinessException(ResponseCode.BadParam,
                        "Handler '" + rule.getHandlerClass() + "' failed: " + e.getMessage());
            }
        }

        // 2. Execute plugin command handlers from ExtensionRegistry
        executePluginCommandHandler(command.getCode(), command.getModelCode(), payload, tenantId, userId, request,
                fieldMapResults, handlerResults, execConfig);

        // 3. Declarative BPM trigger — skipped under dry-run since BPM
        // process state lives outside the command's transaction envelope.
        if (!request.isDryRun()) {
            executeBpmTrigger(execConfig, command, payload, request, handlerResults);
        }

        return handlerResults;
    }

    private CommandHandler resolveCommandHandler(String handlerClass) {
        try {
            return applicationContext.getBean(handlerClass, CommandHandler.class);
        } catch (NoSuchBeanDefinitionException missingByBeanName) {
            return applicationContext.getBeansOfType(CommandHandler.class).values().stream()
                    .filter(handler -> handlerClass.equals(handler.getHandlerName()))
                    .findFirst()
                    .orElseThrow(() -> missingByBeanName);
        }
    }

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
        Map<String, String> fieldDataTypes = new HashMap<>();
        for (FieldDefinition fieldDefinition : modelDef.getFields()) {
            if (fieldDefinition != null && StringUtils.hasText(fieldDefinition.getCode())) {
                modelFieldCodes.add(fieldDefinition.getCode());
                fieldDataTypes.put(fieldDefinition.getCode(),
                        StringUtils.hasText(fieldDefinition.getDataType()) ? fieldDefinition.getDataType() : "text");
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
            String dataType = fieldDataTypes.getOrDefault(key, "text");
            if (!CommandExecutorUtils.isTypeCompatible(value, dataType)) {
                log.warn("HANDLER: skipping field '{}' — Java type {} is not compatible with dataType '{}'. "
                        + "Handler should return correct types or exclude this field from result map.",
                        key, value.getClass().getSimpleName(), dataType);
                continue;
            }
            persistable.put(key, value);
        }

        if (persistable.isEmpty()) {
            return;
        }

        String recordIdStr = (request != null && StringUtils.hasText(request.getTargetRecordId()))
                ? request.getTargetRecordId()
                : fieldMapResults != null ? (String) fieldMapResults.get("recordPid") : null;
        if (!StringUtils.hasText(recordIdStr)) {
            return;
        }

        String tableName = metaModelService.getTableName(modelCode);
        CommandExecutorUtils.validateSqlIdentifier(tableName, "handler field tableName");

        Map<String, Object> conditions;
        Map.Entry<String, Object> idEntry;
        String sql = "SELECT id FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        Map<String, Object> lookupParams = Map.of("tenantId", tenantId, "pid", recordIdStr);
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, lookupParams);
        if (rows != null && !rows.isEmpty()) {
            Long dbId = ((Number) rows.get(0).get("id")).longValue();
            idEntry = Map.entry("id", dbId);
            conditions = Map.of("tenant_id", tenantId, "id", dbId);
        } else {
            var fallbackEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdStr);
            idEntry = fallbackEntry;
            conditions = Map.of("tenant_id", tenantId, fallbackEntry.getKey(), fallbackEntry.getValue());
        }

        // JSONB-awareness: a handler may return a full record map (e.g. lifecycle
        // status-transition handlers return the whole updated row via getById), so
        // `persistable` can include jsonb host columns (e.g. cr_cj_seed_urls). The
        // plain update provider binds those as varchar → "column is of type jsonb but
        // expression is of type character varying". Mirror DynamicDataServiceImpl /
        // CommandFieldMapExecutor and add the ::jsonb cast for jsonb columns present
        // in the data map. (Distinct from OSS #398, which only covered the
        // CommandSideEffectExecutor UPDATE_RECORD + partial-CRUD paths.)
        Set<String> jsonbColumns = resolveJsonbColumns(modelDef, tableName);
        if (shouldUseScopedWrite()) {
            executeScopedUpdate(tableName, modelCode, idEntry, persistable, tenantId, jsonbColumns);
        } else if (jsonbColumns.isEmpty()) {
            dynamicDataMapper.update(tableName, persistable, conditions);
        } else {
            dynamicDataMapper.updateWithJsonb(tableName, persistable, conditions, jsonbColumns);
        }
        log.debug("HANDLER: wrote {} fields to {} (pid={})", persistable.size(), tableName, recordIdStr);
    }

    /**
     * Resolve the set of JSONB host columns for a model, combining the model
     * definition's declared jsonb fields with the physical jsonb columns reported
     * by the database. Same approach as
     * {@code CommandFieldMapExecutor#resolveJsonbColumns}.
     */
    private Set<String> resolveJsonbColumns(ModelDefinition modelDef, String tableName) {
        Set<String> jsonbColumns = new LinkedHashSet<>();
        if (modelDef != null) {
            jsonbColumns.addAll(com.auraboot.framework.meta.util.JsonbFieldHelper.getJsonbHostColumns(modelDef));
        }
        if (StringUtils.hasText(tableName)) {
            try {
                Set<String> physicalColumns = dynamicDataMapper.findJsonbColumns(tableName);
                if (physicalColumns != null) {
                    jsonbColumns.addAll(physicalColumns);
                }
            } catch (Exception e) {
                log.debug("Could not resolve physical JSONB columns for {}: {}", tableName, e.getMessage());
            }
        }
        return jsonbColumns;
    }

    private void executeScopedUpdate(
            String tableName,
            String modelCode,
            Map.Entry<String, Object> idEntry,
            Map<String, Object> data,
            Long tenantId,
            Set<String> jsonbColumns) {
        CommandExecutorUtils.validateSqlIdentifier(tableName, "handler field tableName");
        CommandExecutorUtils.validateSqlIdentifier(idEntry.getKey(), "handler field id column");

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ");
        int index = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            CommandExecutorUtils.validateSqlIdentifier(entry.getKey(), "handler field column");
            if (index > 0) {
                sql.append(", ");
            }
            String paramName = "set" + index;
            if (jsonbColumns != null && jsonbColumns.contains(entry.getKey())) {
                sql.append(entry.getKey()).append(" = #{params.").append(paramName)
                        .append(",jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb");
            } else {
                sql.append(entry.getKey()).append(" = #{params.").append(paramName).append("}");
            }
            Object parameterValue = entry.getValue();
            if (jsonbColumns != null && jsonbColumns.contains(entry.getKey())
                    && parameterValue != null && !(parameterValue instanceof String)) {
                parameterValue = com.auraboot.framework.meta.util.JsonbFieldHelper.toJsonString(parameterValue);
            }
            params.put(paramName, parameterValue);
            index++;
        }

        params.put("recordId", idEntry.getValue());
        params.put("tenantId", tenantId);
        sql.append(" WHERE ")
                .append(idEntry.getKey())
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        appendScopedWriteGuards(sql, tenantId, modelCode);

        dynamicDataMapper.updateByQuery(sql.toString(), params);
    }

    private boolean shouldUseScopedWrite() {
        return dataPermissionEngine != null
                && dataDomainService != null
                && MetaContext.exists()
                && !MetaContext.isDataPermissionBypassed()
                && MetaContext.getCurrentUserId() != null;
    }

    private void appendScopedWriteGuards(StringBuilder sql, Long tenantId, String modelCode) {
        Long userId = MetaContext.getCurrentUserId();
        try {
            String rowFilter = DynamicDataQueryScope.rowFilter(tenantId, modelCode, userId,
                    () -> dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
            appendScopedFilter(sql, rowFilter);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission for HANDLER result write on model {}",
                    modelCode, e);
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Data permission evaluation failed for model: " + modelCode);
        }

        try {
            String domainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                    () -> dataDomainService.buildDomainFilter(modelCode, userId));
            appendScopedFilter(sql, domainFilter);
        } catch (Exception e) {
            log.error("Failed to apply domain filter for HANDLER result write on model {}",
                    modelCode, e);
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Data domain filter evaluation failed for model: " + modelCode);
        }
    }

    private void appendScopedFilter(StringBuilder sql, String filter) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        String normalized = filter.trim();
        if (normalized.regionMatches(true, 0, "AND ", 0, 4)) {
            normalized = normalized.substring(4).trim();
        } else if (normalized.regionMatches(true, 0, "WHERE ", 0, 6)) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.isBlank()) {
            return;
        }
        rejectStatementInjectionMarkers(normalized);
        sql.append(" AND ").append(normalized);
    }

    private void rejectStatementInjectionMarkers(String filter) {
        if (filter.contains(";") || filter.contains("--") || filter.contains("/*") || filter.contains("*/")) {
            throw new BusinessException(ResponseCode.BadParam, "Unsafe DataScope filter for HANDLER result write");
        }
    }

    // ==================== Helper methods ====================

    /**
     * Open the command's authority for the handler stage when the boundary actually granted one
     * (DDR-2026-07-22). Only an AUTHORIZED verdict qualifies: a command that declared no
     * permissions granted nothing, and NOT_APPLICABLE must keep today's behaviour exactly.
     *
     * <p>Disabled by default. The flip is gated on what observe mode reports from a real workload —
     * whether any command would newly reach a target its caller cannot read.
     */
    // package-private: the safety property (only AUTHORIZED opens a scope) is directly tested.
    /**
     * Pin the handler stage to the aggregate root the request named, when it named one.
     *
     * <p>Independent of {@link #commandDataAuthorityEnabled} on purpose: opening this scope only
     * ever <em>adds</em> a constraint (writes get pinned to the authorized document), so it is safe
     * on every path, whereas the flag gates whether authority is <em>inherited</em>. Models that
     * declare no aggregate binding are unaffected, so this changes no behaviour until a model opts
     * in.</p>
     */
    <T> T withCommandAggregate(CommandPipelineContext ctx, java.util.function.Supplier<T> body) {
        CommandExecuteRequest request = ctx.getRequest();
        String aggregateId = request == null ? null : request.getTargetRecordId();
        if (!StringUtils.hasText(aggregateId)) {
            return body.get();
        }
        return MetaContext.runWithCommandAggregate(aggregateId, body);
    }

    <T> T withCommandAuthority(CommandPipelineContext ctx, java.util.function.Supplier<T> body) {
        if (!commandDataAuthorityEnabled) {
            return body.get();
        }
        CommandAuthorizationVerdict verdict = ctx.getAuthorizationVerdict();
        if (verdict == null || !verdict.isAuthorized()) {
            return body.get();
        }
        return MetaContext.runWithCommandAuthority(verdict.permissionCode(), body);
    }

    private void executePluginCommandHandler(String commandCode, String modelCode,
                                              Map<String, Object> payload, Long tenantId, Long userId,
                                              CommandExecuteRequest request,
                                              Map<String, Object> fieldMapResults,
                                              Map<String, Object> handlerResults,
                                              Map<String, Object> execConfig) {
        if (extensionRegistry == null) {
            return;
        }

        String handlerCode = resolvePluginHandlerCode(commandCode, execConfig);
        Optional<CommandHandlerExtension> pluginHandler = extensionRegistry.getCommandHandler(handlerCode);
        // Opt-in secondaries chain AFTER the primary (same transaction + context). Empty for every
        // command with no opt-in secondaries, i.e. every command today — so this is a no-op now.
        List<CommandHandlerExtension> secondaryHandlers = extensionRegistry.getSecondaryCommandHandlers(handlerCode);
        if (pluginHandler.isEmpty() && secondaryHandlers.isEmpty()) {
            log.debug("No plugin command handler found for: {} (command={})", handlerCode, commandCode);
            return;
        }

        // The primary may be absent when a command is otherwise declarative (e.g. a pure
        // state-transition) but a downstream plugin chains a secondary onto it.
        CommandHandlerExtension handler = pluginHandler.orElse(null);

        Map<String, Object> asyncHandlerParams = resolveHandlerParams(execConfig);

        // Opt-in async dispatch: commands declaring handlerParams.async run off the
        // request thread via AsyncTaskService, so long-running handlers (e.g. bulk
        // Excel imports) finish in the background instead of blocking the HTTP
        // request and tripping the BFF proxy 30s timeout (502). Dry-run stays
        // synchronous — its side effects must roll back inside the JDBC envelope.
        // Async dispatch applies to the primary only; chained secondaries are for
        // synchronous, same-transaction side effects and are not run on the async path.
        if (handler != null && !request.isDryRun() && isAsyncHandler(asyncHandlerParams) && asyncTaskService != null) {
            String effectiveRecordId = resolveEffectiveRecordId(request, fieldMapResults);
            String taskCode = submitAsyncHandlerTask(handlerCode, commandCode, modelCode,
                    effectiveRecordId, payload, asyncHandlerParams,
                    tenantId, userId);
            handlerResults.put("async", true);
            handlerResults.put("taskCode", taskCode);
            handlerResults.put("taskType", CommandHandlerAsyncTaskExecutor.TASK_TYPE);
            // Surface the target record pid so callers (e.g. a form submitting a
            // model-bound async command) can redirect to the just-created record's
            // detail page. The synchronous handler return carries this pid naturally;
            // the async envelope otherwise only had {async, taskCode}, leaving the UI
            // with no record to navigate to. Purely additive — existing consumers
            // ignore the extra key.
            if (StringUtils.hasText(effectiveRecordId)) {
                handlerResults.put("recordPid", effectiveRecordId);
            }
            log.info("Command {} dispatched asynchronously (handler={}): taskCode={}",
                    commandCode, handlerCode, taskCode);
            return;
        }

        log.info("Executing plugin command handler for: {} (command={}, primary={}, secondaries={})",
                handlerCode, commandCode,
                handler != null ? handler.getClass().getName() : "<none>", secondaryHandlers.size());

        DynamicDataQueryScope queryScope = DynamicDataQueryScope.open();
        try {
            String namespace = handlerCode.contains(":") ? handlerCode.split(":")[0] : null;
            Map<String, Object> pluginSettings = new HashMap<>();
            pluginSettings.putAll(resolveHandlerParams(execConfig));
            pluginSettings.put("__commandCode", commandCode);
            pluginSettings.put("__handlerCode", handlerCode);
            pluginSettings.put("__dataAccessor",
                    new com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl(dynamicDataService));
            if (biTemporalService != null) {
                pluginSettings.put("__biTemporalAccessor",
                        new BiTemporalAccessorImpl(biTemporalService, objectMapper));
            }
            if (llmProviderFactory != null) {
                pluginSettings.put(CommandHandlerExtension.AI_PROVIDER_ACCESSOR_KEY,
                        new LlmProviderAccessorImpl(llmProviderFactory, objectMapper, tenantId));
            }
            if (fileService != null && storageProvider != null) {
                pluginSettings.put(CommandHandlerExtension.FILE_ACCESSOR_KEY,
                        new FileAccessorImpl(fileService, storageProvider, userId));
            }
            if (asyncTaskService != null && objectMapper != null) {
                pluginSettings.put(CommandHandlerExtension.ASYNC_TASK_ACCESSOR_KEY,
                        new AsyncTaskAccessorImpl(asyncTaskService, objectMapper, tenantId, userId));
            }
            CommandHandlerExtension.CommandContext pluginContext = CommandHandlerExtension.CommandContext.builder()
                    .tenantId(tenantId)
                    .namespace(namespace)
                    .commandType(handlerCode)
                    .modelCode(modelCode)
                    .recordId(resolveEffectiveRecordId(request, fieldMapResults))
                    .payload(payload)
                    .settings(pluginSettings)
                    .dryRun(request.isDryRun())
                    .build();

            // Primary handler (if any). Gated per-handler on supportsDryRun() (PR-56): a primary
            // that is not dry-run-safe is skipped under dry-run because external side effects
            // escape the JDBC rollback envelope.
            if (handler != null) {
                if (request.isDryRun() && !handler.supportsDryRun()) {
                    log.info("Dry-run: skipping primary handler {} for command {} (supportsDryRun()=false)",
                            handler.getClass().getName(), handlerCode);
                } else {
                    Object result = handler.execute(pluginContext);
                    if (result instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        handlerResults.putAll(resultMap);
                        log.info("Plugin handler returned {} entries", resultMap.size());
                    } else if (result != null) {
                        handlerResults.put("pluginResult", result);
                    }
                }
            }

            // Chained secondaries run after the primary, in priority order, sharing the same
            // context (and DataAccessor) and the same command transaction. A secondary that
            // throws propagates out and rolls back the whole command (intended atomicity). Their
            // result keys are merged without clobbering the primary's (putIfAbsent).
            for (CommandHandlerExtension secondary : secondaryHandlers) {
                if (request.isDryRun() && !secondary.supportsDryRun()) {
                    log.info("Dry-run: skipping secondary handler {} for command {} (supportsDryRun()=false)",
                            secondary.getClass().getName(), handlerCode);
                    continue;
                }
                log.info("Executing chained secondary handler {} for command {}",
                        secondary.getClass().getName(), handlerCode);
                Object secResult = secondary.execute(pluginContext);
                if (secResult instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> secMap = (Map<String, Object>) secResult;
                    secMap.forEach(handlerResults::putIfAbsent);
                    log.info("Secondary handler {} returned {} entries",
                            secondary.getClass().getSimpleName(), secMap.size());
                }
            }

        } catch (Exception e) {
            log.error("Plugin command handler execution failed for {} (command={}): {}",
                    handlerCode, commandCode, e.getMessage(), e);
            // Plugin handlers use stable, transport-neutral error keys because
            // the plugin API must not depend on host web exceptions. Preserve
            // the optimistic-concurrency semantic at the host boundary so DSL
            // clients receive HTTP 409 and can offer reload/retry recovery.
            if (e.getMessage() != null && e.getMessage().contains("iot.error.version_conflict")) {
                throw new com.auraboot.framework.exception.ConflictException(e.getMessage(), e);
            }
            throw new BusinessException(ResponseCode.BadParam, "Plugin handler execution failed: " + e.getMessage());
        } finally {
            queryScope.close();
        }
    }

    private String resolvePluginHandlerCode(String commandCode, Map<String, Object> execConfig) {
        if (execConfig != null) {
            Object handler = execConfig.get("handler");
            if (handler instanceof String handlerCode && StringUtils.hasText(handlerCode)) {
                return handlerCode.trim();
            }
        }
        return commandCode;
    }

    private boolean isAsyncHandler(Map<String, Object> handlerParams) {
        Object v = handlerParams.get("async");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private String submitAsyncHandlerTask(String handlerCode, String commandCode, String modelCode,
                                          String recordPid, Map<String, Object> payload,
                                          Map<String, Object> handlerParams, Long tenantId, Long userId) {
        Map<String, Object> input = new HashMap<>();
        input.put("handlerCode", handlerCode);
        input.put("commandCode", commandCode);
        input.put("tenantId", tenantId);
        input.put("userId", userId);
        input.put("modelCode", modelCode);
        input.put("recordPid", recordPid);
        input.put("payload", payload != null ? payload : Collections.emptyMap());
        input.put("handlerParams", handlerParams);
        // The async executor never re-enters the pipeline, so the boundary's decision would be lost
        // at the thread hand-off — and the handler would run with no authority at all, which is the
        // exact path that failed in production. Persist the verdict WITH the task, so background
        // work carries an authority someone can point at rather than an inherited bypass flag.
        String commandAuthority = MetaContext.getCommandAuthority();
        if (commandAuthority != null) {
            input.put("commandAuthority", commandAuthority);
        }

        AsyncTaskSubmitRequest taskRequest = new AsyncTaskSubmitRequest();
        taskRequest.setTaskType(CommandHandlerAsyncTaskExecutor.TASK_TYPE);
        taskRequest.setTaskName(commandCode != null ? commandCode : handlerCode);
        taskRequest.setInputParams(objectMapper.valueToTree(input));

        AsyncTaskDTO dto = asyncTaskService.submitTask(taskRequest, tenantId, userId);
        return dto.getTaskCode();
    }

    private Map<String, Object> resolveHandlerParams(Map<String, Object> execConfig) {
        if (execConfig == null) {
            return Collections.emptyMap();
        }
        Object rawParams = execConfig.get("handlerParams");
        if (!(rawParams instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> params = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key && StringUtils.hasText(key)) {
                params.put(key, entry.getValue());
            }
        }
        return params;
    }

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

        String recordPid = request != null ? request.getTargetRecordId() : null;
        String businessKey = command.getModelCode() + ":" + (recordPid != null ? recordPid : "new");

        String titleTemplate = (String) trigger.getOrDefault("titleTemplate", command.getCode());
        String title = resolveBpmTitle(titleTemplate, payload, command);

        Map<String, Object> businessData = new HashMap<>();
        businessData.put("modelCode", command.getModelCode());
        businessData.put("recordPid", recordPid);
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

    private String resolveEffectiveRecordId(CommandExecuteRequest request, Map<String, Object> fieldMapResults) {
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            return request.getTargetRecordId();
        }
        if (fieldMapResults == null) {
            return null;
        }
        Object recordId = fieldMapResults.get("recordPid");
        if (recordId instanceof String recordIdStr && StringUtils.hasText(recordIdStr)) {
            return recordIdStr;
        }
        return null;
    }
}
