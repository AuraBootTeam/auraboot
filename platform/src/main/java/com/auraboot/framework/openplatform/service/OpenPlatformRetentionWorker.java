package com.auraboot.framework.openplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenPlatformRetentionWorker {
    private final JdbcTemplate jdbcTemplate;

    @Value("${open-platform.retention.audit-days:90}")
    private int auditDays;

    @Value("${open-platform.retention.idempotency-grace-days:7}")
    private int idempotencyGraceDays;

    @Scheduled(cron = "${open-platform.retention.cron:0 15 3 * * *}",
            zone = "${open-platform.retention.zone:UTC}")
    @Transactional
    public void purge() {
        validateDays(auditDays);
        validateDays(idempotencyGraceDays);
        Instant now = Instant.now();
        int rateWindows = jdbcTemplate.update(
                "DELETE FROM ab_open_api_rate_window WHERE window_start < ?",
                Timestamp.from(now.minus(Duration.ofDays(2))));
        int tokens = jdbcTemplate.update(
                "DELETE FROM ab_application_access_token WHERE expires_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(7))));
        int idempotency = jdbcTemplate.update(
                "DELETE FROM ab_open_api_idempotency WHERE expires_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(idempotencyGraceDays))));
        int audits = jdbcTemplate.update(
                "DELETE FROM ab_open_api_call_audit WHERE occurred_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(auditDays))));
        log.info("Open Platform retention completed rateWindows={} tokens={} idempotency={} audits={}",
                rateWindows, tokens, idempotency, audits);
    }

    private void validateDays(int days) {
        if (days < 1 || days > 3650) {
            throw new IllegalStateException("Open Platform retention days must be between 1 and 3650");
        }
    }
}
