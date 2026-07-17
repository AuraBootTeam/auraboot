package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionActionServiceTaskExtensionTest {

    @Test
    void supportsActionHandlersAndWritesResultVariable() throws Exception {
        AtomicReference<ResolvedActionPlan> capturedPlan = new AtomicReference<>();
        AtomicReference<DecisionContext> capturedContext = new AtomicReference<>();
        ActionHandler smsHandler = new ActionHandler() {
            @Override
            public boolean supports(String actionType) {
                return "SEND_SMS".equals(actionType);
            }

            @Override
            public void execute(ResolvedActionPlan plan, DecisionContext context) {
            }

            @Override
            public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
                capturedPlan.set(plan);
                capturedContext.set(context);
                return Map.of("sentCount", 1, "provider", "stub");
            }
        };
        DecisionActionServiceTaskExtension extension =
                new DecisionActionServiceTaskExtension(provider(smsHandler), new ObjectMapper());
        Map<String, Object> variables = new HashMap<>();
        variables.put("businessKey", "REQ-1");
        ServiceTaskActionExtension.ActionContext actionContext = ServiceTaskActionExtension.ActionContext.builder()
                .actionType("SEND_SMS")
                .variables(variables)
                .properties(Map.of(
                        "target", "PHONE:${record.phone}",
                        "payloadJson", "{\"content\":\"流程短信\"}",
                        "idempotencyKey", "bpm:REQ-1:SEND_SMS",
                        "resultVar", "smsResult",
                        "ruleCode", "bpm-node-action",
                        "order", "7"))
                .build();

        Object result = extension.execute(actionContext);

        assertThat(extension.supports("SEND_SMS")).isTrue();
        assertThat(extension.supports("WEBHOOK")).isFalse();
        assertThat(result).isEqualTo(Map.of("sentCount", 1, "provider", "stub"));
        assertThat(variables).containsEntry("smsResult", result);
        assertThat(capturedPlan.get()).isNotNull();
        assertThat(capturedPlan.get().ruleCode()).isEqualTo("bpm-node-action");
        assertThat(capturedPlan.get().type()).isEqualTo("SEND_SMS");
        assertThat(capturedPlan.get().target()).isEqualTo("PHONE:${record.phone}");
        assertThat(capturedPlan.get().order()).isEqualTo(7);
        assertThat(capturedPlan.get().payload()).containsEntry("content", "流程短信");
        assertThat(capturedPlan.get().idempotencyKey()).isEqualTo("bpm:REQ-1:SEND_SMS");
        assertThat(capturedContext.get().resolve(com.auraboot.framework.decision.ast.Scope.PROCESS, "businessKey").value())
                .isEqualTo("REQ-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void actionExecutionExceptionWritesStructuredFailureResultVariableBeforeRethrow() {
        ActionHandler smsHandler = new ActionHandler() {
            @Override
            public boolean supports(String actionType) {
                return "SEND_SMS".equals(actionType);
            }

            @Override
            public void execute(ResolvedActionPlan plan, DecisionContext context) {
            }

            @Override
            public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
                throw new ActionExecutionException("No real SMS sender available", Map.of(
                        "channel", "sms",
                        "failureReason", "provider_unavailable",
                        "targetPhones", java.util.List.of("+8613800138000"),
                        "sentCount", 0), null);
            }
        };
        DecisionActionServiceTaskExtension extension =
                new DecisionActionServiceTaskExtension(provider(smsHandler), new ObjectMapper());
        Map<String, Object> variables = new HashMap<>();
        ServiceTaskActionExtension.ActionContext actionContext = ServiceTaskActionExtension.ActionContext.builder()
                .actionType("SEND_SMS")
                .variables(variables)
                .properties(Map.of(
                        "target", "+8613800138000",
                        "payloadJson", "{\"content\":\"流程短信\"}",
                        "idempotencyKey", "bpm:REQ-1:SEND_SMS",
                        "resultVar", "smsResult",
                        "ruleCode", "bpm-node-action"))
                .build();

        assertThatThrownBy(() -> extension.execute(actionContext))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessageContaining("No real SMS sender available");

        assertThat(variables).containsKey("smsResult");
        Map<String, Object> failure = (Map<String, Object>) variables.get("smsResult");
        assertThat(failure)
                .containsEntry("status", "FAILED")
                .containsEntry("actionType", "SEND_SMS")
                .containsEntry("ruleCode", "bpm-node-action")
                .containsEntry("idempotencyKey", "bpm:REQ-1:SEND_SMS")
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "provider_unavailable")
                .containsEntry("sentCount", 0)
                .containsEntry("error", "No real SMS sender available");
        assertThat((java.util.List<String>) failure.get("targetPhones")).containsExactly("+8613800138000");
    }

    private static ObjectProvider<ActionHandler> provider(ActionHandler handler) {
        return new ObjectProvider<>() {
            @Override
            public ActionHandler getObject(Object... args) throws BeansException {
                return handler;
            }

            @Override
            public ActionHandler getIfAvailable() throws BeansException {
                return handler;
            }

            @Override
            public ActionHandler getIfUnique() throws BeansException {
                return handler;
            }

            @Override
            public ActionHandler getObject() throws BeansException {
                return handler;
            }

            @Override
            public Iterator<ActionHandler> iterator() {
                return Stream.of(handler).iterator();
            }

            @Override
            public Stream<ActionHandler> stream() {
                return Stream.of(handler);
            }
        };
    }
}
