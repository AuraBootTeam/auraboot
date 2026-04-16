package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic command handler that evaluates a Drools rule by code.
 *
 * <p>This handler lets any plugin declaratively invoke a rule from a command
 * pipeline or BPMN serviceTask without shipping custom Java. It is registered
 * under the well-known command code {@link #COMMAND_CODE} and is discovered by
 * {@code ExtensionRegistry#getCommandHandler(String)} from the core Spring
 * context.
 *
 * <p>Payload contract:
 * <ul>
 *   <li>{@code ruleCode} (required) — the {@code ab_bpm_rule.rule_code} to evaluate.</li>
 *   <li>{@code facts} (optional) — {@code Map<String,Object>} of facts fed to the engine.
 *       If absent an empty map is used.</li>
 * </ul>
 *
 * <p>Return: the evaluator's {@code _ruleResult} map. Downstream steps can read
 * individual keys directly.
 *
 * <p>Failure semantics:
 * <ul>
 *   <li>Missing {@code ruleCode} → {@link BusinessException} with i18n key
 *       {@code bpm.rule.rule_code_required}.</li>
 *   <li>Rule not found / DRL compile / engine error → {@link BusinessException}
 *       with i18n key {@code bpm.rule.execution_failed}.</li>
 *   <li>Rule reports {@code _ruleResult.valid == false} → {@link BusinessException}
 *       carrying the rule-reported {@code reason} as the i18n key (so submit-time
 *       validation surfaces a localized message).</li>
 * </ul>
 *
 * @since 7.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmRunRuleHandler implements CommandHandlerExtension {

    /** Well-known command code this handler services. */
    public static final String COMMAND_CODE = "bpm:run-rule";

    /** Payload key carrying the rule code. */
    public static final String ARG_RULE_CODE = "ruleCode";

    /** Payload key carrying the facts map. */
    public static final String ARG_FACTS = "facts";

    /** Rule output key that flags validation success/failure. */
    public static final String RESULT_VALID = "valid";

    /** Rule output key carrying the i18n reason when {@code valid == false}. */
    public static final String RESULT_REASON = "reason";

    /** i18n message key when required argument missing. */
    public static final String ERR_RULE_CODE_REQUIRED = "bpm.rule.rule_code_required";

    /** i18n message key when evaluation blows up. */
    public static final String ERR_EXECUTION_FAILED = "bpm.rule.execution_failed";

    private final DroolsEngineService droolsEngineService;

    @Override
    public String getCommandType() {
        return COMMAND_CODE;
    }

    @Override
    public Object execute(CommandContext context) {
        Map<String, Object> payload = context.payload() != null ? context.payload() : Map.of();

        Object ruleCodeObj = payload.get(ARG_RULE_CODE);
        if (ruleCodeObj == null || ruleCodeObj.toString().isBlank()) {
            throw new BusinessException(ERR_RULE_CODE_REQUIRED);
        }
        String ruleCode = ruleCodeObj.toString();

        @SuppressWarnings("unchecked")
        Map<String, Object> facts = payload.get(ARG_FACTS) instanceof Map
                ? (Map<String, Object>) payload.get(ARG_FACTS)
                : new HashMap<>();

        Map<String, Object> ruleResult;
        try {
            ruleResult = droolsEngineService.evaluate(ruleCode, facts);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Rule execution failed: ruleCode={}, error={}", ruleCode, e.getMessage(), e);
            throw new BusinessException(ERR_EXECUTION_FAILED);
        }

        // Enforce submit-time validation semantics: rule reports valid=false → abort.
        Object validFlag = ruleResult != null ? ruleResult.get(RESULT_VALID) : null;
        if (Boolean.FALSE.equals(validFlag)) {
            Object reason = ruleResult.get(RESULT_REASON);
            String messageKey = (reason != null && !reason.toString().isBlank())
                    ? reason.toString()
                    : ERR_EXECUTION_FAILED;
            throw new BusinessException(messageKey);
        }

        return ruleResult != null ? ruleResult : Map.of();
    }
}
