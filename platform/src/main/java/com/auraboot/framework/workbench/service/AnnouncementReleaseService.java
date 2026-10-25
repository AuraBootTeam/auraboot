package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.*;
import com.auraboot.framework.exception.*;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Accept a frozen release intent; a fenced worker performs the actual publication transaction. */
@Service
@RequiredArgsConstructor
public class AnnouncementReleaseService {
    public static final String PUBLISH="announcement:publish";
    public static final String CANCEL="announcement:cancel_schedule";
    public static final String RETRY="announcement:retry_publication";
    public static final String RELEASED_EVENT="announcement.publication.committed.v1";
    private final AnnouncementAccessService access;
    private final AnnouncementPolicyService policies;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementReleaseMapper releases;
    private final AnnouncementReminderMapper reminders;
    private final AnnouncementCodec codec;
    private final AnnouncementPreparationService preparation;
    private final AnnouncementTenantPolicyService tenantPolicies;
    private final AnnouncementApprovalProofService approvalProof;
    private final ReliableIntegrationAccessor outbox;
    private final com.auraboot.framework.permission.service.UserPermissionService permissions;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean deliveryEnabled;

    public long authorizeTarget(String pid) { return access.manage(pid,"publish",false).getRowVersion(); }

    @Transactional
    public Map<String,Object> execute(String command,String pid,Map<String,Object> input,String operation,Integer expected) {
        Announcement record=access.manage(pid,"publish",true);
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        if(operation==null || operation.isBlank() || operation.length()>100) invalid("operation_required");
        if(input!=null && !input.isEmpty()) invalid("protected_input");
        Map<String,Object> intent=new TreeMap<>();intent.put("recordPid",pid);intent.put("revision",expected);
        String digest=codec.hash(intent);
        if(records.claim(tenant,actor,command,operation,digest)==0) {
            Map<String,Object> previous=records.operation(tenant,actor,command,operation);
            if(previous==null || !digest.equals(previous.get("request_hash")))
                throw new ConflictException("IDEMPOTENCY_CONFLICT","Announcement operation content changed",Map.of());
            return codec.object(previous.get("result").toString());
        }
        if(expected==null) throw new CasVersionRequiredException("Announcement revision is required",Map.of("recordPid",pid));
        if(!expected.equals(record.getRowVersion())) conflict();
        Map<String,Object> result;
        if(PUBLISH.equals(command)) result=acceptExemption(record);
        else if(CANCEL.equals(command)) result=cancel(record);
        else if(RETRY.equals(command)) result=retry(record);
        else throw new IllegalArgumentException("Unsupported announcement release operation");
        result.put("operationId",operation);
        records.audit(UniqueIdGenerator.generate(),tenant,actor,pid,(String)result.get("versionPid"),command,operation,expected,expected+1);
        if(records.finish(tenant,actor,command,operation,pid,codec.encode(result))!=1) throw new IllegalStateException("Release operation was not persisted");
        return result;
    }

    private Map<String,Object> acceptExemption(Announcement record) {
        access.actor("exempt");
        if(tenantPolicies.current().forceApproval()) invalid("approval_required");
        if(!deliveryEnabled) invalid("delivery_disabled");
        if(!"draft".equals(record.getStatus()) || record.getActiveVersionPid()!=null) invalid("not_editable");
        var policy=policies.load(record.getPid());
        if(!"exempt".equals(policy.approvalMode()) || policy.exemptionReason()==null || policy.exemptionReason().isBlank()) invalid("exemption_required");
        var prepared=preparation.prepare(record);
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        var member=prepared.member();String version=UniqueIdGenerator.generate(),release=UniqueIdGenerator.generate();
        String zone=prepared.timezone(),authorization=prepared.authorizationHash();Instant planned=prepared.plannedAt();
        String state=policy.scheduledAt()==null ? "unpublished" : "scheduled";
        if(releases.freeze(tenant,record.getPid(),version,record.getEditorRevision(),prepared.snapshotHash(),prepared.snapshot(),actor)!=1) conflict();
        preparation.persistEstimate(tenant,version,prepared);
        if(releases.control(tenant,version,release,state,planned,record.getExpiresAt())!=1
            || releases.queue(tenant,release,record.getPid(),version,actor,(String)member.get("member_pid"),authorization,planned,planned.plus(Duration.ofMinutes(tenantPolicies.current().lateWindowMinutes())),zone)!=1
            || releases.accept(tenant,record.getPid(),version,actor,state,record.getRowVersion())!=1) conflict();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("recordPid",record.getPid());result.put("pid",record.getPid());result.put("versionPid",version);
        result.put("releaseAttemptPid",release);result.put("row_version",record.getRowVersion()+1);
        result.put("status",state);result.put("operationState","accepted");
        return result;
    }

