package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
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
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.BiTemporalAccessorImpl;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private BiTemporalService biTemporalService;

    @Autowired(required = false)
    private com.auraboot.framework.bpm.service.BpmIntegrationService bpmIntegrationService;

    @Override public String name() { return "handler"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        var handlerRules = ctx.getRulesByType().getOrDefault("handler", Collections.emptyList());
        Map<String, Object> handlerResults = executeHandlerPhase(
                handlerRules, ctx.getCommand(), ctx.getPayload(), ctx.getFieldMapResults(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getRequest(), ctx.getExecConfig());
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
                CommandHandler handler = applicationContext.getBean(rule.getHandlerClass(), CommandHandler.class);
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
        executePluginCommandHandler(command.getCode(), command.getModelCode(), payload, tenantId, request,
                fieldMapResults, handlerResults, execConfig);

        // 3. Declarative BPM trigger — skipped under dry-run since BPM
        // process state lives outside the command's transaction envelope.
        if (!request.isDryRun()) {
            executeBpmTrigger(execConfig, command, payload, request, handlerResults);
        }

        return handlerResults;
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
                : fieldMapResults != null ? (String) fieldMapResults.get("recordId") : null;
        if (!StringUtils.hasText(recordIdStr)) {
            return;
        }

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
    }

    // ==================== Helper methods ====================

    private void executePluginCommandHandler(String commandCode, String modelCode,
                                              Map<String, Object> payload, Long tenantId,
                                              CommandExecuteRequest request,
                                              Map<String, Object> fieldMapResults,
                                              Map<String, Object> handlerResults,
                                              Map<String, Object> execConfig) {
        if (extensionRegistry == null) {
            return;
        }

        String handlerCode = resolvePluginHandlerCode(commandCode, execConfig);
        Optional<CommandHandlerExtension> pluginHandler = extensionRegistry.getCommandHandler(handlerCode);
        if (pluginHandler.isEmpty()) {
            log.debug("No plugin command handler found for: {} (command={})", handlerCode, commandCode);
            return;
        }

        CommandHandlerExtension handler = pluginHandler.get();

        // PR-56: gate plugin handlers on the supportsDryRun() SPI method.
        // Plugins that do not explicitly opt in are skipped under dry-run
        // because external side effects escape the JDBC rollback envelope.
        if (request.isDryRun() && !handler.supportsDryRun()) {
            log.info("Dry-run: skipping plugin handler {} for command {} (supportsDryRun()=false)",
                    handler.getClass().getName(), handlerCode);
            return;
        }

        log.info("Executing plugin command handler for: {} (command={}, handler={})",
                handlerCode, commandCode, handler.getClass().getName());

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

            Object result = handler.execute(pluginContext);

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                handlerResults.putAll(resultMap);
                log.info("Plugin handler returned {} entries", resultMap.size());
            } else if (result != null) {
                handlerResults.put("pluginResult", result);
            }

        } catch (Exception e) {
            log.error("Plugin command handler execution failed for {} (command={}): {}",
                    handlerCode, commandCode, e.getMessage(), e);
            throw new BusinessException(ResponseCode.BadParam, "Plugin handler execution failed: " + e.getMessage());
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

        String recordId = request != null ? request.getTargetRecordId() : null;
        String businessKey = command.getModelCode() + ":" + (recordId != null ? recordId : "new");

        String titleTemplate = (String) trigger.getOrDefault("titleTemplate", command.getCode());
        String title = resolveBpmTitle(titleTemplate, payload, command);

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
        Object recordId = fieldMapResults.get("recordId");
        if (recordId instanceof String recordIdStr && StringUtils.hasText(recordIdStr)) {
            return recordIdStr;
        }
        return null;
    }
}
