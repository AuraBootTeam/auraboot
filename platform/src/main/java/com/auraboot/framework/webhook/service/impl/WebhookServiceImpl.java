package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.service.OpenApiEventCatalog;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.webhook.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Implementation of WebhookService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDispatcher webhookDispatcher;
    private final FieldEncryptionService fieldEncryptionService;
    private final ApplicationInstallationMapper installationMapper;
    private final OpenApiEventCatalog eventCatalog;

    @Override
    @Transactional
    public WebhookSubscription create(WebhookCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant now = Instant.now();

        WebhookSubscription entity = new WebhookSubscription();
        entity.setTenantId(tenantId);
        entity.setPid(UniqueIdGenerator.generate());
        entity.setInstallationPid(validateInstallation(tenantId, request.getInstallationPid()));
        entity.setName(request.getName());
        entity.setTargetUrl(request.getTargetUrl());
        entity.setEventType(request.getEventType());
        entity.setEventVersion(validateEventVersion(entity.getInstallationPid(), request));
        entity.setModelCode(request.getModelCode());
        entity.setFilterExpression(request.getFilterExpression());
        entity.setSecret(fieldEncryptionService.encrypt(request.getSecret()));
        entity.setSecretRotatedAt(hasSecret(request.getSecret()) ? now : null);
        entity.setHeaders(request.getHeaders());
        entity.setMaxRetries(request.getMaxRetries());
        entity.setTimeoutMs(request.getTimeoutMs());
        entity.setEnabled(request.isEnabled());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        subscriptionMapper.insert(entity);
        log.info("Created webhook subscription: pid={}, event={}, url={}",
                entity.getPid(), request.getEventType(), request.getTargetUrl());
        return entity;
    }

    @Override
    public WebhookSubscription getByPid(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return subscriptionMapper.findByPid(tenantId, pid);
    }

    @Override
    public List<WebhookSubscription> listByEventType(String eventType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return subscriptionMapper.findByEventType(tenantId, eventType);
    }

    @Override
    public List<WebhookSubscription> listAll() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return subscriptionMapper.findByTenant(tenantId);
    }

    @Override
    @Transactional
    public WebhookSubscription update(String pid, WebhookCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        WebhookSubscription existing = subscriptionMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("Webhook subscription not found: " + pid);
        }

        existing.setName(request.getName());
        existing.setInstallationPid(validateInstallation(tenantId, request.getInstallationPid()));
        existing.setTargetUrl(request.getTargetUrl());
        existing.setEventType(request.getEventType());
        existing.setEventVersion(validateEventVersion(existing.getInstallationPid(), request));
        existing.setModelCode(request.getModelCode());
        existing.setFilterExpression(request.getFilterExpression());
        // If masked value sent back, keep existing encrypted secret
        String newSecret = request.getSecret();
        if (newSecret != null && newSecret.startsWith("****")) {
            // Client sent back masked value — keep DB value unchanged
        } else {
            existing.setSecret(fieldEncryptionService.encrypt(newSecret));
            existing.setSecretRotatedAt(hasSecret(newSecret) ? Instant.now() : null);
        }
        existing.setHeaders(request.getHeaders());
        existing.setMaxRetries(request.getMaxRetries());
        existing.setTimeoutMs(request.getTimeoutMs());
        existing.setEnabled(request.isEnabled());
        existing.setUpdatedAt(Instant.now());

        subscriptionMapper.updateById(existing);
        log.info("Updated webhook subscription: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        subscriptionMapper.deleteByPid(tenantId, pid);
        log.info("Deleted webhook subscription: pid={}", pid);
    }

    @Override
    @Transactional
    public void enable(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        subscriptionMapper.updateEnabled(tenantId, pid, true);
    }

    @Override
    @Transactional
    public void disable(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        subscriptionMapper.updateEnabled(tenantId, pid, false);
    }

    @Override
    public void testWebhook(String pid, Map<String, Object> testPayload) {
        WebhookSubscription subscription = getByPid(pid);
        if (subscription == null) {
            throw new IllegalArgumentException("Webhook subscription not found: " + pid);
        }
        Map<String, Object> payload = testPayload;
        if (subscription.getInstallationPid() != null) {
            int version = subscription.getEventVersion() == null ? 1 : subscription.getEventVersion();
            var descriptor = eventCatalog.requireExternal(subscription.getEventType(), version);
            payload = eventCatalog.envelope(descriptor, version, "test-" + subscription.getPid(), testPayload);
        }
        webhookDispatcher.dispatch(subscription.getEventType(), payload, MetaContext.getCurrentTenantId());
    }

    private String validateInstallation(Long tenantId, String installationPid) {
        if (installationPid == null || installationPid.isBlank()) {
            return null;
        }
        if (installationMapper.findByTenantAndPid(tenantId, installationPid) == null) {
            throw new IllegalArgumentException("Open Platform installation not found: " + installationPid);
        }
        return installationPid;
    }

    private int validateEventVersion(String installationPid, WebhookCreateRequest request) {
        int version = request.getEventVersion() == null ? 1 : request.getEventVersion();
        if (version < 1) {
            throw new IllegalArgumentException("Webhook event version must be positive");
        }
        if (installationPid != null) {
            eventCatalog.requireExternal(request.getEventType(), version);
        }
        return version;
    }

    private boolean hasSecret(String value) {
        return value != null && !value.isBlank();
    }
}
