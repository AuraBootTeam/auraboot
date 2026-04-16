package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link BpmPublishNotificationHandler}. External notification
 * channels (email / push / SMS / webhook) are always mocked per project
 * conventions; the handler's job is to translate its payload into a
 * {@link NotificationSendRequest}, which this test verifies against the
 * NotificationService collaborator.
 */
@ExtendWith(MockitoExtension.class)
class BpmPublishNotificationHandlerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private BpmPublishNotificationHandler handler;

    @Test
    void execute_explicitRecipient_sendsViaTemplate() {
        doNothing().when(notificationService).send(org.mockito.ArgumentMatchers.any());

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmPublishNotificationHandler.ARG_EVENT_CODE, "wd_request_approved");
        payload.put(BpmPublishNotificationHandler.ARG_RECIPIENT_USER_ID, "42");
        payload.put(BpmPublishNotificationHandler.ARG_TEMPLATE_PARAMS, Map.of("reqCode", "REQ-001"));

        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(ctx);
        assertThat(result.get(BpmPublishNotificationHandler.RESULT_NOTIFICATION_ID))
                .isEqualTo("wd_request_approved:42");

        ArgumentCaptor<NotificationSendRequest> captor =
                ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(notificationService).send(captor.capture());
        NotificationSendRequest sent = captor.getValue();
        assertThat(sent.getTemplateCode()).isEqualTo("wd_request_approved");
        assertThat(sent.getRecipientId()).isEqualTo("42");
        assertThat(sent.getVariables()).containsEntry("reqCode", "REQ-001");
        assertThat(sent.getSourceType()).isEqualTo("bpm");
    }

    @Test
    void execute_recipientFromApplicant_resolvesFromPayload() {
        doNothing().when(notificationService).send(org.mockito.ArgumentMatchers.any());

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmPublishNotificationHandler.ARG_EVENT_CODE, "wd_request_rejected");
        payload.put(BpmPublishNotificationHandler.ARG_RECIPIENT_FROM,
                BpmPublishNotificationHandler.RECIPIENT_FROM_APPLICANT);
        payload.put("initiatorUserId", "99");

        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        handler.execute(ctx);

        ArgumentCaptor<NotificationSendRequest> captor =
                ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo("99");
    }

    @Test
    void execute_recipientFromAssignee_resolvesFromPayload() {
        doNothing().when(notificationService).send(org.mockito.ArgumentMatchers.any());

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmPublishNotificationHandler.ARG_EVENT_CODE, "task_reminder");
        payload.put(BpmPublishNotificationHandler.ARG_RECIPIENT_FROM,
                BpmPublishNotificationHandler.RECIPIENT_FROM_ASSIGNEE);
        payload.put("assigneeUserId", "77");

        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        handler.execute(ctx);

        ArgumentCaptor<NotificationSendRequest> captor =
                ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo("77");
    }

    @Test
    void execute_missingEventCode_throws() {
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(Map.of(BpmPublishNotificationHandler.ARG_RECIPIENT_USER_ID, "1"))
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmPublishNotificationHandler.ERR_EVENT_CODE_REQUIRED);
    }

    @Test
    void execute_unresolvedRecipient_throws() {
        Map<String, Object> payload = Map.of(
                BpmPublishNotificationHandler.ARG_EVENT_CODE, "x",
                BpmPublishNotificationHandler.ARG_RECIPIENT_FROM,
                BpmPublishNotificationHandler.RECIPIENT_FROM_APPLICANT
        );
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(payload)
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmPublishNotificationHandler.ERR_RECIPIENT_UNRESOLVED);
    }

    @Test
    void execute_invalidRecipientFrom_throws() {
        Map<String, Object> payload = Map.of(
                BpmPublishNotificationHandler.ARG_EVENT_CODE, "x",
                BpmPublishNotificationHandler.ARG_RECIPIENT_FROM, "random-role"
        );
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmPublishNotificationHandler.COMMAND_CODE)
                .payload(payload)
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmPublishNotificationHandler.ERR_RECIPIENT_FROM_INVALID);
    }
}
