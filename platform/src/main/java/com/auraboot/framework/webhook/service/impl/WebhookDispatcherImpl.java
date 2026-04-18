package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Implementation of WebhookDispatcher.
 * Sends HTTP POST requests to webhook subscribers with HMAC signature.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcherImpl implements WebhookDispatcher {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    /**
     * Shared JDK HTTP client for webhook delivery (P3-E DNS-rebinding
     * hardening). JDK {@link HttpClient} is what {@link PinnedHttpRequests}
     * targets for pinning the validated IP at connect time.
     */
    private static final HttpClient PINNED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS))
            .build();

    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDeliveryLogMapper deliveryLogMapper;
    private final ObjectMapper objectMapper;
    private final FieldEncryptionService fieldEncryptionService;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "webhook-retry"); t.setDaemon(true); return t; });

    private static final Pattern DANGEROUS_SPEL_PATTERN = Pattern.compile(
            "(?i)(T\\s*\\(|new\\s+|getClass|forName|invoke|exec|Runtime|Process|System|Thread|Class\\." +
            "|#root|#this|\\bvalueOf\\b|java\\.|javax\\.|org\\.springframework)"
    );
    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    @Override
    @Async("eventTaskExecutor")
    public void dispatch(String eventType, Map<String, Object> payload, Long tenantId) {
        List<WebhookSubscription> subscriptions =
                subscriptionMapper.findByEventType(tenantId, eventType);

        for (WebhookSubscription subscription : subscriptions) {
            deliverWithRetry(subscription, payload);
        }
    }

    private void deliverWithRetry(WebhookSubscription subscription, Map<String, Object> payload) {
        if (!matchesFilter(subscription, payload)) {
            log.debug("Webhook filtered out: subscription={}, filter={}",
                    subscription.getPid(), subscription.getFilterExpression());
            return;
        }
        deliverAttempt(subscription, payload, 0);
    }

    private void deliverAttempt(WebhookSubscription subscription, Map<String, Object> payload, int retryCount) {
        int maxRetries = subscription.getMaxRetries() != null ? subscription.getMaxRetries() : 3;

        WebhookDeliveryLog logEntry = new WebhookDeliveryLog();
        logEntry.setPid(UniqueIdGenerator.generate());
        logEntry.setTenantId(subscription.getTenantId());
        logEntry.setSubscriptionPid(subscription.getPid());
        logEntry.setRequestUrl(subscription.getTargetUrl());
        logEntry.setRetryCount(retryCount);

        // Extract event ID from payload
        Object eventId = payload.get("_eventId");
        if (eventId != null) {
            logEntry.setEventId(String.valueOf(eventId));
        }

        try {
            String body = objectMapper.writeValueAsString(payload);
            logEntry.setRequestBody(body);

            // Validate URL + pin the resolved IP so the HTTP send cannot be
            // re-resolved to a different address (P3-E #1 DNS rebinding TOCTOU).
            SsrfValidator.ValidatedTarget target =
                    SsrfValidator.validate(subscription.getTargetUrl());
            if (target == null) {
                throw new IllegalArgumentException(
                        "webhook target could not be resolved: " + subscription.getTargetUrl());
            }

            int readTimeoutMs = subscription.getTimeoutMs() != null
                    ? subscription.getTimeoutMs()
                    : DEFAULT_READ_TIMEOUT_MS;

            HttpRequest.Builder requestBuilder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Event", subscription.getEventType())
                    .header("X-Webhook-Timestamp", String.valueOf(Instant.now().toEpochMilli()));

            // Add HMAC signature if secret is configured
            if (subscription.getSecret() != null && !subscription.getSecret().isBlank()) {
                String decryptedSecret = fieldEncryptionService.decrypt(subscription.getSecret());
                String signature = computeHmac(body, decryptedSecret);
                requestBuilder.header("X-Webhook-Signature", signature);
            }

            // Add custom headers
            if (subscription.getHeaders() != null) {
                Map<String, String> customHeaders = objectMapper.readValue(
                        subscription.getHeaders(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                customHeaders.forEach(requestBuilder::header);
            }

            requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            HttpResponse<String> response = PINNED_HTTP_CLIENT.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            logEntry.setResponseStatus(status);
            logEntry.setResponseBody(response.body());
            if (status >= 400) {
                // Preserve prior RestTemplate semantics: 4xx/5xx trigger retry.
                throw new RuntimeException("Webhook returned error status " + status);
            }
            logEntry.setDeliveryStatus("success");
            logEntry.setDeliveredAt(Instant.now());
            deliveryLogMapper.insert(logEntry);

            log.debug("Webhook delivered: subscription={}, url={}, status={}",
                    subscription.getPid(), subscription.getTargetUrl(), status);

        } catch (IllegalArgumentException e) {
            // SSRF or invalid URL — do not retry
            log.warn("SSRF blocked or invalid URL for webhook: subscription={}, url={}, reason={}",
                    subscription.getPid(), subscription.getTargetUrl(), e.getMessage());
            logEntry.setDeliveryStatus("failed");
            logEntry.setErrorMessage(e.getMessage());
            deliveryLogMapper.insert(logEntry);
        } catch (Exception e) {
            logEntry.setDeliveryStatus("failed");
            logEntry.setErrorMessage(e.getMessage());
            deliveryLogMapper.insert(logEntry);

            int nextRetry = retryCount + 1;
            if (nextRetry <= maxRetries) {
                // Non-blocking exponential backoff with jitter: ~1s, ~2s, ~4s, ~8s...
                long backoffMs = (long) (Math.pow(2, retryCount) * 1000 * (0.5 + Math.random() * 0.5));
                log.warn("Webhook delivery failed (retry {}/{}): url={}, error={}, next attempt in {}ms",
                        nextRetry, maxRetries, subscription.getTargetUrl(), e.getMessage(), backoffMs);
                retryScheduler.schedule(
                        () -> deliverAttempt(subscription, payload, nextRetry),
                        backoffMs, TimeUnit.MILLISECONDS);
            } else {
                log.error("Webhook delivery failed after {} retries: url={}",
                        maxRetries, subscription.getTargetUrl());
            }
        }
    }

    private boolean matchesFilter(WebhookSubscription sub, Map<String, Object> payload) {
        String expr = sub.getFilterExpression();
        if (expr == null || expr.isBlank()) {
            return true;
        }
        if (expr.length() > 500 || DANGEROUS_SPEL_PATTERN.matcher(expr).find()) {
            log.warn("Rejected dangerous or oversized filter expression for webhook: subscription={}, expr={}",
                    sub.getPid(), expr);
            return false;
        }
        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext
                    .forPropertyAccessors(new MapAccessor())
                    .withRootObject(payload).build();
            Object result = SPEL_PARSER.parseExpression(expr).getValue(ctx);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Filter expression evaluation failed for webhook: subscription={}, expr={}, error={}",
                    sub.getPid(), expr, e.getMessage());
            return false;
        }
    }

    private String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return "sha256=" + sb;
        } catch (Exception e) {
            // Do not send unsigned webhooks — throw to prevent delivery (25.2 fix)
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }
}
