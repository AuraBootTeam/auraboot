package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.IdempotentKeyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to clean up expired idempotent keys.
 * Runs every hour to delete records whose expired_at has passed.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentKeyCleanupTask {

    private final IdempotentKeyMapper idempotentKeyMapper;

    /**
     * Delete expired idempotent keys every hour.
     * Uses fixedRate to ensure consistent cleanup interval regardless of execution time.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupExpiredKeys() {
        try {
            int deleted = idempotentKeyMapper.deleteExpired();
            if (deleted > 0) {
                log.info("Cleaned up {} expired idempotent keys", deleted);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup expired idempotent keys: {}", e.getMessage());
        }
    }
}
