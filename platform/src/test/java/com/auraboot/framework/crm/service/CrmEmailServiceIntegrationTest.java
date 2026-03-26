package com.auraboot.framework.crm.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.internet.MimeMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for CrmEmailService.
 * <p>
 * JavaMailSender is mocked (external dependency), but all DB operations use real PostgreSQL.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrmEmailServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CrmEmailService crmEmailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JavaMailSender mailSender;

    private static final String TEST_PREFIX = "crm_email_test_" + System.currentTimeMillis();

    @BeforeEach
    void setupMocks() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    @Test
    @Order(1)
    void renderTemplate_replacesVariables() {
        String template = "Hello {{name}}, your order {{orderId}} is ready.";
        Map<String, String> vars = Map.of("name", "Alice", "orderId", "ORD-001");
        String result = crmEmailService.renderTemplate(template, vars);
        assertThat(result).isEqualTo("Hello Alice, your order ORD-001 is ready.");
    }

    @Test
    @Order(2)
    void renderTemplate_preservesUnknownVariables() {
        String template = "Hello {{name}}, see {{unknown}}.";
        Map<String, String> vars = Map.of("name", "Bob");
        String result = crmEmailService.renderTemplate(template, vars);
        assertThat(result).isEqualTo("Hello Bob, see {{unknown}}.");
    }

    @Test
    @Order(3)
    void renderTemplate_handlesNullTemplate() {
        assertThat(crmEmailService.renderTemplate(null, Map.of("a", "b"))).isNull();
    }

    @Test
    @Order(4)
    void sendEmail_createsLogAndSendsViaMail() {
        // Arrange: create a template in the DB
        String templateId = createTestTemplate("Welcome {{name}}", "<p>Hello {{name}}, welcome!</p>");

        // Act
        String emailLogId = crmEmailService.sendEmail(templateId, "test@example.com", Map.of("name", "Charlie"));

        // Assert: email log created with SENT status
        assertThat(emailLogId).isNotBlank();
        Map<String, Object> log = getEmailLog(emailLogId);
        assertThat(log).isNotNull();
        assertThat(log.get("crm_el_status")).isEqualTo("sent");
        assertThat(log.get("crm_el_to_address")).isEqualTo("test@example.com");
        assertThat(log.get("crm_el_subject")).isEqualTo("Welcome Charlie");
        assertThat(log.get("crm_el_sent_at")).isNotNull();

        // Verify mail was sent
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @Order(5)
    void sendEmail_failsWithInvalidTemplate() {
        assertThatThrownBy(() ->
            crmEmailService.sendEmail("nonexistent-template-id", "test@example.com", Map.of())
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Email template not found");
    }

    @Test
    @Order(6)
    void sendEmail_recordsFailedStatusWhenMailThrows() {
        // Arrange: create a template
        String templateId = createTestTemplate("Subject", "Body");

        // Make mailSender.send throw
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        // Act & Assert
        assertThatThrownBy(() ->
            crmEmailService.sendEmail(templateId, "fail@example.com", Map.of())
        ).isInstanceOf(RuntimeException.class);

        // The email log should exist with FAILED status
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT crm_el_status FROM mt_crm_email_log WHERE crm_el_to_address = ? AND tenant_id = ?",
            "fail@example.com", getTestTenant().getId()
        );
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).get("crm_el_status")).isEqualTo("failed");

        // Reset the mock for next tests
        reset(mailSender);
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    @Test
    @Order(7)
    void sendBulkEmail_sendsToAllCampaignMembers() {
        // Arrange: create campaign and members
        String campaignId = createTestCampaign();
        createTestCampaignMember(campaignId, "Member1", "m1@example.com");
        createTestCampaignMember(campaignId, "Member2", "m2@example.com");
        createTestCampaignMember(campaignId, "NoEmail", null); // should be skipped

        String templateId = createTestTemplate("Campaign Update", "<p>Hi {{name}}!</p>");

        // Act
        int sent = crmEmailService.sendBulkEmail(templateId, campaignId);

        // Assert: 2 emails sent (member without email is skipped)
        assertThat(sent).isEqualTo(2);
        verify(mailSender, atLeast(2)).send(any(MimeMessage.class));
    }

    @Test
    @Order(8)
    void sendBulkEmail_returnsZeroForEmptyCampaign() {
        String campaignId = createTestCampaign();
        String templateId = createTestTemplate("Empty", "Body");

        int sent = crmEmailService.sendBulkEmail(templateId, campaignId);
        assertThat(sent).isEqualTo(0);
    }

    @Test
    @Order(9)
    void recordEmailEvent_updatesStatus() {
        // Arrange
        String templateId = createTestTemplate("Track Test", "Body");
        String emailLogId = crmEmailService.sendEmail(templateId, "track@example.com", Map.of());

        // Act: record OPENED event
        crmEmailService.recordEmailEvent(emailLogId, "opened");

        // Assert
        Map<String, Object> log = getEmailLog(emailLogId);
        assertThat(log.get("crm_el_status")).isEqualTo("opened");
        assertThat(log.get("crm_el_opened_at")).isNotNull();
    }

    @Test
    @Order(10)
    void recordEmailEvent_updatesStatusForBounced() {
        String templateId = createTestTemplate("Bounce Test", "Body");
        String emailLogId = crmEmailService.sendEmail(templateId, "bounce@example.com", Map.of());

        crmEmailService.recordEmailEvent(emailLogId, "bounced");

        Map<String, Object> log = getEmailLog(emailLogId);
        assertThat(log.get("crm_el_status")).isEqualTo("bounced");
    }

    // ===== Helper methods =====

    private String createTestTemplate(String subject, String body) {
        String pid = UniqueIdGenerator.generate();
        Instant now = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO mt_crm_email_template " +
            "(pid, tenant_id, crm_et_name, crm_et_subject, crm_et_body, crm_et_status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, 'active', ?, ?)",
            pid, getTestTenant().getId(), TEST_PREFIX + "_TPL_" + pid.substring(0, 8),
            subject, body, Timestamp.from(now), Timestamp.from(now)
        );
        return pid;
    }

    private String createTestCampaign() {
        String pid = UniqueIdGenerator.generate();
        Instant now = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO mt_crm_campaign " +
            "(pid, tenant_id, crm_cpn_code, crm_cpn_name, crm_cpn_status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'active', ?, ?)",
            pid, getTestTenant().getId(), TEST_PREFIX + "_CPN_" + pid.substring(0, 8),
            TEST_PREFIX + " Campaign", Timestamp.from(now), Timestamp.from(now)
        );
        return pid;
    }

    private void createTestCampaignMember(String campaignId, String name, String email) {
        String pid = UniqueIdGenerator.generate();
        Instant now = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO mt_crm_campaign_member " +
            "(pid, tenant_id, crm_cm_campaign_id, crm_cm_member_type, crm_cm_member_name, crm_cm_email, " +
            "crm_cm_status, crm_cm_added_date, created_at, updated_at) " +
            "VALUES (?, ?, ?, 'contact', ?, ?, 'invited', ?, ?, ?)",
            pid, getTestTenant().getId(), campaignId, name, email,
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private Map<String, Object> getEmailLog(String emailLogId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM mt_crm_email_log WHERE pid = ? AND tenant_id = ?",
            emailLogId, getTestTenant().getId()
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
