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
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        ctx.setHasPluginHandler(hasPluginHandler(ctx.getCommand().getCode()));
        ctx.setPluginRequiresDslPersistence(ctx.isHasPluginHandler()
                && shouldExecuteDslPersistenceWithPlugin(ctx.getExecConfig(), ctx.getRequest()));
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

    private boolean hasPluginHandler(String commandCode) {
        return extensionRegistry != null && extensionRegistry.getCommandHandler(commandCode).isPresent();
    }

    private boolean shouldExecuteDslPersistenceWithPlugin(Map<String, Object> execConfig,
                                                           com.auraboot.framework.meta.dto.CommandExecuteRequest request) {
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
}