    private Map<String,Object> retry(Announcement record) {
        if(!deliveryEnabled) invalid("delivery_disabled");
        if(record.getCurrentVersionPid()==null || record.getActiveVersionPid()!=null
            || !Set.of("unpublished","scheduled").contains(record.getStatus())) invalid("not_editable");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        var job=releases.lock(tenant,record.getCurrentVersionPid());
        var control=releases.version(tenant,record.getCurrentVersionPid());
        if(!retryable(record,job,control,Instant.now())) invalid("publication_not_retryable");
        if(releases.retry(tenant,(String)job.get("pid"),((Number)job.get("fence")).longValue())!=1
            || releases.bumpRetry(tenant,record.getPid(),record.getCurrentVersionPid(),actor,record.getRowVersion())!=1) conflict();
        return new LinkedHashMap<>(Map.of("pid",record.getPid(),"recordPid",record.getPid(),"versionPid",record.getCurrentVersionPid(),
            "releaseAttemptPid",job.get("pid"),"row_version",record.getRowVersion()+1,"status",record.getStatus(),"operationState","accepted"));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(String pid) {
        var record=access.manage(pid,"read",false);
        if(record.getCurrentVersionPid()==null) return Map.of("records",List.of(),"total",0);
        Long tenant=MetaContext.getCurrentTenantId();var job=releases.read(tenant,record.getCurrentVersionPid());
        if(job==null) return Map.of("records",List.of(),"total",0);
        var control=releases.version(tenant,record.getCurrentVersionPid());
        var view=new LinkedHashMap<String,Object>();view.put("pid",job.get("pid"));view.put("recordPid",pid);
        view.put("versionPid",record.getCurrentVersionPid());view.put("recordRevision",record.getRowVersion());
        view.put("status",job.get("status"));view.put("plannedAt",job.get("scheduled_at"));
        view.put("latestAllowedAt",job.get("latest_allowed_at"));view.put("attempts",job.get("attempts"));
        view.put("errorCode",job.get("error_code")==null ? null : publicFailureCode(job.get("error_code")));
        view.put("canRetry",deliveryEnabled && permissions.hasPermission(MetaContext.getCurrentUserId(),"announcement.announcement.publish")
            && retryable(record,job,control,Instant.now()));
        return Map.of("records",List.of(view),"total",1);
    }

    private static boolean retryable(Announcement record,Map<String,Object> job,Map<String,Object> control,Instant now) {
        return job!=null && control!=null && record.getActiveVersionPid()==null
            && Set.of("unpublished","scheduled").contains(record.getStatus())
            && Set.of("blocked","retryable_failed").contains(job.get("status"))
            && Set.of("unpublished","scheduled").contains(control.get("publication_state"))
            && !now.isAfter(time(job.get("latest_allowed_at")))
            && (control.get("expires_at")==null || now.isBefore(time(control.get("expires_at"))));
    }

    public static String publicFailureCode(Object value) {
        return value!=null && Set.of("ACTOR_INACTIVE","AUTHORIZATION_DENIED","AUTHORIZATION_CHANGED","DELIVERY_DISABLED","APPROVAL_REQUIRED",
            "APPROVAL_NOT_READY","SCHEDULE_MISSED","VERSION_SUPERSEDED","TRANSIENT_DATABASE_FAILURE","RETRY_EXHAUSTED","EXECUTION_FAILURE")
            .contains(value) ? value.toString() : "PUBLICATION_BLOCKED";
    }

    private Map<String,Object> cancel(Announcement record) {
        Long tenant=MetaContext.getCurrentTenantId();
        if(record.getCurrentVersionPid()==null || record.getActiveVersionPid()!=null) invalid("not_editable");
        Map<String,Object> job=releases.lock(tenant,record.getCurrentVersionPid());
        if(job==null || !Set.of("queued","running","retryable_failed","blocked").contains(job.get("status"))) conflict();
        if(releases.cancel(tenant,record.getCurrentVersionPid())!=1
            || releases.stopControl(tenant,record.getCurrentVersionPid(),"withdrawn")!=1
            || releases.stopRecord(tenant,record.getPid(),record.getCurrentVersionPid(),"withdrawn")!=1) conflict();
        return new LinkedHashMap<>(Map.of("recordPid",record.getPid(),"versionPid",record.getCurrentVersionPid(),
            "row_version",record.getRowVersion()+1,"status","withdrawn","operationState","completed"));
    }

    public String authorization(AnnouncementAudienceService.Resolution resolution,Map<String,Object> member) {
        return preparation.authorization(resolution,member);
    }

    @Transactional
    public Map<String,Object> claim(){
        // Only the durable task registry is global; business execution restores the captured tenant below.
        return MetaContext.runWithoutTenantFilter(releases::claim);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void perform(Map<String,Object> claim) {
        Long tenant=((Number)claim.get("tenant_id")).longValue(),actor=((Number)claim.get("actor_user_id")).longValue();
        String version=(String)claim.get("version_pid"),release=(String)claim.get("pid");
        long fence=((Number)claim.get("fencing_token")).longValue();
        Map<String,Object> member=MetaContext.runWithoutTenantFilter(()->releases.actor(tenant,actor));
        if(member==null) { fail(claim,"blocked","ACTOR_INACTIVE");return; }
        MetaContext.Snapshot previous=MetaContext.snapshot();
        try {
            Long memberId=((Number)member.get("member_id")).longValue();
            MetaContext.setContext(tenant,actor,(String)member.get("user_pid"),(String)member.get("username"));
            MetaContext.setContext(tenant,actor,(String)member.get("user_pid"),(String)member.get("username"),releases.actorRoles(tenant,memberId));
            MetaContext.setMemberId(memberId);
            // The aggregate lock is always first, matching cancel/withdraw and avoiding inverted locks.
            Map<String,Object> frozen=releases.version(tenant,version);
            if(frozen==null) throw new IllegalStateException("Release version does not exist");
            String pid=(String)frozen.get("announcement_pid");
            Announcement record=access.manage(pid,"publish",true);
            Map<String,Object> job=releases.lock(tenant,version);
            if(job==null || !"running".equals(job.get("status")) || ((Number)job.get("fence")).longValue()!=fence
                || !Boolean.TRUE.equals(job.get("lease_valid"))) return;
            if(!version.equals(record.getCurrentVersionPid()) || record.getActiveVersionPid()!=null) { fail(claim,"canceled","VERSION_SUPERSEDED");return; }
            Instant now=Instant.now(),latest=time(job.get("latest_allowed_at"));
            if(now.isAfter(latest)) {
                releases.stopControl(tenant,version,"missed");releases.stopRecord(tenant,pid,version,"missed");
                records.audit(UniqueIdGenerator.generate(),tenant,actor,pid,version,"announcement:publication_missed",release,record.getRowVersion(),record.getRowVersion()+1);
                fail(claim,"missed","SCHEDULE_MISSED");return;
            }
            if(!deliveryEnabled) invalid("delivery_disabled");
            Map<String,Object> snapshot=codec.object(frozen.get("snapshot").toString());
            @SuppressWarnings("unchecked") var policy=policies.parseStored((Map<String,Object>)snapshot.get("policy"));
            if("approved".equals(frozen.get("authorization_basis"))) {
                access.actor("submit");
                if(!"approved".equals(frozen.get("approval_state"))) invalid("approval_not_ready");
                approvalProof.requireValid(tenant,(String)frozen.get("current_submission_pid"),version,(String)frozen.get("snapshot_hash"));
            } else if("exempt".equals(frozen.get("authorization_basis"))) {
                access.actor("exempt");
                if(tenantPolicies.current().forceApproval()) invalid("approval_required");
            } else invalid("approval_not_ready");
            var recipients=audiences.resolve(policy.audience());
            if(!job.get("actor_member_pid").equals(member.get("member_pid")) || !job.get("authorization_hash").equals(authorization(recipients,member))) invalid("authorization_changed");
            if(recipients.recipients().isEmpty()) invalid("audience_empty");
            policies.verifyAttachments(policy,pid,true);
            Instant expiry=snapshot.get("expiresAt")==null ? null : Instant.parse(snapshot.get("expiresAt").toString());
            Instant due=Boolean.TRUE.equals(policy.acknowledgementRequired()) ? now.plus(Duration.ofDays(policy.acknowledgementDays())) : null;
            if(expiry!=null && (!now.isBefore(expiry) || (due!=null && due.isAfter(expiry)))) invalid("expiry_invalid");
            keepLease(tenant,release,fence);
            String recipientSnapshot=UniqueIdGenerator.generate();
            if(releases.beginSnapshot(tenant,recipientSnapshot,version,now,recipients.recipients().size())!=1) throw new IllegalStateException("Recipient snapshot was not opened");
            List<AnnouncementAudienceService.Recipient> targets=recipients.recipients();
            for(int start=0;start<targets.size();start+=500) {
                keepLease(tenant,release,fence);
                List<AnnouncementAudienceService.Recipient> batch=targets.subList(start,Math.min(start+500,targets.size()));
                if(releases.recipients(tenant,version,batch)!=batch.size()) throw new IllegalStateException("Recipient facts were not persisted");
            }
            if(releases.seal(tenant,version)!=1) throw new IllegalStateException("Recipient snapshot was not sealed with its exact count");
            keepLease(tenant,release,fence);
            if(releases.publishControl(tenant,version,now,due)!=1 || releases.publishRecord(tenant,pid,version,actor,now)!=1
                ) conflict();
            if(releases.finish(tenant,release,fence,"succeeded",null)!=1)
                throw new org.springframework.dao.CannotAcquireLockException("Announcement release lease was lost before commit");
            if(reminders.createPlan(tenant,version,release)!=1) throw new IllegalStateException("Reminder plan was not persisted");
            records.audit(UniqueIdGenerator.generate(),tenant,actor,pid,version,"announcement:publication_completed",release,record.getRowVersion(),record.getRowVersion()+1);
            outbox.enqueueIdempotent(new IntegrationEventEnvelope("1.0",release,RELEASED_EVENT,"auraboot.announcement",version,now,tenant,
                release,null,"announcement:"+version,1,Map.of("versionPid",version,"batchPid",release,"recipientSnapshotPid",recipientSnapshot,"announcementPid",pid),Map.of()));
        } finally {MetaContext.clear();MetaContext.restore(previous);}
    }

    private void keepLease(Long tenant,String release,long fence) {
        // Renew only a live lease under the already-held job lock; never revive an expired worker.
        if(releases.renew(tenant,release,fence)!=1)
            throw new org.springframework.dao.CannotAcquireLockException("Announcement release lease expired during execution");
    }

    @Transactional
    public void fail(Map<String,Object> claim,String state,String error) {
        // This updates only the trusted claimed task's exact tenant/pid/fence after a rolled-back business transaction.
        MetaContext.runWithoutTenantFilter(()->releases.finish(((Number)claim.get("tenant_id")).longValue(),(String)claim.get("pid"),
            ((Number)claim.get("fencing_token")).longValue(),state,error));
    }
    private static Instant time(Object value) {
        if(value instanceof Instant instant) return instant;
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if(value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        throw new IllegalStateException("Release timestamp was not mapped");
    }
    private static void invalid(String reason){throw BusinessException.i18n("announcement.error."+reason);}
    private static void conflict(){throw new ConflictException("CAS_VERSION_CONFLICT","Announcement release state changed",Map.of());}
}
