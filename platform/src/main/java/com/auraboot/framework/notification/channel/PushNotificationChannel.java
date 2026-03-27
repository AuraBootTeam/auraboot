package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Push notification channel (APNs/FCM).
 * Uses Firebase Cloud Messaging when configured (push.fcm.enabled=true),
 * otherwise falls back to stub logging mode.
 *
 * @since 6.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushNotificationChannel implements NotificationChannel {

    private final DeviceTokenService deviceTokenService;

    @Autowired(required = false)
    private FcmPushService fcmPushService;

    @Override
    public String getChannelCode() {
        return "push";
    }

    @Override
    public NotificationResult send(NotificationMessage message) {
        int totalSent = 0;
        int totalFailed = 0;

        for (Long userId : message.getRecipientUserIds()) {
            List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(
                    message.getTenantId(), userId);
            if (tokens.isEmpty()) {
                log.debug("No valid push tokens for user={}, skipping", userId);
                continue;
            }

            String deepLink = getDeepLink(message);
            int badge = getBadge(message);

            for (PushDeviceToken token : tokens) {
                if (fcmPushService != null) {
                    // Real FCM push
                    try {
                        boolean valid = fcmPushService.sendToDevice(
                                token,
                                message.getSubject() != null ? message.getSubject() : "",
                                message.getBody() != null ? message.getBody() : "",
                                deepLink,
                                message.getCategory(),
                                badge);
                        if (!valid) {
                            // Token is invalid, mark it
                            deviceTokenService.invalidateToken(token.getId());
                            totalFailed++;
                        } else {
                            totalSent++;
                        }
                    } catch (Exception e) {
                        // CATCH: non-transactional — FCM HTTP call failure, log and continue to next token
                        log.error("FCM send failed for token id={}, userId={}: {}",
                                token.getId(), userId, e.getMessage());
                        totalFailed++;
                    }
                } else {
                    // Stub mode: log the payload
                    log.info("PUSH_STUB: platform={} tokenType={} userId={} title='{}' body='{}' deepLink={}",
                            token.getPlatform(),
                            token.getTokenType(),
                            userId,
                            message.getSubject(),
                            truncate(message.getBody(), 100),
                            deepLink);
                    totalSent++;
                }
            }
        }

        log.info("Push channel: sent={}, failed={}, recipients={}",
                totalSent, totalFailed, message.getRecipientUserIds().size());
        return totalFailed == 0 ? NotificationResult.ok()
                : NotificationResult.fail("Some push deliveries failed: " + totalFailed + " failures");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private String getDeepLink(NotificationMessage message) {
        if (message.getExtras() != null && message.getExtras().containsKey("deep_link")) {
            return message.getExtras().get("deep_link").toString();
        }
        return "";
    }

    private int getBadge(NotificationMessage message) {
        if (message.getExtras() != null && message.getExtras().containsKey("badge")) {
            Object badge = message.getExtras().get("badge");
            if (badge instanceof Number n) {
                return n.intValue();
            }
        }
        return 1;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
