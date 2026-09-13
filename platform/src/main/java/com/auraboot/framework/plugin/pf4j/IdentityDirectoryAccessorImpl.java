package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Platform-owned adapter that keeps identity entities and mappers out of plugins. */
@Service
public class IdentityDirectoryAccessorImpl implements IdentityDirectoryAccessor {
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final TenantMemberService tenantMemberService;
    private final UserMapper userMapper;
    private final UserService userService;

    public IdentityDirectoryAccessorImpl(RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                                         TenantMemberService tenantMemberService, UserMapper userMapper,
                                         UserService userService) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.tenantMemberService = tenantMemberService;
        this.userMapper = userMapper;
        this.userService = userService;
    }

    @Override
    public List<String> userPidsForRole(Long tenantId, String roleIdOrCode) {
        if (tenantId == null || roleIdOrCode == null || roleIdOrCode.isBlank()) return List.of();
        Long roleId;
        try {
            roleId = Long.parseLong(roleIdOrCode.trim());
        } catch (NumberFormatException ignored) {
            roleId = roleMapper.findIdByCode(tenantId, roleIdOrCode.trim());
        }
        if (roleId == null) return List.of();
        return userRoleMapper.findMemberIdsByRoleId(roleId).stream()
                .map(tenantMemberService::getById)
                .filter(Objects::nonNull)
                .map(TenantMember::getUserId)
                .filter(Objects::nonNull)
                .map(userMapper::findPidByUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<String> roleCodes(Long tenantId, Long memberId, List<Long> roleIds) {
        if (tenantId == null) return List.of();
        Set<String> codes = new LinkedHashSet<>();
        if (memberId != null) addCodes(codes, roleMapper.findByMemberIdAndTenantId(memberId, tenantId));
        if (roleIds != null) {
            roleIds.stream().filter(Objects::nonNull)
                    .map(id -> roleMapper.findByTenantIdAndId(tenantId, id))
                    .filter(Objects::nonNull)
                    .map(Role::getCode)
                    .filter(code -> code != null && !code.isBlank())
                    .forEach(codes::add);
        }
        return List.copyOf(codes);
    }

    @Override
    public UserIdentity findUser(String idOrPid) {
        if (idOrPid == null || idOrPid.isBlank()) return null;
        User user;
        try {
            user = userService.findByUserId(Long.parseLong(idOrPid));
        } catch (NumberFormatException ignored) {
            user = userService.findByPid(idOrPid);
        }
        if (user == null) return null;
        String displayName = user.getNickName() != null ? user.getNickName() : user.getUserName();
        return new UserIdentity(user.getId(), user.getPid(), user.getUserName(), displayName);
    }

    private void addCodes(Set<String> target, List<Role> roles) {
        if (roles == null) return;
        roles.stream().map(Role::getCode).filter(code -> code != null && !code.isBlank()).forEach(target::add);
    }
}
