package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.UserSpaceDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class UserSpaceService {
    private final TenantMemberService tenantMemberService;
    private final TenantService tenantService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final SystemModeService systemModeService;
    public List<UserSpaceDTO> listForUser(Long userId) {
        List<Long> tenantIds = tenantMemberService.getTenantIdsByUserId(userId);
        if (tenantIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UserSpaceDTO> spaces = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant == null) continue;

            boolean isSystem = "System".equals(tenant.getName());

            // Get member's roles in this tenant (resolve memberId → roleId → role code)
            TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
            Long memberId = tenantMember != null ? tenantMember.getId() : null;
            List<UserRole> userRoles = memberId != null
                    ? userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getMemberId, memberId)
                        .eq(UserRole::getTenantId, tenantId)
                        .eq(UserRole::getDeletedFlag, false))
                    : Collections.emptyList();
            List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
            List<String> roleCodes;
            if (roleIds.isEmpty()) {
                roleCodes = Collections.emptyList();
            } else {
                List<Role> roles = roleService.listByIds(roleIds);
                roleCodes = roles.stream()
                        .map(Role::getCode)
                        .filter(c -> c != null)
                        .toList();
            }

            if (systemModeService.isSingleTenant()) {
                Long defaultTenantId = systemModeService.getDefaultTenantId();
                boolean defaultBusinessSpace = !isSystem && tenantId.equals(defaultTenantId);
                boolean platformOperatorSpace = isSystem && roleCodes.contains("platform_admin");
                if (!defaultBusinessSpace && !platformOperatorSpace) {
                    continue;
                }
            }

            spaces.add(UserSpaceDTO.builder()
                    .tenantId(tenantId)
                    .tenantName(tenant.getName())
                    .tenantDisplayName(tenant.getDisplayName())
                    .spaceType(isSystem ? "platform" : "business")
                    .roleCodes(roleCodes)
                    .isDefault(!isSystem && spaces.stream().noneMatch(s -> "business".equals(s.getSpaceType())))
                    .build());
        }

        return spaces;
    }
}
