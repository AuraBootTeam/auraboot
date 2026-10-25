package com.auraboot.framework.workbench.service;

import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Non-transactional polling boundary; each claimed task runs in its own business transaction. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="auraboot.announcement.worker-enabled",havingValue="true",matchIfMissing=true)
public class AnnouncementReleaseWorker {
    private final AnnouncementReleaseService releases;

    @Scheduled(fixedDelayString="${auraboot.announcement.poll-delay-ms:5000}")
    public void tick() {
        for(int i=0;i<10;i++) {
            Map<String,Object> claim=releases.claim();
            if(claim==null || claim.isEmpty()) return;
            // CATCH: this boundary has no transaction; perform has already rolled back before failure evidence is stored.
            try { releases.perform(claim); }
            catch(AccessDeniedException | BusinessException error) {
                String reason=failureCode(error);
                releases.fail(claim,"blocked",reason);
                log.warn("Announcement release blocked: release={}, reason={}",claim.get("pid"),reason);
            } catch(TransientDataAccessException error) {
                boolean exhausted=((Number)claim.get("attempts")).intValue()>=4;
                releases.fail(claim,exhausted ? "blocked" : "retryable_failed",exhausted ? "RETRY_EXHAUSTED" : "TRANSIENT_DATABASE_FAILURE");
                log.warn("Announcement release database failure: release={}",claim.get("pid"),error);
            } catch(RuntimeException error) {
                releases.fail(claim,"blocked","EXECUTION_FAILURE");
                log.error("Announcement release failed: release={}",claim.get("pid"),error);
            }
        }
    }

    static String failureCode(RuntimeException error) {
        if(error instanceof AccessDeniedException) return "AUTHORIZATION_DENIED";
        String message=error.getMessage(),prefix="$i18n:announcement.error.";
        return AnnouncementReleaseService.publicFailureCode(message!=null && message.startsWith(prefix)
            ? message.substring(prefix.length()).toUpperCase(java.util.Locale.ROOT) : null);
    }
}
