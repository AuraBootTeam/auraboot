package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.entity.UserDeactivation;

/**
 * Service for user account deactivation with a 7-day cooling-off period.
 * <p>
 * Flow:
 * <ol>
 *   <li>User requests deactivation → record created with COOLING_OFF status</li>
 *   <li>During cooling-off (7 days), user can cancel the request</li>
 *   <li>After cooling-off expires, scheduler anonymizes the account and marks COMPLETED</li>
 * </ol>
 *
 * @since 7.1.0
 */
public interface UserDeactivationService {

    /**
     * Request account deactivation. Starts a 7-day cooling-off period.
     *
     * @param userId          the user requesting deactivation
     * @param reason          optional reason for deactivation
     * @param consentSnapshot JSON string with user consent agreement and timestamp
     * @return the created deactivation record
     * @throws com.auraboot.framework.exception.BusinessException if user already has an active request
     */
    UserDeactivation requestDeactivation(Long userId, String reason, String consentSnapshot);

    /**
     * Cancel an active deactivation during the cooling-off period.
     *
     * @param userId the user cancelling deactivation
     * @throws com.auraboot.framework.exception.BusinessException if no active deactivation exists
     */
    void cancelDeactivation(Long userId);

    /**
     * Get the current deactivation status for a user.
     *
     * @param userId the user to check
     * @return the active deactivation record, or null if none
     */
    UserDeactivation getDeactivationStatus(Long userId);

    /**
     * Process all expired cooling-off periods: anonymize user data and complete deactivation.
     * Called by the scheduler every hour.
     */
    void processExpiredDeactivations();
}
