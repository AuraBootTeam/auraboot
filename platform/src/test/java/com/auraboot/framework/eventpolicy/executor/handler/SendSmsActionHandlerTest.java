package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendSmsActionHandlerTest {

    @Mock
    private SmsSenderRouter smsSenderRouter;

    private SendSmsActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SendSmsActionHandler(smsSenderRouter);
    }

    @Test
    void supports_sendSmsOnlyAndReportsRuntimeAvailability() {
        when(smsSenderRouter.realSenderAvailability())
                .thenReturn(new SmsSenderRouter.SmsProviderAvailability(true, List.of("aliyun_sms"), null));

        assertThat(handler.supports("SEND_SMS")).isTrue();
        assertThat(handler.supports("SEND_IM")).isFalse();
        assertThat(handler.runtimeAvailable()).isTrue();
        assertThat(handler.runtimeProviderDependencies().get(0).providerType()).isEqualTo("SMS");
        assertThat(handler.runtimeProviderDependencies().get(0).providerCodes()).containsExactly("aliyun_sms");
        assertThat(handler.runtimeProviderDependencies().get(0).available()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithResult_sendsSmsViaRealProviderAndReturnsTracePayload() {
        when(smsSenderRouter.sendWithRealProvider(eq("+8613800138000"), eq("sla_timeout"), anyMap()))
                .thenReturn(new SmsSenderRouter.RoutedSmsResult("aliyun_sms", SmsSendResult.ok("msg-sms-1")));
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SMS",
                "SEND_SMS",
                "PHONE:${record.data.mobile}",
                10,
                Map.of(
                        "template", "sla_timeout",
                        "content", "单据 ${record.recordPid} 已超时",
                        "title", "SLA 超时"),
                "REQ-1:R-SMS:SEND_SMS");

        Map<String, Object> result = handler.executeWithResult(plan, decisionContext());

        assertThat(result)
                .containsEntry("channel", "sms")
                .containsEntry("provider", "aliyun_sms")
                .containsEntry("sentCount", 1)
                .containsEntry("ruleCode", "R-SMS")
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-1");
        assertThat((List<String>) result.get("targetPhones")).containsExactly("+8613800138000");
        assertThat((List<String>) result.get("messageIds")).containsExactly("msg-sms-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsSenderRouter).sendWithRealProvider(eq("+8613800138000"), eq("sla_timeout"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue())
                .containsEntry("content", "单据 REQ-1 已超时")
                .containsEntry("title", "SLA 超时")
                .containsEntry("ruleCode", "R-SMS");
    }

    @Test
    void executeWithResult_withoutRealProviderThrowsWithResolvedFailureEvidence() {
        when(smsSenderRouter.sendWithRealProvider(eq("+8613800138000"), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("No real SMS sender available"));
        ResolvedActionPlan plan = new ResolvedActionPlan(
                "R-SMS",
                "SEND_SMS",
                "+8613800138000",
                10,
                Map.of("content", "超时提醒"),
                "key");

        Throwable thrown = catchThrowable(() -> handler.executeWithResult(plan, decisionContext()));

        assertThat(thrown)
                .isInstanceOf(ActionExecutionException.class)
                .hasMessageContaining("No real SMS sender available");
        assertThat(((ActionExecutionException) thrown).resultPayload())
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "sms_delivery_failed")
                .containsEntry("errorMessage", "No real SMS sender available")
                .containsEntry("sentCount", 0)
                .containsEntry("ruleCode", "R-SMS");
        assertThat((List<String>) ((ActionExecutionException) thrown).resultPayload().get("targetPhones"))
                .containsExactly("+8613800138000");
    }

    @Test
    void executeWithResult_requiresTargetAndContent() {
        ResolvedActionPlan noTarget = new ResolvedActionPlan(
                "R", "SEND_SMS", null, 10, Map.of("content", "消息"), "key");
        ActionExecutionException noTargetError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noTarget, decisionContext()));
        assertThat(noTargetError).hasMessageContaining("target");
        assertThat(noTargetError.resultPayload())
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "action_target_missing")
                .containsEntry("field", "target")
                .containsEntry("actionType", "SEND_SMS")
                .containsEntry("ruleCode", "R")
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-1");

        ResolvedActionPlan noContent = new ResolvedActionPlan(
                "R", "SEND_SMS", "+8613800138000", 10, Map.of(), "key");
        ActionExecutionException noContentError = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(noContent, decisionContext()));
        assertThat(noContentError).hasMessageContaining("content");
        assertThat(noContentError.resultPayload())
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "payload_content_missing")
                .containsEntry("field", "payload.content")
                .containsEntry("target", "+8613800138000");
    }

    @Test
    void executeWithResult_invalidPhoneTargetKeepsStructuredFailurePayload() {
        ResolvedActionPlan invalidTarget = new ResolvedActionPlan(
                "R", "SEND_SMS", "PHONE:not-a-phone", 10, Map.of("content", "消息"), "key");

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(invalidTarget, decisionContext()));

        assertThat(error).hasMessageContaining("phone number");
        assertThat(error.resultPayload())
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("targetType", "PHONE")
                .containsEntry("target", "PHONE:not-a-phone")
                .containsEntry("field", "target")
                .containsEntry("invalidTarget", "PHONE:not-a-phone");
    }

    private static DecisionContext decisionContext() {
        return DecisionContext.builder()
                .scope(Scope.RECORD, Map.of(
                        "entityCode", "wd_leave_request",
                        "recordPid", "REQ-1",
                        "data", Map.of("mobile", "+8613800138000")))
                .build();
    }
}
