package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DroolsServiceTaskDelegate}. Rule engine is mocked; this
 * verifies property → facts wiring, result merge-back into process variables,
 * and BusinessException propagation on {@code valid=false}.
 */
@ExtendWith(MockitoExtension.class)
class DroolsServiceTaskDelegateTest {

    @Mock
    private DroolsEngineService droolsEngineService;

    @InjectMocks
    private DroolsServiceTaskDelegate delegate;

    @Test
    void execute_happyPath_mergesResultIntoProcessVars() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_RULE_CODE, "wd_leave_routing");
        props.put(BpmServiceTaskConstants.ATTR_FACTS_VARS, "days,type");

        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 5);
        vars.put("type", "annual");
        vars.put("other", "ignored");

        when(droolsEngineService.evaluate(eq("wd_leave_routing"), any()))
                .thenReturn(Map.of("approverRole", "hr"));

        ExecutionContext ctx = mockContext(props, vars);
        delegate.execute(ctx);

        assertThat(vars).containsEntry("approverRole", "hr");
    }

    @Test
    void execute_ruleInvalid_throwsWithReason() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_RULE_CODE, "wd_leave_validation");
        when(droolsEngineService.evaluate(any(), any()))
                .thenReturn(Map.of("valid", false, "reason", "annual_leave_insufficient"));

        ExecutionContext ctx = mockContext(props, new HashMap<>());
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("annual_leave_insufficient");
    }

    @Test
    void execute_missingRuleCode_throws() {
        ExecutionContext ctx = mockContext(new HashMap<>(), new HashMap<>());
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(DroolsServiceTaskDelegate.ERR_RULE_CODE_REQUIRED);
    }

    private ExecutionContext mockContext(Map<String, String> properties, Map<String, Object> request) {
        ExecutionContext ctx = org.mockito.Mockito.mock(ExecutionContext.class);
        IdBasedElement element = org.mockito.Mockito.mock(IdBasedElement.class);
        when(ctx.getRequest()).thenReturn(request);
        when(ctx.getBaseElement()).thenReturn(element);
        when(element.getProperties()).thenReturn(properties);
        return ctx;
    }
}
