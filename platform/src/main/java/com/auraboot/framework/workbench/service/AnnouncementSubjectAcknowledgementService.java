package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementAcknowledgementMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;

/** Core-internal evaluator; only the native subject factory exports a plugin capability. */
@Service
@RequiredArgsConstructor
public class AnnouncementSubjectAcknowledgementService {
    private final AnnouncementAcknowledgementMapper proof;
    private final AnnouncementLifecycleMapper lifecycle;
    private final AnnouncementCodec codec;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Check> checkSubject(Long tenant, Long userId, String user, String memberPid, List<Requirement> required) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !Objects.equals(tenant, MetaContext.getCurrentTenantId())) throw new IllegalStateException("Original writable tenant transaction required");
        if (required == null || required.isEmpty() || required.size() > 32) throw new IllegalArgumentException("Bounded exact plan required");
        required = List.copyOf(required);
        var seen = new HashSet<String>();
        for (var item : required) if (!seen.add(item.versionPid()) || !memberPid.equals(item.memberPid())) throw new IllegalArgumentException("Exact subject plan required");
        var member = proof.currentMember(tenant, userId, user);
        boolean current = member != null && memberPid.equals(member.get("member_pid")) && user.equals(member.get("user_pid"));
        var versions = new HashMap<String,Map<String,Object>>(); var roots = new TreeSet<String>();
        if (current) for (var item : required) {
            var version = proof.version(tenant, item.versionPid());
            if (version != null) { versions.put(item.versionPid(), version); roots.add((String) version.get("announcement_pid")); }
        }
        var locked = new HashMap<String,Announcement>();
        for (String root : roots) locked.put(root, lifecycle.lock(tenant, root));
        Instant now = proof.databaseNow(); var result = new ArrayList<Check>();
        for (var item : required) {
            var version = versions.get(item.versionPid());
            if (!current) result.add(absent(item, Status.MEMBER_CHANGED, now));
            else if (version == null) result.add(absent(item, Status.MISSING, now));
            else {
                var fresh = proof.version(tenant, item.versionPid());
                if (fresh == null || !Objects.equals(fresh.get("announcement_pid"), version.get("announcement_pid"))) throw unavailable();
                result.add(evaluate(item, tenant, user, locked.get((String) version.get("announcement_pid")), fresh, now));
            }
        }
        var retained = required; var satisfied = result.stream().filter(c -> c.status() == Status.SATISFIED).toList();
        Object memberId = member == null ? null : member.get("member_id");
        if (!satisfied.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                var freshMember = proof.currentMember(tenant, userId, user);
                if (readOnly || freshMember == null || !Objects.equals(memberId, freshMember.get("member_id"))
                        || !memberPid.equals(freshMember.get("member_pid"))) throw unavailable();
                Instant commitNow = proof.databaseNow();
                for (var previous : satisfied) {
                    var item = retained.stream().filter(r -> r.versionPid().equals(previous.requestedVersionPid())).findFirst().orElseThrow();
                    var fresh = proof.version(tenant, item.versionPid()); var before = versions.get(item.versionPid());
                    if (fresh == null || !Objects.equals(before.get("announcement_pid"), fresh.get("announcement_pid"))) throw unavailable();
                    if (evaluate(item, tenant, user, lifecycle.lock(tenant, (String) before.get("announcement_pid")), fresh, commitNow).status() != Status.SATISFIED) throw unavailable();
                }
            }
        });
        return List.copyOf(result);
    }

    Check evaluate(Requirement required, Long tenant, String user, Announcement root,
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
        return new Check(required.versionPid(), null, null, status, false, false, status != Status.MEMBER_CHANGED, false, null, null, now, null);
    }
    private static Instant instant(Object value) { return value == null ? null : AnnouncementDeliveryPlanResolver.instant(value); }
    private static RuntimeException unavailable() { return com.auraboot.framework.exception.BusinessException.i18n("announcement.error.recipient_unavailable"); }
}
