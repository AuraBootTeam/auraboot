package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.mapper.PushDeviceTokenMapper;
import com.auraboot.framework.notification.model.PushDeviceToken;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Service for managing push device tokens (APNs/FCM).
 * Handles registration, deregistration, and lookup of device tokens.
 *
 * @since 6.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final PushDeviceTokenMapper pushDeviceTokenMapper;

    /**
     * Register or update a device token. If a token with the same push_token
     * already exists for this tenant, update it; otherwise create a new one.
     * Also updates last_used_at to track token freshness.
     */
    @Transactional
    public PushDeviceToken registerToken(Long tenantId, Long userId, String platform,
                                          String pushToken, String deviceId,
                                          String tokenType, String appVersion,
                                          String osVersion) {
        // Look for existing token (including soft-deleted ones to reactivate)
        PushDeviceToken existing = findByPushToken(tenantId, pushToken);

        if (existing != null) {
            // Update existing token
            existing.setUserId(userId);
            existing.setPlatform(platform);
            existing.setDeviceId(deviceId);
            existing.setTokenType(tokenType != null ? tokenType : "apns");
            existing.setAppVersion(appVersion);
            existing.setOsVersion(osVersion);
            existing.setIsValid(true);
            existing.setLastUsedAt(Instant.now());
            existing.setUpdatedAt(Instant.now());
            existing.setDeletedFlag(false);
            pushDeviceTokenMapper.updateById(existing);
            log.info("Updated device token for user={} platform={} tokenType={}", userId, platform, tokenType);
            return existing;
        }

        // Create new token
        PushDeviceToken token = new PushDeviceToken();
        token.setTenantId(tenantId);
        token.setUserId(userId);
        token.setPlatform(platform);
        token.setPushToken(pushToken);
        token.setDeviceId(deviceId);
        token.setTokenType(tokenType != null ? tokenType : "apns");
        token.setAppVersion(appVersion);
        token.setOsVersion(osVersion);
        token.setIsValid(true);
        token.setLastUsedAt(Instant.now());
        token.setCreatedAt(Instant.now());
        token.setUpdatedAt(Instant.now());
        token.setDeletedFlag(false);
        pushDeviceTokenMapper.insert(token);
        log.info("Registered new device token for user={} platform={} tokenType={}", userId, platform, tokenType);
        return token;
    }

    /**
     * Soft-delete a device token by push_token value.
     */
    @Transactional
    public void unregisterToken(Long tenantId, Long userId, String pushToken) {
        LambdaUpdateWrapper<PushDeviceToken> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PushDeviceToken::getTenantId, tenantId)
                .eq(PushDeviceToken::getUserId, userId)
                .eq(PushDeviceToken::getPushToken, pushToken)
                .set(PushDeviceToken::getDeletedFlag, true)
                .set(PushDeviceToken::getIsValid, false)
                .set(PushDeviceToken::getUpdatedAt, Instant.now());
        int rows = pushDeviceTokenMapper.update(null, wrapper);
        log.info("Unregistered device token for user={}, rows affected={}", userId, rows);
    }

    /**
     * Get all valid (non-deleted, non-invalidated) tokens for a user.
     */
    public List<PushDeviceToken> getValidTokens(Long tenantId, Long userId) {
        return pushDeviceTokenMapper.findValidTokensByUserId(tenantId, userId);
    }

    /**
     * Mark a token as invalid (e.g. APNs feedback says token expired).
     */
    @Transactional
    public void invalidateToken(Long id) {
        int rows = pushDeviceTokenMapper.invalidateToken(id);
        log.info("Invalidated device token id={}, rows affected={}", id, rows);
    }

    /**
     * Find a token by push_token value, including soft-deleted ones (for upsert/reactivation).
     * Uses raw SQL to bypass @TableLogic filtering.
     */
    private PushDeviceToken findByPushToken(Long tenantId, String pushToken) {
        return pushDeviceTokenMapper.findByPushTokenIncludeDeleted(tenantId, pushToken);
    }
}
