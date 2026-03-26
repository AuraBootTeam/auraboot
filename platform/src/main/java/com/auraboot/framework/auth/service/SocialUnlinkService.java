package com.auraboot.framework.auth.service;

/**
 * Minimal interface for unlinking social accounts during user lifecycle events
 * (e.g., account deactivation).
 * <p>
 * This interface lives in core so that core services (like {@code UserDeactivationServiceImpl})
 * can optionally depend on it without importing classes from the enterprise-infra module.
 * The full implementation is provided by {@code SocialLinkServiceImpl} in platform-enterprise-infra,
 * which implements both this interface and the richer {@code SocialLinkService}.
 *
 * @since 7.2.0
 */
public interface SocialUnlinkService {

    /**
     * Unlink all social accounts for the given user.
     * Called during account deactivation to clean up OAuth links.
     *
     * @param userId the platform user ID
     */
    void unlinkAllByUserId(Long userId);
}
