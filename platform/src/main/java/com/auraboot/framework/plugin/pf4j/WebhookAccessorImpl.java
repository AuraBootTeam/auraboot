package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.WebhookAccessor;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Platform-side impl of {@link WebhookAccessor}. A Spring {@code @Service} so the host
 * autowires it BY TYPE into plugin {@code BackgroundComponentExtension} beans (same
 * mechanism as {@code BackgroundDataAccessorImpl}); no explicit registration.
 *
 * <p>Thin delegate to the existing {@link WebhookDispatcher} (async delivery + retry +
 * signing + delivery log already provided by the platform webhook framework).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookAccessorImpl implements WebhookAccessor {

    private final WebhookDispatcher webhookDispatcher;

    @Override
    public void dispatch(long tenantId, String eventType, Map<String, Object> payload) {
        webhookDispatcher.dispatch(eventType, payload, tenantId);
    }
}
