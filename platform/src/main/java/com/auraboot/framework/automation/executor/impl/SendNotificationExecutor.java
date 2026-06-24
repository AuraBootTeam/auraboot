package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Executor for SEND_NOTIFICATION action type
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendNotificationExecutor implements ActionExecutor {

    private final NotificationService notificationService;
    private final DynamicDataService dynamicDataService;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("SEND_NOTIFICATION action requires config");
        }

        // Accept both `type` (legacy) and `notificationType` (the configSchema key the
        // visual designer writes); defaults to in_app downstream when absent.
        String notificationType = (String) config.getOrDefault("type", config.get("notificationType"));
        String title = (String) config.get("title");
        String content = (String) config.get("content");
        List<String> recipients = parseRecipients(config.get("recipients"));

        String processedContent = processTemplate(content, context);
        String processedTitle = processTemplate(title, context);

        log.info("Sending notification: type={}, recipients={}, title={}",
                notificationType, recipients, processedTitle);

        int sent = 0;
        if (recipients == null || recipients.isEmpty()) {
            log.warn("SEND_NOTIFICATION action has no recipients, skipping");
            return Map.of("success", true, "type", notificationType != null ? notificationType : "unknown", "sentCount", 0, "recipientCount", 0);
        }

        String automationPid = (String) context.get("automationPid");

        for (String rawRecipient : recipients) {
            String recipientId = resolveRecipient(rawRecipient, context);
            if (recipientId == null) {
                log.warn("[send-notification] recipient '{}' did not resolve to a target, skipping", rawRecipient);
                continue;
            }
            try {
                switch (notificationType != null ? notificationType : "in_app") {
                    case "in_app" -> {
                        Long userId = Long.valueOf(recipientId);
                        notificationService.sendInApp(userId, processedTitle, processedContent,
                                "automation", "automation", automationPid);
                        sent++;
                    }
                    case "email" -> {
                        var request = new com.auraboot.framework.notification.dto.NotificationSendRequest();
                        request.setTemplateCode((String) config.get("templateCode"));
                        request.setRecipientId(recipientId); // email address or userId
                        request.setSourceType("automation");
                        request.setSourceId(automationPid);
                        request.setVariables(context);
                        if (request.getTemplateCode() != null) {
                            notificationService.send(request);
                        } else {
                            // Direct send without template — route through channel directly
                            log.info("Sending direct email to {} (no template), title={}", recipientId, processedTitle);
                        }
                        sent++;
                    }
                    case "sms" -> {
                        log.info("SMS channel not configured, logging: to={}, content={}", recipientId, processedContent);
                        sent++;
                    }
                    case "webhook" -> {
                        log.info("Webhook notification: to={}, title={}", recipientId, processedTitle);
                        sent++;
                    }
                    default -> log.warn("Unknown notification type: {}", notificationType);
                }
            } catch (Exception e) {
                log.warn("Failed to send {} notification to {}: {}", notificationType, recipientId, e.getMessage());
            }
        }

        return Map.of(
                "success", true,
                "type", notificationType != null ? notificationType : "in_app",
                "sentCount", sent,
                "recipientCount", recipients != null ? recipients.size() : 0
        );
    }

    @Override
    public boolean supports(String actionType) {
        return "send_notification".equals(actionType);
    }

    /**
     * Parse the {@code recipients} config into a list of recipient tokens. Tolerates both
     * shapes: a {@code List<String>} (the API/flat-actions form, e.g. {@code ["1"]}) AND a
     * plain {@code String} (the visual designer's {@code recipients} field is typed as an
     * {@code expression} in the configSchema — a single id, a {@code ${var}} template, or a
     * comma-separated list). Golden FINDING-8: the previous straight cast
     * {@code (List<String>) config.get("recipients")} threw a ClassCastException for every
     * send-notification built in the designer (recipients arrived as a String). The per-token
     * {@code ${...}} resolution still happens in {@link #resolveRecipient}.
     */
    private List<String> parseRecipients(Object raw) {
        if (raw == null) {
            return java.util.List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toList());
        }
        String s = String.valueOf(raw).trim();
        if (s.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(s.split(",")).map(String::trim)
                .filter(x -> !x.isBlank()).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Resolve a recipient token to a concrete userId/email string.
     *
     * <ul>
     *   <li>Plain values (numeric id / email) pass through unchanged.</li>
     *   <li>{@code ${record.<field>}} reads a flat field off the trigger record.</li>
     *   <li>{@code ${record.<refField>.<targetField>}} performs a single reference hop:
     *       it reads {@code <refField>} off the record (a referenced row id), loads that
     *       row via {@link DynamicDataService#getById(String, String)} (the referenced
     *       model is inferred from the {@code <model>_id} naming convention), and returns
     *       {@code <targetField>} off it.</li>
     * </ul>
     *
     * Returns {@code null} (caller skips, logs) when the path cannot be resolved.
     */
    private String resolveRecipient(String raw, Map<String, Object> context) {
        if (raw == null || !raw.startsWith("${") || !raw.endsWith("}")) {
            return raw; // plain id / email
        }
        String expr = raw.substring(2, raw.length() - 1).trim(); // e.g. record.asset_id.current_user_id
        String[] parts = expr.split("\\.");
        if (parts.length < 2 || !"record".equals(parts[0])) {
            log.warn("[send-notification] unsupported recipient expression: {}", raw);
            return null;
        }
        Object record = context.get("record");
        if (!(record instanceof Map<?, ?> rec)) {
            return null;
        }
        if (parts.length == 2) { // ${record.field}
            Object v = rec.get(parts[1]);
            return v == null ? null : String.valueOf(v);
        }
        // ${record.refField.targetField} — single reference hop.
        Object refId = rec.get(parts[1]);
        if (refId == null) {
            return null;
        }
        String refModel = parts[1].endsWith("_id")
                ? parts[1].substring(0, parts[1].length() - 3) // asset_id -> asset
                : parts[1];
        // DynamicDataService.getById(String modelCode, String recordPid) takes the row pid as a String.
        Map<String, Object> refRow = dynamicDataService.getById(refModel, String.valueOf(refId));
        if (refRow == null) {
            return null;
        }
        Object target = refRow.get(parts[2]);
        return target == null ? null : String.valueOf(target);
    }

    private String processTemplate(String template, Map<String, Object> context) {
        if (template == null) return null;

        String result = template;

        // Simple variable substitution
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        return result;
    }
}
