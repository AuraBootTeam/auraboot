package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Load command definition, parse execution config, and batch-load binding rules.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class LoadPhase implements CommandPhase {

    private final CommandMetadataCacheService commandMetadataCache;
    private final CommandService commandService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "load";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        CommandDefinition command = commandMetadataCache.findCurrentCommandByCode(ctx.getCommandCode());
        List<BindingRule> allRules;
        if (command == null) {
            CommandDefinitionDTO releaseCommand = commandService.findByCode(ctx.getCommandCode());
            command = fromDto(releaseCommand);
            allRules = releaseCommand.getBindingRules() == null
                    ? List.of()
                    : releaseCommand.getBindingRules().stream().map(this::fromDto).toList();
        } else {
            allRules = commandMetadataCache.findBindingRulesByCommandId(command.getId());
        }
        if (!Status.PUBLISHED.getCode().equals(command.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam, "Command is not published: " + ctx.getCommandCode());
        }
        ctx.setCommand(command);

        // Parse executionConfig
        ctx.setExecConfig(parseExecutionConfig(command));

        // A CREATE command never operates on an existing command target. Callers such as
        // automation legitimately carry the triggering record pid in the payload, but may also
        // populate targetRecordId as a convenience for UPDATE/DELETE commands. Keeping that source
        // pid as the target of a CREATE makes the authorization and snapshot phases look up the
        // source pid in the new record's model. Clear it here, after the authoritative command type
        // is known; FieldMapPhase will publish the newly created record pid back onto the request.
        normalizeCreateTarget(ctx);

        // Resolve concurrency settings
        ctx.setConcurrencyKey(resolveConcurrencyKey(ctx.getExecConfig(), ctx.getPayload()));
        ctx.setLockTimeoutMs(resolveLockTimeout(ctx.getExecConfig()));

        // Batch-load binding rules
        Map<String, List<BindingRule>> rulesByType = allRules.stream()
                .filter(r -> r.getEnabled() != null && r.getEnabled())
                .collect(Collectors.groupingBy(BindingRule::getRuleType));
        ctx.setRulesByType(rulesByType);
    }

    private CommandDefinition fromDto(CommandDefinitionDTO source) {
        CommandDefinition command = new CommandDefinition();
        command.setId(source.getId());
        command.setPid(source.getPid());
        command.setTenantId(source.getTenantId());
        command.setCode(source.getCode());
        command.setDisplayName(source.getDisplayName());
        command.setDescription(source.getDescription());
        command.setModelCode(source.getModelCode());
        command.setInputSchema(source.getInputSchema());
        command.setTargetModels(source.getTargetModels());
        command.setExecutionConfig(source.getExecutionConfig());
        command.setCmdRiskLevel(source.getCmdRiskLevel());
        command.setVersion(source.getVersion());
        command.setSemver(source.getSemver());
        command.setIsCurrent(source.getIsCurrent());
        command.setStatus(source.getStatus());
        return command;
    }

    private BindingRule fromDto(BindingRuleDTO source) {
        BindingRule rule = new BindingRule();
        rule.setId(source.getId());
        rule.setPid(source.getPid());
        rule.setCommandId(source.getCommandId());
        rule.setRuleType(source.getRuleType());
        rule.setExpression(source.getExpression());
        rule.setTargetModel(source.getTargetModel());
        rule.setTargetField(source.getTargetField());
        rule.setSourceField(source.getSourceField());
        rule.setHandlerClass(source.getHandlerClass());
        rule.setEventType(source.getEventType());
        rule.setConfig(source.getConfig());
        rule.setSequence(source.getSequence());
        rule.setEnabled(source.getEnabled());
        return rule;
    }

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

    private void normalizeCreateTarget(CommandPipelineContext ctx) {
        Object configuredType = ctx.getExecConfig().get("type");
        boolean configuredCreate = configuredType instanceof String type
                && "create".equalsIgnoreCase(type);
        boolean requestCreateWithoutConfiguredType = !(configuredType instanceof String type
                && !type.isBlank())
                && ctx.getRequest() != null
                && "create".equalsIgnoreCase(ctx.getRequest().getOperationType());
        if ((configuredCreate || requestCreateWithoutConfiguredType) && ctx.getRequest() != null) {
            ctx.getRequest().setTargetRecordId(null);
        }
    }

    private String resolveConcurrencyKey(Map<String, Object> config, Map<String, Object> payload) {
        if (config == null || config.isEmpty()) return null;
        String keyTemplate = (String) config.get("concurrencyKey");
        if (keyTemplate == null || keyTemplate.isEmpty()) return null;
        String resolved = keyTemplate;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String placeholder = "${payload." + entry.getKey() + "}";
            if (resolved.contains(placeholder) && entry.getValue() != null) {
                resolved = resolved.replace(placeholder, entry.getValue().toString());
            }
        }
        return resolved;
    }

    private long resolveLockTimeout(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return 5000L;
        Object timeout = config.get("lockTimeoutMs");
        return timeout instanceof Number ? ((Number) timeout).longValue() : 5000L;
    }
}
