package com.auraboot.framework.email.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailSequenceEnrollmentMapper;
import com.auraboot.framework.email.mapper.EmailSequenceStepMapper;
import com.auraboot.framework.email.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executor for processing due email sequence enrollments.
 *
 * <p>Invoked on a schedule by {@code EmailSequenceJob}. For each due enrollment:
 * <ol>
 *   <li>Reply detection — if the contact replied, mark enrollment as replied and skip.</li>
 *   <li>Advance to next step — if there is no next step, complete the enrollment.</li>
 *   <li>Render templates — simple {@code {{variable}}} substitution.</li>
 *   <li>Send via {@link EmailSendService}.</li>
 *   <li>Update enrollment progress and compute next send time.</li>
 * </ol>
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSequenceExecutor {

    /** Simple Mustache-style variable pattern: {@code {{variableName}}}. */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /** Enrollment status for contacts who replied (not a formal EmailConstants value). */
    static final String ENROLLMENT_REPLIED = "replied";

    private final EmailSequenceEnrollmentMapper enrollmentMapper;
    private final EmailSequenceStepMapper       stepMapper;
    private final EmailAccountMapper            accountMapper;
    private final EmailMessageMapper            messageMapper;
    private final EmailSendService              emailSendService;
    private final EmailSequenceService          sequenceService;

    // ──────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Processes all enrollments whose {@code next_send_at} is due.
     *
     * <p>Called by {@link com.auraboot.framework.email.job.EmailSequenceJob} on schedule.
     */
    public void processDueEnrollments() {
        List<EmailSequenceEnrollment> due = enrollmentMapper.findDueEnrollments();
        log.info("processDueEnrollments: {} due enrollments found", due.size());

        for (EmailSequenceEnrollment enrollment : due) {
            Long tenantId = enrollment.getTenantId();
            if (tenantId == null) {
                log.warn("Skipping email sequence enrollment without tenantId: enrollmentId={}", enrollment.getId());
                continue;
            }
            MetaContext.setSystemTenantContext(tenantId);
            try {
                processEnrollment(enrollment);
            } catch (Exception e) {
                log.error("Failed to process enrollmentId={}: {}", enrollment.getId(), e.getMessage(), e);
                // Mark enrollment as failed so it doesn't block the queue
                sequenceService.updateEnrollmentStatus(enrollment.getId(),
                        EmailConstants.ENROLLMENT_FAILED);
            } finally {
                MetaContext.clear();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: single enrollment processing
    // ──────────────────────────────────────────────────────────────────────────

    private void processEnrollment(EmailSequenceEnrollment enrollment) throws Exception {
        Long enrollmentId = enrollment.getId();

        // 1. Reply detection: count inbound emails from the contact since enrollment
        EmailAccount account = accountMapper.selectById(enrollment.getAccountId());
        if (account == null) {
            log.warn("processEnrollment: account not found for enrollmentId={}", enrollmentId);
            sequenceService.updateEnrollmentStatus(enrollmentId, EmailConstants.ENROLLMENT_FAILED);
            return;
        }

        int replyCount = messageMapper.countInboundFrom(
                enrollment.getAccountId(),
                enrollment.getContactEmail(),
                enrollment.getEnrolledAt());

        if (replyCount > 0) {
            log.info("processEnrollment: enrollmentId={} replied ({} messages), marking replied",
                    enrollmentId, replyCount);
            sequenceService.updateEnrollmentStatus(enrollmentId, ENROLLMENT_REPLIED);
            return;
        }

        // 2. Determine next step (steps are 1-indexed; currentStep is the last sent step)
        int nextStepOrder = enrollment.getCurrentStep() + 1;
        List<EmailSequenceStep> steps = stepMapper.findBySequenceId(enrollment.getSequenceId());

        EmailSequenceStep nextStep = steps.stream()
                .filter(s -> s.getStepOrder() == nextStepOrder)
                .findFirst()
                .orElse(null);

        if (nextStep == null) {
            // No more steps — sequence complete
            log.info("processEnrollment: enrollmentId={} completed (no step {})",
                    enrollmentId, nextStepOrder);
            sequenceService.updateEnrollmentStatus(enrollmentId, EmailConstants.ENROLLMENT_COMPLETED);
            return;
        }

        // 3. Render templates
        String subject = renderTemplate(nextStep.getSubjectTemplate(), enrollment);
        String body    = renderTemplate(nextStep.getBodyTemplate(), enrollment);

        // 4. Send via EmailSendService
        emailSendService.send(
                account,
                List.of(enrollment.getContactEmail()),
                List.of(),
                List.of(),
                subject,
                body,
                null,   // no threading for sequence emails
                false   // tracking disabled by default
        );

        log.info("processEnrollment: enrollmentId={} sent step {} to {}",
                enrollmentId, nextStepOrder, enrollment.getContactEmail());

        // 5. Advance current_step and compute next next_send_at
        EmailSequenceStep nextNextStep = steps.stream()
                .filter(s -> s.getStepOrder() == nextStepOrder + 1)
                .findFirst()
                .orElse(null);

        EmailSequenceEnrollment update = new EmailSequenceEnrollment();
        update.setId(enrollmentId);
        update.setCurrentStep(nextStepOrder);

        if (nextNextStep != null) {
            Instant nextAt = Instant.now().plus(nextNextStep.getDelayDays(), ChronoUnit.DAYS);
            update.setNextSendAt(nextAt);
            update.setStatus(EmailConstants.ENROLLMENT_ACTIVE);
        } else {
            // This was the last step — mark completed
            update.setStatus(EmailConstants.ENROLLMENT_COMPLETED);
            update.setCompletedAt(Instant.now());
        }

        enrollmentMapper.updateById(update);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Template rendering (package-private for unit testing)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Renders a template string by substituting {@code {{variable}}} placeholders.
     *
     * <p>Supported variables:
     * <ul>
     *   <li>{@code {{email}}} — contact email</li>
     *   <li>{@code {{modelCode}}} — CRM model code</li>
     *   <li>{@code {{recordId}}} — CRM record ID</li>
     * </ul>
     *
     * <p>Unknown variables are left as-is. Null template returns an empty string.
     *
     * @param template   the template string (may be null)
     * @param enrollment the enrollment context providing variable values
     * @return rendered string
     */
    public String renderTemplate(String template, EmailSequenceEnrollment enrollment) {
        if (template == null) {
            return "";
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variable = matcher.group(1);
            String value    = resolveVariable(variable, enrollment);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String resolveVariable(String variable, EmailSequenceEnrollment enrollment) {
        return switch (variable) {
            case "email"     -> enrollment.getContactEmail() != null ? enrollment.getContactEmail() : "";
            case "modelCode" -> enrollment.getModelCode()    != null ? enrollment.getModelCode()    : "";
            case "recordId"  -> enrollment.getRecordId()     != null ? enrollment.getRecordId()     : "";
            default          -> "{{" + variable + "}}"; // leave unknown variables unchanged
        };
    }
}
