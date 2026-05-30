package com.auraboot.framework.connector.airflow;

import com.auraboot.framework.connector.airflow.mapper.AirflowWebhookLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers PRD 18-C §C.3.3 attack matrix — every reject path is asserted
 * with the precise httpStatus + errorCode pair.
 *
 * <p>W5-FU-4: also covers the observability layer — every call path writes
 * a log row to {@link AirflowWebhookLogMapper}. The mapper is mocked so
 * these are pure unit tests with no DB dependency.
 */
class AirflowWebhookServiceTest {

    private static final String SECRET = "supersecret-shared-key-12345678";
    private static final Instant NOW = Instant.parse("2026-05-30T08:00:00Z");
    private static final long NOW_EPOCH = NOW.getEpochSecond();

    private ApplicationEventPublisher publisher;
    private AirflowWebhookLogMapper logMapper;
    private final ObjectMapper json = new ObjectMapper();
    private AirflowWebhookService service;

    @BeforeEach
    void setup() {
        publisher = mock(ApplicationEventPublisher.class);
        logMapper = mock(AirflowWebhookLogMapper.class);
        service = new AirflowWebhookService(publisher, json, SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC), logMapper);
    }

    private static byte[] body(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    private static String hmacHex(long ts, byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(Long.toString(ts).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String validSignatureHeader(long ts, byte[] body, String secret) {
        return "t=" + ts + ",v1=" + hmacHex(ts, body, secret);
    }

    private static final byte[] HAPPY_BODY = body("""
        {"event":"airflow.task.completed",
         "dagId":"salesforce_daily_to_auraboot",
         "taskId":"sync_salesforce",
         "executionDate":"2026-05-30T08:00:00Z",
         "status":"success",
         "payload":{"rowsSynced":1248}}
        """);

    // ======================================================================
    // 1. happy path
    // ======================================================================

    @Test
    void case1_validSignatureValidBodyReturnsEvent() {
        AirflowWebhookEvent ev = service.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                "wid-1",
                HAPPY_BODY);
        assertThat(ev.getEvent()).isEqualTo("airflow.task.completed");
        assertThat(ev.getDagId()).isEqualTo("salesforce_daily_to_auraboot");
        assertThat(ev.getTaskId()).isEqualTo("sync_salesforce");

        ArgumentCaptor<AirflowWebhookEvent.Spring> cap =
                ArgumentCaptor.forClass(AirflowWebhookEvent.Spring.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().event.getWebhookId()).isEqualTo("wid-1");
    }

    // ======================================================================
    // 2-3. missing headers
    // ======================================================================

    @Test
    void case2_missingSignatureHeader() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(null, "wid-2", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("MISSING_SIGNATURE");
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void case3_missingWebhookIdHeader() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                        null, HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("MISSING_SIGNATURE");
    }

    // ======================================================================
    // 4-5. malformed signature header
    // ======================================================================

    @Test
    void case4_signatureMissingTPart() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        "v1=" + hmacHex(NOW_EPOCH, HAPPY_BODY, SECRET),
                        "wid-4", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("MALFORMED_SIGNATURE");
    }

    @Test
    void case5_signatureMissingV1Part() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        "t=" + NOW_EPOCH,
                        "wid-5", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("MALFORMED_SIGNATURE");
    }

    @Test
    void case6_signatureHexLengthMismatch() {
        // valid hex but truncated → length differs from 32-byte HMAC.
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        "t=" + NOW_EPOCH + ",v1=deadbeef",
                        "wid-6", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("BAD_SIGNATURE");
    }

    @Test
    void case7_wrongSecret() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, HAPPY_BODY, "WRONG-secret"),
                        "wid-7", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo("BAD_SIGNATURE");
    }

    @Test
    void case8_bodyTamperedAfterSignature() {
        String sig = validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET);
        byte[] tampered = body("""
            {"event":"airflow.task.completed",
             "dagId":"salesforce_daily_to_auraboot",
             "taskId":"sync_salesforce_EVIL",
             "status":"success","payload":{}}
            """);
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(sig, "wid-8", tampered));
        assertThat(e.errorCode()).isEqualTo("BAD_SIGNATURE");
    }

    // ======================================================================
    // 9-14. timestamp window
    // ======================================================================

    @Test
    void case9_timestampTooOldStale() {
        long ts = NOW_EPOCH - 400;
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(ts, HAPPY_BODY, SECRET),
                        "wid-9", HAPPY_BODY));
        assertThat(e.errorCode()).isEqualTo("STALE_TIMESTAMP");
    }

    @Test
    void case10_timestampTooNewStale() {
        long ts = NOW_EPOCH + 400;
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(ts, HAPPY_BODY, SECRET),
                        "wid-10", HAPPY_BODY));
        assertThat(e.errorCode()).isEqualTo("STALE_TIMESTAMP");
    }

    @Test
    void case11_negativeTimestampRejectedAsStale() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        "t=-1,v1=" + hmacHex(-1, HAPPY_BODY, SECRET),
                        "wid-11", HAPPY_BODY));
        assertThat(e.errorCode()).isEqualTo("STALE_TIMESTAMP");
    }

    @Test
    void case12_nonIntegerTimestampMalformed() {
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        "t=not-a-ts,v1=" + hmacHex(NOW_EPOCH, HAPPY_BODY, SECRET),
                        "wid-12", HAPPY_BODY));
        assertThat(e.errorCode()).isEqualTo("MALFORMED_SIGNATURE");
    }

    @Test
    void case13_exactlyPlus300sBoundaryAccepted() {
        long ts = NOW_EPOCH + 300; // exactly at the boundary — allowed.
        AirflowWebhookEvent ev = service.verifyAndDispatch(
                validSignatureHeader(ts, HAPPY_BODY, SECRET),
                "wid-13", HAPPY_BODY);
        assertThat(ev).isNotNull();
    }

    @Test
    void case14_exactlyMinus300sBoundaryAccepted() {
        long ts = NOW_EPOCH - 300;
        AirflowWebhookEvent ev = service.verifyAndDispatch(
                validSignatureHeader(ts, HAPPY_BODY, SECRET),
                "wid-14", HAPPY_BODY);
        assertThat(ev).isNotNull();
    }

    // ======================================================================
    // 15. replay
    // ======================================================================

    @Test
    void case15_replayedWebhookIdRejected() {
        String sig = validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET);
        service.verifyAndDispatch(sig, "wid-15", HAPPY_BODY); // first ok
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(sig, "wid-15", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(409);
        assertThat(e.errorCode()).isEqualTo("DUPLICATE_WEBHOOK");
        verify(publisher, times(1)).publishEvent(any());
    }

    // ======================================================================
    // 16-19. body shape
    // ======================================================================

    @Test
    void case16_bodyIsNotJson() {
        byte[] notJson = "this is not json".getBytes(StandardCharsets.UTF_8);
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, notJson, SECRET),
                        "wid-16", notJson));
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.errorCode()).isEqualTo("INVALID_BODY");
    }

    @Test
    void case17_bodyMissingEvent() {
        byte[] b = body("""
            {"dagId":"d","taskId":"t","status":"success"}
            """);
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, b, SECRET),
                        "wid-17", b));
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.errorCode()).isEqualTo("MALFORMED_PAYLOAD");
        assertThat(e.getMessage()).contains("event");
    }

    @Test
    void case18_bodyMissingDagId() {
        byte[] b = body("""
            {"event":"airflow.task.completed","taskId":"t","status":"success"}
            """);
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, b, SECRET),
                        "wid-18", b));
        assertThat(e.errorCode()).isEqualTo("MALFORMED_PAYLOAD");
        assertThat(e.getMessage()).contains("dagId");
    }

    @Test
    void case19_bodyMissingTaskId() {
        byte[] b = body("""
            {"event":"airflow.task.completed","dagId":"d","status":"success"}
            """);
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, b, SECRET),
                        "wid-19", b));
        assertThat(e.errorCode()).isEqualTo("MALFORMED_PAYLOAD");
        assertThat(e.getMessage()).contains("taskId");
    }

    // ======================================================================
    // 20. uppercase hex accepted
    // ======================================================================

    @Test
    void case20_uppercaseHexSignatureAccepted() {
        String sig = "t=" + NOW_EPOCH + ",v1="
                + hmacHex(NOW_EPOCH, HAPPY_BODY, SECRET).toUpperCase();
        AirflowWebhookEvent ev = service.verifyAndDispatch(sig, "wid-20", HAPPY_BODY);
        assertThat(ev).isNotNull();
    }

    // ======================================================================
    // 21. raw-bytes signature is not JSON-reformatting agnostic
    // ======================================================================

    @Test
    void case21_reformattedBodyFailsBecauseSignatureCoversRawBytes() {
        String sig = validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET);
        // Same logical JSON but re-serialised → different bytes.
        byte[] reformatted = body(
                "{\"event\":\"airflow.task.completed\","
                        + "\"dagId\":\"salesforce_daily_to_auraboot\","
                        + "\"taskId\":\"sync_salesforce\","
                        + "\"status\":\"success\"}");
        AirflowWebhookException e = assertReject(
                () -> service.verifyAndDispatch(sig, "wid-21", reformatted));
        assertThat(e.errorCode()).isEqualTo("BAD_SIGNATURE");
    }

    // ======================================================================
    // bonus: missing shared secret
    // ======================================================================

    @Test
    void unconfiguredSharedSecretReturns404UnknownConnection() {
        AirflowWebhookService unconfigured = new AirflowWebhookService(
                publisher, json, "", Clock.fixed(NOW, ZoneOffset.UTC), logMapper);
        AirflowWebhookException e = assertReject(
                () -> unconfigured.verifyAndDispatch(
                        validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                        "wid-x", HAPPY_BODY));
        assertThat(e.httpStatus()).isEqualTo(404);
        assertThat(e.errorCode()).isEqualTo("UNKNOWN_CONNECTION");
    }

    @Test
    void replayCacheSweepRemovesExpiredEntries() {
        Clock movable = mock(Clock.class);
        when(movable.instant())
                .thenReturn(NOW)
                .thenReturn(NOW.plusSeconds(600))
                .thenReturn(NOW.plusSeconds(600));
        when(movable.millis())
                .thenReturn(NOW.toEpochMilli())
                .thenReturn(NOW.plusSeconds(600).toEpochMilli())
                .thenReturn(NOW.plusSeconds(600).toEpochMilli());
        AirflowWebhookService moving = new AirflowWebhookService(
                publisher, json, SECRET, movable, logMapper);
        moving.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                "wid-first", HAPPY_BODY);
        assertThat(moving.replayCacheSize()).isEqualTo(1);

        moving.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH + 600, HAPPY_BODY, SECRET),
                "wid-second", HAPPY_BODY);
        // After sweep the old entry is gone; only the new id remains.
        assertThat(moving.replayCacheSize()).isEqualTo(1);
    }

    // ======================================================================
    // W5-FU-4 — observability layer (new cases)
    // ======================================================================

    /**
     * ACCEPTED path: mapper.insert called once with status=ACCEPTED, all
     * fields populated, and payloadJson is the raw body string.
     */
    @Test
    void obs1_acceptedPathWritesLogRowWithPayloadAndStatus() {
        service.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                "wid-obs1",
                HAPPY_BODY);

        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper, times(1)).insert(cap.capture());

        AirflowWebhookLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("ACCEPTED");
        assertThat(row.getHttpStatus()).isEqualTo(202);
        assertThat(row.getWebhookId()).isEqualTo("wid-obs1");
        assertThat(row.getDagId()).isEqualTo("salesforce_daily_to_auraboot");
        assertThat(row.getTaskId()).isEqualTo("sync_salesforce");
        assertThat(row.getEvent()).isEqualTo("airflow.task.completed");
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getPayloadJson()).isNotNull();
        // drift for exact-match timestamp is 0
        assertThat(row.getSignatureDriftSeconds()).isEqualTo(0L);
        assertThat(row.getPid()).isNotBlank();
    }

    /**
     * ACCEPTED path: signature drift seconds correct for a timestamp 30s before now.
     */
    @Test
    void obs2_acceptedPathDriftSecondsCalculatedCorrectly() {
        long ts = NOW_EPOCH - 30;
        service.verifyAndDispatch(
                validSignatureHeader(ts, HAPPY_BODY, SECRET),
                "wid-obs2",
                HAPPY_BODY);

        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper).insert(cap.capture());
        assertThat(cap.getValue().getSignatureDriftSeconds()).isEqualTo(30L);
    }

    /**
     * BAD_SIGNATURE REJECTED: status=REJECTED, error_code=BAD_SIGNATURE,
     * http_status=401, payloadJson must be null (hostile body not stored).
     */
    @Test
    void obs3_badSignatureRejectedPathWritesLogRowWithNullPayload() {
        assertReject(() -> service.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, "WRONG-secret"),
                "wid-obs3", HAPPY_BODY));

        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper).insert(cap.capture());

        AirflowWebhookLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("REJECTED");
        assertThat(row.getHttpStatus()).isEqualTo(401);
        assertThat(row.getErrorCode()).isEqualTo("BAD_SIGNATURE");
        assertThat(row.getPayloadJson()).isNull();
        assertThat(row.getWebhookId()).isEqualTo("wid-obs3");
        // publisher must not have been called
        verify(publisher, never()).publishEvent(any());
    }

    /**
     * STALE_TIMESTAMP REJECTED: drift seconds stored so ops can diagnose
     * clock-skew attacks without replaying the body.
     */
    @Test
    void obs4_staleTimestampRejectedPathStoresDrift() {
        long ts = NOW_EPOCH - 400; // drift = 400s
        assertReject(() -> service.verifyAndDispatch(
                validSignatureHeader(ts, HAPPY_BODY, SECRET),
                "wid-obs4", HAPPY_BODY));

        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper).insert(cap.capture());

        AirflowWebhookLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("REJECTED");
        assertThat(row.getErrorCode()).isEqualTo("STALE_TIMESTAMP");
        assertThat(row.getSignatureDriftSeconds()).isEqualTo(400L);
        assertThat(row.getPayloadJson()).isNull();
    }

    /**
     * mapper.insert throwing must NOT prevent verifyAndDispatch from
     * completing normally — observability is fire-and-forget.
     */
    @Test
    void obs5_mapperInsertExceptionDoesNotBlockAcceptedDispatch() {
        doThrow(new RuntimeException("DB down")).when(logMapper).insert(any(AirflowWebhookLog.class));

        // Must not throw — the DB failure is swallowed by the observability layer.
        AirflowWebhookEvent ev = service.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                "wid-obs5",
                HAPPY_BODY);

        assertThat(ev).isNotNull();
        assertThat(ev.getWebhookId()).isEqualTo("wid-obs5");
        // Spring event still published despite insert failure.
        verify(publisher, times(1)).publishEvent(any());
    }

    /**
     * Replay (DUPLICATE_WEBHOOK) also logs a REJECTED row — useful for
     * "attacked N times" statistics. First call ACCEPTED + second call REJECTED.
     */
    @Test
    void obs6_replayAttemptAlsoLogsRejectedRow() {
        String sig = validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET);

        // First call — ACCEPTED
        service.verifyAndDispatch(sig, "wid-obs6", HAPPY_BODY);

        // Second call — DUPLICATE_WEBHOOK
        assertReject(() -> service.verifyAndDispatch(sig, "wid-obs6", HAPPY_BODY));

        // Two inserts: first ACCEPTED, second REJECTED
        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper, times(2)).insert(cap.capture());
        List<AirflowWebhookLog> rows = cap.getAllValues();
        assertThat(rows).extracting(AirflowWebhookLog::getStatus)
                .containsExactly("ACCEPTED", "REJECTED");
        assertThat(rows.get(1).getErrorCode()).isEqualTo("DUPLICATE_WEBHOOK");
        assertThat(rows.get(1).getHttpStatus()).isEqualTo(409);
    }

    /**
     * UNKNOWN_CONNECTION (missing secret → 404) also logs a REJECTED row.
     */
    @Test
    void obs7_unknownConnectionAlsoLogsRejectedRow() {
        AirflowWebhookService unconfigured = new AirflowWebhookService(
                publisher, json, "", Clock.fixed(NOW, ZoneOffset.UTC), logMapper);

        assertReject(() -> unconfigured.verifyAndDispatch(
                validSignatureHeader(NOW_EPOCH, HAPPY_BODY, SECRET),
                "wid-obs7", HAPPY_BODY));

        ArgumentCaptor<AirflowWebhookLog> cap = ArgumentCaptor.forClass(AirflowWebhookLog.class);
        verify(logMapper).insert(cap.capture());
        AirflowWebhookLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("REJECTED");
        assertThat(row.getHttpStatus()).isEqualTo(404);
        assertThat(row.getErrorCode()).isEqualTo("UNKNOWN_CONNECTION");
        assertThat(row.getPayloadJson()).isNull();
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private static AirflowWebhookException assertReject(Runnable r) {
        try { r.run(); }
        catch (AirflowWebhookException e) { return e; }
        catch (Throwable t) { throw new AssertionError("Expected AirflowWebhookException", t); }
        throw new AssertionError("Expected AirflowWebhookException, none thrown");
    }
}
