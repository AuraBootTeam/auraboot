package com.auraboot.framework.auth.scheduler;

import com.auraboot.framework.auth.service.UserDeactivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that periodically processes expired deactivation cooling-off periods.
 * Runs every hour to check for accounts that have passed the 7-day cooling-off
 * and need to be anonymized.
 *
 * @since 7.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeactivationScheduler {

    private final UserDeactivationService deactivationService;

    /**
     * Process expired cooling-off periods every hour.
     * Each expired record triggers user data anonymization and account deactivation.
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    public void processExpiredDeactivations() {
        log.info("Processing expired deactivation cooling-off periods...");
        try {
            deactivationService.processExpiredDeactivations();
        } catch (Exception e) {
            log.error("Error processing expired deactivations: {}", e.getMessage(), e);
        }
    }
}
