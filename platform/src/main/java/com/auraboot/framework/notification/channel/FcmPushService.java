package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.model.PushDeviceToken;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends push notifications via Firebase Cloud Messaging.
 * Supports both FCM (Android) and APNs (iOS via FCM bridge).
 *
 * @since 6.5.0
 */
@Slf4j
@RequiredArgsConstructor
public class FcmPushService {

    private final FirebaseMessaging firebaseMessaging;

    /**
     * Send a push notification to a single device token.
     *
     * @param token    device token record
     * @param title    notification title
     * @param body     notification body text
     * @param deepLink deep link URL (e.g., auraboot://chat/123)
     * @param category notification category (e.g., "chat", "approval")
     * @param badge    badge count (iOS only)
     * @return true if sent successfully, false if token is invalid and should be invalidated
     * @throws FirebaseMessagingException if a non-recoverable error occurs
     */
    public boolean sendToDevice(PushDeviceToken token, String title, String body,
                                 String deepLink, String category, int badge)
            throws FirebaseMessagingException {
        Message.Builder builder = Message.builder()
                .setToken(token.getPushToken())
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putData("deep_link", deepLink != null ? deepLink : "")
                .putData("deepLink", deepLink != null ? deepLink : "")
                .putData("category", category != null ? category : "system");

        // Android-specific config
        builder.setAndroidConfig(AndroidConfig.builder()
                .setNotification(AndroidNotification.builder()
                        .setClickAction("com.auraboot.android.PUSH_DEEP_LINK")
                        .build())
                .build());

        // APNs-specific config (iOS via FCM bridge)
        builder.setApnsConfig(ApnsConfig.builder()
                .setAps(Aps.builder()
                        .setBadge(badge)
                        .setCategory(category != null ? category : "system")
                        .setSound("default")
                        .build())
                .putCustomData("deepLink", deepLink != null ? deepLink : "")
                .build());

        try {
            String messageId = firebaseMessaging.send(builder.build());
            log.debug("FCM message sent: messageId={}, platform={}, userId={}",
                    messageId, token.getPlatform(), token.getUserId());
            return true;
        } catch (FirebaseMessagingException e) {
            // Check if the token is invalid/unregistered
            if (isTokenInvalid(e)) {
                log.info("FCM token invalid/unregistered: userId={}, platform={}, invalidating",
                        token.getUserId(), token.getPlatform());
                return false;
            }
            throw e;
        }
    }

    /**
     * Check if the FCM error indicates the token is no longer valid.
     */
    private boolean isTokenInvalid(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.INVALID_ARGUMENT;
    }
}
