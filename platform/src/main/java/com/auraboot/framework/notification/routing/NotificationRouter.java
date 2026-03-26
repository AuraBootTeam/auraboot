package com.auraboot.framework.notification.routing;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.event.AuraEvent;
import com.auraboot.framework.notification.channel.NotificationChannel;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.auraboot.framework.notification.digest.DigestService;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.service.NotificationPreferenceService;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Event-driven notification router.
 *
 * Listens to {@link CommandCompletedEvent} and {@link BpmEvent}, matches templates
 * by code, resolves recipients via {@link RecipientResolver}, renders templates,
 * and dispatches to all configured channels.
 *
 * Replaces the former OutboxNotificationDispatcher with multi-channel support
 * and pluggable recipient resolution.
 *
 * @since 6.0.0
 */
@Slf4j
@Component
public class NotificationRouter {

    private final NotificationTemplateService templateService;
    private final RecipientResolver recipientResolver;
    private final Map<String, NotificationChannel> channelMap;

    @Autowired(required = false)
    private NotificationPreferenceService preferenceService;

    @Autowired(required = false)
    private DigestService digestService;

    public NotificationRouter(NotificationTemplateService templateService,
                               RecipientResolver recipientResolver,
                               List<NotificationChannel> channels) {
        this.templateService = templateService;
        this.recipientResolver = recipientResolver;
        this.channelMap = channels.stream()
                .collect(Collectors.toMap(
                        c -> normalizeChannelCode(c.getChannelCode()),
                        c -> c));
    }

    /** Package-private setter for unit testing. */
    void setPreferenceService(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /** Package-private setter for unit testing. */
    void setDigestService(DigestService digestService) {
        this.digestService = digestService;
    }

    @EventListener
    public void onCommandCompleted(CommandCompletedEvent event) {
        route(event, event.getCommandCode());
    }

    @EventListener
    public void onBpmEvent(BpmEvent event) {
        route(event, event.getEventType());
    }

    void route(AuraEvent event, String templateCode) {
        try {
            NotificationTemplate template = templateService.getByCode(templateCode);
            if (template == null || Boolean.FALSE.equals(template.getEnabled())) {
                return;
            }

            List<Long> recipients = recipientResolver.resolve(event,
                    template.getRecipientStrategy(), null);
            if (recipients.isEmpty()) {
                log.debug("No recipients resolved for template {}, skipping", templateCode);
                return;
            }

            String subject = renderTemplate(template.getSubjectTemplate(), event.getPayload());
            String body = renderTemplate(template.getBodyTemplate(), event.getPayload());

            List<String> channelCodes = parseChannels(template);
            String category = template.getCategory() != null ? template.getCategory() : "business";

            for (String channelCode : channelCodes) {
                try {
                    // Filter recipients by their notification preferences
                    List<Long> filteredRecipients = recipients;
                    if (preferenceService != null) {
                        filteredRecipients = preferenceService.filterRecipients(
                                recipients, channelCode, category);
                        if (filteredRecipients.isEmpty()) {
                            log.debug("All recipients opted out of channel={} category={}, skipping",
                                    channelCode, category);
                            continue;
                        }
                    }

                    // IN_APP always sends immediately; other channels use digest if available
                    if ("in_app".equals(normalizeChannelCode(channelCode))) {
                        sendImmediate(channelCode, event, filteredRecipients, templateCode,
                                subject, body, category);
                    } else if (digestService != null) {
                        for (Long userId : filteredRecipients) {
                            digestService.accumulate(event.getTenantId(), userId, channelCode,
                                    templateCode, category);
                        }
                    } else {
                        // No digest service, send immediately
                        sendImmediate(channelCode, event, filteredRecipients, templateCode,
                                subject, body, category);
                    }
                } catch (Exception e) {
                    log.error("Channel {} dispatch failed for template {}: {}",
                            channelCode, templateCode, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("NotificationRouter failed for templateCode={}: {}", templateCode, e.getMessage(), e);
        }
    }

    private void sendImmediate(String channelCode, AuraEvent event,
                               List<Long> filteredRecipients, String templateCode,
                               String subject, String body, String category) {
        NotificationChannel channel = channelMap.get(normalizeChannelCode(channelCode));
        if (channel != null && channel.isAvailable()) {
            NotificationResult result = channel.send(NotificationMessage.builder()
                    .tenantId(event.getTenantId())
                    .recipientUserIds(filteredRecipients)
                    .templateCode(templateCode)
                    .subject(subject)
                    .body(body)
                    .category(category)
                    .sourceType(event.getModelCode())
                    .sourceId(event.getRecordId())
                    .build());
            if (!result.isSuccess()) {
                log.warn("Channel {} failed for template {}: {}",
                        channelCode, templateCode, result.getErrorMessage());
            }
        } else if (channel == null) {
            log.warn("No channel implementation found for code: {}", channelCode);
        }
    }

    List<String> parseChannels(NotificationTemplate template) {
        // If new `channels` field is set (JSON array), parse it
        if (template.getChannels() != null && !template.getChannels().isBlank()) {
            try {
                String raw = template.getChannels().trim();
                if (raw.startsWith("[")) {
            return Arrays.stream(raw.substring(1, raw.length() - 1).split(","))
                            .map(s -> s.trim().replace("\"", ""))
                            .map(NotificationRouter::normalizeChannelCode)
                            .filter(s -> !s.isEmpty())
                            .toList();
                }
            } catch (Exception e) {
                log.warn("Failed to parse channels JSON: {}", template.getChannels());
            }
        }
        // Fall back to single channel field
        return template.getChannel() != null
                ? List.of(normalizeChannelCode(template.getChannel()))
                : List.of("in_app");
    }

    String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? escapeHtml(entry.getValue().toString()) : "";
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    /**
     * Escape HTML special characters to prevent XSS in notification templates
     * (especially important for EMAIL channel which renders HTML).
     */
    static String escapeHtml(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String normalizeChannelCode(String channelCode) {
        return channelCode == null ? null : channelCode.toLowerCase(Locale.ROOT);
    }
}
