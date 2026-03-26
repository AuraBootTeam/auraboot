package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationChannel SPI implementations.
 *
 * @since 5.3.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationChannel SPI")
class NotificationChannelTest {

    // ==================== InAppChannel ====================

    @Nested
    @DisplayName("InAppChannel")
    class InAppChannelTests {

        @Mock
        private NotificationMapper notificationMapper;

        @Mock
        private NotificationSseService sseService;

        @Mock
        private NotificationQueryService queryService;

        @InjectMocks
        private InAppChannel inAppChannel;

        @Test
        @DisplayName("channelCode should be IN_APP")
        void channelCode() {
            assertEquals("in_app", inAppChannel.getChannelCode());
        }

        @Test
        @DisplayName("isAvailable should always return true")
        void isAvailable() {
            assertTrue(inAppChannel.isAvailable());
        }

        @Test
        @DisplayName("send should persist notification and push SSE")
        void sendPersistsAndPushes() {
            when(queryService.getUnreadCount(42L)).thenReturn(5);

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(42L))
                    .subject("Test Title")
                    .body("Test Body")
                    .category("business")
                    .sourceType("order")
                    .sourceId("ORD-001")
                    .build();

            NotificationResult result = inAppChannel.send(message);

            assertTrue(result.isSuccess());
            assertNull(result.getErrorMessage());

            // Verify notification was inserted
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationMapper).insert(captor.capture());
            Notification saved = captor.getValue();
            assertEquals(1L, saved.getTenantId());
            assertEquals(42L, saved.getUserId());
            assertEquals("Test Title", saved.getTitle());
            assertEquals("Test Body", saved.getContent());
            assertEquals("business", saved.getCategory());
            assertEquals("normal", saved.getPriority());
            assertEquals("order", saved.getSourceType());
            assertEquals("ORD-001", saved.getSourceId());
            assertFalse(saved.getIsRead());

