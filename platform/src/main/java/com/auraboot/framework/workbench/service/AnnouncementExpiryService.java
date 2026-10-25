package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Expiry is idempotent under the same root lock as publication, withdrawal and recipient actions. */
@Service
@RequiredArgsConstructor
public class AnnouncementExpiryService {
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementReleaseMapper versions;
    private final com.auraboot.framework.workbench.mapper.AnnouncementReminderMapper reminderPlans;
    public List<Map<String,Object>> due(){return MetaContext.runWithoutTenantFilter(records::dueExpiry);}
    @Transactional
    public void expire(Long tenant,String pid,String version) {
        var previous=MetaContext.snapshot();
        try {
            MetaContext.setContext(tenant,0L,"announcement-expiry-worker","announcement-expiry-worker");
            var record=records.lock(tenant,pid);
            if(record==null || !"published".equals(record.getStatus()) || !version.equals(record.getActiveVersionPid())) return;
            var control=versions.version(tenant,version);
            if(control==null || control.get("expires_at")==null || AnnouncementDeliveryPlanResolver.instant(control.get("expires_at")).isAfter(Instant.now())) return;
            if(records.expireVersion(tenant,version)!=1 || records.expire(tenant,pid,version)!=1) throw new IllegalStateException("Announcement expiry transition was lost");
            records.closeRecipientTasks(tenant,version);records.cancelRecipientDeliveries(tenant,version);reminderPlans.cancelPlan(tenant,version);
            records.audit(UniqueIdGenerator.generate(),tenant,0L,pid,version,"announcement:expired",version,record.getRowVersion(),record.getRowVersion()+1);
        } finally {MetaContext.restore(previous);}
    }
}
