package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.*;
import com.auraboot.framework.meta.service.impl.DomainExportTaskService;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Idempotent business acceptance binds one export to the exact authorized capture and announcement. */
@Service
@RequiredArgsConstructor
public class AnnouncementStatisticsExportCommand {
    public static final String CODE="announcement:export_statistics";
    private final AnnouncementAccessService access;
    private final AnnouncementStatisticsService statistics;
    private final AnnouncementLifecycleMapper operations;
    private final AnnouncementCodec codec;
    private final ObjectProvider<DomainExportTaskService> exports;
    private record Authorized(Announcement record,String token,String version) {}

    public long authorizeTarget(String pid,Map<String,Object> payload) {return authorize(pid,payload,false).record().getRowVersion();}

    @Transactional
    public Map<String,Object> execute(String pid,Map<String,Object> payload,String operation) {
        var authorized=authorize(pid,payload,true);
        if(operation==null || operation.isBlank() || operation.length()>100) throw BusinessException.i18n("announcement.error.operation_required");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        String hash=codec.hash(Map.of("recordPid",pid,"snapshotToken",authorized.token()));
        if(operations.claim(tenant,actor,CODE,operation,hash)==0) {
            var previous=operations.operation(tenant,actor,CODE,operation);
            if(previous==null || !hash.equals(previous.get("request_hash")))
                throw new ConflictException("IDEMPOTENCY_CONFLICT","Announcement export intent changed",Map.of());
            if(previous.get("result")==null) throw new IllegalStateException("Announcement export operation has no committed result");
            return codec.object(previous.get("result").toString());
        }
        var task=exports.getObject().submit(AnnouncementStatisticsExportSource.CODE,authorized.token());
        Map<String,Object> result=Map.of("recordPid",pid,"versionPid",authorized.version(),"exportTaskPid",task.getPid(),
            "operationId",operation,"operationState","accepted");
        int revision=authorized.record().getRowVersion();
        if(operations.audit(UniqueIdGenerator.generate(),tenant,actor,pid,authorized.version(),CODE,operation,revision,revision)!=1
            || operations.finish(tenant,actor,CODE,operation,pid,codec.encode(result))!=1)
            throw new IllegalStateException("Announcement export acceptance evidence was not persisted");
        return result;
    }
    private Authorized authorize(String pid,Map<String,Object> payload,boolean lock) {
        if(payload==null || !payload.keySet().equals(Set.of("snapshotToken"))
            || !(payload.get("snapshotToken") instanceof String token) || token.isBlank() || token.length()>36) {
            throw BusinessException.i18n("announcement.error.protected_input");
        }
        var record=access.manage(pid,"stats",lock);access.actor("export");
        // Replay may outlive the viewing token; a new task still requires the source's 30-minute acceptance window.
        var snapshot=statistics.authorizeExport(token,false);
        if(!pid.equals(codec.object(snapshot.get("metadata").toString()).get("recordPid")))
            throw new AccessDeniedException("Announcement export capture belongs to a different record");
        return new Authorized(record,token,(String)snapshot.get("version_pid"));
    }
}
