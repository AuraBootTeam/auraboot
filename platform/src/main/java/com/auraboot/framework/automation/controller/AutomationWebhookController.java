package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Webhook receiver endpoint for automation triggers.
 * External services can POST to this endpoint to trigger WEBHOOK-type automations.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/automations/webhooks")
@RequiredArgsConstructor
@Tag(name = "Automation Webhooks", description = "Webhook receiver for automation triggers")
public class AutomationWebhookController {

    private final AutomationMapper automationMapper;
    private final AutomationTriggerService automationTriggerService;
    private final ObjectMapper objectMapper;

    @PostMapping("/{automationPid}")
    @Operation(summary = "Receive webhook", description = "Receive an external webhook to trigger an automation")
    public ApiResponse<Map<String, Object>> receiveWebhook(
            @PathVariable String automationPid,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Token", required = false) String token) {

        log.info("Received webhook for automation: pid={}", automationPid);

        Automation automation = automationMapper.findByPid(automationPid);
        if (automation == null) {
            return ApiResponse.error("Automation not found: " + automationPid);
        }

        if (!Boolean.TRUE.equals(automation.getEnabled())) {
            return ApiResponse.error("Automation is disabled");
        }

        if (!"webhook".equals(automation.getTriggerType())) {
            return ApiResponse.error("Automation is not a webhook trigger");
        }

        // Validate webhook security against the raw body bytes — not a re-serialized Map.
        // HashMap.toString() key ordering is non-canonical and would break HMAC verification.
        TriggerConfig config = automation.getTriggerConfig();
        if (config != null) {
            String validationMode = config.getValidationMode();
            if ("signature".equals(validationMode)) {
                if (!validateSignature(rawBody, signature, config.getSecret())) {
                    return ApiResponse.error("Invalid webhook signature");
                }
            } else if ("token".equals(validationMode)) {
                if (!validateToken(token, config.getSecret())) {
                    return ApiResponse.error("Invalid webhook token");
                }
            }
        }

        // Parse the raw JSON body into a Map for downstream automation logic.
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse webhook body for automation {}: {}", automationPid, e.getMessage());
            return ApiResponse.error("Invalid webhook body: not valid JSON");
        }

        // Execute automation
        Map<String, Object> triggerPayload = new HashMap<>(payload);
        triggerPayload.put("event", "webhook");

        AutomationLog logEntry = automationTriggerService.executeAutomation(
                automation, null, triggerPayload);

        return ApiResponse.success(Map.of(
                "logPid", logEntry.getPid(),
                "status", logEntry.getStatus()
        ));
    }

    /**
     * Validates an HMAC-SHA256 signature over the raw request body bytes.
     *
     * <p>The HMAC is computed over the exact UTF-8 bytes that arrived over the wire,
     * so the client must sign the same byte sequence. Comparison is constant-time
     * (MessageDigest.isEqual) to prevent timing side-channels, and hex is
     * lower-cased before comparison to make it deterministic.</p>
     *
     * @param rawBody   exact request body string (UTF-8)
     * @param signature hex-encoded HMAC provided by the caller (case-insensitive input)
     * @param secret    shared secret
     * @return true if the signature matches
     */
    private boolean validateSignature(String rawBody, String signature, String secret) {
        if (signature == null || signature.isEmpty() || secret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash); // already lowercase
            // Normalize caller-provided hex to lowercase, then compare constant-time.
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] actualBytes   = signature.toLowerCase(java.util.Locale.ROOT)
                                            .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } catch (Exception e) {
            log.warn("Failed to validate webhook signature: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates a bearer token using a constant-time byte comparison to prevent
     * timing side-channels that could leak the secret via response latency.
     *
     * @param token  token provided by the caller
     * @param secret expected token (shared secret)
     * @return true if the token matches
     */
    private boolean validateToken(String token, String secret) {
        if (token == null || secret == null) {
            return false;
        }
        byte[] tokenBytes  = token.getBytes(StandardCharsets.UTF_8);
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(tokenBytes, secretBytes);
    }
}
