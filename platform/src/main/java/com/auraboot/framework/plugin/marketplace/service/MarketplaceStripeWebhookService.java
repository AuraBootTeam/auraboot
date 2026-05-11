package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketplaceStripeWebhookService {

    private static final String PROVIDER_STRIPE = "stripe";
    private static final String SIGNATURE_SCHEME = "v1";

    private final MarketplacePaidService paidService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;
    private final long toleranceSeconds;

    public MarketplaceStripeWebhookService(
            MarketplacePaidService paidService,
            ObjectMapper objectMapper,
            @Value("${auraboot.payment.stripe.webhook-secret:}") String webhookSecret,
            @Value("${auraboot.payment.stripe.webhook-tolerance-seconds:300}") long toleranceSeconds
    ) {
        this.paidService = paidService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
        this.toleranceSeconds = toleranceSeconds;
    }

    public PaymentEventResponse handleWebhook(String signatureHeader, String payload) {
        verifySignature(signatureHeader, payload);

        JsonNode root = readPayload(payload);
        String stripeEventId = text(root, "id");
        String stripeEventType = text(root, "type");
        JsonNode object = root.path("data").path("object");
        PaymentEventRequest request = toPaymentEventRequest(stripeEventId, stripeEventType, object, root);
        if (request == null) {
            PaymentEventResponse ignored = new PaymentEventResponse();
            ignored.setProvider(PROVIDER_STRIPE);
            ignored.setStatus("ignored");
            ignored.setEventPid(stripeEventId);
            return ignored;
        }

        Long tenantId = tenantId(object);
        ContextSnapshot previous = captureContext();
        MetaContext.setContext(tenantId, 0L, "stripe-webhook", "stripe-webhook");
        try {
            return paidService.applyPaymentEvent(request);
        } finally {
            restoreContext(previous);
        }
    }

    private PaymentEventRequest toPaymentEventRequest(
            String stripeEventId,
            String stripeEventType,
            JsonNode object,
            JsonNode root
    ) {
        PaymentEventRequest request = new PaymentEventRequest();
        request.setProvider(PROVIDER_STRIPE);
        request.setEventId(stripeEventId);
        request.setIdempotencyKey(firstMetadataText(object, "idempotency_key", "idempotencyKey"));
        request.setPurchasePid(firstText(
                metadata(object, "purchase_pid"),
                metadata(object, "purchasePid"),
                text(object, "client_reference_id")
        ));
        request.setRawPayload(objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {}));

        switch (stripeEventType) {
            case "checkout.session.completed" -> {
                request.setEventType("payment_confirmed");
                request.setProviderSessionId(text(object, "id"));
                request.setProviderPaymentId(firstText(text(object, "payment_intent"), text(object, "id")));
                request.setProviderReference(text(object, "id"));
                return request;
            }
            case "checkout.session.async_payment_failed", "payment_intent.payment_failed" -> {
                request.setEventType("payment_failed");
                request.setProviderPaymentId(text(object, "id"));
                request.setProviderReference(text(object, "latest_charge"));
                return request;
            }
            case "refund.succeeded" -> {
                request.setEventType("refund_confirmed");
                request.setProviderPaymentId(firstText(text(object, "payment_intent"), text(object, "charge"), text(object, "id")));
                request.setProviderReference(text(object, "id"));
                request.setProviderRefundReference(text(object, "id"));
                return request;
            }
            case "charge.refunded" -> {
                request.setEventType("refund_confirmed");
                request.setProviderPaymentId(firstText(text(object, "payment_intent"), text(object, "id")));
                String refundId = text(object.path("refunds").path("data").path(0), "id");
                request.setProviderReference(firstText(refundId, text(object, "id")));
                request.setProviderRefundReference(firstText(refundId, text(object, "id")));
                return request;
            }
            default -> {
                return null;
            }
        }
    }

    private void verifySignature(String signatureHeader, String payload) {
        if (!StringUtils.hasText(webhookSecret)) {
            throw new StripeWebhookException(503, "Stripe webhook secret is not configured");
        }
        if (!StringUtils.hasText(signatureHeader)) {
            throw new StripeWebhookException(401, "Stripe webhook signature is required");
        }
        if (payload == null) {
            throw new StripeWebhookException(400, "Stripe webhook payload is required");
        }

        StripeSignature signature = parseSignatureHeader(signatureHeader);
        long now = Instant.now().getEpochSecond();
        if (toleranceSeconds > 0 && Math.abs(now - signature.timestamp()) > toleranceSeconds) {
            throw new StripeWebhookException(401, "Stripe webhook timestamp is outside tolerance");
        }

        String signedPayload = signature.timestamp() + "." + payload;
        String expected = hmacSha256Hex(signedPayload, webhookSecret);
        boolean matched = signature.signatures().stream()
                .anyMatch(actual -> constantTimeEquals(expected, actual));
        if (!matched) {
            throw new StripeWebhookException(401, "Stripe webhook signature verification failed");
        }
    }

    private StripeSignature parseSignatureHeader(String signatureHeader) {
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if ("t".equals(key)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw new StripeWebhookException(400, "Stripe webhook timestamp is malformed");
                }
            } else if (SIGNATURE_SCHEME.equals(key) && StringUtils.hasText(value)) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new StripeWebhookException(400, "Stripe webhook signature header is malformed");
        }
        return new StripeSignature(timestamp, signatures);
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new StripeWebhookException(400, "Stripe webhook payload is malformed JSON");
        }
    }

    private Long tenantId(JsonNode object) {
        String tenantId = firstMetadataText(object, "tenant_id", "tenantId");
        if (!StringUtils.hasText(tenantId)) {
            throw new StripeWebhookException(400, "Stripe webhook tenant metadata is required");
        }
        try {
            return Long.parseLong(tenantId);
        } catch (NumberFormatException ex) {
            throw new StripeWebhookException(400, "Stripe webhook tenant metadata is malformed");
        }
    }

    private String firstMetadataText(JsonNode object, String... fields) {
        for (String field : fields) {
            String value = metadata(object, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String metadata(JsonNode object, String field) {
        return text(object.path("metadata"), field);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text : null;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new StripeWebhookException(500, "Stripe webhook signature verifier is unavailable");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ContextSnapshot captureContext() {
        if (!MetaContext.exists()) {
            return null;
        }
        return new ContextSnapshot(
                MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(),
                MetaContext.getCurrentUserPid(),
                MetaContext.getCurrentUsername(),
                MetaContext.getCurrentRoleIds()
        );
    }

    private void restoreContext(ContextSnapshot snapshot) {
        if (snapshot == null) {
            MetaContext.clear();
            return;
        }
        MetaContext.setContext(
                snapshot.tenantId(),
                snapshot.userId(),
                snapshot.userPid(),
                snapshot.username(),
                snapshot.roleIds()
        );
    }

    private record StripeSignature(long timestamp, List<String> signatures) {
    }

    private record ContextSnapshot(Long tenantId, Long userId, String userPid, String username, Set<Long> roleIds) {
    }

    public static class StripeWebhookException extends RuntimeException {
        private final int httpStatus;

        public StripeWebhookException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
