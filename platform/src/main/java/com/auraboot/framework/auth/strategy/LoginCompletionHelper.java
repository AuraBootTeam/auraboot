package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.dto.LoginContextRef;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.constant.ExecutionScope;
import com.auraboot.framework.auth.constant.SessionStage;
import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;

/**
 * Shared login-completion pipeline used by all AuthStrategy implementations.
 * <p>
 * After each strategy has verified the user's identity (password check, OTP match, OAuth token, etc.),
 * it delegates to this helper to:
 * <ol>
 *   <li>Resolve tenant membership and status</li>
 *   <li>Generate a JWT with the user's security version</li>
 *   <li>Create a server-side session record</li>
 *   <li>Flag mustChangePassword / passwordExpired</li>
 * </ol>
 *
 * @since 7.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginCompletionHelper {

    private final JwtUtil jwtUtil;
    private final TenantMemberService tenantMemberService;
    private final TenantService tenantService;
    private final SessionManagementService sessionManagementService;
    private final PasswordManagementService passwordManagementService;
    private final UserApplicationPreferenceService userApplicationPreferenceService;

    @Autowired(required = false)
    private LoginApplicationChannelMapper loginApplicationChannelMapper;

    /**
     * Complete the login flow after identity verification.
     *
     * @param user      the authenticated user entity
     * @param ipAddress client IP (may be null)
     * @param userAgent client User-Agent (may be null)
     * @return fully populated authentication response
     */
    public AuthenticationResponse completeLogin(User user, String ipAddress, String userAgent) {
        return completeLogin(user, null, ipAddress, userAgent);
    }

    /** Complete login using the server-resolved application, channel, and tenant context. */
    public AuthenticationResponse completeLogin(
            User user,
            FederatedLoginContext federatedContext,
            String ipAddress,
            String userAgent) {
        // 1. Build a lightweight UserDetails for JWT generation
        CustomUserDetails userDetails = new CustomUserDetails(
                user.getEmail(),
                user.getPassword() != null ? user.getPassword() : "",
                user.getId(),
                user.getPid(),
                Collections.singletonList(new SimpleGrantedAuthority("role_user")),
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled()
        );

        // 2. Resolve the application before tenant routing. Application-scoped preferences are
        // intentionally available before a tenant has been selected.
        String applicationCode = federatedContext == null || federatedContext.getApplicationCode() == null
                ? "business-web"
                : federatedContext.getApplicationCode();
        String channelCode = federatedContext == null || federatedContext.getLoginChannelCode() == null
                ? "default-business-web"
                : federatedContext.getLoginChannelCode();
        LoginContextRef loginContext = loginApplicationChannelMapper == null
                ? null
                : loginApplicationChannelMapper.resolveLoginContext(applicationCode, channelCode,
                        federatedContext == null ? null : federatedContext.getTenantId());

        // 3. Resolve tenant info and member ID. A stored tenant is only a routing hint: it must
        // still be present in the user's current active memberships.
        Long tenantId = null;
        Long memberId = null;
        String tenantStatus = "none";
        String nextAction = "BIND_SCHOOL";
        try {
            if (federatedContext != null && federatedContext.getTenantId() != null) {
                tenantId = federatedContext.getTenantId();
            } else if (loginContext == null) {
                // Compatibility path for installations that have not configured login applications.
                tenantId = tenantMemberService.getTenantIdByUserId(user.getId());
            } else {
                List<Long> activeTenantIds = tenantMemberService.getTenantIdsByUserId(user.getId());
                if (activeTenantIds.size() == 1) {
                    tenantId = activeTenantIds.getFirst();
                } else if (activeTenantIds.size() > 1) {
                    String recentTenantPid = getLastTenantPid(user.getId(), loginContext.getApplicationId());
                    Tenant recentTenant = recentTenantPid == null ? null : tenantService.findByPid(recentTenantPid);
                    if (recentTenant != null && activeTenantIds.contains(recentTenant.getId())) {
                        tenantId = recentTenant.getId();
                    } else {
                        nextAction = "SELECT_SCHOOL";
                    }
                }
            }
            if (tenantId != null) {
                TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
                if (tenantMember != null) {
                    memberId = tenantMember.getId();
                    tenantStatus = tenantMember.getStatus();
                    if ("active".equalsIgnoreCase(tenantStatus)) {
                        tenantStatus = "member";
                        nextAction = "ENTER";
                    }
                }
            }
            log.info("Found tenant {} with status {} and memberId {} for user {}", tenantId, tenantStatus, memberId, user.getId());
        } catch (Exception e) {
            log.warn("Failed to get tenant for user {}: {}", user.getId(), e.getMessage());
        }

        // A suspended organization must not be able to log in. The gate reads the TENANT's status
        // (the organization), not the member's: a member row can be "active" inside an org that has
        // been suspended platform-side, and the block above only ever inspected the member row and
        // wrote it to a log. Resolved OUTSIDE the swallow-catch on purpose — a genuine suspension
        // must propagate, not be logged and ignored like a lookup blip — and placed before any JWT
        // or session is minted, so a suspended tenant leaves with an error, never a usable token.
        // Users with no tenant (tenantId == null) are unaffected.
        if (tenantId != null) {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant != null && StatusConstants.SUSPENDED.equalsIgnoreCase(tenant.getStatus())) {
                log.warn("Login refused for user {}: tenant {} is suspended", user.getId(), tenantId);
                throw BusinessException.i18n("tenant.suspended");
            }
        }

        // Set memberId on userDetails for downstream use
        userDetails.setMemberId(memberId);

        // Persist only a validated active tenant. Failure is non-fatal because this is a routing
        // convenience, never part of the authorization decision.
        if (tenantId != null && "ENTER".equals(nextAction) && loginContext != null) {
            rememberLastTenant(user.getId(), loginContext.getApplicationId(), tenantId);
        }

        // 4. Generate JWT with security version and memberId
        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String jwt;
        if (loginApplicationChannelMapper != null && tenantId != null) {
            LoginContextRef tenantLoginContext = loginApplicationChannelMapper.resolveLoginContext(
                    applicationCode, channelCode, tenantId);
            if (tenantLoginContext != null) {
                loginContext = tenantLoginContext;
            }
        }
        if (loginContext == null) {
            jwt = jwtUtil.generateTokenWithTenantId(
                    userDetails, user.getPid(), tenantId, memberId, securityVersion);
        } else {
            jwt = jwtUtil.generateTokenWithContext(
                    userDetails,
                    user.getPid(),
                    new SessionTokenContext(
                            tenantId,
                            memberId,
                            loginContext.getApplicationId(),
                            loginContext.getLoginChannelId(),
                            tenantId == null ? null : ExecutionScope.TENANT,
                            null,
                            null,
                            tenantId == null ? SessionStage.ONBOARDING : SessionStage.READY,
                            1,
                            securityVersion));
        }

        // 4. Create session record — non-fatal; login should succeed even if session persistence fails
        // CATCH: non-transactional auxiliary operation — session creation failure must not block login
        try {
            sessionManagementService.createSession(user.getId(), jwt, ipAddress, userAgent);
        } catch (Exception e) {
            log.warn("Session creation failed for user {} — login will proceed without session record: {}",
                    user.getId(), e.getMessage());
        }

        // 5. Build response and check password status
        AuthenticationResponse response = new AuthenticationResponse(
                jwt, user.getId(), user.getPid(),
                user.getNickName() != null ? user.getNickName() : user.getUserName(),
                tenantId, tenantStatus
        );
        response.setNextAction(nextAction);

        if (Boolean.TRUE.equals(user.getMustChangePassword())
                || passwordManagementService.isPasswordExpired(user)) {
            response.setMustChangePassword(true);
        }

        return response;
    }

    private String getLastTenantPid(Long userId, Long applicationId) {
        try {
            return userApplicationPreferenceService.getLastTenantPid(userId, applicationId);
        } catch (RuntimeException e) {
            log.warn("Could not read last-tenant preference for user {} application {}: {}",
                    userId, applicationId, e.getMessage());
            return null;
        }
    }

    private void rememberLastTenant(Long userId, Long applicationId, Long tenantId) {
        try {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant != null && tenant.getPid() != null) {
                userApplicationPreferenceService.setLastTenant(userId, applicationId, tenant.getPid());
            }
        } catch (RuntimeException e) {
            log.warn("Could not persist last-tenant preference for user {} application {}: {}",
                    userId, applicationId, e.getMessage());
        }
    }
}