            // Verify SSE push
            verify(sseService).pushUnreadCount(42L, 5);
        }

        @Test
        @DisplayName("send should handle multiple recipients")
        void sendMultipleRecipients() {
            when(queryService.getUnreadCount(anyLong())).thenReturn(1);

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(10L, 20L, 30L))
                    .subject("Broadcast")
                    .body("To all")
                    .build();

            NotificationResult result = inAppChannel.send(message);

            assertTrue(result.isSuccess());
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationMapper, times(3)).insert(captor.capture());
            verify(sseService, times(3)).pushUnreadCount(anyLong(), eq(1));
        }

        @Test
        @DisplayName("send should default category to SYSTEM when null")
        void sendDefaultsCategory() {
            when(queryService.getUnreadCount(anyLong())).thenReturn(0);

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Title")
                    .body("Body")
                    .category(null)
                    .build();

            inAppChannel.send(message);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationMapper).insert(captor.capture());
            assertEquals("system", captor.getValue().getCategory());
        }

        @Test
        @DisplayName("send should return failure on mapper exception")
        void sendFailsOnException() {
            doThrow(new RuntimeException("DB error"))
                    .when(notificationMapper).insert(any(Notification.class));

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Title")
                    .body("Body")
                    .build();

            NotificationResult result = inAppChannel.send(message);

            assertFalse(result.isSuccess());
            assertEquals("DB error", result.getErrorMessage());
        }

        @Test
        @DisplayName("SSE push failure should not cause send to fail")
        void ssePushFailureDoesNotBreakSend() {
            when(queryService.getUnreadCount(anyLong()))
                    .thenThrow(new RuntimeException("SSE broken"));

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Title")
                    .body("Body")
                    .build();

            NotificationResult result = inAppChannel.send(message);

            assertTrue(result.isSuccess());
            verify(notificationMapper).insert(any(Notification.class));
        }
    }

    // ==================== EmailChannel ====================

    @Nested
    @DisplayName("EmailChannel")
    class EmailChannelTests {

        @Mock
        private EmailSender emailSender;

        @InjectMocks
        private EmailChannel emailChannel;

        @Test
        @DisplayName("channelCode should be EMAIL")
        void channelCode() {
            assertEquals("email", emailChannel.getChannelCode());
        }

        @Test
        @DisplayName("isAvailable should always return true")
        void isAvailable() {
            assertTrue(emailChannel.isAvailable());
        }

        @Test
        @DisplayName("send should delegate to EmailSender when email is in extras")
        void sendWithEmail() {
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Welcome")
                    .body("<h1>Hello</h1>")
                    .extras(Map.of("email", "user@example.com"))
                    .build();

            NotificationResult result = emailChannel.send(message);

            assertTrue(result.isSuccess());
            verify(emailSender).send("user@example.com", "Welcome", "<h1>Hello</h1>");
        }

        @Test
        @DisplayName("send should fail when no email in extras")
        void sendWithoutEmail() {
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Welcome")
                    .body("Hello")
                    .build();

            NotificationResult result = emailChannel.send(message);

            assertFalse(result.isSuccess());
            assertEquals("No email address available", result.getErrorMessage());
            verifyNoInteractions(emailSender);
        }

        @Test
        @DisplayName("send should fail when extras email is blank")
        void sendWithBlankEmail() {
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Welcome")
                    .body("Hello")
                    .extras(Map.of("email", "  "))
                    .build();

            NotificationResult result = emailChannel.send(message);

            assertFalse(result.isSuccess());
            verifyNoInteractions(emailSender);
        }

        @Test
        @DisplayName("send should return failure on EmailSender exception")
        void sendFailsOnException() {
            doThrow(new RuntimeException("SMTP error"))
                    .when(emailSender).send(anyString(), anyString(), anyString());

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Subject")
                    .body("Body")
                    .extras(Map.of("email", "fail@example.com"))
                    .build();

            NotificationResult result = emailChannel.send(message);

            assertFalse(result.isSuccess());
            assertEquals("SMTP error", result.getErrorMessage());
        }
    }

    // ==================== WeChatWorkChannel ====================

    @Nested
    @DisplayName("WeChatWorkChannel")
    class WeChatWorkChannelTests {

        @Test
        @DisplayName("channelCode should be WECHAT_WORK")
        void channelCode() {
            WeChatWorkChannel channel = new WeChatWorkChannel(null, null);
            assertEquals("wechat_work", channel.getChannelCode());
        }

        @Test
        @DisplayName("isAvailable should return false when not configured")
        void isNotAvailableByDefault() {
            WeChatWorkChannel channel = new WeChatWorkChannel(null, null);
            assertFalse(channel.isAvailable());
        }

        @Test
        @DisplayName("send should fail when not configured")
        void sendFailsWhenNotConfigured() {
            WeChatWorkChannel channel = new WeChatWorkChannel(null, null);
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Test")
                    .body("Body")
                    .build();

            NotificationResult result = channel.send(message);

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("not configured"));
        }
    }

    // ==================== DingTalkChannel ====================

    @Nested
    @DisplayName("DingTalkChannel")
    class DingTalkChannelTests {

        @Test
        @DisplayName("channelCode should be DINGTALK")
        void channelCode() {
            DingTalkChannel channel = new DingTalkChannel(null, null);
            assertEquals("dingtalk", channel.getChannelCode());
        }

        @Test
        @DisplayName("isAvailable should return false when not configured")
        void isNotAvailableByDefault() {
            DingTalkChannel channel = new DingTalkChannel(null, null);
            assertFalse(channel.isAvailable());
        }

        @Test
        @DisplayName("send should fail when not configured")
        void sendFailsWhenNotConfigured() {
            DingTalkChannel channel = new DingTalkChannel(null, null);
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Test")
                    .body("Body")
                    .build();

            NotificationResult result = channel.send(message);

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("not configured"));
        }
    }

    // ==================== SlackChannel ====================

    @Nested
    @DisplayName("SlackChannel")
    class SlackChannelTests {

        @Test
        @DisplayName("channelCode should be SLACK")
        void channelCode() {
            SlackChannel channel = new SlackChannel(null, null);
            assertEquals("slack", channel.getChannelCode());
        }

        @Test
        @DisplayName("isAvailable should return false when not configured")
        void isNotAvailableByDefault() {
            SlackChannel channel = new SlackChannel(null, null);
            assertFalse(channel.isAvailable());
        }

        @Test
        @DisplayName("send should fail when not configured")
        void sendFailsWhenNotConfigured() {
            SlackChannel channel = new SlackChannel(null, null);
            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(1L)
                    .recipientUserIds(List.of(1L))
                    .subject("Test")
                    .body("Body")
                    .build();

            NotificationResult result = channel.send(message);

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("not configured"));
        }
    }

    // ==================== NotificationResult ====================

    @Nested
    @DisplayName("NotificationResult")
    class NotificationResultTests {

        @Test
        @DisplayName("ok() should create successful result")
        void okResult() {
            NotificationResult result = NotificationResult.ok();
            assertTrue(result.isSuccess());
            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("fail() should create failed result with message")
        void failResult() {
            NotificationResult result = NotificationResult.fail("Something broke");
            assertFalse(result.isSuccess());
            assertEquals("Something broke", result.getErrorMessage());
        }
    }
}
