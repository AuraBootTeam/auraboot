package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final String GROUP_PREFIX = "GROUP:";
    private static final String TEAM_PREFIX = "TEAM:";
    private static final String PHONE_PREFIX = "PHONE:";
    private static final String DEFAULT_SMS_TEMPLATE = "direct_message";
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

    private final NotificationService notificationService;
    private final DynamicDataService dynamicDataService;
    private final SmsSenderRouter smsSenderRouter;

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

        String processedContent = AutomationActionValueResolver.resolveString(content, context);
        String processedTitle = AutomationActionValueResolver.resolveString(title, context);

        log.info("Sending notification: type={}, recipients={}, title={}",
                notificationType, recipients, processedTitle);

        int sent = 0;
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("SEND_NOTIFICATION action requires recipients");
        }

        String automationPid = context.get("automationPid") != null
                ? String.valueOf(context.get("automationPid"))
                : String.valueOf(context.getOrDefault("_automation_id", ""));
        String effectiveType = notificationType != null ? notificationType : "in_app";

        for (String rawRecipient : recipients) {
            String recipientId = resolveRecipient(rawRecipient, context);
            if (recipientId == null) {
                log.warn("[send-notification] recipient '{}' did not resolve to a target, skipping", rawRecipient);
                continue;
            }
            try {
                switch (effectiveType) {
                    case "in_app" -> {
                        sendInApp(recipientId, processedTitle, processedContent, automationPid);
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
                        sendSms(recipientId, processedTitle, processedContent, config, context);
                        sent++;
                    }
                    case "webhook" -> {
                        log.info("Webhook notification: to={}, title={}", recipientId, processedTitle);
                        sent++;
                    }
                    default -> log.warn("Unknown notification type: {}", notificationType);
                }
            } catch (Exception e) {
                log.warn("Failed to send {} notification to {}: {}", effectiveType, recipientId, e.getMessage());
                if ("sms".equals(effectiveType)) {
                    throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
                }
            }
        }

        return Map.of(
                "success", true,
                "type", effectiveType,
                "sentCount", sent,
                "recipientCount", recipients != null ? recipients.size() : 0
        );
    }

    private void sendInApp(String recipientId, String title, String content, String automationPid) {
        if (recipientId.startsWith(USER_PREFIX)) {
            notificationService.sendInApp(parseUserId(recipientId), title, content,
                    "automation", "automation", automationPid);
            return;
        }
        if (recipientId.startsWith(ROLE_PREFIX)) {
            notificationService.sendInAppToRecipient("role", suffix(recipientId, ROLE_PREFIX), title, content,
                    "automation", "automation", automationPid);
            return;
        }
        if (recipientId.startsWith(GROUP_PREFIX)) {
            notificationService.sendInAppToRecipient("group", suffix(recipientId, GROUP_PREFIX), title, content,
                    "automation", "automation", automationPid);
            return;
        }
        if (recipientId.startsWith(TEAM_PREFIX)) {
            notificationService.sendInAppToRecipient("team", suffix(recipientId, TEAM_PREFIX), title, content,
                    "automation", "automation", automationPid);
            return;
        }
        notificationService.sendInApp(Long.valueOf(recipientId), title, content,
                "automation", "automation", automationPid);
    }

    private void sendSms(String recipientId, String title, String content,
                         Map<String, Object> config, Map<String, Object> context) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("SMS notification requires content");
        }
        String phone = normalizeSmsRecipient(recipientId);
        String template = firstNonBlank(
                string(config.get("template")),
                string(config.get("templateCode")),
                DEFAULT_SMS_TEMPLATE);
        SmsSenderRouter.RoutedSmsResult routed = smsSenderRouter.sendWithRealProvider(
                phone,
                template,
                smsParams(content, title, context));
        SmsSendResult result = routed.sendResult();
        if (result == null || !result.isSuccess()) {
            String error = result != null ? result.getErrorMessage() : "empty result";
            throw new IllegalStateException("SMS notification failed via " + routed.providerCode() + ": " + error);
        }
    }

    private static String normalizeSmsRecipient(String recipientId) {
        String value = recipientId != null ? recipientId.trim() : "";
        if (value.startsWith(PHONE_PREFIX)) {
            value = value.substring(PHONE_PREFIX.length()).trim();
        }
        if (!PHONE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "SMS notification target must be a phone number or PHONE:<number>: " + recipientId);
        }
        return value;
    }

    private static Map<String, String> smsParams(String content, String title, Map<String, Object> context) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("content", content);
        if (title != null && !title.isBlank()) {
            params.put("title", title);
        }
        putString(params, "automationPid", context.get("automationPid"));
        putString(params, "modelCode", context.get("modelCode"));
        putString(params, "recordPid", context.get("recordPid"));
        return params;
    }

    private static long parseUserId(String recipientId) {
        try {
            return Long.parseLong(suffix(recipientId, USER_PREFIX));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid notification target, expected USER:<numericId>: " + recipientId, e);
        }
    }

    private static String suffix(String target, String prefix) {
        String value = target.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Invalid notification target, missing value after " + prefix);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static void putString(Map<String, String> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put(key, String.valueOf(value));
        }
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

}
