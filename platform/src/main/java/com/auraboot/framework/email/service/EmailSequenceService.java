package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailSequenceEnrollmentMapper;
import com.auraboot.framework.email.mapper.EmailSequenceMapper;
import com.auraboot.framework.email.mapper.EmailSequenceStepMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailSequence;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.model.EmailSequenceStep;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service for CRUD operations on email sequences, steps, and enrollments.
 *
 * <p>A sequence is a drip campaign with ordered steps. Contacts are enrolled into
 * a sequence and receive each step's email after the configured delay.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSequenceService {

    private final EmailSequenceMapper           emailSequenceMapper;
    private final EmailSequenceStepMapper       emailSequenceStepMapper;
    private final EmailSequenceEnrollmentMapper enrollmentMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Sequence CRUD
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists all non-deleted sequences for the given tenant.
     */
    public List<EmailSequence> listSequences(Long tenantId) {
        return emailSequenceMapper.selectList(
                new LambdaQueryWrapper<EmailSequence>()
                        .eq(EmailSequence::getTenantId, tenantId)
                        .orderByDesc(EmailSequence::getCreatedAt));
    }

    /**
     * Returns a sequence by its primary key.
     */
    public EmailSequence getSequence(Long id) {
        return emailSequenceMapper.selectById(id);
    }

    /**
     * Creates a new sequence in {@code draft} status.
     *
     * @param tenantId    owning tenant
     * @param userId      creating user
     * @param name        sequence name
     * @param description optional description
     * @return the persisted sequence
     */
    public EmailSequence createSequence(Long tenantId, Long userId, String name, String description) {
        EmailSequence seq = new EmailSequence();
        seq.setTenantId(tenantId);
        seq.setCreatedBy(userId);
        seq.setName(name);
        seq.setDescription(description);
        seq.setStatus(EmailConstants.SEQ_STATUS_DRAFT);
        seq.setDeletedFlag(false);
        seq.setCreatedAt(Instant.now());
        seq.setUpdatedAt(Instant.now());
        emailSequenceMapper.insert(seq);
        log.info("createSequence: sequenceId={} name='{}' by userId={}", seq.getId(), name, userId);
        return seq;
    }

    /**
     * Updates the name and/or description of a sequence.
     *
     * @param id          sequence ID
     * @param name        new name (null = no change)
     * @param description new description (null = no change)
     */
    public void updateSequence(Long id, String name, String description) {
        EmailSequence update = new EmailSequence();
        update.setId(id);
        if (name != null) {
            update.setName(name);
        }
        if (description != null) {
            update.setDescription(description);
        }
        update.setUpdatedAt(Instant.now());
        emailSequenceMapper.updateById(update);
    }

    /**
     * Updates the lifecycle status of a sequence.
     *
     * <p>When archiving, all active enrollments are paused automatically.
     *
     * @param id     sequence ID
     * @param status one of: draft, active, paused, archived
     */
    public void updateStatus(Long id, String status) {
        EmailSequence update = new EmailSequence();
        update.setId(id);
        update.setStatus(status);
        update.setUpdatedAt(Instant.now());
        emailSequenceMapper.updateById(update);

        if (EmailConstants.SEQ_STATUS_ARCHIVED.equals(status)) {
            // Pause all active enrollments for this sequence
            List<EmailSequenceEnrollment> active = enrollmentMapper.selectList(
                    new LambdaQueryWrapper<EmailSequenceEnrollment>()
                            .eq(EmailSequenceEnrollment::getSequenceId, id)
                            .eq(EmailSequenceEnrollment::getStatus, EmailConstants.ENROLLMENT_ACTIVE));
            for (EmailSequenceEnrollment enrollment : active) {
                updateEnrollmentStatus(enrollment.getId(), EmailConstants.ENROLLMENT_PAUSED);
            }
            log.info("updateStatus: sequenceId={} archived, {} enrollments paused", id, active.size());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Step management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns all steps for a sequence in execution order.
     */
    public List<EmailSequenceStep> getSteps(Long sequenceId) {
        return emailSequenceStepMapper.findBySequenceId(sequenceId);
    }

    /**
     * Adds a new step to a sequence.
     *
     * @param sequenceId      owning sequence
     * @param stepOrder       1-based position
     * @param delayDays       days to wait before sending
     * @param subjectTemplate subject line template
     * @param bodyTemplate    HTML body template
     * @return the persisted step
     */
    public EmailSequenceStep addStep(Long sequenceId, int stepOrder, int delayDays,
                                     String subjectTemplate, String bodyTemplate) {
        EmailSequenceStep step = new EmailSequenceStep();
        step.setSequenceId(sequenceId);
        step.setStepOrder(stepOrder);
        step.setDelayDays(delayDays);
        step.setSubjectTemplate(subjectTemplate);
        step.setBodyTemplate(bodyTemplate);
        step.setCreatedAt(Instant.now());
        emailSequenceStepMapper.insert(step);
        return step;
    }

    /**
     * Updates an existing step's fields.
     */
    public void updateStep(Long stepId, Integer stepOrder, Integer delayDays,
                           String subjectTemplate, String bodyTemplate) {
        EmailSequenceStep update = new EmailSequenceStep();
        update.setId(stepId);
        if (stepOrder != null)      update.setStepOrder(stepOrder);
        if (delayDays != null)      update.setDelayDays(delayDays);
        if (subjectTemplate != null) update.setSubjectTemplate(subjectTemplate);
        if (bodyTemplate != null)    update.setBodyTemplate(bodyTemplate);
        emailSequenceStepMapper.updateById(update);
    }

    /**
     * Deletes a step from a sequence.
     */
    public void deleteStep(Long stepId) {
        emailSequenceStepMapper.deleteById(stepId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Enrollment
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Enrolls a single contact into a sequence.
     *
     * <p>The first step's {@code delay_days} is used to calculate {@code next_send_at}.
     * Enrollment starts at step 0 (not started); the executor will advance it.
     *
     * @param tenantId     owning tenant
     * @param sequenceId   sequence to enroll into
     * @param accountId    email account to send from
     * @param contactEmail recipient email address
     * @param modelCode    DSL model code for the linked CRM record (may be null)
     * @param recordId     CRM record ID (may be null)
     * @return the persisted enrollment
     */
    public EmailSequenceEnrollment enroll(Long tenantId, Long sequenceId, Long accountId,
                                          String contactEmail, String modelCode, String recordId) {
        // Determine first step delay
        List<EmailSequenceStep> steps = emailSequenceStepMapper.findBySequenceId(sequenceId);
        int firstDelayDays = steps.isEmpty() ? 0 : steps.get(0).getDelayDays();

        Instant now     = Instant.now();
        Instant firstAt = now.plus(firstDelayDays, ChronoUnit.DAYS);

        EmailSequenceEnrollment enrollment = new EmailSequenceEnrollment();
        enrollment.setTenantId(tenantId);
        enrollment.setSequenceId(sequenceId);
        enrollment.setAccountId(accountId);
        enrollment.setContactEmail(contactEmail);
        enrollment.setModelCode(modelCode);
        enrollment.setRecordId(recordId);
        enrollment.setCurrentStep(0);
        enrollment.setStatus(EmailConstants.ENROLLMENT_ACTIVE);
        enrollment.setNextSendAt(firstAt);
        enrollment.setEnrolledAt(now);
        enrollment.setCreatedAt(now);

        enrollmentMapper.insert(enrollment);
        log.info("enroll: enrollmentId={} contactEmail={} sequenceId={}",
                enrollment.getId(), contactEmail, sequenceId);
        return enrollment;
    }

    /**
     * Updates the status of an enrollment.
     *
     * @param id     enrollment ID
     * @param status one of: active, paused, completed, failed, unsubscribed
     */
    public void updateEnrollmentStatus(Long id, String status) {
        EmailSequenceEnrollment update = new EmailSequenceEnrollment();
        update.setId(id);
        update.setStatus(status);
        if (EmailConstants.ENROLLMENT_COMPLETED.equals(status)) {
            update.setCompletedAt(Instant.now());
        }
        enrollmentMapper.updateById(update);
    }

    /**
     * Lists all enrollments for a sequence.
     *
     * @param sequenceId the sequence to query
     * @return enrollments ordered by created_at descending
     */
    public List<EmailSequenceEnrollment> listEnrollments(Long sequenceId) {
        return enrollmentMapper.selectList(
                new LambdaQueryWrapper<EmailSequenceEnrollment>()
                        .eq(EmailSequenceEnrollment::getSequenceId, sequenceId)
                        .orderByDesc(EmailSequenceEnrollment::getCreatedAt));
    }
}
