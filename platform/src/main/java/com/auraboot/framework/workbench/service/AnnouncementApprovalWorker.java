package com.auraboot.framework.workbench.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import com.auraboot.framework.exception.BusinessException;

/** Provider uncertainty retries the same persisted submission; domain denials require explicit recovery. */
@Component
@ConditionalOnProperty(prefix="auraboot.announcement.approval",name="worker-enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
@Slf4j
public class AnnouncementApprovalWorker {
    private final AnnouncementApprovalService service;
    @Scheduled(fixedDelayString="${auraboot.announcement.approval.poll-ms:5000}")
    public void poll() {
        for(int i=0;i<10;i++) {
            var claim=service.claim();if(claim==null) return;
            try {service.perform(claim);}
            catch(RuntimeException failure) {
                // CATCH: outside the rolled-back synchronization transaction; never substitutes approval for uncertainty.
                String reason=failure instanceof BusinessException ? failure.getMessage() : failure.getClass().getSimpleName();
                service.fail(claim,reason!=null && reason.length()<=100 ? reason : "APPROVAL_SYNC_FAILED",
                    failure instanceof BusinessException || failure instanceof AccessDeniedException || failure instanceof SecurityException);
                log.error("Announcement approval synchronization failed for {}",claim.get("pid"),failure);
            }
        }
    }
}
