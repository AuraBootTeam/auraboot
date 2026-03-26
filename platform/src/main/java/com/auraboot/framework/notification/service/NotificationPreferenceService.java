package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.entity.NotificationPreference;

import java.util.List;

/**
 * Service for managing user notification preferences.
 * Opt-out model: all channels enabled by default.
 *
 * @since 6.0.0
 */
public interface NotificationPreferenceService {

    /**
     * Get all preference records for a user.
     */
    List<NotificationPreference> getPreferences(Long userId);

    /**
     * Create or update a preference record (upsert).
     */
    void updatePreference(Long userId, String channel, String category, boolean enabled);

    /**
     * Check if a specific channel+category is enabled for a user.
     * Returns true by default (opt-out model).
     * SYSTEM + IN_APP is always forced on.
     */
    boolean isEnabled(Long userId, String channel, String category);

    /**
     * Filter a list of user IDs to only those who haven't opted out
     * of the given channel+category combination.
     */
    List<Long> filterRecipients(List<Long> userIds, String channel, String category);
}
