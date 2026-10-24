package com.auraboot.framework.plugin.extension;

import java.util.List;

/** Read-only, tenant-aware identity directory exposed to application plugins. */
public interface IdentityDirectoryAccessor {

    /** Resolve all public user pids assigned to a tenant role id or role code. */
    List<String> userPidsForRole(Long tenantId, String roleIdOrCode);

    /** Resolve stable role codes for current membership/role identifiers. */
    List<String> roleCodes(Long tenantId, Long memberId, List<Long> roleIds);

    /** Find a user by public pid or numeric id text. */
    UserIdentity findUser(String idOrPid);

    /** Resolve a user only when they are an active member of the current tenant. */
    UserIdentity findActiveTenantUser(Long tenantId, String idOrPid);

    /** Strict approval requires both exact role PIDs and enabled membership incarnations. */
    default boolean supportsStrictApprovalIdentity() { return false; }

    /** Exact public role identity; older hosts cannot claim strict role support. */
    default List<String> userPidsForRolePid(Long tenantId, String rolePid) {
        throw new UnsupportedOperationException("Strict public role lookup is unavailable");
    }

    /** Enabled membership incarnation, used by sealed approval assignments. */
    default TenantUserIdentity findEnabledTenantMembership(Long tenantId, String userPid) { return null; }

    record TenantUserIdentity(UserIdentity user, String memberPid, Long memberId) {
        public TenantUserIdentity(UserIdentity user,String memberPid) {this(user,memberPid,null);}
    }

    record UserIdentity(Long id, String pid, String username, String displayName) {}
}
