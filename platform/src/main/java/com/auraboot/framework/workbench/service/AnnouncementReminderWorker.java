package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.mapper.AnnouncementReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Polling is outside the business transaction; failed authorization leaves durable blocked evidence. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="auraboot.announcement.worker-enabled",havingValue="true",matchIfMissing=true)
public class AnnouncementReminderWorker {
    private final AnnouncementReminderMapper plans;
    private final AnnouncementReminderService reminders;
    @Scheduled(fixedDelayString="${auraboot.announcement.reminder-poll-ms:60000}")
    public void tick() {
        for(var plan:MetaContext.runWithoutTenantFilter(plans::due)) {
            // CATCH: scan has already rolled back; block records the failure in a separate transaction.
            try {reminders.scan(plan);}
            catch(AccessDeniedException | BusinessException denied) {reminders.block(plan,"ACTOR_OR_VERSION_UNAVAILABLE");}
            catch(RuntimeException failure) {
                reminders.block(plan,"EXECUTION_FAILURE");log.error("Announcement reminder scan failed: version={}",plan.get("version_pid"),failure);
            }
        }
        MetaContext.runWithoutTenantFilter(plans::deleteExpiredPreviews);
    }
}
