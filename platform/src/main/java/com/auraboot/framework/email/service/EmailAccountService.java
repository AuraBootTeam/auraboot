package com.auraboot.framework.email.service;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing connected Gmail accounts (personal and shared mailboxes).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Handle OAuth2 callback: exchange code → encrypt tokens → persist account.</li>
 *   <li>List accounts visible to a user (own personal + all tenant shared).</li>
 *   <li>Manage shared mailbox membership (add/remove members, set roles).</li>
 *   <li>Disconnect accounts: revoke token at Google, clear credentials, set status=revoked.</li>
 * </ul>
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAccountService {

    private final EmailAccountMapper        emailAccountMapper;
    private final EmailAccountMemberMapper  emailAccountMemberMapper;
    private final GmailApiClient            gmailApiClient;
    private final FieldEncryptionService    fieldEncryptionService;

    // ──────────────────────────────────────────────────────────────────────────
    // OAuth2 / Account lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Handles the OAuth2 callback: exchanges the authorization code for tokens,
     * creates or updates the {@link EmailAccount}, and (for shared accounts) adds
     * the connecting user as an owner member.
     *
     * @param tenantId    current tenant
     * @param userId      user who initiated the OAuth flow
     * @param code        authorization code from Google
     * @param accountType {@link EmailConstants#ACCOUNT_TYPE_PERSONAL} or
     *                    {@link EmailConstants#ACCOUNT_TYPE_SHARED}
     * @return the saved (or updated) account
     */
    @Transactional
    public EmailAccount handleOAuthCallback(Long tenantId, Long userId,
                                            String code, String accountType) {
        Map<String, String> tokens;
        try {
            tokens = gmailApiClient.exchangeCode(code);
        } catch (IOException e) {
            log.error("Gmail code exchange failed for userId={}: {}", userId, e.getMessage());
            throw new BusinessException("Failed to exchange Gmail authorization code: " + e.getMessage());
        }

        String email       = tokens.get("email");
        String accessToken = tokens.get("access_token");
        String refreshToken = tokens.get("refresh_token");
        long   expiresIn   = Long.parseLong(tokens.getOrDefault("expires_in", "3600"));
        Instant expiresAt  = Instant.now().plusSeconds(expiresIn);

        // Encrypt tokens before persistence
        String encAccessToken  = fieldEncryptionService.encrypt(accessToken);
        String encRefreshToken = fieldEncryptionService.encrypt(refreshToken);

        // Upsert: find existing account for this email+tenant, else create
        EmailAccount account = findExistingAccount(tenantId, email);
        if (account == null) {
            account = new EmailAccount();
            account.setTenantId(tenantId);
            account.setEmailAddress(email);
            account.setProvider(EmailConstants.PROVIDER_GMAIL);
            account.setSyncMode(EmailConstants.SYNC_MODE_MANUAL);
        }

        account.setUserId(userId);
        account.setAccountType(accountType);
        account.setDisplayName(email);
        account.setAccessToken(encAccessToken);
        account.setRefreshToken(encRefreshToken);
        account.setTokenExpiresAt(expiresAt);
        account.setStatus(EmailConstants.ACCOUNT_STATUS_ACTIVE);

        if (account.getId() == null) {
            emailAccountMapper.insert(account);
            log.info("Created new email account: id={}, email={}, type={}", account.getId(), email, accountType);
        } else {
            emailAccountMapper.updateById(account);
            log.info("Updated email account: id={}, email={}, type={}", account.getId(), email, accountType);
        }

        // For shared accounts, ensure the connecting user is recorded as owner
        if (EmailConstants.ACCOUNT_TYPE_SHARED.equals(accountType)) {
            ensureOwnerMember(account.getId(), userId);
        }

        return account;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Query
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists accounts visible to the given user:
     * <ul>
     *   <li>Personal accounts owned by this user</li>
     *   <li>All shared accounts in the tenant</li>
     * </ul>
     *
     * @param tenantId current tenant
     * @param userId   current user
     * @return combined list, tokens are NOT decrypted (caller should mask before returning to client)
     */
    public List<EmailAccount> listAccounts(Long tenantId, Long userId) {
        List<EmailAccount> allActive = emailAccountMapper.findAllActive(tenantId);

        return allActive.stream()
                .filter(acc ->
                        // personal accounts belong to this user
                        (EmailConstants.ACCOUNT_TYPE_PERSONAL.equals(acc.getAccountType())
                                && userId.equals(acc.getUserId()))
                        // shared accounts are visible to everyone in the tenant
                        || EmailConstants.ACCOUNT_TYPE_SHARED.equals(acc.getAccountType()))
                .collect(Collectors.toList());
    }

    /**
     * Returns the account by ID.
     *
     * @param id account ID
     * @return account entity, or {@code null} if not found
     */
    public EmailAccount getAccount(Long id) {
        return emailAccountMapper.selectById(id);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mutation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Updates the sync mode for an account.
     *
     * @param id       account ID
     * @param syncMode {@link EmailConstants#SYNC_MODE_MANUAL} or
     *                 {@link EmailConstants#SYNC_MODE_AUTO}
     */
    @Transactional
    public void updateSyncMode(Long id, String syncMode) {
        EmailAccount account = requireAccount(id);
        account.setSyncMode(syncMode);
        emailAccountMapper.updateById(account);
        log.info("Updated sync mode: accountId={}, syncMode={}", id, syncMode);
    }

    /**
     * Disconnects an account: revokes the token at Google, clears credentials,
     * and marks the account as {@code revoked}.
     *
     * @param id account ID
     */
    @Transactional
    public void disconnect(Long id) {
        EmailAccount account = requireAccount(id);

        // Revoke at Google (best-effort)
        if (account.getRefreshToken() != null && !account.getRefreshToken().isBlank()) {
            gmailApiClient.revokeToken(account.getRefreshToken());
        }

        // Clear tokens and mark revoked using explicit SQL (updateById skips null fields)
        emailAccountMapper.revokeTokens(account.getId());

        log.info("Disconnected email account: id={}, email={}", id, account.getEmailAddress());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Member management (shared mailboxes)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Adds a member to a shared mailbox, or updates their role if already a member.
     *
     * @param accountId shared account ID
     * @param userId    user to add
     * @param role      {@link EmailConstants#MEMBER_ROLE_OWNER} or
     *                  {@link EmailConstants#MEMBER_ROLE_MEMBER}
     */
    @Transactional
    public EmailAccountMember addMember(Long accountId, Long userId, String role) {
        // Upsert by accountId + userId
        EmailAccountMember existing = findMember(accountId, userId);
        if (existing != null) {
            existing.setRole(role);
            emailAccountMemberMapper.updateById(existing);
            log.info("Updated member role: accountId={}, userId={}, role={}", accountId, userId, role);
            return existing;
        }

        EmailAccountMember member = new EmailAccountMember();
        member.setAccountId(accountId);
        member.setUserId(userId);
        member.setRole(role);
        member.setAssignmentWeight(1);
        emailAccountMemberMapper.insert(member);
        log.info("Added member: accountId={}, userId={}, role={}", accountId, userId, role);
        return member;
    }

    /**
     * Removes a member from a shared mailbox.
     *
     * @param accountId shared account ID
     * @param userId    user to remove
     */
    @Transactional
    public void removeMember(Long accountId, Long userId) {
        emailAccountMemberMapper.delete(new LambdaQueryWrapper<EmailAccountMember>()
                .eq(EmailAccountMember::getAccountId, accountId)
                .eq(EmailAccountMember::getUserId, userId));
        log.info("Removed member: accountId={}, userId={}", accountId, userId);
    }

    /**
     * Lists all members of a shared mailbox ordered by assignment weight.
     *
     * @param accountId shared account ID
     * @return ordered list of members
     */
    public List<EmailAccountMember> listMembers(Long accountId) {
        return emailAccountMemberMapper.findByAccountId(accountId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private EmailAccount requireAccount(Long id) {
        EmailAccount account = emailAccountMapper.selectById(id);
        if (account == null) {
            throw new BusinessException("Email account not found: " + id);
        }
        return account;
    }

    private EmailAccount findExistingAccount(Long tenantId, String email) {
        return emailAccountMapper.selectOne(new LambdaQueryWrapper<EmailAccount>()
                .eq(EmailAccount::getTenantId, tenantId)
                .eq(EmailAccount::getEmailAddress, email)
                .last("LIMIT 1"));
    }

    private EmailAccountMember findMember(Long accountId, Long userId) {
        return emailAccountMemberMapper.selectOne(new LambdaQueryWrapper<EmailAccountMember>()
                .eq(EmailAccountMember::getAccountId, accountId)
                .eq(EmailAccountMember::getUserId, userId));
    }

    private void ensureOwnerMember(Long accountId, Long userId) {
        EmailAccountMember existing = findMember(accountId, userId);
        if (existing == null) {
            addMember(accountId, userId, EmailConstants.MEMBER_ROLE_OWNER);
        }
    }
}
