package com.auraboot.framework.email.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.service.EmailAccountService;
import com.auraboot.framework.email.service.GmailApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller for Gmail account OAuth2 binding and management.
 *
 * <p>The callback endpoint {@code /api/email/oauth2/callback} is whitelisted from JWT auth
 * because it receives the OAuth redirect from Google (no user session at this point).
 * The {@code state} parameter carries the encoded identity and account type.
 *
 * @since 6.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/email")
@RequiredArgsConstructor
@Tag(name = "Email Accounts", description = "Gmail OAuth2 account binding and shared mailbox management")
public class EmailAccountController {

    private static final String STATE_SEPARATOR = ":";
    private static final Set<String> ALLOWED_ACCOUNT_TYPES = Set.of(
            EmailConstants.ACCOUNT_TYPE_PERSONAL,
            EmailConstants.ACCOUNT_TYPE_SHARED);

    private final EmailAccountService   emailAccountService;
    private final GmailApiClient        gmailApiClient;
    private final FieldEncryptionService fieldEncryptionService;

    // ──────────────────────────────────────────────────────────────────────────
    // OAuth2 flow
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Initiates the Gmail OAuth2 authorization flow.
     *
     * <p>Returns a JSON payload with the authorization URL and opaque state token.
     * The frontend should redirect the user's browser to {@code url}.
     *
     * @param type {@code personal} or {@code shared}
     * @return {@code {url: "https://accounts.google.com/…", state: "…"}}
     */
    @GetMapping("/oauth2/authorize")
    @Operation(summary = "Start Gmail OAuth2 authorization flow")
    public ApiResponse<Map<String, String>> authorize(
            @RequestParam(defaultValue = EmailConstants.ACCOUNT_TYPE_PERSONAL) String type) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId   = MetaContext.get().getUserId();

        // Encode tenantId:userId:accountType:nonce into state so callback can recover identity
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String state = tenantId + STATE_SEPARATOR + userId + STATE_SEPARATOR + type + STATE_SEPARATOR + nonce;
        // Encrypt state so it cannot be tampered with
        String encryptedState = fieldEncryptionService.encrypt(state);

        String authUrl = gmailApiClient.buildAuthorizationUrl(encryptedState);
        log.info("OAuth2 authorize initiated: userId={}, type={}", userId, type);

