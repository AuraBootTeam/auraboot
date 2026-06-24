package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.email.controller.EmailMessageController;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.auraboot.framework.email.service.EmailRecordLinkService;
import com.auraboot.framework.email.service.EmailSendService;
import com.auraboot.framework.email.service.EmailTrackingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailMessageControllerPidContractTest {

    @Mock private EmailMessageMapper emailMessageMapper;
    @Mock private EmailRecordLinkMapper emailRecordLinkMapper;
    @Mock private EmailRecordLinkService emailRecordLinkService;
    @Mock private EmailSendService emailSendService;
    @Mock private EmailTrackingService emailTrackingService;
    @Mock private EmailAccountMapper emailAccountMapper;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void linkRecordReadsRecordPidFromRequestBody() {
        MetaContext.setCurrentTenantId(7L);
        EmailMessage message = new EmailMessage();
        message.setId(100L);
        message.setGmailThreadId("thread-db");
        when(emailMessageMapper.selectById(100L)).thenReturn(message);

        EmailRecordLink link = new EmailRecordLink();
        link.setRecordPid("01KEMAILPID");
        when(emailRecordLinkService.manualLink(7L, 100L, "thread-body", "crm_contact", "01KEMAILPID"))
                .thenReturn(link);

        EmailMessageController controller = newController();
        ApiResponse<EmailRecordLink> response = controller.linkRecord(
                100L,
                Map.of("modelCode", "crm_contact", "recordPid", "01KEMAILPID", "threadId", "thread-body"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getRecordPid()).isEqualTo("01KEMAILPID");
        verify(emailRecordLinkService).manualLink(7L, 100L, "thread-body", "crm_contact", "01KEMAILPID");
    }

    @Test
    void byRecordRequestParamIsRecordPid() throws Exception {
        Method method = EmailMessageController.class.getMethod(
                "getByRecord", String.class, String.class, int.class, int.class);
        Parameter recordParam = method.getParameters()[1];
        assertThat(recordParam.getName()).isEqualTo("recordPid");
    }

    private EmailMessageController newController() {
        return new EmailMessageController(
                emailMessageMapper,
                emailRecordLinkMapper,
                emailRecordLinkService,
                emailSendService,
                emailTrackingService,
                emailAccountMapper);
    }
}
