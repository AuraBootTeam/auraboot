package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.webhook.service.WebhookDispatcher;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** G2: WebhookAccessor is a thin delegate to the platform WebhookDispatcher. */
class WebhookAccessorImplTest {

    @Test
    void dispatch_delegatesToWebhookDispatcher() {
        WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
        WebhookAccessorImpl accessor = new WebhookAccessorImpl(dispatcher);
        Map<String, Object> payload = Map.of("jobId", 42L, "status", "COMPLETED");

        accessor.dispatch(7L, "crawler.job.completed", payload);

        verify(dispatcher).dispatch(eq("crawler.job.completed"), eq(payload), eq(7L));
    }
}
