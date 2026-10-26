package com.auraboot.framework.workbench.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded per-record expiry; one failed record cannot roll back another tenant's completed cleanup. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="auraboot.announcement.worker-enabled",havingValue="true",matchIfMissing=true)
public class AnnouncementExpiryWorker {
    private final AnnouncementExpiryService expiry;
    @Scheduled(fixedDelayString="${auraboot.announcement.expiry-poll-delay-ms:30000}")
    public void tick() {
        for(var row:expiry.due()) {
            try {expiry.expire(((Number)row.get("tenant_id")).longValue(),(String)row.get("pid"),(String)row.get("version"));}
            catch(RuntimeException failure) {
                // CATCH: the per-record transaction has rolled back. Keep the durable due record visible for operator investigation.
                log.error("Announcement expiry failed: tenant={}, announcement={}",row.get("tenant_id"),row.get("pid"),failure);
            }
        }
    }
}
