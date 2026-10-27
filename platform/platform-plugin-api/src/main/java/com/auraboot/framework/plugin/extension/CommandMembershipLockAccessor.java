package com.auraboot.framework.plugin.extension;

import java.util.List;

/** Identity assertions held by the caller's physical Core command transaction, not an authorization grant. */
public interface CommandMembershipLockAccessor {
    String SETTINGS_KEY = "__commandMembershipLockAccessor";

    /**
     * Lock the complete participant set before domain effects: all user rows by numeric ID,
     * then all member rows by numeric ID. The authenticated actor must be included exactly.
     * One to thirty-two distinct exact pairs; results preserve request order. A later call
     * may use a subset, but cannot extend the set after any member lock has been acquired.
     * Requires an existing writable transaction on Core's actual MyBatis datasource.
     * Enabled/active identities and incarnations are rechecked before commit, including
     * changes made by the caller itself. No fields or versions are written to acquire locks.
     */
    List<IdentityDirectoryAccessor.TenantUserIdentity> requireEnabledMemberships(List<MembershipAssertion> expected);

    record MembershipAssertion(String userPid, String memberPid) {
        public MembershipAssertion {
            exact(userPid); exact(memberPid);
        }
        private static void exact(String value) {
            if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 64
                    || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                    || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE))
                throw new IllegalArgumentException("An exact public identity PID is required");
        }
    }
}
