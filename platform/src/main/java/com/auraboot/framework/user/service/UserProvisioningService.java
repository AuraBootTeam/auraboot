package com.auraboot.framework.user.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * User Provisioning Service — creates users and assigns them to tenants with roles.
 *
 * <p>This is the "Admin Create User" entry mode, used in:
 * <ul>
 *   <li>Private deployment / ERP / internal systems (primary path)</li>
 *   <li>E2E test setup scripts</li>
 *   <li>Bulk user import</li>
 * </ul>
 *
 * <p>Distinct from Self-Registration (public cloud) and SSO JIT Provisioning (enterprise).
 *
 * @since 7.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserService userService;
    private final TenantMemberService tenantMemberService;
    private final RoleService roleService;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$";

    /**
     * Provision a new user: create account + add to tenant + assign roles.
     *
     * @param request   provisioning details (email, name, password, roles)
     * @param tenantId  target tenant (from MetaContext)
     * @param creatorId the admin user performing the provisioning
     * @return provisioning result with user details
     */
    @Transactional(rollbackFor = Exception.class)
    public UserProvisionResponse provision(UserProvisionRequest request, Long tenantId, Long creatorId) {
        // Quota enforcement is handled by QuotaEnforcementAspect (AOP) on addMember()
        // when auraboot.quota.enforcement.enabled=true

        // 1. Determine password
        String password = request.getInitialPassword();
        String temporaryPassword = null;
        boolean mustChangePassword = false;

        if (password == null || password.isBlank()) {
            temporaryPassword = generateTemporaryPassword(12);
            password = temporaryPassword;
            mustChangePassword = true;
        }

        // 2. Create user account
        User user;
        try {
            user = userService.signUp(request.getEmail(), password, request.getDisplayName());
        } catch (Exception e) {
            // Check if user already exists
            User existing = userService.findByEmail(request.getEmail());
            if (existing != null) {
                throw new BusinessException("User already exists: " + request.getEmail());
            }
            throw e;
        }

        if (mustChangePassword) {
            user.setMustChangePassword(true);
            userService.update(user);
        }

        // 3. Add to tenant as active member
        try {
            tenantMemberService.addMember(user.getId(), tenantId, "active");
        } catch (BusinessException e) {
            // Already a member — acceptable for idempotent provisioning
            log.info("User {} already a member of tenant {}, continuing with role assignment",
                    request.getEmail(), tenantId);
        }

        // 4. Resolve member ID and assign roles
        com.auraboot.framework.tenant.dao.entity.TenantMember tenantMember =
                tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        Long memberId = tenantMember != null ? tenantMember.getId() : null;
        List<String> assignedRoles = assignRoles(request, memberId, tenantId);

        log.info("User provisioned: email={}, userId={}, tenantId={}, roles={}",
                request.getEmail(), user.getId(), tenantId, assignedRoles);

        return UserProvisionResponse.builder()
                .userId(user.getId())
                .userPid(user.getPid())
                .email(user.getEmail())
                .displayName(request.getDisplayName())
                .tenantId(tenantId)
                .assignedRoles(assignedRoles)
                .mustChangePassword(mustChangePassword)
                .temporaryPassword(temporaryPassword)
                .build();
    }

    private List<String> assignRoles(UserProvisionRequest request, Long memberId, Long tenantId) {
        List<String> assigned = new ArrayList<>();
        if (memberId == null) {
            log.warn("Cannot assign roles: memberId is null for tenant {}", tenantId);
            return assigned;
        }

        List<String> roleCodes = request.getRoleCodes();

        if (roleCodes == null || roleCodes.isEmpty()) {
            Role defaultRole = roleService.findDefaultRole(tenantId);
            if (defaultRole != null) {
                roleService.assignRoleToMember(memberId, defaultRole.getId(), tenantId);
                assigned.add(defaultRole.getCode());
            }
        } else {
            List<Role> tenantRoles = roleService.findByTenantId(tenantId);
            for (String code : roleCodes) {
                Role role = tenantRoles.stream()
                        .filter(r -> code.equals(r.getCode()))
                        .findFirst()
                        .orElse(null);
                if (role != null) {
                    roleService.assignRoleToMember(memberId, role.getId(), tenantId);
                    assigned.add(code);
                } else {
                    log.warn("Role code '{}' not found in tenant {}, skipping", code, tenantId);
                }
            }
        }

        return assigned;
    }

    private String generateTemporaryPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
