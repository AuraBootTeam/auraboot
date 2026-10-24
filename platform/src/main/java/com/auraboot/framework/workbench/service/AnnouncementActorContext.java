package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Re-establish current authority for an actor captured in a trusted durable record, never a client payload. */
@Component
@RequiredArgsConstructor
public class AnnouncementActorContext {
    private final AnnouncementReleaseMapper actors;

    public <T> T call(Long tenant,Long actor,String originalMemberPid,Supplier<T> action) {
        if(tenant==null || actor==null || originalMemberPid==null || originalMemberPid.isBlank())
            throw new AccessDeniedException("The original announcement actor is unavailable");
        var member=MetaContext.runWithoutTenantFilter(()->actors.actor(tenant,actor));
        if(member==null || !originalMemberPid.equals(member.get("member_pid")))
            throw new AccessDeniedException("The original announcement membership is no longer active");
        if(!(member.get("member_id") instanceof Number memberId) || !(member.get("user_pid") instanceof String userPid)
            || !(member.get("username") instanceof String username))
            throw new IllegalStateException("Announcement actor identity mapping is incomplete");
        var roles=MetaContext.runWithoutTenantFilter(()->actors.actorRoles(tenant,memberId.longValue()));
        var previous=MetaContext.snapshot();
        var identity=new MetaContext.Snapshot(tenant,actor,userPid,username,roles,memberId.longValue(),null,
            previous==null ? null : previous.otelTraceId(),null);
        var result=new AtomicReference<T>();
        MetaContext.runWithSnapshot(identity,()->result.set(action.get()));
        return result.get();
    }
}
