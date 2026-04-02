package com.auraboot.framework.notification.channel;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.dto.NotificationRecipient;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationServiceImpl channel routing via the SPI registry.
 *
 * @since 5.3.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationServiceImpl — Channel Routing")
class NotificationServiceChannelRoutingTest {

    @Mock
    private NotificationTemplateService templateService;

    @Mock
    private NotificationSendLogMapper sendLogMapper;

    @Mock
    private NotificationChannel inAppChannel;

    @Mock
    private NotificationChannel emailChannel;

    @Mock
    private EmailSender emailSender;

    private NotificationServiceImpl notificationService;

    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        // Set up channel mocks
        when(inAppChannel.getChannelCode()).thenReturn("in_app");
        when(inAppChannel.isAvailable()).thenReturn(true);
        when(inAppChannel.send(any())).thenReturn(NotificationResult.ok());

        when(emailChannel.getChannelCode()).thenReturn("email");
        when(emailChannel.isAvailable()).thenReturn(true);
        when(emailChannel.send(any())).thenReturn(NotificationResult.ok());

        notificationService = new NotificationServiceImpl(
                templateService, sendLogMapper, emailSender, List.of(inAppChannel, emailChannel));

        // Mock MetaContext
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        metaContextMock.close();
    }

    @Test
    @DisplayName("send() routes IN_APP template to InAppChannel")
    void sendRoutesToInAppChannel() {
        NotificationTemplate template = createTemplate("test_tpl", "in_app");
        when(templateService.getByCode("test_tpl")).thenReturn(template);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("test_tpl")
                .recipientId("42")
                .variables(Map.of("key", "value"))
                .sourceType("order")
                .sourceId("ORD-001")
                .build();

        notificationService.send(request);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(inAppChannel).send(captor.capture());
        verify(emailChannel, never()).send(any());

        NotificationMessage msg = captor.getValue();
        assertEquals(1L, msg.getTenantId());
        assertEquals(List.of(42L), msg.getRecipientUserIds());
        assertEquals("Body with value", msg.getBody());
    }

    @Test
    @DisplayName("send() routes EMAIL template to EmailSender")
    void sendRoutesToEmailChannel() {
        NotificationTemplate template = createTemplate("email_tpl", "email");
        when(templateService.getByCode("email_tpl")).thenReturn(template);

        // EMAIL recipientId can be an email address (non-numeric)
        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("email_tpl")
                .recipientId("user@test.com")
                .build();

        notificationService.send(request);

        verify(emailSender).send("user@test.com", "Subject ${key}", "Body with ${key}");
        verify(inAppChannel, never()).send(any());
        verify(emailChannel, never()).send(any());
    }

    @Test
    @DisplayName("send() logs to sendLogMapper after channel send")
    void sendLogsResult() {
        NotificationTemplate template = createTemplate("log_tpl", "in_app");
        when(templateService.getByCode("log_tpl")).thenReturn(template);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("log_tpl")
                .recipientId("1")
                .build();

        notificationService.send(request);

        verify(sendLogMapper).insert(any(NotificationSendLog.class));
    }

    @Test
    @DisplayName("send() skips when template not found")
    void sendSkipsWhenNoTemplate() {
        when(templateService.getByCode("missing")).thenReturn(null);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("missing")
                .recipientId("1")
                .build();

        notificationService.send(request);

        verify(inAppChannel, never()).send(any());
        verify(emailChannel, never()).send(any());
    }

    @Test
    @DisplayName("send() skips when channel not registered")
    void sendSkipsWhenChannelNotRegistered() {
        NotificationTemplate template = createTemplate("sms_tpl", "sms");
        when(templateService.getByCode("sms_tpl")).thenReturn(template);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("sms_tpl")
                .recipientId("1")
                .build();

        notificationService.send(request);

        verify(inAppChannel, never()).send(any());
        verify(emailChannel, never()).send(any());
    }

    @Test
    @DisplayName("sendInApp() delegates to IN_APP channel directly")
    void sendInAppDelegates() {
        notificationService.sendInApp(42L, "Title", "Content", "alert", "src", "id1");

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(inAppChannel).send(captor.capture());

        NotificationMessage msg = captor.getValue();
        assertEquals(List.of(42L), msg.getRecipientUserIds());
        assertEquals("Title", msg.getSubject());
        assertEquals("Content", msg.getBody());
        assertEquals("alert", msg.getCategory());
    }

    @Test
    @DisplayName("sendBatch() sends to each recipient via correct channel")
    void sendBatchRoutesCorrectly() {
        NotificationTemplate template = createTemplate("batch_tpl", "in_app");
        when(templateService.getByCode("batch_tpl")).thenReturn(template);

        List<NotificationRecipient> recipients = List.of(
                NotificationRecipient.builder().userId(10L).build(),
                NotificationRecipient.builder().userId(20L).build()
        );

        notificationService.sendBatch("batch_tpl", recipients, Map.of());

        verify(inAppChannel, times(2)).send(any());
    }

    @Test
    @DisplayName("send() handles channel that returns failure")
    void sendHandlesChannelFailure() {
        when(inAppChannel.send(any())).thenReturn(NotificationResult.fail("DB down"));

        NotificationTemplate template = createTemplate("fail_tpl", "in_app");
        when(templateService.getByCode("fail_tpl")).thenReturn(template);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode("fail_tpl")
                .recipientId("1")
                .build();

        // Should not throw
        assertDoesNotThrow(() -> notificationService.send(request));

        // Should still log the send attempt with FAILED status
        ArgumentCaptor<NotificationSendLog> logCaptor = ArgumentCaptor.forClass(NotificationSendLog.class);
        verify(sendLogMapper).insert(logCaptor.capture());
        assertEquals("failed", logCaptor.getValue().getStatus());
        assertEquals("DB down", logCaptor.getValue().getErrorMessage());
    }

    // ==================== Helpers ====================

    private NotificationTemplate createTemplate(String code, String channel) {
        NotificationTemplate template = new NotificationTemplate();
        template.setCode(code);
        template.setChannel(channel);
        template.setSubjectTemplate("Subject ${key}");
        template.setBodyTemplate("Body with ${key}");
        template.setEnabled(true);
        return template;
    }
}
