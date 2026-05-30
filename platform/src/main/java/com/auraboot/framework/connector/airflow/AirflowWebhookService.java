package com.auraboot.framework.connector.airflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Airflow webhook receiver. PRD 18-C §C.3.3.
 *
 * <p>Verifies the {@code X-AuraBoot-Signature} header against the raw body,
 * then publishes an {@link AirflowWebhookEvent} on the Spring application
 * event bus so downstream listeners react without the controller knowing
 * them.
 *
 * <p>Security rules — every failure path has a fixed HTTP status + error
 * code (see the design doc table):
 * <ul>
 *   <li>HMAC algorithm: {@code HmacSHA256} over UTF-8 bytes of
 *       {@code <unix_ts> + "." + <raw body>}.</li>
 *   <li>Comparison: {@link MessageDigest#isEqual} timing-safe; both sides
 *       lowercased hex.</li>
 *   <li>Time window: {@value #TIME_WINDOW_SECONDS}s either direction
 *       around {@code now}.</li>
 *   <li>Replay: in-memory webhook-id TTL cache, 5-minute window —
 *       single-node only. Multi-node deployments will swap for a Redis-
 *       backed cache in a follow-up.</li>
 * </ul>
 */
@Slf4j
@Service
public class AirflowWebhookService {

    public static final String SIGNATURE_HEADER = "X-AuraBoot-Signature";
    public static final String WEBHOOK_ID_HEADER = "X-AuraBoot-Webhook-Id";

    static final long TIME_WINDOW_SECONDS = 300L;
    static final long REPLAY_CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper jsonMapper;
    private final Clock clock;

    /** Per-connection secret. Single-tenant placeholder; replace with table-backed store in W5-M3.1. */
    private final String sharedSecret;

    /**
     * In-memory replay cache: {@code webhookId → expiryEpochMs}. Bounded by a
     * scheduled sweep removing entries whose expiry is in the past.
     */
    private final Map<String, Long> seen = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AirflowWebhookService(ApplicationEventPublisher publisher,
                                 ObjectMapper jsonMapper,
                                 @Value("${auraboot.airflow.webhook.shared-secret:}") String sharedSecret) {
        this(publisher, jsonMapper, sharedSecret, Clock.systemUTC());
    }

    /** Test seam. */
    AirflowWebhookService(ApplicationEventPublisher publisher,
                          ObjectMapper jsonMapper,
                          String sharedSecret,
                          Clock clock) {
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
        this.clock = clock;
    }

    /**
     * Verify + dispatch. Throws {@link AirflowWebhookException} on any
     * failure; otherwise returns the verified event for the controller to
     * relay back to the caller.
     */
    public AirflowWebhookEvent verifyAndDispatch(String signatureHeader,
                                                 String webhookIdHeader,
                                                 byte[] rawBody) {
        if (sharedSecret.isBlank()) {
            throw new AirflowWebhookException(404, "UNKNOWN_CONNECTION",
                    "No shared_secret configured (auraboot.airflow.webhook.shared-secret unset)");
        }
        if (webhookIdHeader == null || webhookIdHeader.isBlank()) {
            throw new AirflowWebhookException(401, "MISSING_SIGNATURE",
                    "Missing " + WEBHOOK_ID_HEADER + " header");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new AirflowWebhookException(401, "MISSING_SIGNATURE",
                    "Missing " + SIGNATURE_HEADER + " header");
        }
        SignatureParts parts = parseSignature(signatureHeader);
        long now = clock.instant().getEpochSecond();
        long drift = Math.abs(now - parts.timestamp);
        if (drift > TIME_WINDOW_SECONDS) {
            throw new AirflowWebhookException(401, "STALE_TIMESTAMP",
                    "Timestamp drift " + drift + "s exceeds window " + TIME_WINDOW_SECONDS + "s");
        }

        byte[] hmac = computeHmac(parts.timestamp + "." + new String(rawBody, StandardCharsets.UTF_8),
                sharedSecret);
        byte[] expected = HexFormat.of().parseHex(parts.signature.toLowerCase());
        if (expected.length != hmac.length || !MessageDigest.isEqual(hmac, expected)) {
            throw new AirflowWebhookException(401, "BAD_SIGNATURE",
                    "HMAC verification failed");
        }

        // Replay guard.
        synchronized (seen) {
            long nowMs = clock.millis();
            // Lazy sweep.
            seen.entrySet().removeIf(e -> e.getValue() < nowMs);
            if (seen.containsKey(webhookIdHeader)) {
                throw new AirflowWebhookException(409, "DUPLICATE_WEBHOOK",
                        "Webhook id replayed: " + webhookIdHeader);
            }
            seen.put(webhookIdHeader, nowMs + REPLAY_CACHE_TTL_MS);
        }

        JsonNode root = parseBody(rawBody);
        AirflowWebhookEvent event = buildEvent(webhookIdHeader, root);
        publisher.publishEvent(new AirflowWebhookEvent.Spring(this, event));
        return event;
    }

    /** Test/observability seam. */
    public int replayCacheSize() {
        synchronized (seen) {
            return seen.size();
        }
    }

    // -- helpers -------------------------------------------------------

    private SignatureParts parseSignature(String header) {
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String t = part.trim();
            if (t.startsWith("t=")) {
                try {
                    long ts = Long.parseLong(t.substring(2));
                    if (ts < 0) {
                        throw new AirflowWebhookException(401, "STALE_TIMESTAMP",
                                "Negative timestamp");
                    }
                    timestamp = ts;
                } catch (NumberFormatException nfe) {
                    throw new AirflowWebhookException(401, "MALFORMED_SIGNATURE",
                            "Timestamp is not a unix epoch integer");
                }
            } else if (t.startsWith("v1=")) {
                signature = t.substring(3);
            }
        }
        if (timestamp == null) {
            throw new AirflowWebhookException(401, "MALFORMED_SIGNATURE",
                    "Signature header missing t=<unix_ts>");
        }
        if (signature == null || signature.isBlank()) {
            throw new AirflowWebhookException(401, "MALFORMED_SIGNATURE",
                    "Signature header missing v1=<hex>");
        }
        try {
            HexFormat.of().parseHex(signature.toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new AirflowWebhookException(401, "BAD_SIGNATURE",
                    "v1 value is not valid lowercase hex");
        }
        return new SignatureParts(timestamp, signature);
    }

    private static byte[] computeHmac(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AirflowWebhookException(500, "INTERNAL", "HMAC compute failed");
        }
    }

    private JsonNode parseBody(byte[] body) {
        try {
            JsonNode n = jsonMapper.readTree(body);
            if (n == null || n.isMissingNode() || !n.isObject()) {
                throw new AirflowWebhookException(400, "INVALID_BODY",
                        "Webhook body must be a JSON object");
            }
            return n;
        } catch (AirflowWebhookException e) {
            throw e;
        } catch (Exception e) {
            throw new AirflowWebhookException(400, "INVALID_BODY",
                    "Webhook body is not valid JSON: " + e.getMessage());
        }
    }

    private AirflowWebhookEvent buildEvent(String webhookId, JsonNode root) {
        String event = textOrThrow(root, "event");
        String dagId = textOrThrow(root, "dagId");
        String taskId = textOrThrow(root, "taskId");
        return AirflowWebhookEvent.builder()
                .webhookId(webhookId)
                .event(event)
                .dagId(dagId)
                .taskId(taskId)
                .executionDate(root.path("executionDate").asText(null))
                .status(root.path("status").asText(null))
                .payload(root.path("payload"))
                .build();
    }

    private static String textOrThrow(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || n.asText("").isBlank()) {
            throw new AirflowWebhookException(400, "MALFORMED_PAYLOAD",
                    "Webhook body missing required field: " + field);
        }
        return n.asText();
    }

    private record SignatureParts(long timestamp, String signature) {}
}
