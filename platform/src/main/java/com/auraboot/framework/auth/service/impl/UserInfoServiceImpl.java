package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.UserInfoResponse;
import com.auraboot.framework.auth.service.UserInfoService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserPreferenceService;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserInfoServiceImpl implements UserInfoService {

    private final UserService userService;
    private final RoleMapper roleMapper;
    private final UserPermissionService userPermissionService;
    private final PermissionMapper permissionMapper;
    private final UserPreferenceService userPreferenceService;
    private final TenantPreferenceService tenantPreferenceService;

    @Autowired(required = false)
    private TenantMemberService tenantMemberService;

    @Override
    public UserInfoResponse buildCurrentUserInfo(Long userId, String userPid, Long tenantId) {
        // 1. User profile
        User user = userService.findByUserId(userId);
        UserInfoResponse.UserDTO userDTO = buildUserDTO(user, tenantId);

        // 2. Roles and permissions
        UserInfoResponse.PermissionsDTO permissionsDTO = buildPermissionsDTO(userId, tenantId);

        // 3. Preferences (user > tenant > default)
        UserInfoResponse.PreferencesDTO preferencesDTO = buildPreferencesDTO(userId, tenantId);

        return new UserInfoResponse(userDTO, permissionsDTO, preferencesDTO);
    }

    private UserInfoResponse.UserDTO buildUserDTO(User user, Long tenantId) {
        UserInfoResponse.UserDTO dto = new UserInfoResponse.UserDTO();
        dto.setId(String.valueOf(user.getId()));
        dto.setPid(user.getPid());
        dto.setName(user.getNickName() != null ? user.getNickName() : user.getUserName());
        dto.setEmail(user.getEmail());
        dto.setMobile(user.getMobile());
        dto.setTenantId(tenantId);
        if (tenantId != null && tenantMemberService != null) {
            dto.setTenantName(tenantMemberService.getTenantNameById(tenantId));
        }
        dto.setImgId(user.getImgId());
        return dto;
    }

    private UserInfoResponse.PermissionsDTO buildPermissionsDTO(Long userId, Long tenantId) {
        List<Role> roles = List.of();
        List<String> permissionCodes = List.of();

        if (tenantId != null) {
            Long memberId = MetaContext.getCurrentMemberId();
            roles = memberId != null ? roleMapper.findByMemberIdAndTenantId(memberId, tenantId) : List.of();

            boolean isAdmin = roles.stream()
                    .anyMatch(r -> "super_admin".equals(r.getCode()) || "tenant_admin".equals(r.getCode()));

            if (isAdmin) {
                List<Permission> allPermissions = permissionMapper.selectList(null);
                permissionCodes = allPermissions.stream()
                        .map(Permission::getCode)
                        .collect(Collectors.toList());

                // Fallback for bootstrap environments where tenant-scoped projection is incomplete
                if (permissionCodes.isEmpty()) {
                    permissionCodes = resolvePermissionsByUserId(userId);
                }
            } else {
                permissionCodes = resolvePermissionsByUserId(userId);
            }
        }

        List<UserInfoResponse.RoleDTO> roleDTOs = roles.stream()
                .map(UserInfoResponse.RoleDTO::fromEntity)
                .collect(Collectors.toList());

        return new UserInfoResponse.PermissionsDTO(roleDTOs, permissionCodes);
    }

    private List<String> resolvePermissionsByUserId(Long userId) {
        Set<Long> permissionIds = userPermissionService.getUserPermissionIds(userId);
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.findByIds(new ArrayList<>(permissionIds)).stream()
                .map(Permission::getCode)
                .collect(Collectors.toList());
    }

    private UserInfoResponse.PreferencesDTO buildPreferencesDTO(Long userId, Long tenantId) {
        Map<String, JsonNode> userPrefs = userPreferenceService.getPreferencesByPrefix(userId, "ui.");
        Map<String, JsonNode> tenantPrefs = tenantId != null
                ? tenantPreferenceService.getPreferencesByPrefix(tenantId, "ui.")
                : Map.of();

        UserInfoResponse.PreferencesDTO dto = new UserInfoResponse.PreferencesDTO();
        dto.setTimezone(resolvePreference(userPrefs, tenantPrefs, "ui.timezone",
                UserInfoResponse.PreferencesDTO.DEFAULT_TIMEZONE));
        dto.setDateFormat(resolvePreference(userPrefs, tenantPrefs, "ui.date.format",
                UserInfoResponse.PreferencesDTO.DEFAULT_DATE_FORMAT));
        dto.setDatetimeFormat(resolvePreference(userPrefs, tenantPrefs, "ui.datetime.format",
                UserInfoResponse.PreferencesDTO.DEFAULT_DATETIME_FORMAT));
        dto.setTimeFormat(resolvePreference(userPrefs, tenantPrefs, "ui.time.format",
                UserInfoResponse.PreferencesDTO.DEFAULT_TIME_FORMAT));
        return dto;
    }

    private String resolvePreference(Map<String, JsonNode> userPrefs,
                                      Map<String, JsonNode> tenantPrefs,
                                      String key, String defaultValue) {
        JsonNode userVal = userPrefs.get(key);
        if (userVal != null && !userVal.isNull()) {
            String text = userVal.asText().trim();
            if (!text.isEmpty()) return text;
        }
        JsonNode tenantVal = tenantPrefs.get(key);
        if (tenantVal != null && !tenantVal.isNull()) {
            String text = tenantVal.asText().trim();
            if (!text.isEmpty()) return text;
        }
        return defaultValue;
    }
}
