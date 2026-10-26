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
        var member = proof.lockMember(tenant, actor, user);
        boolean currentMember = member != null && member.get("member_id") instanceof Number id
                && Objects.equals(MetaContext.getCurrentMemberId(), id.longValue());
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
        var expiring = result.stream().filter(c -> c.status() == Status.SATISFIED && c.validUntil() != null).toList();
        if (!expiring.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                Instant commitCheck = proof.databaseNow();
                if (expiring.stream().anyMatch(c -> !commitCheck.isBefore(c.validUntil()))) {
                    throw BusinessException.i18n("announcement.error.recipient_unavailable");
                }
            }
        });
        return result;
    }

    private Check check(Requirement required, Long tenant, String user, Announcement root,
                        Map<String,Object> version, Instant now) {
        if (root == null || Boolean.TRUE.equals(root.getDeletedFlag())) return absent(required, Status.MISSING, now);
        String active = root.getActiveVersionPid(), hash = (String) version.get("snapshot_hash");
        Instant expiry = instant(version.get("expires_at"));
        String publication = (String) version.get("publication_state");
        Status unavailable = "withdrawn".equals(publication) ? Status.WITHDRAWN
                : "expired".equals(publication) || expiry != null && !now.isBefore(expiry) ? Status.EXPIRED
                : !required.versionPid().equals(active) ? Status.SUPERSEDED
                : !"published".equals(publication) || !"published".equals(root.getStatus()) ? Status.NOT_VISIBLE : null;
        if (unavailable != null) return new Check(required.versionPid(), active, null, unavailable,
                false, false, true, false, null, null, now, expiry);
        var recipient = proof.recipient(tenant, required.versionPid(), user, required.memberPid());
        if (recipient == null) return new Check(required.versionPid(), active, null, Status.NOT_VISIBLE,
                true, false, true, false, null, null, now, expiry);
        var snapshot = codec.object((String) version.get("snapshot"));
        boolean needsAck = snapshot.get("policy") instanceof Map<?,?> policy
                && Boolean.TRUE.equals(policy.get("acknowledgementRequired"));
        Instant acknowledged = instant(recipient.get("acknowledged_at"));
        String acknowledgedHash = (String) recipient.get("acknowledged_hash");
        Status status = !required.snapshotHash().equals(hash) ? Status.SNAPSHOT_CHANGED
                : !needsAck ? Status.NOT_REQUIRED
                : recipient.get("first_read_at") == null || acknowledged == null || !hash.equals(acknowledgedHash)
                    || acknowledged.isAfter(now) ? Status.NOT_ACKNOWLEDGED : Status.SATISFIED;
        return new Check(required.versionPid(), active, hash, status, true, true, true,
                needsAck, acknowledged, acknowledgedHash, now, expiry);
    }

    private static Check absent(Requirement required, Status status, Instant now) {
        return new Check(required.versionPid(), null, null, status, false, false,
                status != Status.MEMBER_CHANGED, false, null, null, now, null);
    }

    private static Instant instant(Object value) {
        return value == null ? null : AnnouncementDeliveryPlanResolver.instant(value);
    }
}
