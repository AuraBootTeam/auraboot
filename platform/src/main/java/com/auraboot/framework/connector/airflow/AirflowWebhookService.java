package com.auraboot.framework.connector.airflow;

import com.auraboot.framework.connector.airflow.mapper.AirflowWebhookLogMapper;
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
import java.util.UUID;

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
 *
 * <p>Observability (W5-FU-4): every call — accepted or rejected — is written
 * to {@code airflow_webhook_log} via {@link AirflowWebhookLogMapper}. The
 * insert is fire-and-forget: a failure there must never block the main
 * verify/dispatch path. See {@link #persistLog} for the explicit rationale.
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

    /** Observability mapper — nullable in unit tests that do not need it. */
    private final AirflowWebhookLogMapper logMapper;

    /**
     * In-memory replay cache: {@code webhookId → expiryEpochMs}. Bounded by a
     * scheduled sweep removing entries whose expiry is in the past.
     */
    private final Map<String, Long> seen = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AirflowWebhookService(ApplicationEventPublisher publisher,
                                 ObjectMapper jsonMapper,
                                 @Value("${auraboot.airflow.webhook.shared-secret:}") String sharedSecret,
                                 AirflowWebhookLogMapper logMapper) {
        this(publisher, jsonMapper, sharedSecret, Clock.systemUTC(), logMapper);
    }

    /** Test seam — logMapper may be null for tests that do not need observability assertions. */
    AirflowWebhookService(ApplicationEventPublisher publisher,
                          ObjectMapper jsonMapper,
                          String sharedSecret,
                          Clock clock) {
        this(publisher, jsonMapper, sharedSecret, clock, null);
    }

    /** Full test seam. */
    AirflowWebhookService(ApplicationEventPublisher publisher,
                          ObjectMapper jsonMapper,
                          String sharedSecret,
                          Clock clock,
                          AirflowWebhookLogMapper logMapper) {
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
        this.clock = clock;
        this.logMapper = logMapper;
    }

    /**
     * Verify + dispatch. Throws {@link AirflowWebhookException} on any
     * failure; otherwise returns the verified event for the controller to
     * relay back to the caller.
     *
     * <p>Every call — accepted or rejected — is persisted to
     * {@code airflow_webhook_log} before the exception is re-thrown so the
     * audit log is always complete regardless of the caller's error handling.
     */
    public AirflowWebhookEvent verifyAndDispatch(String signatureHeader,
                                                 String webhookIdHeader,
                                                 byte[] rawBody) {
        if (sharedSecret.isBlank()) {
            AirflowWebhookException ex = new AirflowWebhookException(404, "UNKNOWN_CONNECTION",
                    "No shared_secret configured (auraboot.airflow.webhook.shared-secret unset)");
            persistRejected(webhookIdHeader, null, 404, "UNKNOWN_CONNECTION", null);
            throw ex;
        }
        if (webhookIdHeader == null || webhookIdHeader.isBlank()) {
            AirflowWebhookException ex = new AirflowWebhookException(401, "MISSING_SIGNATURE",
                    "Missing " + WEBHOOK_ID_HEADER + " header");
            persistRejected(null, null, 401, "MISSING_SIGNATURE", null);
            throw ex;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            AirflowWebhookException ex = new AirflowWebhookException(401, "MISSING_SIGNATURE",
                    "Missing " + SIGNATURE_HEADER + " header");
            persistRejected(webhookIdHeader, null, 401, "MISSING_SIGNATURE", null);
            throw ex;
        }

        // Parse signature — may itself throw MALFORMED_SIGNATURE or STALE_TIMESTAMP
        // (negative timestamp). We need the timestamp for drift calc if we catch.
        SignatureParts parts;
        try {
            parts = parseSignature(signatureHeader);
        } catch (AirflowWebhookException ex) {
            // Drift is undefined if timestamp parse fails entirely.
            Long drift = tryExtractDrift(signatureHeader);
            persistRejected(webhookIdHeader, drift, ex.httpStatus(), ex.errorCode(), null);
            throw ex;
        }

        long now = clock.instant().getEpochSecond();
        long drift = Math.abs(now - parts.timestamp);
        if (drift > TIME_WINDOW_SECONDS) {
            AirflowWebhookException ex = new AirflowWebhookException(401, "STALE_TIMESTAMP",
                    "Timestamp drift " + drift + "s exceeds window " + TIME_WINDOW_SECONDS + "s");
            persistRejected(webhookIdHeader, drift, 401, "STALE_TIMESTAMP", null);
            throw ex;
        }

        byte[] hmac = computeHmac(parts.timestamp + "." + new String(rawBody, StandardCharsets.UTF_8),
                sharedSecret);
        byte[] expected = HexFormat.of().parseHex(parts.signature.toLowerCase());
        if (expected.length != hmac.length || !MessageDigest.isEqual(hmac, expected)) {
            AirflowWebhookException ex = new AirflowWebhookException(401, "BAD_SIGNATURE",
                    "HMAC verification failed");
            persistRejected(webhookIdHeader, drift, 401, "BAD_SIGNATURE", null);
            throw ex;
        }

        // Replay guard.
        synchronized (seen) {
            long nowMs = clock.millis();
            // Lazy sweep.
            seen.entrySet().removeIf(e -> e.getValue() < nowMs);
            if (seen.containsKey(webhookIdHeader)) {
                AirflowWebhookException ex = new AirflowWebhookException(409, "DUPLICATE_WEBHOOK",
                        "Webhook id replayed: " + webhookIdHeader);
                // Log the replay attempt — useful for "attacked N times" statistics.
                persistRejected(webhookIdHeader, drift, 409, "DUPLICATE_WEBHOOK", null);
                throw ex;
            }
            seen.put(webhookIdHeader, nowMs + REPLAY_CACHE_TTL_MS);
        }

        JsonNode root;
        try {
            root = parseBody(rawBody);
        } catch (AirflowWebhookException ex) {
            persistRejected(webhookIdHeader, drift, ex.httpStatus(), ex.errorCode(), null);
            throw ex;
        }

        AirflowWebhookEvent event;
        try {
            event = buildEvent(webhookIdHeader, root);
        } catch (AirflowWebhookException ex) {
            persistRejected(webhookIdHeader, drift, ex.httpStatus(), ex.errorCode(), null);
            throw ex;
        }

        // All checks passed — persist ACCEPTED row before publishing the event.
        persistAccepted(event, drift, rawBody);

        publisher.publishEvent(new AirflowWebhookEvent.Spring(this, event));
        return event;
    }

    /** Test/observability seam. */
    public int replayCacheSize() {
        synchronized (seen) {
            return seen.size();
        }
    }

    // -- observability helpers ---------------------------------------------

    /**
     * Persist an ACCEPTED log row. Payload is stored as-is (raw body JSON
     * string) so downstream analytics can read connector-specific fields.
     *
     * <p><b>Exception-safety</b>: this method swallows all exceptions.
     * Observability must not block or corrupt the primary dispatch path.
     * The catch(Exception) here is intentional and documented — it is the
     * canonical fire-and-forget observability pattern (§8 AGENTS.md allows
     * catch(Exception) with explicit Javadoc justification).
     */
    private void persistAccepted(AirflowWebhookEvent event, long driftSeconds, byte[] rawBody) {
        try {
            if (logMapper == null) return;
            AirflowWebhookLog row = AirflowWebhookLog.builder()
                    .pid(newPid())
                    .webhookId(event.getWebhookId())
                    .event(event.getEvent())
                    .dagId(event.getDagId())
                    .taskId(event.getTaskId())
                    .status("ACCEPTED")
                    .httpStatus(202)
                    .signatureDriftSeconds(driftSeconds)
                    .payloadJson(new String(rawBody, StandardCharsets.UTF_8))
                    .build();
            logMapper.insert(row);
        } catch (Exception e) {
            // Intentional: observability write failure must not break the verified dispatch path.
            // Log at WARN so the anomaly is visible without alarming on-call unnecessarily.
            log.warn("[airflow-webhook] Failed to persist ACCEPTED log row for webhookId={}: {}",
                    event.getWebhookId(), e.getMessage(), e);
        }
    }

    /**
     * Persist a REJECTED log row. {@code payloadJson} is intentionally omitted
     * (null) — the body may be from a hostile sender; persisting unverified
     * content would pollute the audit trail with attacker-controlled data.
     *
     * <p><b>Exception-safety</b>: same fire-and-forget contract as
     * {@link #persistAccepted} — observability must not interfere with the
     * exception propagation to the caller. The catch(Exception) is intentional
     * per AGENTS.md §8 exception pattern (explicit Javadoc required).
     *
     * @param webhookId           value of X-AuraBoot-Webhook-Id; may be null if the
     *                            header itself was missing
     * @param signatureDriftSecs  |now - t| in seconds; null if timestamp unparseable
     * @param httpStatus          HTTP status that will be returned to the caller
     * @param errorCode           error code from {@link AirflowWebhookException}
     * @param tenantId            owning tenant; null during single-tenant transition
     */
    private void persistRejected(String webhookId, Long signatureDriftSecs,
                                  int httpStatus, String errorCode, Long tenantId) {
        try {
            if (logMapper == null) return;
            // Use a stable fallback id when the header is absent so the row is still queryable.
            String effectiveWebhookId = (webhookId != null && !webhookId.isBlank())
                    ? webhookId
                    : "unknown-" + UUID.randomUUID();
            AirflowWebhookLog row = AirflowWebhookLog.builder()
                    .pid(newPid())
                    .webhookId(effectiveWebhookId)
                    .tenantId(tenantId)
                    .status("REJECTED")
                    .httpStatus(httpStatus)
                    .errorCode(errorCode)
                    .signatureDriftSeconds(signatureDriftSecs)
                    // payloadJson intentionally null — do not store unverified body content.
                    .payloadJson(null)
                    .build();
            logMapper.insert(row);
        } catch (Exception e) {
            // Intentional: observability write failure must not interfere with exception propagation.
            log.warn("[airflow-webhook] Failed to persist REJECTED log row webhookId={} errorCode={}: {}",
                    webhookId, errorCode, e.getMessage(), e);
        }
    }

    /**
     * Best-effort extraction of the drift value from a signature header that
     * may be partially parseable. Used when {@link #parseSignature} throws
     * before we reach the drift check.
     */
    private Long tryExtractDrift(String signatureHeader) {
        if (signatureHeader == null) return null;
        try {
            for (String part : signatureHeader.split(",")) {
                String t = part.trim();
                if (t.startsWith("t=")) {
                    long ts = Long.parseLong(t.substring(2));
                    if (ts >= 0) {
                        return Math.abs(clock.instant().getEpochSecond() - ts);
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort only; caller handles null.
        }
        return null;
    }

    /** Generate a ULID-shaped pid. Simple UUID-based fallback; full ULID in follow-up. */
    private static String newPid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // -- core helpers -------------------------------------------------------

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
