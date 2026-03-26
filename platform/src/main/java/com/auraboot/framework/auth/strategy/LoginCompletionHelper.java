package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;

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
    private final SessionManagementService sessionManagementService;
    private final PasswordManagementService passwordManagementService;

    /**
     * Complete the login flow after identity verification.
     *
     * @param user      the authenticated user entity
     * @param ipAddress client IP (may be null)
     * @param userAgent client User-Agent (may be null)
     * @return fully populated authentication response
     */
    public AuthenticationResponse completeLogin(User user, String ipAddress, String userAgent) {
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

        // 2. Resolve tenant info
        Long tenantId = null;
        String tenantStatus = "none";
        try {
            tenantId = tenantMemberService.getTenantIdByUserId(user.getId());
            if (tenantId != null) {
                TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
                if (tenantMember != null) {
                    tenantStatus = tenantMember.getStatus();
                    if ("active".equalsIgnoreCase(tenantStatus)) {
                        tenantStatus = "member";
                    }
                }
            }
            log.info("Found tenant {} with status {} for user {}", tenantId, tenantStatus, user.getId());
        } catch (Exception e) {
            log.warn("Failed to get tenant for user {}: {}", user.getId(), e.getMessage());
        }

        // 3. Generate JWT with security version
        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String jwt = jwtUtil.generateTokenWithTenantId(userDetails, user.getPid(), tenantId, securityVersion);

        // 4. Create session record
        try {
            sessionManagementService.createSession(user.getId(), jwt, ipAddress, userAgent);
        } catch (Exception e) {
            log.warn("Failed to create session record: {}", e.getMessage());
        }

        // 5. Build response and check password status
        AuthenticationResponse response = new AuthenticationResponse(
                jwt, user.getId(), user.getPid(),
                user.getNickName() != null ? user.getNickName() : user.getUserName(),
                tenantId, tenantStatus
        );

        if (Boolean.TRUE.equals(user.getMustChangePassword())
                || passwordManagementService.isPasswordExpired(user)) {
            response.setMustChangePassword(true);
        }

        return response;
    }
}
