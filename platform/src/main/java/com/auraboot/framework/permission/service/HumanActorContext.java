package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.service.InstalledIntegrationExecution;
import com.auraboot.framework.permission.mapper.HumanActorMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Rehydrate membership at a trusted host seam; this is not permission to impersonate a payload user. */
@Service
@RequiredArgsConstructor
public class HumanActorContext {
    private final HumanActorMapper actors;
    private final RoleMapper roles;
    private final com.auraboot.framework.event.context.ThreadContextScopes contexts;

    public <T> T call(Long tenant, Long user, String expectedUserPid, Long expectedMember, Supplier<T> action) {
        if (tenant == null || tenant <= 0 || user == null && !StringUtils.hasText(expectedUserPid)
                || user != null && user <= 0 || expectedUserPid != null && !StringUtils.hasText(expectedUserPid)
                || expectedMember != null && expectedMember <= 0) throw denied();
        requireSupportedSource();
        var identities = actors.findActive(tenant, user, expectedUserPid);
        if (identities == null || identities.size() != 1) throw denied();
        var identity = identities.getFirst();
        if (!(identity.get("user_id") instanceof Number actualUser)
                || !(identity.get("member_id") instanceof Number member)
                || !(identity.get("user_pid") instanceof String pid) || !StringUtils.hasText(pid)
                || actualUser.longValue() <= 0 || member.longValue() <= 0
                || user != null && !user.equals(actualUser.longValue())
                || expectedUserPid != null && !expectedUserPid.equals(pid)
                || expectedMember != null && !expectedMember.equals(member.longValue())) throw denied();
        // A handoff retains domain constraints only for this exact actor; a new human root uses run().
        contexts.requireActor(tenant, actualUser.longValue(), member.longValue());
        // The actor query flushes the mapper session cache, so a prior role projection is not reused.
        var currentRoles = MetaContext.runWithoutTenantFilter(() -> roles.findByMemberIdAndTenantId(member.longValue(), tenant));
        if (currentRoles == null || currentRoles.stream().anyMatch(role -> role.getId() == null)) throw denied();
        Set<Long> roleIds = currentRoles.stream().map(com.auraboot.framework.rbac.entity.Role::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var previous = MetaContext.snapshot();
        Long environment = previous != null && tenant.equals(previous.tenantId()) ? previous.envId() : null;
        String username = identity.get("username") instanceof String value ? value : pid;
        var snapshot = new MetaContext.Snapshot(tenant, actualUser.longValue(), pid, username, roleIds,
                member.longValue(), environment, previous == null ? null : previous.otelTraceId(), null);
        var result = new AtomicReference<T>();
        // This scope clears inherited command authority and restores the original thread on failure too.
        MetaContext.runWithSnapshot(snapshot, () -> result.set(action.get()));
        return result.get();
    }

    /** New trusted human work must not inherit the previous agent turn's principal or envelope. */
    public void run(Long tenant, Long user, String expectedUserPid, Long expectedMember, Runnable action) {
        requireSupportedSource();
        contexts.runCleared(() -> call(tenant, user, expectedUserPid, expectedMember,
                () -> { action.run(); return null; }));
    }

    private static void requireSupportedSource() {
        if (MachineDataAccessContext.isActive() || InstalledIntegrationExecution.active()
                || MetaContext.isImpersonating()) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("HUMAN_ACTOR_UNAVAILABLE: a current user and active membership are required");
    }
}
