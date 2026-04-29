package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.RuleOverride;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.validation.CrossFieldRuleEngine;
import com.auraboot.framework.meta.validation.RuleEvaluationResult;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@Order(800)
@RequiredArgsConstructor
public class PreInvariantPhase implements CommandPhase {

    private final InvariantEngine invariantEngine;
    private final CommandStateCheckExecutor stateCheckExecutor;
    private final MetaModelService metaModelService;
    private final CommandSpelEvaluator spelEvaluator;
    private final ExtensionRegistry extensionRegistry;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private I18nService i18nService;

    @Override
    public String name() {
        return "pre_invariant";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // Pre-invariant
        String stateField = stateCheckExecutor.getStateFieldForModel(ctx.getCommand().getModelCode());
        String currentState = (ctx.getRequest() != null
                && StringUtils.hasText(ctx.getRequest().getTargetRecordId()) && stateField != null)
                ? stateCheckExecutor.readCurrentState(ctx.getTenantId(), ctx.getCommand().getModelCode(),
                        ctx.getRequest().getTargetRecordId(), stateField)
                : null;
        invariantEngine.evaluatePreInvariants(
                ctx.getTenantId(), ctx.getCommand().getCode(), ctx.getCommand().getModelCode(),
                ctx.getPayload(), ctx.getRequest() != null ? ctx.getRequest().getTargetRecordId() : null,
                currentState);

        // Cross-field rules
        executeCrossFieldRules(ctx.getCommand(), ctx.getPayload(), ctx.getExecConfig());

        // Resolve plugin handler flags
        String pluginHandlerCode = resolvePluginHandlerCode(ctx.getCommand().getCode(), ctx.getExecConfig());
        Optional<CommandHandlerExtension> pluginHandler = findPluginHandler(pluginHandlerCode);
        ctx.setHasPluginHandler(pluginHandler.isPresent());
        ctx.setPluginRequiresDslPersistence(pluginHandler
                .map(handler -> handler.requiresDslPersistence(
                        pluginHandlerCode, ctx.getExecConfig(), ctx.getRequest()))
                .orElse(false));
    }

    // ==================== Inlined delegate methods ====================

    @SuppressWarnings("unchecked")
    private void executeCrossFieldRules(CommandDefinition command,
                                         Map<String, Object> payload,
                                         Map<String, Object> execConfig) {
        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        List<CrossFieldRule> modelRules = (modelDef != null && modelDef.getRules() != null)
                ? modelDef.getRules() : List.of();
        if (modelRules.isEmpty() && (execConfig == null || !execConfig.containsKey("ruleOverrides"))) {
            return;
        }

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

        var spelContext = spelEvaluator.buildSpelContext(payload);
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
        if (result.hasWarnings()) {
            for (var w : result.warnings()) {
                log.info("Cross-field validation warning [{}]: {}", w.ruleId(), w.message());
            }
        }
    }

    private Optional<CommandHandlerExtension> findPluginHandler(String commandCode) {
        return extensionRegistry != null
                ? extensionRegistry.getCommandHandler(commandCode)
                : Optional.empty();
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
}
