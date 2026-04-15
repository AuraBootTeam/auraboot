package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationServiceTaskDelegate}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTaskDelegateTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationServiceTaskDelegate delegate;

    @Test
    void execute_happyPath_resolvesApplicantAndSends() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_EVENT_CODE, "wd_request_approved");
        props.put(BpmServiceTaskConstants.ATTR_RECIPIENT_FROM,
                NotificationServiceTaskDelegate.RECIPIENT_FROM_APPLICANT);
        props.put(BpmServiceTaskConstants.ATTR_TEMPLATE_PARAMS_VARS, "days,businessKey");

        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUserId", "user-1");
        vars.put("days", 3);
        vars.put("businessKey", "rec-1");

        ExecutionContext ctx = mockContext(props, vars, "svc_notify_approved");
        delegate.execute(ctx);

        ArgumentCaptor<NotificationSendRequest> captor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(notificationService).send(captor.capture());
        NotificationSendRequest req = captor.getValue();
        assertThat(req.getTemplateCode()).isEqualTo("wd_request_approved");
        assertThat(req.getRecipientId()).isEqualTo("user-1");
        assertThat(req.getVariables()).containsEntry("days", 3).containsEntry("businessKey", "rec-1");
    }

    @Test
    void execute_assigneeRecipient_resolvesAssigneeUserId() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_EVENT_CODE, "evt");
        props.put(BpmServiceTaskConstants.ATTR_RECIPIENT_FROM,
                NotificationServiceTaskDelegate.RECIPIENT_FROM_ASSIGNEE);
        Map<String, Object> vars = Map.of("assigneeUserId", "boss");

        ExecutionContext ctx = mockContext(props, new HashMap<>(vars), "node");
        delegate.execute(ctx);

        ArgumentCaptor<NotificationSendRequest> captor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo("boss");
    }

    @Test
    void execute_missingEventCode_throws() {
        ExecutionContext ctx = mockContext(new HashMap<>(), new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(NotificationServiceTaskDelegate.ERR_EVENT_CODE_REQUIRED);
    }

    @Test
    void execute_recipientUnresolved_throws() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_EVENT_CODE, "evt");
        props.put(BpmServiceTaskConstants.ATTR_RECIPIENT_FROM,
                NotificationServiceTaskDelegate.RECIPIENT_FROM_APPLICANT);
        ExecutionContext ctx = mockContext(props, new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(NotificationServiceTaskDelegate.ERR_RECIPIENT_UNRESOLVED);
    }

    private ExecutionContext mockContext(Map<String, String> properties,
                                          Map<String, Object> request, String activityId) {
        ExecutionContext ctx = org.mockito.Mockito.mock(ExecutionContext.class);
        IdBasedElement element = org.mockito.Mockito.mock(IdBasedElement.class);
        when(ctx.getRequest()).thenReturn(request);
        when(ctx.getBaseElement()).thenReturn(element);
        when(element.getProperties()).thenReturn(properties);
        org.mockito.Mockito.lenient().when(element.getId()).thenReturn(activityId);
        return ctx;
    }
}