        return ApiResponse.ok(Map.of("url", authUrl, "state", encryptedState));
    }

    /**
     * Handles the OAuth2 callback from Google.
     *
     * <p>This endpoint is whitelisted from JWT auth. It validates the state, exchanges
     * the authorization code, and redirects the browser to {@code /email/settings}.
     */
    @GetMapping("/oauth2/callback")
    @Operation(summary = "Handle Gmail OAuth2 callback (whitelisted from JWT auth)")
    @SuppressWarnings("java/user-controlled-bypass")
    public void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response) throws IOException {

        if (error != null) {
            log.warn("Gmail OAuth callback error: {}", error);
            response.sendRedirect("/email/settings?error=" + error);
            return;
        }

        if (code == null || state == null) {
            log.warn("Gmail OAuth callback missing code or state");
            response.sendRedirect("/email/settings?error=missing_params");
            return;
        }

        try {
            // Decrypt and parse state
            String plainState = fieldEncryptionService.decrypt(state);
            String[] parts    = plainState.split(STATE_SEPARATOR, 4);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Malformed state token");
            }
            Long   tenantId   = Long.parseLong(parts[0]);
            Long   userId     = Long.parseLong(parts[1]);
            String accountType = parts[2];
            if (!ALLOWED_ACCOUNT_TYPES.contains(accountType)) {
                throw new IllegalArgumentException("Invalid account type");
            }

            // code/state are user-supplied OAuth parameters, but state is an
            // encrypted server-issued envelope. Only after decryption and
            // accountType allow-list validation do we exchange the OAuth code.
            emailAccountService.handleOAuthCallback(tenantId, userId, code, accountType);

            log.info("Gmail OAuth callback completed: userId={}, type={}", userId, accountType);
            response.sendRedirect("/email/settings?connected=true");

        } catch (IllegalArgumentException e) {
            log.warn("Gmail OAuth callback invalid state: {}", e.getMessage());
            response.sendRedirect("/email/settings?error=invalid_state");
        } catch (Exception e) {
            log.error("Gmail OAuth callback unexpected error", e);
            response.sendRedirect("/email/settings?error=server_error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Account CRUD
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists all email accounts visible to the current user.
     * Tokens are masked before returning to the client.
     */
    @GetMapping("/accounts")
    @Operation(summary = "List connected email accounts for current user")
    public ApiResponse<List<EmailAccount>> listAccounts() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId   = MetaContext.get().getUserId();

        List<EmailAccount> accounts = emailAccountService.listAccounts(tenantId, userId);
        accounts.forEach(this::maskTokens);
        return ApiResponse.ok(accounts);
    }

    /**
     * Updates the sync mode for an email account.
     *
     * @param id       account ID
     * @param syncMode {@code manual} or {@code auto}
     */
    @PutMapping("/accounts/{id}/sync-mode")
    @Operation(summary = "Update sync mode for an email account")
    public ApiResponse<Void> updateSyncMode(@PathVariable Long id,
                                             @RequestParam String syncMode) {
        emailAccountService.updateSyncMode(id, syncMode);
        return ApiResponse.ok();
    }

    /**
     * Disconnects (revokes) an email account.
     * Revokes the token at Google and clears credentials in the database.
     */
    @DeleteMapping("/accounts/{id}")
    @Operation(summary = "Disconnect an email account (revoke OAuth tokens)")
    public ApiResponse<Void> disconnect(@PathVariable Long id) {
        emailAccountService.disconnect(id);
        return ApiResponse.ok();
    }

    /**
     * Triggers an immediate manual sync for an email account.
     * Placeholder — full implementation in Task 4 (EmailSyncService).
     */
    @PostMapping("/accounts/{id}/sync")
    @Operation(summary = "Trigger immediate sync for an email account (placeholder)")
    public ApiResponse<Map<String, Object>> syncNow(@PathVariable Long id) {
        log.info("Manual sync requested for accountId={}", id);
        return ApiResponse.ok(Map.of("message", "Sync scheduled", "accountId", id));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shared mailbox member management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists all members of a shared mailbox.
     */
    @GetMapping("/accounts/{id}/members")
    @Operation(summary = "List members of a shared mailbox")
    public ApiResponse<List<EmailAccountMember>> listMembers(@PathVariable Long id) {
        return ApiResponse.ok(emailAccountService.listMembers(id));
    }

    /**
     * Adds a user to a shared mailbox.
     *
     * @param id     account ID
     * @param userId user to add
     * @param role   {@code owner} or {@code member}
     */
    @PostMapping("/accounts/{id}/members")
    @Operation(summary = "Add a member to a shared mailbox")
    public ApiResponse<EmailAccountMember> addMember(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestParam(defaultValue = EmailConstants.MEMBER_ROLE_MEMBER) String role) {

        EmailAccountMember member = emailAccountService.addMember(id, userId, role);
        return ApiResponse.ok(member);
    }

    /**
     * Removes a user from a shared mailbox.
     *
     * @param id     account ID
     * @param userId user to remove
     */
    @DeleteMapping("/accounts/{id}/members")
    @Operation(summary = "Remove a member from a shared mailbox")
    public ApiResponse<Void> removeMember(@PathVariable Long id, @RequestParam Long userId) {
        emailAccountService.removeMember(id, userId);
        return ApiResponse.ok();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Clears tokens from the account object before sending to the client. */
    private void maskTokens(EmailAccount account) {
        account.setAccessToken(null);
        account.setRefreshToken(null);
    }
}
