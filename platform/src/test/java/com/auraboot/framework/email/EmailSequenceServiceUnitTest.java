package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailSequenceEnrollmentMapper;
import com.auraboot.framework.email.mapper.EmailSequenceMapper;
import com.auraboot.framework.email.mapper.EmailSequenceStepMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailSequence;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.model.EmailSequenceStep;
import com.auraboot.framework.email.service.EmailSequenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link EmailSequenceService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSequenceService Unit Tests")
class EmailSequenceServiceUnitTest {

    @Mock private EmailSequenceMapper sequenceMapper;
    @Mock private EmailSequenceStepMapper stepMapper;
    @Mock private EmailSequenceEnrollmentMapper enrollmentMapper;

    private EmailSequenceService service;

    @BeforeEach
    void setUp() {
        service = new EmailSequenceService(sequenceMapper, stepMapper, enrollmentMapper);
    }

    @Test
    @DisplayName("listSequences delegates to mapper.selectList")
    void listSequences_delegates() {
        EmailSequence s = new EmailSequence();
        when(sequenceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
        assertThat(service.listSequences(7L)).hasSize(1);
    }

    @Test
    @DisplayName("getSequence delegates to mapper.selectById")
    void getSequence_delegates() {
        EmailSequence s = new EmailSequence();
        when(sequenceMapper.selectById(11L)).thenReturn(s);
        assertThat(service.getSequence(11L)).isSameAs(s);
    }

    @Test
    @DisplayName("createSequence sets default fields and inserts")
    void createSequence_setsDefaults() {
        EmailSequence created = service.createSequence(7L, 99L, "Onboard", "desc");
        assertThat(created.getTenantId()).isEqualTo(7L);
        assertThat(created.getCreatedBy()).isEqualTo(99L);
        assertThat(created.getName()).isEqualTo("Onboard");
        assertThat(created.getDescription()).isEqualTo("desc");
        assertThat(created.getStatus()).isEqualTo(EmailConstants.SEQ_STATUS_DRAFT);
        assertThat(created.getDeletedFlag()).isFalse();
        assertThat(created.getCreatedAt()).isNotNull();
        verify(sequenceMapper).insert(created);
    }

    @Test
    @DisplayName("updateSequence applies non-null fields only")
    void updateSequence_partial() {
        service.updateSequence(5L, "NewName", null);
        ArgumentCaptor<EmailSequence> cap = ArgumentCaptor.forClass(EmailSequence.class);
        verify(sequenceMapper).updateById(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("NewName");
        assertThat(cap.getValue().getDescription()).isNull();
        assertThat(cap.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateStatus(archived) pauses all active enrollments")
    void updateStatus_archived_pausesEnrollments() {
        EmailSequenceEnrollment e1 = new EmailSequenceEnrollment();
        e1.setId(101L);
        EmailSequenceEnrollment e2 = new EmailSequenceEnrollment();
        e2.setId(102L);
        when(enrollmentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(e1, e2));

        service.updateStatus(33L, EmailConstants.SEQ_STATUS_ARCHIVED);

        verify(sequenceMapper).updateById(any(EmailSequence.class));
        // 2 enrollment updates (paused) via updateEnrollmentStatus → mapper.updateById
        verify(enrollmentMapper, times(2)).updateById(any(EmailSequenceEnrollment.class));
    }

    @Test
    @DisplayName("updateStatus(active) does not query enrollments")
    void updateStatus_active_noEnrollmentChange() {
        service.updateStatus(34L, EmailConstants.SEQ_STATUS_ACTIVE);
        verify(sequenceMapper).updateById(any(EmailSequence.class));
        verify(enrollmentMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("getSteps delegates to mapper.findBySequenceId")
    void getSteps_delegates() {
        EmailSequenceStep s = new EmailSequenceStep();
        when(stepMapper.findBySequenceId(7L)).thenReturn(List.of(s));
        assertThat(service.getSteps(7L)).hasSize(1);
    }

    @Test
    @DisplayName("addStep populates fields and inserts")
    void addStep_inserts() {
        EmailSequenceStep step = service.addStep(10L, 1, 3, "S", "B");
        assertThat(step.getSequenceId()).isEqualTo(10L);
        assertThat(step.getStepOrder()).isEqualTo(1);
        assertThat(step.getDelayDays()).isEqualTo(3);
        assertThat(step.getSubjectTemplate()).isEqualTo("S");
        assertThat(step.getBodyTemplate()).isEqualTo("B");
        assertThat(step.getCreatedAt()).isNotNull();
        verify(stepMapper).insert(step);
    }

    @Test
    @DisplayName("updateStep applies only non-null parameters")
    void updateStep_partial() {
        service.updateStep(50L, null, 7, null, null);
        ArgumentCaptor<EmailSequenceStep> cap = ArgumentCaptor.forClass(EmailSequenceStep.class);
        verify(stepMapper).updateById(cap.capture());
        assertThat(cap.getValue().getDelayDays()).isEqualTo(7);
        assertThat(cap.getValue().getStepOrder()).isNull();
        assertThat(cap.getValue().getSubjectTemplate()).isNull();
    }

    @Test
    @DisplayName("deleteStep delegates to mapper.deleteById")
    void deleteStep_delegates() {
        service.deleteStep(60L);
        verify(stepMapper).deleteById(60L);
    }

    @Test
    @DisplayName("enroll uses first step's delay for nextSendAt")
    void enroll_usesFirstStepDelay() {
        EmailSequenceStep step = new EmailSequenceStep();
        step.setStepOrder(1);
        step.setDelayDays(2);
        when(stepMapper.findBySequenceId(40L)).thenReturn(List.of(step));

        EmailSequenceEnrollment e = service.enroll(7L, 40L, 5L,
                "x@y.com", "crm_contact", "REC1");

        assertThat(e.getTenantId()).isEqualTo(7L);
        assertThat(e.getSequenceId()).isEqualTo(40L);
        assertThat(e.getCurrentStep()).isEqualTo(0);
        assertThat(e.getStatus()).isEqualTo(EmailConstants.ENROLLMENT_ACTIVE);
        assertThat(e.getEnrolledAt()).isNotNull();
        assertThat(e.getNextSendAt()).isAfter(e.getEnrolledAt().minusSeconds(1));
        verify(enrollmentMapper).insert(e);
    }

    @Test
    @DisplayName("enroll defaults to 0 delay when no steps exist")
    void enroll_noSteps_zeroDelay() {
        when(stepMapper.findBySequenceId(41L)).thenReturn(List.of());
        EmailSequenceEnrollment e = service.enroll(7L, 41L, 5L, "x@y.com", null, null);
        assertThat(e.getNextSendAt()).isEqualTo(e.getEnrolledAt());
    }

    @Test
    @DisplayName("updateEnrollmentStatus(completed) sets completedAt")
    void updateEnrollmentStatus_completed_setsCompletedAt() {
        service.updateEnrollmentStatus(70L, EmailConstants.ENROLLMENT_COMPLETED);
        ArgumentCaptor<EmailSequenceEnrollment> cap = ArgumentCaptor.forClass(EmailSequenceEnrollment.class);
        verify(enrollmentMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(EmailConstants.ENROLLMENT_COMPLETED);
        assertThat(cap.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateEnrollmentStatus(paused) does not set completedAt")
    void updateEnrollmentStatus_paused_noCompletedAt() {
        service.updateEnrollmentStatus(71L, EmailConstants.ENROLLMENT_PAUSED);
        ArgumentCaptor<EmailSequenceEnrollment> cap = ArgumentCaptor.forClass(EmailSequenceEnrollment.class);
        verify(enrollmentMapper).updateById(cap.capture());
        assertThat(cap.getValue().getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("listEnrollments delegates to mapper.selectList")
    void listEnrollments_delegates() {
        EmailSequenceEnrollment e = new EmailSequenceEnrollment();
        when(enrollmentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(e));
        assertThat(service.listEnrollments(80L)).hasSize(1);
    }
}
