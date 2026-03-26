package com.auraboot.framework.email.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Gmail OAuth2 integration.
 *
 * <p>Bind via {@code aura.email.gmail.*} in application.yml / environment variables.
 *
 * @since 6.5.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "aura.email.gmail")
public class GmailApiConfig {

    /** Google OAuth2 client ID. */
    private String clientId;

    /** Google OAuth2 client secret. */
    private String clientSecret;

    /**
     * OAuth2 redirect URI that Google will call after user authorization.
     * Must be registered in the Google Cloud Console.
     */
    private String redirectUri = "http://localhost:6443/api/email/oauth2/callback";
}
