package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.webhook.service.WebhookService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final WebhookDeliveryLogMapper deliveryLogMapper;

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
        entity.setModelCode(request.getModelCode());
        entity.setFilterExpression(request.getFilterExpression());
        entity.setSecret(fieldEncryptionService.encrypt(request.getSecret()));
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
        existing.setModelCode(request.getModelCode());
        existing.setFilterExpression(request.getFilterExpression());
        // If masked value sent back, keep existing encrypted secret
        String newSecret = request.getSecret();
        if (newSecret != null && newSecret.startsWith("****")) {
            // Client sent back masked value — keep DB value unchanged
        } else {
            existing.setSecret(fieldEncryptionService.encrypt(newSecret));
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
        webhookDispatcher.dispatch(subscription.getEventType(), testPayload,
                MetaContext.getCurrentTenantId());
    }

    @Override
    public List<WebhookDeliveryLog> listDeliveryLogs(String subscriptionPid, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return deliveryLogMapper.selectList(
                new LambdaQueryWrapper<WebhookDeliveryLog>()
                        .eq(WebhookDeliveryLog::getTenantId, tenantId)
                        .eq(WebhookDeliveryLog::getSubscriptionPid, subscriptionPid)
                        .orderByDesc(WebhookDeliveryLog::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, 200)));
    }

    @Override
    public boolean replayDelivery(String deliveryPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int replayed = deliveryLogMapper.replay(tenantId, deliveryPid, MetaContext.getCurrentUserPid());
        return replayed == 1;
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
}
