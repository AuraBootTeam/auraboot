package com.auraboot.framework.auth.dto;

import lombok.Builder;
import lombok.Value;

/** Public, non-sensitive product-entry policy used to render login and onboarding surfaces. */
@Value
@Builder
public class AccessPolicyResponse {
    String deploymentMode;
    String userRegistrationPolicy;
    String tenantProvisioningPolicy;
    String partyCreationPolicy;
    boolean partyInvitationEnabled;
    boolean actorSwitchEnabled;
}
