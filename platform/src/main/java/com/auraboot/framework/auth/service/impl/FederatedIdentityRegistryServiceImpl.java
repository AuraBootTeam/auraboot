package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.ExternalIdentityAttributes;
import com.auraboot.framework.auth.dto.ExternalIdentityLinkSummary;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.entity.ExternalIdentityLink;
import com.auraboot.framework.auth.mapper.ExternalIdentityLinkMapper;
import com.auraboot.framework.auth.mapper.IdentityProviderInstanceMapper;
import com.auraboot.framework.auth.service.FederatedIdentityRegistryService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FederatedIdentityRegistryServiceImpl implements FederatedIdentityRegistryService {

    private static final String DEFAULT_APPLICATION = "business-web";
    private static final String DEFAULT_CHANNEL = "default-business-web";

    private final IdentityProviderInstanceMapper identityProviderInstanceMapper;
    private final ExternalIdentityLinkMapper externalIdentityLinkMapper;
    private final SystemModeService systemModeService;

    @Override
    public FederatedLoginContext resolveLoginContext(
            String applicationCode,
            String channelCode,
            String identityProviderCode,
            Long requestedTenantId) {
        String application = normalize(applicationCode, DEFAULT_APPLICATION);
        String channel = normalize(channelCode, DEFAULT_CHANNEL);
        String provider = requireText(identityProviderCode, "Identity provider code is required");
        Long tenantId = resolveTenantId(requestedTenantId);

        FederatedLoginContext context = identityProviderInstanceMapper.resolveFederatedContext(
                application, channel, provider, tenantId);
        if (context == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND,
                    "No active identity provider is bound to this login channel");
        }
        if (systemModeService.isSingleTenant() && !tenantId.equals(context.getTenantId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Identity provider is not available for the default business tenant");
        }
        return context;
    }

    @Override
    public ExternalIdentityLink findActiveLink(Long identityProviderInstanceId, String subject) {
        return externalIdentityLinkMapper.findActiveBySubject(
                requirePositive(identityProviderInstanceId, "Identity provider instance is required"),
                requireText(subject, "External subject is required"));
    }

    @Override
    public List<ExternalIdentityLinkSummary> listActiveLinks(Long userId, Long tenantId) {
        return externalIdentityLinkMapper.listActiveByUser(
                requirePositive(userId, "User is required"),
                requirePositive(tenantId, "Business tenant context is required"));
    }

    @Override
    @Transactional
    public ExternalIdentityLink link(
            FederatedLoginContext context,
            Long userId,
            ExternalIdentityAttributes attributes) {
        if (context == null || context.getIdentityProviderInstanceId() == null) {
            throw new IllegalArgumentException("Resolved federated login context is required");
        }
        Long validUserId = requirePositive(userId, "User is required");
        String subject = requireText(attributes == null ? null : attributes.subject(),
                "External subject is required");

        ExternalIdentityLink subjectLink = externalIdentityLinkMapper.findActiveBySubject(
                context.getIdentityProviderInstanceId(), subject);
        if (subjectLink != null) {
            if (!validUserId.equals(subjectLink.getUserId())) {
                throw conflict("External identity is already linked to another user");
            }
            updateAttributes(subjectLink, attributes);
            externalIdentityLinkMapper.updateById(subjectLink);
            return subjectLink;
        }

        ExternalIdentityLink userLink = externalIdentityLinkMapper.findActiveByUserAndInstance(
                validUserId, context.getIdentityProviderInstanceId());
        if (userLink != null) {
            throw conflict("User already has a different identity linked for this provider");
        }

        ExternalIdentityLink link = new ExternalIdentityLink();
        link.setPid(UlidGenerator.generate());
        link.setApplicationId(context.getApplicationId());
        link.setIdentityProviderInstanceId(context.getIdentityProviderInstanceId());
        link.setTenantId(context.getTenantId());
        link.setUserId(validUserId);
        link.setExternalSubject(subject);
        link.setLinkedAt(Instant.now());
        updateAttributes(link, attributes);
        try {
            externalIdentityLinkMapper.insert(link);
        } catch (DuplicateKeyException ex) {
            throw conflict("External identity link conflicts with an existing active link");
        }
        return link;
    }

    @Override
    @Transactional
    public void recordLogin(Long linkId) {
        ExternalIdentityLink link = externalIdentityLinkMapper.selectById(
                requirePositive(linkId, "External identity link is required"));
        if (link == null || link.getUnlinkedAt() != null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "External identity link not found");
        }
        link.setLastLoginAt(Instant.now());
        externalIdentityLinkMapper.updateById(link);
    }

    @Override
    @Transactional
    public void unlink(Long userId, Long identityProviderInstanceId) {
        ExternalIdentityLink link = externalIdentityLinkMapper.findActiveByUserAndInstance(
                requirePositive(userId, "User is required"),
                requirePositive(identityProviderInstanceId, "Identity provider instance is required"));
        if (link == null) {
            return;
        }
        link.setUnlinkedAt(Instant.now());
        externalIdentityLinkMapper.updateById(link);
    }

    private Long resolveTenantId(Long requestedTenantId) {
        if (systemModeService.isSingleTenant()) {
            Long defaultTenantId = systemModeService.getDefaultTenantId();
            return requirePositive(defaultTenantId,
                    "SINGLE mode default business tenant is not configured");
        }
        return requirePositive(requestedTenantId,
                "Tenant is required for federated login in this deployment mode");
    }

    private void updateAttributes(ExternalIdentityLink link, ExternalIdentityAttributes attributes) {
        link.setExternalUsername(attributes.username());
        link.setEmail(attributes.email());
        link.setClaims(normalize(attributes.claimsJson(), "{}"));
        link.setLastLoginAt(Instant.now());
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static Long requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ResponseCode.BUSINESS_ERROR, message);
    }
}
