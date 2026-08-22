package com.auraboot.framework.auth.service;

import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.user.dao.entity.User;

/** Applies deployment admission policy after a login channel creates a user identity. */
public interface UserAdmissionService {

    void assertSelfRegistrationAllowed();

    /**
     * Admits a newly self-registered identity to the server-owned default business tenant when
     * running in SINGLE mode. MULTI/HYBRID identities remain tenant-less until an explicit flow.
     */
    TenantMember admitSelfRegisteredUser(User user);
}
