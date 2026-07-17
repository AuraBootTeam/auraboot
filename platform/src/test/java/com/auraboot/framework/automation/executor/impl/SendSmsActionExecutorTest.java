package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendSmsActionExecutorTest {

    @Mock
    private SmsSenderRouter smsSenderRouter;

    private SendSmsActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SendSmsActionExecutor(smsSenderRouter);
    }

    @Test
    void supports_sendSmsOnly() {
        assertThat(executor.supports("send_sms")).isTrue();
        assertThat(executor.supports("send_im")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_sendsSmsViaRealProviderAndReturnsEvidence() {
        when(smsSenderRouter.sendWithRealProvider(eq("+8613800138000"), eq("sla_timeout"), anyMap()))
                .thenReturn(new SmsSenderRouter.RoutedSmsResult("tencent_sms", SmsSendResult.ok("msg-001")));

        AutomationAction action = AutomationAction.builder()
                .type("send_sms")
                .config(Map.of(
                        "target", "PHONE:${record.mobile}",
                        "template", "sla_timeout",
                        "content", "单据 ${recordPid} 已超时"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "recordPid", "REQ-1",
                "record", Map.of("mobile", "+8613800138000")));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("channel", "sms")
                .containsEntry("provider", "tencent_sms")
                .containsEntry("sentCount", 1);
        assertThat((List<String>) result.get("targetPhones")).containsExactly("+8613800138000");
        assertThat((List<String>) result.get("messageIds")).containsExactly("msg-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsSenderRouter).sendWithRealProvider(eq("+8613800138000"), eq("sla_timeout"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("content", "单据 REQ-1 已超时");
    }

    @Test
    void execute_withoutRealProviderThrowsInsteadOfFakeCounting() {
        when(smsSenderRouter.sendWithRealProvider(eq("+8613800138000"), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("No real SMS sender available"));
        AutomationAction action = AutomationAction.builder()
                .type("send_sms")
                .config(Map.of(
                        "target", "+8613800138000",
                        "content", "超时提醒"))
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of(
                        "modelCode", "wd_leave_request",
                        "recordPid", "REQ-1")))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessageContaining("No real SMS sender available")
                .satisfies(error -> {
                    ActionExecutionException actionError = (ActionExecutionException) error;
                    assertThat(actionError.resultPayload())
                            .containsEntry("success", false)
                            .containsEntry("channel", "sms")
                            .containsEntry("template", "direct_message")
                            .containsEntry("sentCount", 0)
                            .containsEntry("failureReason", "sms_delivery_failed")
                            .containsEntry("errorMessage", "No real SMS sender available")
                            .containsEntry("modelCode", "wd_leave_request")
                            .containsEntry("recordPid", "REQ-1");
                    assertThat((List<String>) actionError.resultPayload().get("targetPhones"))
                            .containsExactly("+8613800138000");
                });
    }

    @Test
    void execute_requiresTargetAndContent() {
        AutomationAction noTarget = AutomationAction.builder()
                .type("send_sms")
                .config(Map.of("content", "消息"))
                .build();
        assertThatThrownBy(() -> executor.execute(noTarget, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");

        AutomationAction noContent = AutomationAction.builder()
                .type("send_sms")
                .config(Map.of("target", "+8613800138000"))
                .build();
        assertThatThrownBy(() -> executor.execute(noContent, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }
}
