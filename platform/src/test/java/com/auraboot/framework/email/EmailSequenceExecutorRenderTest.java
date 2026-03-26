package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailSequenceEnrollmentMapper;
import com.auraboot.framework.email.mapper.EmailSequenceStepMapper;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.service.EmailSendService;
import com.auraboot.framework.email.service.EmailSequenceExecutor;
import com.auraboot.framework.email.service.EmailSequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EmailSequenceExecutor#renderTemplate(String, EmailSequenceEnrollment)}.
 *
 * <p>No Spring context — all dependencies are mocked.  The method under test is
 * package-private to allow white-box testing without reflection.
 *
 * @since 6.5.0
 */
@DisplayName("EmailSequenceExecutor.renderTemplate Unit Tests (ESR-01~ESR-05)")
class EmailSequenceExecutorRenderTest {

    private EmailSequenceExecutor executor;

    @BeforeEach
    void setUp() {
        // All dependencies mocked — renderTemplate has no external dependencies
        executor = new EmailSequenceExecutor(
                mock(EmailSequenceEnrollmentMapper.class),
                mock(EmailSequenceStepMapper.class),
                mock(EmailAccountMapper.class),
                mock(EmailMessageMapper.class),
                mock(EmailSendService.class),
                mock(EmailSequenceService.class)
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESR-01: replaces {{email}}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ESR-01: {{email}} is replaced with contactEmail")
    void esr01_replacesEmail() {
        EmailSequenceEnrollment enrollment = enrollment("alice@example.com", "crm_contact", "7");
        String result = executor.renderTemplate("Hello {{email}}, welcome!", enrollment);
        assertThat(result).isEqualTo("Hello alice@example.com, welcome!");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESR-02: replaces {{modelCode}} and {{recordId}}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ESR-02: {{modelCode}} and {{recordId}} are replaced")
    void esr02_replacesModelCodeAndRecordId() {
        EmailSequenceEnrollment enrollment = enrollment("bob@example.com", "crm_lead", "42");
        String result = executor.renderTemplate(
                "Model: {{modelCode}}, Record: {{recordId}}", enrollment);
        assertThat(result).isEqualTo("Model: crm_lead, Record: 42");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESR-03: multiple occurrences are all replaced
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ESR-03: multiple {{email}} occurrences are all replaced")
    void esr03_replacesAllOccurrences() {
        EmailSequenceEnrollment enrollment = enrollment("carol@example.com", null, null);
        String result = executor.renderTemplate(
                "Hi {{email}}! Your email is {{email}}.", enrollment);
        assertThat(result).isEqualTo("Hi carol@example.com! Your email is carol@example.com.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESR-04: unknown variables are left unchanged
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ESR-04: unknown {{variables}} are left unchanged")
    void esr04_unknownVariablesUnchanged() {
        EmailSequenceEnrollment enrollment = enrollment("dave@example.com", "crm_contact", "1");
        String result = executor.renderTemplate(
                "Hi {{email}}, your rep is {{repName}}", enrollment);
        assertThat(result).isEqualTo("Hi dave@example.com, your rep is {{repName}}");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESR-05: null template returns empty string; null field values become empty
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ESR-05: null template → empty string; null enrollment fields → empty string")
    void esr05_nullHandling() {
        EmailSequenceEnrollment enrollment = enrollment(null, null, null);

        // Null template
        String nullResult = executor.renderTemplate(null, enrollment);
        assertThat(nullResult).isEmpty();

        // Null enrollment fields render as empty string (not "null")
        String fieldResult = executor.renderTemplate(
                "Email=|{{email}}| Model=|{{modelCode}}| Record=|{{recordId}}|", enrollment);
        assertThat(fieldResult).isEqualTo("Email=|| Model=|| Record=||");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────────────────────

    private EmailSequenceEnrollment enrollment(String email, String modelCode, String recordId) {
        EmailSequenceEnrollment e = new EmailSequenceEnrollment();
        e.setContactEmail(email);
        e.setModelCode(modelCode);
        e.setRecordId(recordId);
        return e;
    }
}
