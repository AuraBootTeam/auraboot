package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.ExternalIdentityAttributes;
import com.auraboot.framework.auth.dto.ExternalIdentityLinkSummary;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.entity.ExternalIdentityLink;

import java.util.List;

public interface FederatedIdentityRegistryService {

    FederatedLoginContext resolveLoginContext(
            String applicationCode,
            String channelCode,
            String identityProviderCode,
            Long requestedTenantId);

    ExternalIdentityLink findActiveLink(Long identityProviderInstanceId, String subject);

    List<ExternalIdentityLinkSummary> listActiveLinks(Long userId, Long tenantId);

    ExternalIdentityLink link(
            FederatedLoginContext context,
            Long userId,
            ExternalIdentityAttributes attributes);

    void recordLogin(Long linkId);

    void unlink(Long userId, Long identityProviderInstanceId);
}
