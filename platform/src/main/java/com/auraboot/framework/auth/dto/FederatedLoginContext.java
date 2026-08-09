package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * Server-resolved pre-authentication context for a concrete identity provider.
 * Client supplied tenant or provider values are never authoritative after this
 * context has been resolved.
 */
@Data
public class FederatedLoginContext {
    private Long applicationId;
    private Long loginChannelId;
    private Long identityProviderInstanceId;
    private Long tenantId;
    private String applicationCode;
    private String loginChannelCode;
    private String identityProviderCode;
    private String providerType;
    private String providerConfig;
    private String secretRef;
    private String channelSettings;
}
