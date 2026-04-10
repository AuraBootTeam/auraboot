package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
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
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "load";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        CommandDefinition command = commandMetadataCache.findCurrentCommandByCode(ctx.getCommandCode());
        if (command == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + ctx.getCommandCode());
        }
        if (!Status.PUBLISHED.getCode().equals(command.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam, "Command is not published: " + ctx.getCommandCode());
        }
        ctx.setCommand(command);

        // Parse executionConfig
        ctx.setExecConfig(parseExecutionConfig(command));

        // Resolve concurrency settings
        ctx.setConcurrencyKey(resolveConcurrencyKey(ctx.getExecConfig(), ctx.getPayload()));
        ctx.setLockTimeoutMs(resolveLockTimeout(ctx.getExecConfig()));

        // Batch-load binding rules
        List<BindingRule> allRules = commandMetadataCache.findBindingRulesByCommandId(command.getId());
        Map<String, List<BindingRule>> rulesByType = allRules.stream()
                .filter(r -> r.getEnabled() != null && r.getEnabled())
                .collect(Collectors.groupingBy(BindingRule::getRuleType));
        ctx.setRulesByType(rulesByType);
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
