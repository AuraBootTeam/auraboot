package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

    @PostMapping("/{automationPid}")
    @Operation(summary = "Receive webhook", description = "Receive an external webhook to trigger an automation")
    public ApiResponse<Map<String, Object>> receiveWebhook(
            @PathVariable String automationPid,
            @RequestBody Map<String, Object> payload,
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

        // Validate webhook security
        TriggerConfig config = automation.getTriggerConfig();
        if (config != null) {
            String validationMode = config.getValidationMode();
            if ("signature".equals(validationMode)) {
                if (!validateSignature(payload.toString(), signature, config.getSecret())) {
                    return ApiResponse.error("Invalid webhook signature");
                }
            } else if ("token".equals(validationMode)) {
                if (config.getSecret() != null && !config.getSecret().equals(token)) {
                    return ApiResponse.error("Invalid webhook token");
                }
            }
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

    private boolean validateSignature(String body, String signature, String secret) {
        if (signature == null || secret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return signature.equalsIgnoreCase(expected);
        } catch (Exception e) {
            log.warn("Failed to validate webhook signature: {}", e.getMessage());
            return false;
        }
    }
}
