package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.email.controller.EmailSequenceController;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.service.EmailSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSequenceControllerPidContractTest {

    @Mock private EmailSequenceService emailSequenceService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void enrollReadsRecordPidFromContactPayload() {
        MetaContext.setCurrentTenantId(7L);
        EmailSequenceEnrollment enrollment = new EmailSequenceEnrollment();
        enrollment.setRecordPid("01KSEQPID");
        when(emailSequenceService.enroll(7L, 55L, 9L, "alice@example.com", "crm_contact", "01KSEQPID"))
                .thenReturn(enrollment);

        EmailSequenceController controller = new EmailSequenceController(emailSequenceService);
        ApiResponse<List<EmailSequenceEnrollment>> response = controller.enroll(
                55L,
                Map.of(
                        "accountId", 9L,
                        "contacts", List.of(Map.of(
                                "email", "alice@example.com",
                                "modelCode", "crm_contact",
                                "recordPid", "01KSEQPID"))));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getRecordPid()).isEqualTo("01KSEQPID");
        verify(emailSequenceService).enroll(7L, 55L, 9L, "alice@example.com", "crm_contact", "01KSEQPID");
    }
}
