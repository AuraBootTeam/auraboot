package com.auraboot.framework.email;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.service.EmailAccountService;
import com.auraboot.framework.email.service.GmailApiClient;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link EmailAccountService} — no Spring, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailAccountService Unit Tests")
class EmailAccountServiceUnitTest {

    @Mock private EmailAccountMapper accountMapper;
    @Mock private EmailAccountMemberMapper memberMapper;
    @Mock private GmailApiClient gmailApiClient;
    @Mock private FieldEncryptionService fieldEncryptionService;

    private EmailAccountService service;

    @BeforeEach
    void setUp() {
        service = new EmailAccountService(accountMapper, memberMapper,
                gmailApiClient, fieldEncryptionService);
    }

    private static EmailAccount account(Long id, String type, Long userId, String email) {
        EmailAccount a = new EmailAccount();
        a.setId(id);
        a.setAccountType(type);
        a.setUserId(userId);
        a.setEmailAddress(email);
        return a;
    }

    @Test
    @DisplayName("listAccounts: shared accounts visible regardless of user; personal only own")
    void listAccounts_filtersCorrectly() {
        EmailAccount p1 = account(1L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 100L, "p1@x.com");
        EmailAccount p2 = account(2L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 200L, "p2@x.com");
        EmailAccount s1 = account(3L, EmailConstants.ACCOUNT_TYPE_SHARED, 999L, "shared@x.com");
        when(accountMapper.findAllActive(7L)).thenReturn(List.of(p1, p2, s1));

        List<EmailAccount> result = service.listAccounts(7L, 100L);
        assertThat(result).extracting(EmailAccount::getId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    @DisplayName("getAccount delegates to mapper.selectById")
    void getAccount_delegates() {
        EmailAccount a = account(5L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 1L, "a@b");
        when(accountMapper.selectById(5L)).thenReturn(a);
        assertThat(service.getAccount(5L)).isSameAs(a);
    }

    @Test
    @DisplayName("updateSyncMode loads + sets syncMode + updates")
    void updateSyncMode_happy() {
        EmailAccount a = account(6L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 1L, "a@b");
        when(accountMapper.selectById(6L)).thenReturn(a);

        service.updateSyncMode(6L, EmailConstants.SYNC_MODE_AUTO);

        assertThat(a.getSyncMode()).isEqualTo(EmailConstants.SYNC_MODE_AUTO);
        verify(accountMapper).updateById(a);
    }

    @Test
    @DisplayName("updateSyncMode throws BusinessException when account missing")
    void updateSyncMode_missing_throws() {
        when(accountMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateSyncMode(99L, "auto"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("disconnect revokes refresh token at Google and clears credentials")
    void disconnect_revokesAndClears() {
        EmailAccount a = account(7L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 1L, "a@b");
        a.setRefreshToken("encryptedRefresh");
        when(accountMapper.selectById(7L)).thenReturn(a);

        service.disconnect(7L);

        verify(gmailApiClient).revokeToken("encryptedRefresh");
        verify(accountMapper).revokeTokens(7L);
    }

    @Test
    @DisplayName("disconnect skips Google revoke when refresh token is blank")
    void disconnect_skipsRevokeWhenBlank() {
        EmailAccount a = account(8L, EmailConstants.ACCOUNT_TYPE_PERSONAL, 1L, "a@b");
        a.setRefreshToken("");
        when(accountMapper.selectById(8L)).thenReturn(a);

        service.disconnect(8L);

        verify(gmailApiClient, never()).revokeToken(anyString());
        verify(accountMapper).revokeTokens(8L);
    }

    @Test
    @DisplayName("addMember updates role when member already exists")
    void addMember_existing_updatesRole() {
        EmailAccountMember existing = new EmailAccountMember();
        existing.setId(50L);
        existing.setAccountId(10L);
        existing.setUserId(20L);
        existing.setRole(EmailConstants.MEMBER_ROLE_MEMBER);

        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        EmailAccountMember result = service.addMember(10L, 20L, EmailConstants.MEMBER_ROLE_OWNER);

        assertThat(result.getRole()).isEqualTo(EmailConstants.MEMBER_ROLE_OWNER);
        verify(memberMapper).updateById(existing);
        verify(memberMapper, never()).insert(any(EmailAccountMember.class));
    }

    @Test
    @DisplayName("addMember inserts new member with weight=1 when none exists")
    void addMember_new_inserts() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        EmailAccountMember result = service.addMember(10L, 30L, EmailConstants.MEMBER_ROLE_MEMBER);

        assertThat(result.getAccountId()).isEqualTo(10L);
        assertThat(result.getUserId()).isEqualTo(30L);
        assertThat(result.getAssignmentWeight()).isEqualTo(1);
        verify(memberMapper).insert(any(EmailAccountMember.class));
    }

    @Test
    @DisplayName("removeMember calls memberMapper.delete with criteria")
    void removeMember_delegates() {
        service.removeMember(11L, 22L);
        verify(memberMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("listMembers delegates to mapper.findByAccountId")
    void listMembers_delegates() {
        EmailAccountMember m = new EmailAccountMember();
        when(memberMapper.findByAccountId(33L)).thenReturn(List.of(m));
        assertThat(service.listMembers(33L)).hasSize(1);
    }

    @Test
    @DisplayName("handleOAuthCallback creates new account and adds owner for shared type")
    void handleOAuthCallback_newSharedAccount_addsOwner() throws IOException {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("email", "user@x.com");
        tokens.put("access_token", "AT");
        tokens.put("refresh_token", "RT");
        tokens.put("expires_in", "3600");
        when(gmailApiClient.exchangeCode("CODE")).thenReturn(tokens);
        when(fieldEncryptionService.encrypt("AT")).thenReturn("EAT");
        when(fieldEncryptionService.encrypt("RT")).thenReturn("ERT");
        // No existing account
        when(accountMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // No existing member
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        EmailAccount result = service.handleOAuthCallback(7L, 99L, "CODE",
                EmailConstants.ACCOUNT_TYPE_SHARED);

        assertThat(result.getEmailAddress()).isEqualTo("user@x.com");
        assertThat(result.getAccessToken()).isEqualTo("EAT");
        assertThat(result.getRefreshToken()).isEqualTo("ERT");
        assertThat(result.getStatus()).isEqualTo(EmailConstants.ACCOUNT_STATUS_ACTIVE);
        verify(accountMapper).insert(any(EmailAccount.class));
        // ensureOwnerMember → addMember → insert new
        verify(memberMapper).insert(any(EmailAccountMember.class));
    }

    @Test
    @DisplayName("handleOAuthCallback updates existing account when found")
    void handleOAuthCallback_updateExisting() throws IOException {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("email", "u@x");
        tokens.put("access_token", "AT");
        tokens.put("refresh_token", "RT");
        // No expires_in → fallback default
        when(gmailApiClient.exchangeCode(eq("C2"))).thenReturn(tokens);
        when(fieldEncryptionService.encrypt(anyString())).thenReturn("ENC");

        EmailAccount existing = new EmailAccount();
        existing.setId(77L);
        existing.setEmailAddress("u@x");
        existing.setTenantId(7L);
        when(accountMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        EmailAccount result = service.handleOAuthCallback(7L, 99L, "C2",
                EmailConstants.ACCOUNT_TYPE_PERSONAL);

        assertThat(result.getId()).isEqualTo(77L);
        verify(accountMapper).updateById(existing);
        verify(accountMapper, never()).insert(any(EmailAccount.class));
        // Personal type → no owner-member ensure
        verify(memberMapper, never()).insert(any(EmailAccountMember.class));
    }

    @Test
    @DisplayName("handleOAuthCallback wraps IOException as BusinessException")
    void handleOAuthCallback_ioFailure() throws IOException {
        when(gmailApiClient.exchangeCode("BAD")).thenThrow(new IOException("oops"));
        assertThatThrownBy(() ->
                service.handleOAuthCallback(7L, 1L, "BAD",
                        EmailConstants.ACCOUNT_TYPE_PERSONAL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Failed to exchange");
    }
}
