package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailSequenceEnrollmentMapper;
import com.auraboot.framework.email.mapper.EmailSequenceStepMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.model.EmailSequenceStep;
import com.auraboot.framework.email.service.EmailSendService;
import com.auraboot.framework.email.service.EmailSequenceExecutor;
import com.auraboot.framework.email.service.EmailSequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailSequenceExecutor#processDueEnrollments()} branches.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSequenceExecutor processDueEnrollments")
class EmailSequenceExecutorProcessTest {

    @Mock private EmailSequenceEnrollmentMapper enrollmentMapper;
    @Mock private EmailSequenceStepMapper stepMapper;
    @Mock private EmailAccountMapper accountMapper;
    @Mock private EmailMessageMapper messageMapper;
    @Mock private EmailSendService emailSendService;
    @Mock private EmailSequenceService sequenceService;

    private EmailSequenceExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EmailSequenceExecutor(enrollmentMapper, stepMapper,
                accountMapper, messageMapper, emailSendService, sequenceService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private static EmailSequenceEnrollment enrollment(long id, long accId, int currentStep) {
        EmailSequenceEnrollment e = new EmailSequenceEnrollment();
        e.setId(id);
        e.setTenantId(42L);
        e.setAccountId(accId);
        e.setSequenceId(99L);
        e.setContactEmail("c@x.com");
        e.setCurrentStep(currentStep);
        e.setEnrolledAt(Instant.now().minusSeconds(3600));
        return e;
    }

    @Test
    @DisplayName("Due enrollment binds tenant context during processing and clears it afterwards")
    void bindsTenantContextForEnrollment() {
        EmailSequenceEnrollment e = enrollment(10L, 20L, 5);
        e.setTenantId(77L);
        EmailAccount account = new EmailAccount();
        account.setId(20L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(20L)).thenAnswer(invocation -> {
            assertEquals(77L, MetaContext.getCurrentTenantId());
            return account;
        });
        when(messageMapper.countInboundFrom(anyLong(), anyString(), any())).thenReturn(0);
        when(stepMapper.findBySequenceId(99L)).thenReturn(List.of(step(1, 0, "s", "b")));

        executor.processDueEnrollments();

        assertFalse(MetaContext.exists());
        verify(sequenceService).updateEnrollmentStatus(10L, EmailConstants.ENROLLMENT_COMPLETED);
    }

    private static EmailSequenceStep step(int order, int delayDays, String subj, String body) {
        EmailSequenceStep s = new EmailSequenceStep();
        s.setStepOrder(order);
        s.setDelayDays(delayDays);
        s.setSubjectTemplate(subj);
        s.setBodyTemplate(body);
        return s;
    }

    @Test
    @DisplayName("Empty due list → no work")
    void noDue() {
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of());
        executor.processDueEnrollments();
        verify(sequenceService, never()).updateEnrollmentStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("Account missing → mark enrollment FAILED")
    void accountMissing_marksFailed() throws Exception {
        EmailSequenceEnrollment e = enrollment(1L, 5L, 0);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(5L)).thenReturn(null);

        executor.processDueEnrollments();

        verify(sequenceService).updateEnrollmentStatus(1L, EmailConstants.ENROLLMENT_FAILED);
        verify(emailSendService, never()).send(any(), anyList(), anyList(), anyList(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Reply detected → mark enrollment REPLIED")
    void replyDetected_marksReplied() throws Exception {
        EmailSequenceEnrollment e = enrollment(2L, 6L, 0);
        EmailAccount account = new EmailAccount();
        account.setId(6L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(6L)).thenReturn(account);
        when(messageMapper.countInboundFrom(eq(6L), eq("c@x.com"), any())).thenReturn(2);

        executor.processDueEnrollments();

        verify(sequenceService).updateEnrollmentStatus(2L, "replied");
        verify(emailSendService, never()).send(any(), anyList(), anyList(), anyList(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("No more steps → mark enrollment COMPLETED")
    void noMoreSteps_completed() {
        EmailSequenceEnrollment e = enrollment(3L, 7L, 5); // expects step 6 which doesn't exist
        EmailAccount account = new EmailAccount();
        account.setId(7L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(messageMapper.countInboundFrom(anyLong(), anyString(), any())).thenReturn(0);
        when(stepMapper.findBySequenceId(99L)).thenReturn(List.of(step(1, 0, "s", "b")));

        executor.processDueEnrollments();

        verify(sequenceService).updateEnrollmentStatus(3L, EmailConstants.ENROLLMENT_COMPLETED);
    }

    @Test
    @DisplayName("Send next step → emailSendService.send invoked + advances enrollment")
    void sendsNextStep_andAdvances() throws Exception {
        EmailSequenceEnrollment e = enrollment(4L, 8L, 0);
        e.setRecordId("REC");
        e.setModelCode("crm_contact");
        EmailAccount account = new EmailAccount();
        account.setId(8L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(8L)).thenReturn(account);
        when(messageMapper.countInboundFrom(anyLong(), anyString(), any())).thenReturn(0);
        when(stepMapper.findBySequenceId(99L)).thenReturn(List.of(
                step(1, 0, "Hi {{email}}", "Hello {{recordId}}"),
                step(2, 5, "Followup", "FU body")
        ));

        executor.processDueEnrollments();

        verify(emailSendService, times(1)).send(eq(account), anyList(), anyList(), anyList(),
                eq("Hi c@x.com"), eq("Hello REC"), isNull(), eq(false));
        verify(enrollmentMapper).updateById(any(EmailSequenceEnrollment.class));
    }

    @Test
    @DisplayName("Send last step → marks enrollment COMPLETED in update")
    void sendsLastStep_marksCompleted() throws Exception {
        EmailSequenceEnrollment e = enrollment(5L, 9L, 0);
        EmailAccount account = new EmailAccount();
        account.setId(9L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(9L)).thenReturn(account);
        when(messageMapper.countInboundFrom(anyLong(), anyString(), any())).thenReturn(0);
        when(stepMapper.findBySequenceId(99L)).thenReturn(List.of(step(1, 0, "Only", "Body")));

        executor.processDueEnrollments();

        verify(emailSendService).send(eq(account), anyList(), anyList(), anyList(),
                eq("Only"), eq("Body"), isNull(), eq(false));
        verify(enrollmentMapper).updateById(any(EmailSequenceEnrollment.class));
    }

    @Test
    @DisplayName("Send throws → catch path marks enrollment FAILED")
    void sendThrows_marksFailed() throws Exception {
        EmailSequenceEnrollment e = enrollment(6L, 10L, 0);
        EmailAccount account = new EmailAccount();
        account.setId(10L);
        when(enrollmentMapper.findDueEnrollments()).thenReturn(List.of(e));
        when(accountMapper.selectById(10L)).thenReturn(account);
        when(messageMapper.countInboundFrom(anyLong(), anyString(), any())).thenReturn(0);
        when(stepMapper.findBySequenceId(99L)).thenReturn(List.of(step(1, 0, "S", "B")));
        when(emailSendService.send(any(), anyList(), anyList(), anyList(),
                anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new java.io.IOException("smtp down"));

        executor.processDueEnrollments();

        verify(sequenceService).updateEnrollmentStatus(6L, EmailConstants.ENROLLMENT_FAILED);
    }
}
