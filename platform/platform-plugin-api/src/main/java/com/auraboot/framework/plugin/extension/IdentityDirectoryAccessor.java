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

    record UserIdentity(Long id, String pid, String username, String displayName) {}
}
