package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin SmartEngine serviceTask delegate that evaluates a Drools rule.
 *
 * <p>Wired into BPMN via {@code smart:class="droolsServiceTaskDelegate"}. The
 * node XML carries the following {@code smart:*} extension attributes
 * (surfaced by SmartEngine as the activity {@code properties} map):
 * <ul>
 *   <li>{@code smart:ruleCode} — the rule code to evaluate.</li>
 *   <li>{@code smart:factsVars} — comma-separated list of process variable names
 *       to collect into the facts map fed to the rule engine. Each listed
 *       variable name becomes a fact key. Absent variables become {@code null}
 *       entries (Drools can still pattern-match on missing keys).</li>
 * </ul>
 *
 * <p>After evaluation the result map is merged back into the process variables
 * so downstream gateway conditions / tasks can read the rule output directly
 * (e.g. {@code ${approverRole == 'manager'}}).
 *
 * <p>If the rule reports {@code valid == false}, a {@link BusinessException}
 * is thrown with the rule-reported reason as the i18n key — mirroring the
 * pre-execution pipeline semantics of {@link com.auraboot.framework.bpm.handler.BpmRunRuleHandler}.
 *
 * @since 7.3.0
 */
@Slf4j
@Component(BpmServiceTaskConstants.BEAN_DROOLS_DELEGATE)
@RequiredArgsConstructor
public class DroolsServiceTaskDelegate implements JavaDelegation {

    public static final String ERR_RULE_CODE_REQUIRED = "bpm.rule.rule_code_required";

    /** Rule output key that flags validation success/failure. */
    public static final String RESULT_VALID = "valid";

    /** Rule output key carrying the i18n reason when {@code valid == false}. */
    public static final String RESULT_REASON = "reason";

    public static final String ERR_EXECUTION_FAILED = "bpm.rule.execution_failed";

    private final DroolsEngineService droolsEngineService;

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
            executionContext.setRequest(processVars);
        }

        Map<String, String> properties = resolveProperties(executionContext);
        String ruleCode = properties.get(BpmServiceTaskConstants.ATTR_RULE_CODE);
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new BusinessException(ERR_RULE_CODE_REQUIRED);
        }

        Map<String, Object> facts = buildFacts(properties.get(BpmServiceTaskConstants.ATTR_FACTS_VARS),
                processVars);

        Map<String, Object> ruleResult;
        try {
            ruleResult = droolsEngineService.evaluate(ruleCode, facts);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Drools serviceTask evaluation failed: ruleCode={}, error={}",
                    ruleCode, e.getMessage(), e);
            throw new BusinessException(ERR_EXECUTION_FAILED);
        }

        if (ruleResult == null) {
            return;
        }

        Object validFlag = ruleResult.get(RESULT_VALID);
        if (Boolean.FALSE.equals(validFlag)) {
            Object reason = ruleResult.get(RESULT_REASON);
            String messageKey = (reason != null && !reason.toString().isBlank())
                    ? reason.toString()
                    : ERR_EXECUTION_FAILED;
            throw new BusinessException(messageKey);
        }

        // Merge result keys back into process variables so gateway conditions
        // can reference them directly (e.g. ${approverRole == 'manager'}).
        for (Map.Entry<String, Object> entry : ruleResult.entrySet()) {
            processVars.put(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Object> buildFacts(String factsVars, Map<String, Object> processVars) {
        Map<String, Object> facts = new HashMap<>();
        if (factsVars == null || factsVars.isBlank()) {
            return facts;
        }
        for (String rawName : factsVars.split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) continue;
            facts.put(name, processVars.get(name));
        }
        return facts;
    }

    private Map<String, String> resolveProperties(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased
                && idBased.getProperties() != null) {
            return idBased.getProperties();
        }
        return new HashMap<>();
    }
}
