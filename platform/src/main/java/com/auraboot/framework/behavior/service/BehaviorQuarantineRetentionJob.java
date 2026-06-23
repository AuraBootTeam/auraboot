package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.config.BehaviorQuarantineRetentionProperties;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorQuarantineRetentionJob {

    private final BehaviorQuarantineMapper mapper;
    private final BehaviorQuarantineRetentionProperties properties;

    @Scheduled(
            initialDelayString = "${behavior.quarantine.retention.initial-delay-ms:300000}",
            fixedDelayString = "${behavior.quarantine.retention.fixed-delay-ms:3600000}"
    )
    @Transactional
    public int cleanupExpired() {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.effectiveDays()));
        int deleted = mapper.deleteOlderThan(cutoff, properties.effectiveBatchSize());
        if (deleted > 0) {
            log.info("Cleaned up {} expired behavior quarantine rows older than {} days",
                    deleted, properties.effectiveDays());
        }
        return deleted;
    }
}
