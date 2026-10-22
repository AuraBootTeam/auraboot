package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.service.EmailAccountService;
import com.auraboot.framework.email.service.GmailApiClient;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link EmailAccountService}.
 *
 * <p>{@link GmailApiClient} is mocked because tests cannot call the real Gmail API.
 * The database operations (insert, update, select) are exercised against real PostgreSQL.
 *
 * <p>Tests run with {@code Propagation.NOT_SUPPORTED} to allow independent DB commits
 * while still inheriting tenant/user context from {@link BaseIntegrationTest}.
 *
 * @since 6.5.0
 */
@Slf4j
@DisplayName("EmailAccountService Integration Tests (EA-01~EA-05)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailAccountServiceIntegrationTest extends BaseIntegrationTest {

    // ── Mocked (cannot call real Gmail API) ──────────────────────────────────
    @MockitoBean
    private GmailApiClient gmailApiClient;

    // ── Real beans ───────────────────────────────────────────────────────────
    @Autowired
    private EmailAccountService emailAccountService;

    @Autowired
    private EmailAccountMapper emailAccountMapper;

    @Autowired
    private EmailAccountMemberMapper emailAccountMemberMapper;

    @Autowired
    private FieldEncryptionService fieldEncryptionService;

    // ── Test state ───────────────────────────────────────────────────────────
    private final String runId = "ea-" + System.currentTimeMillis();

    private Long testTenantId;
    private Long testUserId;

    /** Persisted account ID shared across ordered tests. */
    private Long createdAccountId;

    /** Second user ID for member management tests (reuse testUserId + 1 as distinct ID). */
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        testTenantId = MetaContext.getCurrentTenantId();
        testUserId   = MetaContext.getCurrentUserId();
        secondUserId = testUserId + 9999L; // distinct ID that doesn't exist as a real user
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EA-01: handleOAuthCallback creates an account with encrypted tokens
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("EA-01: handleOAuthCallback creates personal account with encrypted tokens")
    void ea01_handleOAuthCallbackCreatesPersonalAccount() throws Exception {
        String testEmail = runId + "@example.com";
        String fakeAccess  = "fake-access-token-" + runId;
        String fakeRefresh = "fake-refresh-token-" + runId;

        when(gmailApiClient.exchangeCode(anyString()))
                .thenReturn(Map.of(
                        "email",         testEmail,
                        "access_token",  fakeAccess,
                        "refresh_token", fakeRefresh,
                        "expires_in",    "3600"
                ));

        EmailAccount account = emailAccountService.handleOAuthCallback(
                testTenantId, testUserId, "dummy-code", EmailConstants.ACCOUNT_TYPE_PERSONAL);

        assertThat(account).isNotNull();
        assertThat(account.getId()).isNotNull();
        assertThat(account.getEmailAddress()).isEqualTo(testEmail);
        assertThat(account.getAccountType()).isEqualTo(EmailConstants.ACCOUNT_TYPE_PERSONAL);
        assertThat(account.getStatus()).isEqualTo(EmailConstants.ACCOUNT_STATUS_ACTIVE);
        assertThat(account.getProvider()).isEqualTo(EmailConstants.PROVIDER_GMAIL);
        assertThat(account.getUserId()).isEqualTo(testUserId);
        assertThat(account.getTenantId()).isEqualTo(testTenantId);
        assertThat(account.getTokenExpiresAt()).isNotNull();

        // Tokens must be stored (encrypted if key is configured, passthrough otherwise)
        EmailAccount saved = emailAccountMapper.selectById(account.getId());
        assertThat(saved).isNotNull();
        assertThat(saved.getAccessToken()).isNotNull().isNotBlank();
        assertThat(saved.getRefreshToken()).isNotNull().isNotBlank();

        // Must decrypt back to original values (decrypt is identity in passthrough mode)
        assertThat(fieldEncryptionService.decrypt(saved.getAccessToken())).isEqualTo(fakeAccess);
        assertThat(fieldEncryptionService.decrypt(saved.getRefreshToken())).isEqualTo(fakeRefresh);

        // If encryption is enabled, stored tokens must NOT equal plaintext
        if (fieldEncryptionService.isEnabled()) {
            assertThat(saved.getAccessToken()).isNotEqualTo(fakeAccess);
            assertThat(saved.getRefreshToken()).isNotEqualTo(fakeRefresh);
        }

        // Record for downstream tests
        createdAccountId = account.getId();
        log.info("EA-01 PASS: accountId={}, email={}", createdAccountId, testEmail);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EA-02: shared account auto-adds connecting user as owner member
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("EA-02: shared account handleOAuthCallback auto-adds connecting user as owner member")
    void ea02_sharedAccountAutoAddsOwnerMember() throws Exception {
        String sharedEmail = runId + "-shared@example.com";

        when(gmailApiClient.exchangeCode(anyString()))
                .thenReturn(Map.of(
                        "email",         sharedEmail,
                        "access_token",  "fake-access-shared",
                        "refresh_token", "fake-refresh-shared",
                        "expires_in",    "3600"
                ));

        EmailAccount account = emailAccountService.handleOAuthCallback(
                testTenantId, testUserId, "dummy-code-shared", EmailConstants.ACCOUNT_TYPE_SHARED);

        assertThat(account).isNotNull();
        assertThat(account.getAccountType()).isEqualTo(EmailConstants.ACCOUNT_TYPE_SHARED);

        // Owner member must have been automatically created
        List<EmailAccountMember> members = emailAccountMemberMapper.findByAccountId(account.getId());
        assertThat(members).isNotEmpty();

        EmailAccountMember ownerMember = members.stream()
                .filter(m -> testUserId.equals(m.getUserId()))
                .findFirst()
                .orElse(null);

        assertThat(ownerMember).as("connecting user must be an owner member").isNotNull();
        assertThat(ownerMember.getRole()).isEqualTo(EmailConstants.MEMBER_ROLE_OWNER);

        // Clean up
        emailAccountMemberMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmailAccountMember>()
                        .eq(EmailAccountMember::getAccountId, account.getId()));
        emailAccountMapper.deleteById(account.getId());

        log.info("EA-02 PASS: accountId={}, ownerId={}", account.getId(), testUserId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EA-03: updateSyncMode changes sync mode
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("EA-03: updateSyncMode persists the new sync mode")
    void ea03_updateSyncModePersists() {
        // Depends on EA-01 having created the account
        assertThat(createdAccountId)
                .as("EA-01 must run first to create the account")
                .isNotNull();

        emailAccountService.updateSyncMode(createdAccountId, EmailConstants.SYNC_MODE_AUTO);

        EmailAccount updated = emailAccountMapper.selectById(createdAccountId);
        assertThat(updated).isNotNull();
        assertThat(updated.getSyncMode()).isEqualTo(EmailConstants.SYNC_MODE_AUTO);

        // Restore to manual for cleanliness
        emailAccountService.updateSyncMode(createdAccountId, EmailConstants.SYNC_MODE_MANUAL);
        assertThat(emailAccountMapper.selectById(createdAccountId).getSyncMode())
                .isEqualTo(EmailConstants.SYNC_MODE_MANUAL);

        log.info("EA-03 PASS: accountId={} sync mode updated", createdAccountId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EA-04: disconnect revokes token and clears credentials
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("EA-04: disconnect revokes token at Google and clears DB credentials")
    void ea04_disconnectRevokesAndClearsTokens() {
        assertThat(createdAccountId).isNotNull();

        doNothing().when(gmailApiClient).revokeToken(anyString());

        emailAccountService.disconnect(createdAccountId);

        // revokeToken must have been called once
        verify(gmailApiClient, times(1)).revokeToken(anyString());

        EmailAccount disconnected = emailAccountMapper.selectById(createdAccountId);
        assertThat(disconnected).isNotNull();
        assertThat(disconnected.getStatus()).isEqualTo("revoked");
        assertThat(disconnected.getAccessToken()).isNull();
        assertThat(disconnected.getRefreshToken()).isNull();
        assertThat(disconnected.getTokenExpiresAt()).isNull();

        log.info("EA-04 PASS: accountId={} disconnected, status=revoked", createdAccountId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EA-05: member management add / list / remove
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("EA-05: addMember / listMembers / removeMember round-trip")
    void ea05_memberManagementRoundTrip() throws Exception {
        // Create a fresh shared account for this test
        String sharedEmail = runId + "-mb@example.com";
        when(gmailApiClient.exchangeCode(anyString()))
                .thenReturn(Map.of(
                        "email",         sharedEmail,
                        "access_token",  "tok-access-mb",
                        "refresh_token", "tok-refresh-mb",
                        "expires_in",    "3600"
                ));

        EmailAccount account = emailAccountService.handleOAuthCallback(
                testTenantId, testUserId, "code-mb", EmailConstants.ACCOUNT_TYPE_SHARED);
        Long accountId = account.getId();

        // addMember: add secondUserId as member
        EmailAccountMember member = emailAccountService.addMember(
                accountId, secondUserId, EmailConstants.MEMBER_ROLE_MEMBER);
        assertThat(member.getId()).isNotNull();
        assertThat(member.getRole()).isEqualTo(EmailConstants.MEMBER_ROLE_MEMBER);

        // listMembers: should contain both testUserId (owner) and secondUserId (member)
        List<EmailAccountMember> members = emailAccountService.listMembers(accountId);
        assertThat(members).hasSizeGreaterThanOrEqualTo(2);
        assertThat(members.stream().map(EmailAccountMember::getUserId))
                .contains(testUserId, secondUserId);

        // Upgrade secondUserId to owner
        emailAccountService.addMember(accountId, secondUserId, EmailConstants.MEMBER_ROLE_OWNER);
        EmailAccountMember upgraded = emailAccountService.listMembers(accountId).stream()
                .filter(m -> secondUserId.equals(m.getUserId()))
                .findFirst()
                .orElseThrow();
        assertThat(upgraded.getRole()).isEqualTo(EmailConstants.MEMBER_ROLE_OWNER);

        // removeMember: remove secondUserId
        emailAccountService.removeMember(accountId, secondUserId);
        List<EmailAccountMember> afterRemove = emailAccountService.listMembers(accountId);
        assertThat(afterRemove.stream().map(EmailAccountMember::getUserId))
                .doesNotContain(secondUserId);

        // Clean up
        emailAccountMemberMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmailAccountMember>()
                        .eq(EmailAccountMember::getAccountId, accountId));
        emailAccountMapper.deleteById(accountId);

        log.info("EA-05 PASS: member management round-trip complete for accountId={}", accountId);
    }
}
