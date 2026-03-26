package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.auraboot.framework.email.service.EmailRecordLinkService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link EmailRecordLinkService}.
 *
 * <p>Tests run against real PostgreSQL. CRM dynamic tables (mt_crm_*)
 * are not assumed to exist, so autoLink is exercised indirectly via
 * manual link / remove operations which only touch ab_email_record_link.
 *
 * @since 6.5.0
 */
@Slf4j
@DisplayName("EmailRecordLinkService Integration Tests (ERL-01~ERL-03)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailRecordLinkServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EmailRecordLinkService emailRecordLinkService;

    @Autowired
    private EmailRecordLinkMapper emailRecordLinkMapper;

    @Autowired
    private EmailMessageMapper emailMessageMapper;

    @Autowired
    private EmailAccountMapper emailAccountMapper;

    private final String runId = "erl-" + System.currentTimeMillis();

    private Long testTenantId;
    private Long testAccountId;
    private Long testMessageId;

    @BeforeEach
    void setUp() {
        testTenantId = MetaContext.getCurrentTenantId();

        // Create a minimal email account for test messages
        EmailAccount account = new EmailAccount();
        account.setTenantId(testTenantId);
        account.setUserId(MetaContext.getCurrentUserId());
        account.setAccountType(EmailConstants.ACCOUNT_TYPE_PERSONAL);
        account.setProvider(EmailConstants.PROVIDER_GMAIL);
        account.setEmailAddress(runId + "@example.com");
        account.setStatus(EmailConstants.ACCOUNT_STATUS_ACTIVE);
        account.setSyncMode(EmailConstants.SYNC_MODE_MANUAL);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        account.setDeletedFlag(false);
        emailAccountMapper.insert(account);
        testAccountId = account.getId();

        // Create a minimal email message
        EmailMessage message = new EmailMessage();
        message.setTenantId(testTenantId);
        message.setAccountId(testAccountId);
        message.setGmailMessageId("gmail-msg-" + runId);
        message.setGmailThreadId("gmail-thread-" + runId);
        message.setDirection(EmailConstants.DIRECTION_INBOUND);
        message.setFromAddress("sender-" + runId + "@example.com");
        message.setSubject("Test subject " + runId);
        message.setIsRead(false);
        message.setGmailDate(Instant.now());
        message.setSyncedAt(Instant.now());
        message.setCreatedAt(Instant.now());
        emailMessageMapper.insert(message);
        testMessageId = message.getId();
    }

    @AfterEach
    void tearDown() {
        // Clean up test data (manual delete, not rollback, due to NOT_SUPPORTED propagation)
        if (testMessageId != null) {
            emailRecordLinkMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmailRecordLink>()
                            .eq(EmailRecordLink::getMessageId, testMessageId));
            emailMessageMapper.deleteById(testMessageId);
        }
        if (testAccountId != null) {
            emailAccountMapper.deleteById(testAccountId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ERL-01: manualLink creates link with manual type
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("ERL-01: manualLink creates link with link_type=manual")
    void erl01_manualLinkCreatesManualLink() {
        String modelCode = "crm_contact";
        String recordId  = "42";
        String threadId  = "gmail-thread-" + runId;

        EmailRecordLink link = emailRecordLinkService.manualLink(
                testTenantId, testMessageId, threadId, modelCode, recordId);

        assertThat(link).isNotNull();
        assertThat(link.getId()).isNotNull();
        assertThat(link.getTenantId()).isEqualTo(testTenantId);
        assertThat(link.getMessageId()).isEqualTo(testMessageId);
        assertThat(link.getThreadId()).isEqualTo(threadId);
        assertThat(link.getModelCode()).isEqualTo(modelCode);
        assertThat(link.getRecordId()).isEqualTo(recordId);
        assertThat(link.getLinkType()).isEqualTo(EmailConstants.LINK_TYPE_MANUAL);
        assertThat(link.getCreatedAt()).isNotNull();

        // Verify persisted in DB
        EmailRecordLink fromDb = emailRecordLinkMapper.selectById(link.getId());
        assertThat(fromDb).isNotNull();
        assertThat(fromDb.getLinkType()).isEqualTo(EmailConstants.LINK_TYPE_MANUAL);

        log.info("ERL-01 PASS: linkId={} created with type=manual", link.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ERL-02: manualLink for different model codes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("ERL-02: manualLink supports different CRM model codes")
    void erl02_manualLinkSupportsMultipleModels() {
        String threadId = "gmail-thread-" + runId;

        EmailRecordLink contactLink = emailRecordLinkService.manualLink(
                testTenantId, testMessageId, threadId, "crm_contact", "1");
        EmailRecordLink leadLink = emailRecordLinkService.manualLink(
                testTenantId, testMessageId, threadId, "crm_lead", "2");
        EmailRecordLink oppLink = emailRecordLinkService.manualLink(
                testTenantId, testMessageId, threadId, "crm_opportunity", "3");

        assertThat(contactLink.getModelCode()).isEqualTo("crm_contact");
        assertThat(leadLink.getModelCode()).isEqualTo("crm_lead");
        assertThat(oppLink.getModelCode()).isEqualTo("crm_opportunity");

        // Verify all three are persisted
        List<EmailRecordLink> links = emailRecordLinkMapper.findByThread(testTenantId, threadId);
        assertThat(links).hasSizeGreaterThanOrEqualTo(3);

        log.info("ERL-02 PASS: 3 links across different models created for messageId={}", testMessageId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ERL-03: removeLink deletes the link from the database
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("ERL-03: removeLink deletes link from database")
    void erl03_removeLinkDeletesFromDb() {
        // Create a link first
        EmailRecordLink link = emailRecordLinkService.manualLink(
                testTenantId, testMessageId, "gmail-thread-" + runId, "crm_contact", "99");
        Long linkId = link.getId();
        assertThat(emailRecordLinkMapper.selectById(linkId)).isNotNull();

        // Delete it
        emailRecordLinkService.removeLink(linkId);

        // Must no longer exist in DB
        EmailRecordLink deleted = emailRecordLinkMapper.selectById(linkId);
        assertThat(deleted).isNull();

        log.info("ERL-03 PASS: linkId={} deleted from database", linkId);
    }
}
