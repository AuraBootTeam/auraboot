package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushNotificationChannelTest {

    @Mock DeviceTokenService deviceTokenService;
    @Mock FcmPushService fcmPushService;

    private PushNotificationChannel channel(boolean withFcm) {
        PushNotificationChannel c = new PushNotificationChannel(deviceTokenService);
        if (withFcm) {
            ReflectionTestUtils.setField(c, "fcmPushService", fcmPushService);
        }
        return c;
    }

    private PushDeviceToken token(long id, String pushTok, String platform) {
        PushDeviceToken t = new PushDeviceToken();
        t.setId(id);
        t.setUserId(1L);
        t.setTenantId(10L);
        t.setPushToken(pushTok);
        t.setPlatform(platform);
        t.setTokenType("fcm");
        return t;
    }

    @Test
    void getChannelCode_andAvailable() {
        PushNotificationChannel c = channel(false);
        assertEquals("push", c.getChannelCode());
        assertTrue(c.isAvailable());
    }

    @Test
    void send_noTokens_skipsUserAndReportsOk() {
        when(deviceTokenService.getValidTokens(10L, 1L)).thenReturn(List.of());
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L).recipientUserIds(List.of(1L))
                .subject("t").body("b").build();

        NotificationResult result = channel(true).send(msg);

        assertTrue(result.isSuccess());
        verifyNoInteractions(fcmPushService);
    }

    @Test
    void send_stubMode_logsAndCountsAsSent() {
        when(deviceTokenService.getValidTokens(10L, 1L))
                .thenReturn(List.of(token(1L, "tok-a", "ios")));
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L).recipientUserIds(List.of(1L))
                .subject("t").body("b body").extras(Map.of("deep_link", "x://d", "badge", 7))
                .build();

        NotificationResult result = channel(false).send(msg);

        assertTrue(result.isSuccess());
    }

    @Test
    void send_fcmSuccess_resultOk() throws Exception {
        when(deviceTokenService.getValidTokens(10L, 1L))
                .thenReturn(List.of(token(1L, "tok-1", "android")));
        when(fcmPushService.sendToDevice(any(), anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(true);
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L).recipientUserIds(List.of(1L))
                .subject("t").body("b").extras(Map.of("badge", 2)).build();

        NotificationResult result = channel(true).send(msg);
        assertTrue(result.isSuccess());
    }

    @Test
    void send_fcmReturnsFalse_invalidatesToken_andReportsFail() throws Exception {
        when(deviceTokenService.getValidTokens(10L, 1L))
                .thenReturn(List.of(token(99L, "bad", "android")));
        when(fcmPushService.sendToDevice(any(), anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(false);
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L).recipientUserIds(List.of(1L))
                .body("b").build();

        NotificationResult result = channel(true).send(msg);

        assertFalse(result.isSuccess());
        assertThat(result.getErrorMessage()).contains("failures");
        verify(deviceTokenService).invalidateToken(99L);
    }

    @Test
    void send_fcmThrows_countsAsFailure_andContinues() throws Exception {
        when(deviceTokenService.getValidTokens(10L, 1L))
                .thenReturn(List.of(token(1L, "tok-1", "android"), token(2L, "tok-2", "android")));
        when(fcmPushService.sendToDevice(any(), anyString(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(mock(FirebaseMessagingException.class))
                .thenReturn(true);
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L).recipientUserIds(List.of(1L))
                .body("b").build();

        NotificationResult result = channel(true).send(msg);

        // 1 failed, 1 sent → still reports failure
        assertFalse(result.isSuccess());
    }
}
