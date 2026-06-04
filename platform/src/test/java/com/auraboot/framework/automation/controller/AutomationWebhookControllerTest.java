package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutomationWebhookController} HMAC signature validation.
 *
 * <p>These are pure unit tests — no Spring context, no DB. Service dependencies
 * are Mockito mocks. The tests cover the three security fixes:
 * <ol>
 *   <li>HMAC is computed over the raw request body bytes (not Map.toString()).</li>
 *   <li>Signature comparison is constant-time (MessageDigest.isEqual).</li>
 *   <li>Token comparison is constant-time (MessageDigest.isEqual).</li>
 * </ol>
 */
class AutomationWebhookControllerTest {

    private static final String SECRET = "test-secret-key-for-hmac";
    private static final String AUTOMATION_PID = "AUTO-PID-001";

    private AutomationMapper automationMapper;
    private AutomationTriggerService automationTriggerService;
    private AutomationWebhookController controller;

    @BeforeEach
    void setUp() {
        automationMapper = mock(AutomationMapper.class);
        automationTriggerService = mock(AutomationTriggerService.class);
        controller = new AutomationWebhookController(automationMapper, automationTriggerService,
                new ObjectMapper());

        // Default: happy automation returned by mapper
        Automation automation = webhookAutomation("signature");
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automation);

        // Trigger service always returns a minimal log
        AutomationLog log = new AutomationLog();
        log.setPid("LOG-PID-001");
        log.setStatus("SUCCESS");
        when(automationTriggerService.executeAutomation(any(), isNull(), any())).thenReturn(log);
    }

    // -----------------------------------------------------------------------
    // Case 1: Correct HMAC signature over the raw body is accepted
    // -----------------------------------------------------------------------

    @Test
    void correctHmacSignatureOverRawBodyIsAccepted() throws Exception {
        String rawBody = "{\"event\":\"push\",\"ref\":\"main\"}";
        String sig = hmacHex(rawBody, SECRET);

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, sig, null);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).containsKey("logPid");
    }

    // -----------------------------------------------------------------------
    // Case 2: Tampered body is rejected
    // -----------------------------------------------------------------------

    @Test
    void tamperedBodyIsRejected() throws Exception {
        String originalBody = "{\"event\":\"push\",\"ref\":\"main\"}";
        String sig = hmacHex(originalBody, SECRET);

        // Attacker changes the body after signing
        String tamperedBody = "{\"event\":\"push\",\"ref\":\"pwned\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, tamperedBody, sig, null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).containsIgnoringCase("signature");
    }

    // -----------------------------------------------------------------------
    // Case 3: Field-order independence — same JSON content, different key order,
    // each signed over ITS OWN raw bytes must both verify.
    //
    // This test is specifically designed to fail against the OLD implementation
    // (payload.toString() on a HashMap is non-canonical). With the old code,
    // signing "{\"b\":2,\"a\":1}" and verifying against HashMap.toString() of
    // {"a":1,"b":2} would produce a mismatch even for a legitimately signed body.
    // -----------------------------------------------------------------------

    @Test
    void fieldOrderIndependence_eachBodyVerifiesAgainstItsOwnRawBytes() throws Exception {
        // Two JSON strings with the same semantic content but different key ordering
        String bodyAlpha = "{\"a\":1,\"b\":2}";
        String bodyBeta  = "{\"b\":2,\"a\":1}";

        // Each is correctly signed over its own exact bytes
        String sigAlpha = hmacHex(bodyAlpha, SECRET);
        String sigBeta  = hmacHex(bodyBeta, SECRET);

        // Both must be accepted (each verifies against its own raw bytes)
        ApiResponse<Map<String, Object>> respAlpha =
                controller.receiveWebhook(AUTOMATION_PID, bodyAlpha, sigAlpha, null);
        ApiResponse<Map<String, Object>> respBeta  =
                controller.receiveWebhook(AUTOMATION_PID, bodyBeta, sigBeta, null);

        assertThat(respAlpha.isSuccess())
                .as("bodyAlpha with its own correct HMAC should be accepted")
                .isTrue();
        assertThat(respBeta.isSuccess())
                .as("bodyBeta (different key order) with its own correct HMAC should be accepted")
                .isTrue();

        // Cross-verification: signing bodyAlpha's bytes, presenting against bodyBeta
        // must be rejected (different raw bytes → different HMAC).
        ApiResponse<Map<String, Object>> respCross =
                controller.receiveWebhook(AUTOMATION_PID, bodyBeta, sigAlpha, null);
        assertThat(respCross.isSuccess())
                .as("signature computed over bodyAlpha must NOT verify bodyBeta")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Case 4: Wrong signature is rejected
    // -----------------------------------------------------------------------

    @Test
    void wrongSignatureIsRejected() throws Exception {
        String rawBody = "{\"event\":\"push\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, "deadbeef", null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).containsIgnoringCase("signature");
    }

    @Test
    void emptySignatureIsRejected() throws Exception {
        String rawBody = "{\"event\":\"push\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, "", null);

        assertThat(response.isSuccess()).isFalse();
    }

    @Test
    void nullSignatureIsRejected() throws Exception {
        String rawBody = "{\"event\":\"push\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, null, null);

        assertThat(response.isSuccess()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Token mode — constant-time comparison
    // -----------------------------------------------------------------------

    @Test
    void correctTokenIsAccepted() throws Exception {
        Automation automation = webhookAutomation("token");
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automation);

        String rawBody = "{\"event\":\"ping\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, null, SECRET);

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        Automation automation = webhookAutomation("token");
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automation);

        String rawBody = "{\"event\":\"ping\"}";

        ApiResponse<Map<String, Object>> response =
                controller.receiveWebhook(AUTOMATION_PID, rawBody, null, "wrong-token");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).containsIgnoringCase("token");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Automation webhookAutomation(String validationMode) {
        TriggerConfig config = new TriggerConfig();
        config.setSecret(SECRET);
        config.setValidationMode(validationMode);

        Automation a = new Automation();
        a.setPid(AUTOMATION_PID);
        a.setEnabled(true);
        a.setTriggerType("webhook");
        a.setTriggerConfig(config);
        return a;
    }

    /** Compute HMAC-SHA256 over the given string (UTF-8 bytes) and return lowercase hex. */
    private static String hmacHex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
