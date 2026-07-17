package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.RecordCommentService;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionHandlerProviderDependenciesTest {

    @Mock private NotificationService notificationService;
    @Mock private SmsSenderRouter smsSenderRouter;
    @Mock private ImConversationService conversationService;
    @Mock private ImMessageService messageService;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private InboxService inboxService;
    @Mock private CcService ccService;
    @Mock private WebhookDispatcher webhookDispatcher;
    @Mock private ProcessEngineService processEngineService;
    @Mock private RecordCommentService recordCommentService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private DrtActionAuditMapper auditMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void actionHandlersAdvertiseProviderDependencyMatrix() {
        when(smsSenderRouter.realSenderAvailability())
                .thenReturn(new SmsSenderRouter.SmsProviderAvailability(
                        false,
                        List.of(),
                        "当前环境未配置真实短信 provider"));

        assertProviderTypes(new NotifyActionHandler(notificationService), "NOTIFY", "NOTIFICATION");
        assertProviderTypes(new SendSmsActionHandler(smsSenderRouter), "SEND_SMS", "SMS");
        assertProviderTypes(new SendImActionHandler(conversationService, messageService, userRoleMapper, objectMapper),
                "SEND_IM", "IM");
        assertProviderTypes(new CreateTaskActionHandler(inboxService, userRoleMapper, objectMapper),
                "CREATE_TASK", "INBOX");
        assertProviderTypes(new CcTaskActionHandler(inboxService, userRoleMapper, ccService, objectMapper),
                "CC_TASK", "INBOX", "BPM");
        assertProviderTypes(new WebhookActionHandler(webhookDispatcher), "WEBHOOK", "WEBHOOK");
        assertProviderTypes(new StartProcessActionHandler(processEngineService), "START_PROCESS", "BPM");
        assertProviderTypes(new AddCommentActionHandler(recordCommentService), "ADD_COMMENT", "COMMENT");
        assertProviderTypes(new UpdateRecordActionHandler(dynamicDataService), "UPDATE_RECORD", "LOWCODE_MODEL");
        assertProviderTypes(new UpdateRecordActionHandler(dynamicDataService), "PATCH_RECORD", "LOWCODE_MODEL");
        assertProviderTypes(new AuditActionHandler(auditMapper, objectMapper), "WRITE_AUDIT", "AUDIT");
    }

    private static void assertProviderTypes(ActionHandler handler, String actionType, String... providerTypes) {
        assertThat(handler.supports(actionType)).isTrue();
        assertThat(handler.runtimeProviderDependencies())
                .extracting(ActionProviderDependency::providerType)
                .containsExactly(providerTypes);
        assertThat(handler.runtimeProviderDependencies())
                .allSatisfy(dependency -> {
                    assertThat(dependency.label()).isNotBlank();
                    assertThat(dependency.availabilityStatus()).isIn("AVAILABLE", "UNAVAILABLE");
                    assertThat(dependency.required()).isTrue();
                });
    }
}
