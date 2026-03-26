package com.auraboot.framework.auth.scheduler;

import com.auraboot.framework.auth.service.UserDeactivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to process expired cooling-off periods for user deactivation.
 * <p>
 * Runs every hour to find COOLING_OFF records that have passed their deadline,
 * then performs anonymization and completes the deactivation.
 *
 * @since 7.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDeactivationScheduler {

    private final UserDeactivationService userDeactivationService;

    /**
     * Process expired deactivation cooling-off periods.
     * Runs every hour (at minute 0).
     */
    @Scheduled(cron = "0 0 * * * *")
    public void processExpiredDeactivations() {
        log.debug("Running deactivation expiry check...");
        try {
            userDeactivationService.processExpiredDeactivations();
        } catch (Exception e) {
            log.error("Error processing expired deactivations: {}", e.getMessage(), e);
        }
    }
}
