package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.service.InstalledIntegrationExecution;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;

/** Reject incomplete human carriers before a lower-level data path can treat them as background work. */
public final class NativeHumanActor {
    private NativeHumanActor() {}

    /** This validates identity completeness, not authorization or nonhuman execution authority. */
    public static void requireCompleteIdentity() {
        if (!MetaContext.exists() || MachineDataAccessContext.isActive()
                || InstalledIntegrationExecution.active()) return;
        Long user = MetaContext.getCurrentUserId();
        Long member = MetaContext.getCurrentMemberId();
        boolean claimsHuman = member != null || user != null && user > 0
                || user == null && StringUtils.hasText(MetaContext.getCurrentUserPid());
        // Existing no-user/system execution still needs its own entry-point authority. This
        // check neither creates such authority nor turns a human with lost membership into it.
        if (!claimsHuman) return;
        if (MetaContext.getCurrentTenantId() == null || user == null || user <= 0
                || member == null || member <= 0 || !StringUtils.hasText(MetaContext.getCurrentUserPid()))
            throw new AccessDeniedException("NATIVE_ACTOR_INCOMPLETE: human data access requires tenant, user and membership identity");
    }
}
