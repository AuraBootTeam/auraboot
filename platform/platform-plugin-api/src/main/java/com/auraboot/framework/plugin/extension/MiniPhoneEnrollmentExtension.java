package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

/** Product-owned admission policy. Inputs are host-verified; tenant and role are server configured. */
public interface MiniPhoneEnrollmentExtension extends ExtensionPoint {
    String getEnrollmentCode();
    String getWriterCommandCode();
    /** Reject missing, inactive or ambiguous directory matches before creating any account. */
    void validate(String verifiedPhone, DataAccessor data);
    /** Display name from the trusted directory, never from the login request. */
    default String displayName(String verifiedPhone, DataAccessor data) { return "WeChat User"; }
    /** Called atomically with platform identity, tenant membership and role assignment. */
    void enroll(long tenantId, String userPid, String verifiedPhone, DataAccessor data);
}
