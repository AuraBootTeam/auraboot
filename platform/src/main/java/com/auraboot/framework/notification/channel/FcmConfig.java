package com.auraboot.framework.notification.channel;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Firebase Cloud Messaging configuration.
 * Enabled when push.fcm.enabled=true and push.fcm.credentials-path is set.
 *
 * @since 6.5.0
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "push.fcm", name = "enabled", havingValue = "true")
public class FcmConfig {

    @Value("${push.fcm.credentials-path:}")
    private String credentialsPath;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials credentials;
            if (credentialsPath != null && !credentialsPath.isBlank()) {
                credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
                log.info("FCM initialized with service account credentials from: {}", credentialsPath);
            } else {
                credentials = GoogleCredentials.getApplicationDefault();
                log.info("FCM initialized with application default credentials");
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            return FirebaseApp.initializeApp(options);
        }
        return FirebaseApp.getInstance();
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    @Bean
    public FcmPushService fcmPushService(FirebaseMessaging firebaseMessaging) {
        return new FcmPushService(firebaseMessaging);
    }
}
