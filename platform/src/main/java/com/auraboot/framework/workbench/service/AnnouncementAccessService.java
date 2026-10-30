package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Shared authoritative management checks, independent from receiver eligibility. */
@Service
@RequiredArgsConstructor
public class AnnouncementAccessService {
    private final AnnouncementLifecycleMapper records;
    private final UserPermissionService permissions;
    private final IdentityDirectoryAccessor identities;

    public Long actor(String action) {
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        if(tenant==null || actor==null || identities.findActiveTenantUser(tenant,MetaContext.getCurrentUserPid())==null
            || !records.isEnabledActor(actor,MetaContext.getCurrentUserPid())
            || !permissions.hasPermission(actor,"announcement.announcement."+action))
            throw new AccessDeniedException("Announcement action is not permitted for the current membership");
        return actor;
    }
    public Announcement manage(String pid,String action,boolean lock) {
        Long actor=actor(action),tenant=MetaContext.getCurrentTenantId();
        Announcement record=lock ? records.lock(tenant,pid) : records.readTarget(tenant,pid);
        if(record==null || Boolean.TRUE.equals(record.getDeletedFlag()))
            throw new RecordUnavailable();
        if(!Objects.equals(record.getOwnerUserId(),actor) && !permissions.hasPermission(actor,"announcement.announcement.manage"))
            throw new AccessDeniedException("Announcement management scope denied");
        return record;
    }
    /** Human-readable management projection; numeric internal foreign keys are never presentation values. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public java.util.Map<String,Object> managementSummary(String pid) {
        var record=manage(pid,"read",false);var view=new java.util.LinkedHashMap<String,Object>();
        view.put("publisherName",records.publisherName(MetaContext.getCurrentTenantId(),pid));
        view.put("publishedAt",record.getPublishedAt());return view;
    }
    public static final class RecordUnavailable extends BusinessException {
        public RecordUnavailable(){super("$i18n:announcement.error.not_found");}
    }
}
