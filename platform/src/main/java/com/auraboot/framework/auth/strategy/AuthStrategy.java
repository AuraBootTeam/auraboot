package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;

/**
 * Strategy interface for multi-channel authentication.
 * <p>
 * Each login channel (email+password, SMS code, email code, social OAuth, etc.)
 * implements this interface with its own verification logic, while sharing
 * the common login-completion pipeline (JWT generation, session creation).
 *
 * @since 7.0.0
 */
public interface AuthStrategy {

    /**
     * Unique channel code identifying this strategy.
     * Examples: EMAIL_PASSWORD, SMS, EMAIL_CODE, WECHAT, GOOGLE, APPLE
     */
    String getChannelCode();

    /**
     * Execute the authentication flow for this channel.
     *
     * @param request the multi-channel login request
     * @return authentication response containing JWT and user info
     */
    AuthenticationResponse authenticate(AuthStrategyRequest request);

    /**
     * Check if this strategy supports the given channel code.
     *
     * @param channelCode the channel code to test
     * @return true if this strategy handles the given channel
     */
    default boolean supports(String channelCode) {
        return getChannelCode().equals(channelCode);
    }
}
