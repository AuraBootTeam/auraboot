package com.auraboot.framework.email.job;

import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.service.EmailSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled job that triggers incremental Gmail sync for all active auto-sync accounts.
 *
 * <p>Runs on a fixed delay (default 120 seconds, configurable via
 * {@code aura.email.sync.interval-seconds}).  Uses fixed-delay (not fixed-rate) so
 * that a long-running sync cycle does not overlap with the next.
 *
 * @since 6.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSyncJob {

    private final EmailSyncService  emailSyncService;
    private final EmailAccountMapper emailAccountMapper;

    /**
     * Iterates all globally-active auto-sync accounts and kicks off a sync for each.
     *
     * <p>Errors in a single account do not abort the remaining accounts.
     */
    @Scheduled(fixedDelayString = "${aura.email.sync.interval-seconds:120}000")
    public void syncAllAccounts() {
        List<EmailAccount> accounts = emailAccountMapper.findAllActiveGlobal();
        if (accounts.isEmpty()) {
            log.debug("Email sync job: no active auto-sync accounts found, skipping");
            return;
        }

        log.info("Email sync job starting: {} accounts to sync", accounts.size());

        int success = 0;
        int failed  = 0;
        for (EmailAccount account : accounts) {
            try {
                emailSyncService.syncAccount(account);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("Email sync job failed for accountId={}: {}",
                        account.getId(), e.getMessage(), e);
            }
        }

        log.info("Email sync job finished: {} succeeded, {} failed (total={})",
                success, failed, accounts.size());
    }
}
