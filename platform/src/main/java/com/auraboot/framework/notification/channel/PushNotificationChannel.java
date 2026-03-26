package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Push notification channel (APNs/FCM).
 * Currently a stub that logs push payloads; real SDK integration will be added later.
 *
 * @since 6.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushNotificationChannel implements NotificationChannel {

    private final DeviceTokenService deviceTokenService;

    @Override
    public String getChannelCode() {
        return "push";
    }

    @Override
    public NotificationResult send(NotificationMessage message) {
        try {
            int totalTokens = 0;
            for (Long userId : message.getRecipientUserIds()) {
                List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(
                        message.getTenantId(), userId);
                if (tokens.isEmpty()) {
                    log.debug("No valid push tokens for user={}, skipping", userId);
                    continue;
                }

                for (PushDeviceToken token : tokens) {
                    // Build push payload
                    Map<String, Object> pushPayload = buildPushPayload(message, token);
                    // Stub: log the payload instead of sending via APNs/FCM
                    log.info("PUSH_STUB: platform={} tokenType={} userId={} title='{}' body='{}' deepLink={}",
                            token.getPlatform(),
                            token.getTokenType(),
                            userId,
                            message.getSubject(),
                            truncate(message.getBody(), 100),
                            getDeepLink(message));
                    totalTokens++;
                }
            }
            log.info("Push channel dispatched to {} tokens for {} recipients",
                    totalTokens, message.getRecipientUserIds().size());
            return NotificationResult.ok();
        } catch (Exception e) {
            log.error("PushNotificationChannel send failed: {}", e.getMessage(), e);
            return NotificationResult.fail(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private Map<String, Object> buildPushPayload(NotificationMessage message, PushDeviceToken token) {
        Map<String, Object> extras = message.getExtras() != null ? message.getExtras() : Map.of();
        return Map.of(
                "title", message.getSubject() != null ? message.getSubject() : "",
                "body", message.getBody() != null ? message.getBody() : "",
                "platform", token.getPlatform(),
                "tokenType", token.getTokenType(),
                "pushToken", token.getPushToken(),
                "deepLink", getDeepLink(message),
                "category", message.getCategory() != null ? message.getCategory() : "system",
                "extras", extras
        );
    }

    private String getDeepLink(NotificationMessage message) {
        if (message.getExtras() != null && message.getExtras().containsKey("deep_link")) {
            return message.getExtras().get("deep_link").toString();
        }
        return "";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
