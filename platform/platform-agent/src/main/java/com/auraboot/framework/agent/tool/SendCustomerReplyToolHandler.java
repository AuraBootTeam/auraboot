package com.auraboot.framework.agent.tool;

import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Built-in tool handler for sending customer reply emails.
 * Triggered by CS Agent when it drafts a reply. Requires approval (L2) before execution.
 *
 * <p>This is a generic platform notification capability: on execution it sends the email and
 * records a send-log row. It intentionally carries no business (CRM) coupling — logging a CRM
 * activity for the reply is the agent's responsibility, driven from its prompt via
 * {@code cmd:crm:create_activity}, so OSS core stays free of vertical command codes
 * (see scripts/check-agent-eval-boundary.mjs and the cs_agent seed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendCustomerReplyToolHandler {

    public static final String TOOL_CODE = "send_customer_reply";

    private final EmailSender emailSender;
    private final DynamicDataMapper dynamicDataMapper;

    public Map<String, Object> execute(Map<String, Object> params, Long tenantId) {
        String recipientEmail = (String) params.get("recipient_email");
        String subject = (String) params.get("reply_subject");
        String body = (String) params.get("reply_body");
        Object complaintIdObj = params.get("complaint_id");

        if (recipientEmail == null || subject == null || body == null) {
            return Map.of("success", false, "error", "Missing required parameters: recipient_email, reply_subject, reply_body");
        }

        // 1. Send email
        try {
            emailSender.send(recipientEmail, subject, body);
        } catch (Exception e) {
            log.error("Failed to send customer reply email to {}: {}", recipientEmail, e.getMessage(), e);
            logSend(tenantId, recipientEmail, subject, body, "failed", e.getMessage());
            return Map.of("success", false, "error", "Email send failed: " + e.getMessage());
        }

        // 2. Log successful send
        logSend(tenantId, recipientEmail, subject, body, "sent", null);

        log.info("Customer reply sent to {} for complaint {}", recipientEmail, complaintIdObj);
        return Map.of("success", true, "message", "Email sent to " + recipientEmail);
    }

    private void logSend(Long tenantId, String recipient, String subject, String body,
                         String status, String errorMessage) {
        Map<String, Object> logData = new HashMap<>();
        // ab_notification_send_log.id is a bigint IDENTITY column — let it auto-generate.
        // (Do not set it to a ULID string; that fails the INSERT with a type mismatch.)
        logData.put("tenant_id", tenantId);
        logData.put("template_code", "cs_agent_reply");
        logData.put("channel", "email");
        logData.put("recipient", recipient);
        logData.put("subject", subject);
        logData.put("content", body);
        logData.put("status", status);
        logData.put("error_message", errorMessage);
        logData.put("sent_at", LocalDateTime.now());
        logData.put("created_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_notification_send_log", logData);
    }
}
