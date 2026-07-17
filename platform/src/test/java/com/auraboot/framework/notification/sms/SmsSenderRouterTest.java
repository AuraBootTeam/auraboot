package com.auraboot.framework.notification.sms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SmsSenderRouter.
 */
@ExtendWith(MockitoExtension.class)
class SmsSenderRouterTest {

    @Mock
    private SmsSender sender1;

    @Mock
    private SmsSender sender2;

    // =========================================================
    // send() — happy path
    // =========================================================

    @Test
    void send_firstAvailableSender_usedForDelivery() {
        SmsSenderRouter router = new SmsSenderRouter(List.of(sender1, sender2));

        when(sender1.isAvailable()).thenReturn(true);
        SmsSendResult expected = SmsSendResult.ok("msg-001");
        when(sender1.send(any(), any(), any())).thenReturn(expected);

        SmsSendResult result = router.send("+8613800138000", "tpl001", Map.of("code", "1234"));

        assertThat(result).isEqualTo(expected);
        verify(sender1).send("+8613800138000", "tpl001", Map.of("code", "1234"));
        verify(sender2, never()).send(any(), any(), any());
    }

    @Test
    void send_firstUnavailable_usesSecond() {
        SmsSenderRouter router = new SmsSenderRouter(List.of(sender1, sender2));

        when(sender1.isAvailable()).thenReturn(false);
        when(sender2.isAvailable()).thenReturn(true);
        SmsSendResult expected = SmsSendResult.ok("msg-002");
        when(sender2.send(any(), any(), any())).thenReturn(expected);

        SmsSendResult result = router.send("+8613900139000", "tpl001", Map.of());

        assertThat(result).isEqualTo(expected);
        verify(sender1, never()).send(any(), any(), any());
        verify(sender2).send(any(), any(), any());
    }

    // =========================================================
    // send() — no available sender
    // =========================================================

    @Test
    void send_noAvailableSender_throwsIllegalState() {
        SmsSenderRouter router = new SmsSenderRouter(List.of(sender1));

        when(sender1.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> router.send("+8613800138000", "tpl001", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SMS sender available");
    }

    @Test
    void send_emptySenderList_throwsIllegalState() {
        SmsSenderRouter router = new SmsSenderRouter(List.of());

        assertThatThrownBy(() -> router.send("+8613800138000", "tpl001", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hasRealSender_falseWhenOnlyNoopSenderIsAvailable() {
        SmsSenderRouter router = new SmsSenderRouter(List.of(new NoOpSmsSender()));

        assertThat(router.hasRealSender()).isFalse();
        assertThat(router.realSenderAvailability().available()).isFalse();
        assertThat(router.realSenderAvailability().providerCodes()).isEmpty();
        assertThat(router.realSenderAvailability().reason()).contains("未配置真实短信 provider");
    }

    @Test
    void realSenderAvailability_reportsConfiguredProviderWhenUnavailable() {
        SmsSender realSender = mock(SmsSender.class);
        when(realSender.getProviderCode()).thenReturn("aliyun_sms");
        when(realSender.isAvailable()).thenReturn(false);

        SmsSenderRouter router = new SmsSenderRouter(List.of(new NoOpSmsSender(), realSender));

        SmsSenderRouter.SmsProviderAvailability availability = router.realSenderAvailability();

        assertThat(availability.available()).isFalse();
        assertThat(availability.providerCodes()).containsExactly("aliyun_sms");
        assertThat(availability.reason()).contains("真实短信 provider 当前不可用");
    }

    @Test
    void realSenderAvailability_reportsAvailableRealProviderCodes() {
        SmsSender realSender = mock(SmsSender.class);
        when(realSender.getProviderCode()).thenReturn("tencent_sms");
        when(realSender.isAvailable()).thenReturn(true);

        SmsSenderRouter router = new SmsSenderRouter(List.of(new NoOpSmsSender(), realSender));

        SmsSenderRouter.SmsProviderAvailability availability = router.realSenderAvailability();

        assertThat(availability.available()).isTrue();
        assertThat(availability.providerCodes()).containsExactly("tencent_sms");
        assertThat(availability.reason()).isNull();
    }

    @Test
    void sendWithRealProvider_rejectsNoopFallback() {
        SmsSenderRouter router = new SmsSenderRouter(List.of(new NoOpSmsSender()));

        assertThatThrownBy(() -> router.sendWithRealProvider("+8613800138000", "tpl001", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No real SMS sender available");
    }

    @Test
    void sendWithRealProvider_usesAvailableNonNoopSender() {
        SmsSender realSender = mock(SmsSender.class);
        when(realSender.getProviderCode()).thenReturn("tencent_sms");
        when(realSender.isAvailable()).thenReturn(true);
        when(realSender.send(eq("+8613800138000"), eq("tpl001"), eq(Map.of("content", "hello"))))
                .thenReturn(SmsSendResult.ok("msg-real-001"));

        SmsSenderRouter router = new SmsSenderRouter(List.of(new NoOpSmsSender(), realSender));

        SmsSenderRouter.RoutedSmsResult result =
                router.sendWithRealProvider("+8613800138000", "tpl001", Map.of("content", "hello"));

        assertThat(result.providerCode()).isEqualTo("tencent_sms");
        assertThat(result.sendResult().getMessageId()).isEqualTo("msg-real-001");
        verify(realSender).send("+8613800138000", "tpl001", Map.of("content", "hello"));
    }
}
