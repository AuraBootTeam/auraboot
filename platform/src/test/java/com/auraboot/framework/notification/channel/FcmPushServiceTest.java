package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FcmPushService}. Mocks {@link FirebaseMessaging}.
 */
@ExtendWith(MockitoExtension.class)
class FcmPushServiceTest {

    @Mock
    FirebaseMessaging firebaseMessaging;

    private PushDeviceToken token() {
        PushDeviceToken t = new PushDeviceToken();
        t.setId(1L);
        t.setUserId(2L);
        t.setTenantId(3L);
        t.setPushToken("device-token");
        t.setPlatform("ios");
        t.setTokenType("fcm");
        return t;
    }

    @Test
    void sendToDevice_returnsTrueOnFcmSuccess() throws Exception {
        FcmPushService svc = new FcmPushService(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenReturn("msgid-123");

        boolean ok = svc.sendToDevice(token(), "title", "body", "auraboot://x", "chat", 5);
        assertThat(ok).isTrue();
        verify(firebaseMessaging).send(any(Message.class));
    }

    @Test
    void sendToDevice_handlesNullDeepLinkAndCategory() throws Exception {
        FcmPushService svc = new FcmPushService(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenReturn("msgid-2");
        boolean ok = svc.sendToDevice(token(), "t", "b", null, null, 0);
        assertThat(ok).isTrue();
    }

    @Test
    void sendToDevice_returnsFalseWhenTokenUnregistered() throws Exception {
        FcmPushService svc = new FcmPushService(firebaseMessaging);
        FirebaseMessagingException ex = mock(FirebaseMessagingException.class);
        when(ex.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(ex);

        assertThat(svc.sendToDevice(token(), "t", "b", "dl", "c", 1)).isFalse();
    }

    @Test
    void sendToDevice_returnsFalseWhenInvalidArgument() throws Exception {
        FcmPushService svc = new FcmPushService(firebaseMessaging);
        FirebaseMessagingException ex = mock(FirebaseMessagingException.class);
        when(ex.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INVALID_ARGUMENT);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(ex);

        assertThat(svc.sendToDevice(token(), "t", "b", "dl", "c", 1)).isFalse();
    }

    @Test
    void sendToDevice_rethrowsForOtherErrors() throws Exception {
        FcmPushService svc = new FcmPushService(firebaseMessaging);
        FirebaseMessagingException ex = mock(FirebaseMessagingException.class);
        when(ex.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INTERNAL);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(ex);

        assertThatThrownBy(() ->
                svc.sendToDevice(token(), "t", "b", "dl", "c", 1)
        ).isInstanceOf(FirebaseMessagingException.class);
    }
}
