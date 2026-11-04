package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementAcknowledgementMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Reuses sealed announcement facts; never creates parallel acknowledgements. */
@Service
@RequiredArgsConstructor
public class AnnouncementAcknowledgementAccessorImpl implements AnnouncementAcknowledgementAccessor {
    private final AnnouncementAcknowledgementMapper proof;
    private final AnnouncementLifecycleMapper lifecycle;
    private final AnnouncementCodec codec;
    private final com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor membershipLocks;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Check> checkForCommand(List<Requirement> requirements) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Acknowledgement proof requires an existing writable Core transaction");
        }
        if (!MetaContext.exists()) throw new AccessDeniedException("Authenticated announcement recipient required");
        Long tenant = MetaContext.getCurrentTenantId(), actor = MetaContext.getCurrentUserId();
        String user = MetaContext.getCurrentUserPid();
        if (tenant == null || actor == null || user == null || user.isBlank()) {
            throw new AccessDeniedException("Authenticated announcement recipient required");
        }
        if (requirements == null || requirements.isEmpty() || requirements.size() > 32) {
            throw new IllegalArgumentException("One to thirty-two exact announcement requirements are required");
        }
        requirements = List.copyOf(requirements);
        var seen = new HashSet<String>();
        for (var required : requirements) {
            if (!seen.add(required.versionPid())) throw new IllegalArgumentException("Duplicate version requirement");
        }
        var member = proof.currentMember(tenant, actor, user);
        boolean currentMember = member != null && member.get("member_id") instanceof Number id
                && Objects.equals(MetaContext.getCurrentMemberId(), id.longValue());
        if (currentMember) membershipLocks.requireEnabledMemberships(List.of(
                new com.auraboot.framework.plugin.extension.CommandMembershipLockAccessor.MembershipAssertion(
                        user, (String) member.get("member_pid"))));
        Map<String,Map<String,Object>> versions = new HashMap<>();
        var rootPids = new TreeSet<String>();
        if (currentMember) for (var required : requirements) {
            var version = proof.version(tenant, required.versionPid());
            if (version != null) {
                versions.put(required.versionPid(), version);
                rootPids.add((String) version.get("announcement_pid"));
            }
        }
        Map<String,Announcement> roots = new HashMap<>();
        for (String pid : rootPids) roots.put(pid, lifecycle.lock(tenant, pid));
        // Lifecycle publication, withdrawal, replacement and reader writes share these root locks.
        Instant now = proof.databaseNow();
        var checks = new ArrayList<Check>();
        for (var required : requirements) {
            boolean matches = currentMember && required.memberPid().equals(member.get("member_pid"));
            if (!matches) {
                checks.add(absent(required, Status.MEMBER_CHANGED, now));
                continue;
            }
            var version = versions.get(required.versionPid());
            if (version == null) {
                checks.add(absent(required, Status.MISSING, now));
                continue;
            }
            // Re-read mutable publication control only after the common aggregate lock.
            version = proof.version(tenant, required.versionPid());
            if (version == null) throw new IllegalStateException("Announcement version disappeared while locked");
            var root = roots.get((String) version.get("announcement_pid"));
            checks.add(check(required, tenant, user, root, version, now));
        }
        List<Check> result = List.copyOf(checks);
        var satisfied = result.stream().filter(c -> c.status() == Status.SATISFIED).toList();
        var requiredByVersion = new HashMap<String,Requirement>();
        requirements.forEach(r -> requiredByVersion.put(r.versionPid(), r));
        Object memberId = member == null ? null : member.get("member_id");
        if (!satisfied.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                var current = proof.currentMember(tenant, actor, user);
                if (current == null || !Objects.equals(memberId, current.get("member_id"))) unavailable();
                Instant commitCheck = proof.databaseNow();
                // Root locks stop other transactions, but the caller may have changed
                // the same records itself. Revalidate all satisfied facts, not just time.
                for (Check previous : satisfied) {
                    Requirement required = requiredByVersion.get(previous.requestedVersionPid());
                    if (!required.memberPid().equals(current.get("member_pid"))) unavailable();
                    var freshVersion = proof.version(tenant, required.versionPid());
                    var originalVersion = versions.get(required.versionPid());
                    if (freshVersion == null || !Objects.equals(freshVersion.get("announcement_pid"), originalVersion.get("announcement_pid"))) unavailable();
                    var freshRoot = lifecycle.lock(tenant, (String) originalVersion.get("announcement_pid"));
                    if (check(required, tenant, user, freshRoot, freshVersion, commitCheck).status() != Status.SATISFIED) unavailable();
                }
            }
        });
        return result;
    }

    private Check check(Requirement required, Long tenant, String user, Announcement root,
                        Map<String,Object> version, Instant now) {
        return new AnnouncementSubjectAcknowledgementService(proof, lifecycle, codec)
                .evaluate(required, tenant, user, root, version, now);
    }

    private static Check absent(Requirement required, Status status, Instant now) {
        return new Check(required.versionPid(), null, null, status, false, false,
                status != Status.MEMBER_CHANGED, false, null, null, now, null);
    }

    private static Instant instant(Object value) {
        return value == null ? null : AnnouncementDeliveryPlanResolver.instant(value);
    }

    private static void unavailable() {
        throw BusinessException.i18n("announcement.error.recipient_unavailable");
    }
}
