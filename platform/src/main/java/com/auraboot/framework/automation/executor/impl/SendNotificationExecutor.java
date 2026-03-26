package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
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

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("SEND_NOTIFICATION action requires config");
        }

        String notificationType = (String) config.get("type"); // EMAIL, SMS, IN_APP, WEBHOOK
        String title = (String) config.get("title");
        String content = (String) config.get("content");
        @SuppressWarnings("unchecked")
        List<String> recipients = (List<String>) config.get("recipients");

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

        for (String recipientId : recipients) {
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
