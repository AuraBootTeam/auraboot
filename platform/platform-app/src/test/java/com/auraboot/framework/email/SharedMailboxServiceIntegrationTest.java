package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.SharedMailboxService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SharedMailboxService}.
 *
 * <p>Redis is real (matches project constraint — no Redis mocking).
 * Gmail API is not needed here (messages are inserted directly).
 *
 * @since 6.5.0
 */
@Slf4j
@DisplayName("SharedMailboxService Integration Tests (SM-01~SM-03)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SharedMailboxServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SharedMailboxService sharedMailboxService;

    @Autowired
    private EmailAccountMapper emailAccountMapper;

    @Autowired
    private EmailAccountMemberMapper emailAccountMemberMapper;

    @Autowired
    private EmailMessageMapper emailMessageMapper;

    private final String runId = "sm-" + System.currentTimeMillis();

    private Long testTenantId;
    private Long testUserId;

    /** Shared account used across ordered tests. */
    private EmailAccount sharedAccount;

    /** Two member user IDs for round-robin testing. */
    private Long memberUserId1;
    private Long memberUserId2;

    @BeforeEach
    void setUp() {
        testTenantId = MetaContext.getCurrentTenantId();
        testUserId   = MetaContext.getCurrentUserId();
        // Use synthetic user IDs that don't need to be real DB users
        memberUserId1 = testUserId + 10001L;
        memberUserId2 = testUserId + 10002L;

        // Create a fresh shared account per test class (reused via static but reset here)
        if (sharedAccount == null) {
            EmailAccount account = new EmailAccount();
            account.setTenantId(testTenantId);
            account.setUserId(testUserId);
            account.setAccountType(EmailConstants.ACCOUNT_TYPE_SHARED);
            account.setProvider(EmailConstants.PROVIDER_GMAIL);
            account.setEmailAddress(runId + "-shared@example.com");
            account.setStatus(EmailConstants.ACCOUNT_STATUS_ACTIVE);
            account.setSyncMode(EmailConstants.SYNC_MODE_MANUAL);
            account.setCreatedAt(Instant.now());
            account.setUpdatedAt(Instant.now());
            account.setDeletedFlag(false);
            emailAccountMapper.insert(account);
            sharedAccount = account;

            // Add two members with equal weight
            addMember(account.getId(), memberUserId1, 1);
            addMember(account.getId(), memberUserId2, 1);
        }
    }

    @AfterAll
    void cleanUp() {
        if (sharedAccount != null) {
            // Restore MetaContext before performing DB operations in @AfterAll
            // (BaseIntegrationTest clears it in @AfterEach, so we must re-set it here)
            MetaContext.setContext(
                    getTestTenant().getId(),
                    getTestUser().getId(),
                    getTestUser().getPid(),
                    getTestUser().getUserName()
            );
            try {
                emailAccountMemberMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmailAccountMember>()
                                .eq(EmailAccountMember::getAccountId, sharedAccount.getId()));
                emailAccountMapper.deleteById(sharedAccount.getId());
            } finally {
                MetaContext.clear();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SM-01: round_robin distributes messages between two members
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("SM-01: round_robin distributes messages across members")
    void sm01_roundRobinDistributes() {
        Set<Long> assignedUserIds = new HashSet<>();

        // Assign 4 messages — with 2 equal-weight members both should be used
        for (int i = 0; i < 4; i++) {
            EmailMessage msg = createTestMessage();
            Long userId = sharedMailboxService.assign(sharedAccount, msg, "round_robin");
            assertThat(userId).as("assigned userId must be one of the members")
                    .isIn(memberUserId1, memberUserId2);
            assignedUserIds.add(userId);

            // Verify persisted in DB
            EmailMessage fromDb = emailMessageMapper.selectById(msg.getId());
            assertThat(fromDb.getAssignedTo()).isEqualTo(userId);
        }

        // Both members must have received at least one message
        assertThat(assignedUserIds).as("both members must be used in round_robin")
                .containsExactlyInAnyOrder(memberUserId1, memberUserId2);

        log.info("SM-01 PASS: round_robin distributed across userIds={}", assignedUserIds);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SM-02: manual strategy returns null (no auto-assignment)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("SM-02: manual strategy returns null — no auto-assignment")
    void sm02_manualStrategyReturnsNull() {
        EmailMessage msg = createTestMessage();
        Long result = sharedMailboxService.assign(sharedAccount, msg, "manual");

        assertThat(result).isNull();

        // Message must remain unassigned in DB
        EmailMessage fromDb = emailMessageMapper.selectById(msg.getId());
        assertThat(fromDb.getAssignedTo()).isNull();

        log.info("SM-02 PASS: manual strategy returned null, message unassigned");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SM-03: personal account assign returns null
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("SM-03: personal account assign returns null regardless of strategy")
    void sm03_personalAccountAssignReturnsNull() {
        EmailAccount personalAccount = new EmailAccount();
        personalAccount.setTenantId(testTenantId);
        personalAccount.setUserId(testUserId);
        personalAccount.setAccountType(EmailConstants.ACCOUNT_TYPE_PERSONAL);
        personalAccount.setProvider(EmailConstants.PROVIDER_GMAIL);
        personalAccount.setEmailAddress(runId + "-personal@example.com");
        personalAccount.setStatus(EmailConstants.ACCOUNT_STATUS_ACTIVE);
        personalAccount.setSyncMode(EmailConstants.SYNC_MODE_MANUAL);
        personalAccount.setCreatedAt(Instant.now());
        personalAccount.setUpdatedAt(Instant.now());
        personalAccount.setDeletedFlag(false);
        emailAccountMapper.insert(personalAccount);

        try {
            EmailMessage msg = createTestMessage();
            Long result = sharedMailboxService.assign(personalAccount, msg, "round_robin");
            assertThat(result).isNull();
            log.info("SM-03 PASS: personal account returned null for assign");
        } finally {
            emailAccountMapper.deleteById(personalAccount.getId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SM-04: claim sets assigned_to on the message
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("SM-04: claim sets assigned_to on the message")
    void sm04_claimSetsAssignedTo() {
        EmailMessage msg = createTestMessage();
        assertThat(msg.getAssignedTo()).isNull();

        sharedMailboxService.claim(msg.getId(), memberUserId1);

        EmailMessage fromDb = emailMessageMapper.selectById(msg.getId());
        assertThat(fromDb.getAssignedTo()).isEqualTo(memberUserId1);

        // Re-claim by different user overwrites
        sharedMailboxService.claim(msg.getId(), memberUserId2);
        EmailMessage reclaimed = emailMessageMapper.selectById(msg.getId());
        assertThat(reclaimed.getAssignedTo()).isEqualTo(memberUserId2);

        log.info("SM-04 PASS: claim and re-claim both set assigned_to correctly");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private EmailMessage createTestMessage() {
        EmailMessage msg = new EmailMessage();
        msg.setTenantId(testTenantId);
        msg.setAccountId(sharedAccount.getId());
        msg.setGmailMessageId("gm-" + runId + "-" + System.nanoTime());
        msg.setGmailThreadId("gt-" + runId);
        msg.setDirection(EmailConstants.DIRECTION_INBOUND);
        msg.setFromAddress("test@external.com");
        msg.setSubject("Test " + runId);
        msg.setIsRead(false);
        msg.setGmailDate(Instant.now());
        msg.setSyncedAt(Instant.now());
        msg.setCreatedAt(Instant.now());
        emailMessageMapper.insert(msg);
        return msg;
    }

    private void addMember(Long accountId, Long userId, int weight) {
        EmailAccountMember member = new EmailAccountMember();
        member.setAccountId(accountId);
        member.setUserId(userId);
        member.setRole(EmailConstants.MEMBER_ROLE_MEMBER);
        member.setAssignmentWeight(weight);
        member.setCreatedAt(Instant.now());
        emailAccountMemberMapper.insert(member);
    }
}
