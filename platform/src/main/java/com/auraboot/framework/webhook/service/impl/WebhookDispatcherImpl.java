package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.WebhookDispatchResult;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.framework.webhook.service.WebhookSignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
    private final WebhookSignature webhookSignature;

    private static final Pattern DANGEROUS_SPEL_PATTERN = Pattern.compile(
            "(?i)(T\\s*\\(|new\\s+|getClass|forName|invoke|exec|Runtime|Process|System|Thread|Class\\." +
            "|#root|#this|\\bvalueOf\\b|java\\.|javax\\.|org\\.springframework)"
    );
    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    @Override
    @Async("eventTaskExecutor")
    public void dispatch(String eventType, Map<String, Object> payload, Long tenantId) {
        dispatchInternal(eventType, payload, tenantId);
    }

    @Override
    public WebhookDispatchResult dispatchTracked(String eventType, Map<String, Object> payload, Long tenantId) {
        return dispatchInternal(eventType, payload, tenantId);
    }

    private WebhookDispatchResult dispatchInternal(String eventType, Map<String, Object> payload, Long tenantId) {
        List<WebhookSubscription> subscriptions =
                subscriptionMapper.findByEventType(tenantId, eventType);

        List<WebhookDispatchResult.Receipt> receipts = new ArrayList<>();
        for (WebhookSubscription subscription : subscriptions) {
            WebhookDispatchResult.Receipt receipt = enqueue(subscription, payload);
            if (receipt != null) {
                receipts.add(receipt);
            }
        }
        return new WebhookDispatchResult(receipts);
    }

    private WebhookDispatchResult.Receipt enqueue(WebhookSubscription subscription, Map<String, Object> payload) {
        if (!matchesFilter(subscription, payload)) {
            log.debug("Webhook filtered out: subscription={}, filter={}",
                    subscription.getPid(), subscription.getFilterExpression());
            return null;
        }
        WebhookDeliveryLog delivery = new WebhookDeliveryLog();
        delivery.setPid(UniqueIdGenerator.generate());
        delivery.setTenantId(subscription.getTenantId());
        delivery.setSubscriptionPid(subscription.getPid());
        delivery.setInstallationPid(subscription.getInstallationPid());
        delivery.setRequestUrl(subscription.getTargetUrl());
        delivery.setRetryCount(0);
        delivery.setMaxRetries(subscription.getMaxRetries() != null ? subscription.getMaxRetries() : 3);
        delivery.setDeliveryStatus("pending");
        delivery.setNextRetryAt(Instant.now());
        delivery.setCreatedAt(Instant.now());
        Object eventId = payload.get("_eventId");
        if (eventId != null) {
            delivery.setEventId(String.valueOf(eventId));
        }
        try {
            delivery.setRequestBody(objectMapper.writeValueAsString(payload));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook payload is not JSON serializable", exception);
        }
        deliveryLogMapper.insert(delivery);
        return receiptFrom(subscription, delivery);
    }

    /** Execute exactly one persisted and leased attempt. The worker owns retries and recovery. */
    @Override
    public void processClaimed(WebhookDeliveryLog delivery) {
        WebhookSubscription subscription = subscriptionMapper.findByPid(
                delivery.getTenantId(), delivery.getSubscriptionPid());
        if (subscription == null || !Boolean.TRUE.equals(subscription.getEnabled())) {
            deliveryLogMapper.markPermanentFailure(delivery.getId(), delivery.getLeaseToken(),
                    "Webhook subscription is missing or disabled");
            return;
        }
        try {
            String body = delivery.getRequestBody();

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

            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest.Builder requestBuilder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Event", subscription.getEventType())
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Delivery", delivery.getPid());

            // Add HMAC signature if secret is configured
            if (subscription.getSecret() != null && !subscription.getSecret().isBlank()) {
                String decryptedSecret = fieldEncryptionService.decrypt(subscription.getSecret());
                String signature = webhookSignature.sign(decryptedSecret, timestamp, body);
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
            if (status >= 400) {
                throw new ResponseFailure(status, truncate(response.body()));
            }
            deliveryLogMapper.markSuccess(delivery.getId(), delivery.getLeaseToken(),
                    status, truncate(response.body()));

            log.debug("Webhook delivered: subscription={}, url={}, status={}",
                    subscription.getPid(), subscription.getTargetUrl(), status);

        } catch (IllegalArgumentException exception) {
            log.warn("SSRF blocked or invalid URL for webhook: subscription={}, url={}, reason={}",
                    subscription.getPid(), subscription.getTargetUrl(), exception.getMessage());
            deliveryLogMapper.markPermanentFailure(delivery.getId(), delivery.getLeaseToken(),
                    truncate(exception.getMessage()));
        } catch (Exception exception) {
            int retryCount = delivery.getRetryCount() == null ? 0 : delivery.getRetryCount();
            long backoffSeconds = Math.min(3600L, 1L << Math.min(retryCount, 12));
            Instant nextRetryAt = Instant.now().plusSeconds(backoffSeconds);
            Integer status = exception instanceof ResponseFailure failure ? failure.status : null;
            String body = exception instanceof ResponseFailure failure ? failure.body : null;
            deliveryLogMapper.markFailure(delivery.getId(), delivery.getLeaseToken(), status, body,
                    truncate(exception.getMessage()), nextRetryAt);
            log.warn("Webhook delivery attempt failed: delivery={}, retry={}, nextRetryAt={}, error={}",
                    delivery.getPid(), retryCount + 1, nextRetryAt, exception.getMessage());
        }
    }

    private WebhookDispatchResult.Receipt receiptFrom(WebhookSubscription subscription, WebhookDeliveryLog logEntry) {
        if (logEntry == null) {
            return null;
        }
        return new WebhookDispatchResult.Receipt(
                subscription.getPid(),
                logEntry.getPid(),
                logEntry.getEventId(),
                logEntry.getDeliveryStatus(),
                "success".equalsIgnoreCase(logEntry.getDeliveryStatus()),
                logEntry.getErrorMessage()
        );
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

    private String truncate(String value) {
        if (value == null || value.length() <= 16_384) {
            return value;
        }
        return value.substring(0, 16_384);
    }

    private static final class ResponseFailure extends RuntimeException {
        private final int status;
        private final String body;

        private ResponseFailure(int status, String body) {
            super("Webhook returned error status " + status);
            this.status = status;
            this.body = body;
        }
    }
}
