package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserDeactivation;
import com.auraboot.framework.auth.mapper.UserDeactivationMapper;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.service.SocialUnlinkService;
import com.auraboot.framework.auth.service.UserDeactivationService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Implementation of the user deactivation flow with 7-day cooling-off period.
 *
 * @since 7.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDeactivationServiceImpl implements UserDeactivationService {

    private static final Duration COOLING_OFF_PERIOD = Duration.ofDays(7);

    private final UserDeactivationMapper deactivationMapper;
    private final UserService userService;
    private final SessionManagementService sessionManagementService;

    /**
     * Optional dependency — only present when platform-enterprise-infra is on the classpath.
     * When absent, social account unlinking is skipped during deactivation (no-op).
     */
    @Autowired(required = false)
    private SocialUnlinkService socialUnlinkService;

    @Override
    @Transactional
    public UserDeactivation requestDeactivation(Long userId, String reason, String consentSnapshot) {
        // 1. Check no active deactivation exists
        UserDeactivation existing = deactivationMapper.findActiveByUserId(userId);
        if (existing != null) {
            throw new BusinessException("An active deactivation request already exists for this account");
        }

        // 2. Check user exists and is enabled
        User user = userService.findByUserId(userId);
        if (user == null) {
            throw new BusinessException("User not found");
        }
        if (!user.isEnabled()) {
            throw new BusinessException("Account is already disabled");
        }

        // 3. Create deactivation record with COOLING_OFF status
        Instant now = Instant.now();
        UserDeactivation deactivation = new UserDeactivation();
        deactivation.setPid(UlidGenerator.generate());
        deactivation.setUserId(userId);
        deactivation.setUserEmail(user.getEmail());
        deactivation.setStatus("cooling_off");
        deactivation.setReason(reason);
        deactivation.setRequestedAt(now);
        deactivation.setCoolingOffUntil(now.plus(COOLING_OFF_PERIOD));
        deactivation.setConsentSnapshot(consentSnapshot);

        deactivationMapper.insert(deactivation);

        // 4. Update user deactivation status
        user.setDeactivationStatus("cooling_off");
        userService.update(user);

        log.info("Deactivation requested for user {} (email: {}), cooling-off until {}",
                userId, user.getEmail(), deactivation.getCoolingOffUntil());

        return deactivation;
    }

    @Override
    @Transactional
    public void cancelDeactivation(Long userId) {
        // 1. Find active deactivation
        UserDeactivation deactivation = deactivationMapper.findActiveByUserId(userId);
        if (deactivation == null) {
            throw new BusinessException("No active deactivation request found");
        }

        // 2. Cancel the deactivation
        int updated = deactivationMapper.cancelByUserId(userId);
        if (updated == 0) {
            throw new BusinessException("Failed to cancel deactivation request");
        }

        // 3. Clear user deactivation status
        User user = userService.findByUserId(userId);
        if (user != null) {
            user.setDeactivationStatus(null);
            userService.update(user);
        }

        log.info("Deactivation cancelled for user {}", userId);
    }

    @Override
    public UserDeactivation getDeactivationStatus(Long userId) {
        return deactivationMapper.findActiveByUserId(userId);
    }

    @Override
    @Transactional
    public void processExpiredDeactivations() {
        List<UserDeactivation> expired = deactivationMapper.findExpiredCoolingOff();
        if (expired.isEmpty()) {
            return;
        }

        log.info("Processing {} expired deactivation cooling-off periods", expired.size());

        for (UserDeactivation deactivation : expired) {
            try {
                anonymizeUser(deactivation);
            } catch (Exception e) {
                log.error("Failed to process deactivation for user {}: {}",
                        deactivation.getUserId(), e.getMessage(), e);
                // Continue processing other records — don't let one failure block all
            }
        }
    }

    /**
     * Anonymize user data and complete the deactivation.
     * This is the irreversible step — user account becomes permanently unusable.
     */
    private void anonymizeUser(UserDeactivation deactivation) {
        Long userId = deactivation.getUserId();
        User user = userService.findByUserId(userId);

        if (user == null) {
            log.warn("User {} not found during deactivation processing, marking as completed", userId);
            completeDeactivationRecord(deactivation);
            return;
        }

        // 1. Anonymize personal data
        user.setEmail("deleted_" + user.getPid() + "@deleted.local");
        user.setMobile(null);
        user.setPassword(null);
        user.setNickName("Deleted User");
        user.setImgId(null);
        user.setUserName("deleted_" + user.getPid());
        user.setSignature(null);
        user.setArea(null);

        // 2. Increment security version — invalidates all JWTs
        Integer currentSv = user.getSecurityVersion();
        user.setSecurityVersion(currentSv != null ? currentSv + 1 : 1);

        // 3. Disable account
        user.setEnabled(false);
        user.setDeactivationStatus("deactivated");

        // 4. Clear password reset tokens
        user.setResetPasswordToken(null);
        user.setResetPasswordSentAt(null);

        userService.update(user);

        // 5. Revoke all active sessions
        try {
            sessionManagementService.revokeAllSessions(userId);
        } catch (Exception e) {
            log.warn("Failed to revoke sessions for user {}: {}", userId, e.getMessage());
        }

        // 6. Unlink all social accounts (only when enterprise-infra module is present)
        if (socialUnlinkService != null) {
            try {
                socialUnlinkService.unlinkAllByUserId(userId);
            } catch (Exception e) {
                log.warn("Failed to unlink social accounts for user {}: {}", userId, e.getMessage());
            }
        }

        // 7. Complete the deactivation record
        completeDeactivationRecord(deactivation);

        log.info("User {} (original email: {}) has been anonymized and deactivated",
                userId, deactivation.getUserEmail());
    }

    /**
     * Mark the deactivation record as completed.
     */
    private void completeDeactivationRecord(UserDeactivation deactivation) {
        Instant now = Instant.now();
        deactivation.setStatus(StatusConstants.COMPLETED);
        deactivation.setAnonymizedAt(now);
        deactivation.setCompletedAt(now);
        deactivationMapper.updateById(deactivation);
    }
}
