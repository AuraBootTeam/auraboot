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
    public static final String RELEASED_EVENT="announcement.publication.committed.v1";
    private final AnnouncementAccessService access;
    private final AnnouncementPolicyService policies;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementReleaseMapper releases;
    private final AnnouncementCodec codec;
    private final TenantClock tenantClock;
    private final ReliableIntegrationAccessor outbox;
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
        else throw new IllegalArgumentException("Unsupported announcement release operation");
        result.put("operationId",operation);
        records.audit(UniqueIdGenerator.generate(),tenant,actor,pid,(String)result.get("versionPid"),command,operation,expected,expected+1);
        if(records.finish(tenant,actor,command,operation,pid,codec.encode(result))!=1) throw new IllegalStateException("Release operation was not persisted");
        return result;
    }

    private Map<String,Object> acceptExemption(Announcement record) {
        access.actor("exempt");
        if(!deliveryEnabled) invalid("delivery_disabled");
        if(!"draft".equals(record.getStatus()) || record.getActiveVersionPid()!=null) invalid("not_editable");
        var policy=policies.load(record.getPid());
        if(!"exempt".equals(policy.approvalMode()) || policy.exemptionReason()==null || policy.exemptionReason().isBlank()) invalid("exemption_required");
        if(record.getTitle()==null || record.getTitle().isBlank() || record.getContent()==null || record.getContent().isBlank()) invalid("content_required");
        Instant now=Instant.now(),planned=policy.scheduledAt()==null ? now : policy.scheduledAt();
        if(policy.scheduledAt()!=null && !planned.isAfter(now)) invalid("schedule_invalid");
        if(record.getExpiresAt()!=null && (!record.getExpiresAt().isAfter(planned)
            || (policy.acknowledgementRequired(record.getAnnouncementPriority())
                && record.getExpiresAt().isBefore(planned.plus(Duration.ofDays(policy.acknowledgementDays())))))) invalid("expiry_invalid");
        var audience=audiences.resolve(policy.audience());
        if(audience.recipients().isEmpty()) invalid("audience_empty");
        policies.verifyAttachments(policy,record.getPid(),true);
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        Map<String,Object> member=releases.actor(tenant,actor);
        if(member==null) throw new AccessDeniedException("Announcement release actor is inactive");
        String version=UniqueIdGenerator.generate(),release=UniqueIdGenerator.generate();
        String zone=tenantClock.getZoneId(tenant).getId();
        String authorization=authorization(audience,member);
        Map<String,Object> frozenPolicy=codec.object(policies.encode(policy));
        frozenPolicy.put("acknowledgementRequired",policy.acknowledgementRequired(record.getAnnouncementPriority()));
        Map<String,Object> snapshot=new TreeMap<>();
        snapshot.put("title",record.getTitle());snapshot.put("content",record.getContent());
        snapshot.put("importance",record.getAnnouncementPriority());snapshot.put("pinned",Boolean.TRUE.equals(record.getPinned()));
        snapshot.put("expiresAt",record.getExpiresAt());snapshot.put("policy",frozenPolicy);
        snapshot.put("timezone",zone);snapshot.put("authorizationHash",authorization);
        snapshot.put("estimatedRecipientCount",audience.recipients().size());snapshot.put("estimatedAt",audience.resolvedAt());
        String state=policy.scheduledAt()==null ? "unpublished" : "scheduled";
        if(releases.freeze(tenant,record.getPid(),version,record.getEditorRevision(),codec.hash(snapshot),codec.encode(snapshot),actor)!=1
            || releases.control(tenant,version,release,state,planned,record.getExpiresAt())!=1
            || releases.queue(tenant,release,record.getPid(),version,actor,(String)member.get("member_pid"),authorization,planned,planned.plus(Duration.ofMinutes(30)),zone)!=1
            || releases.accept(tenant,record.getPid(),version,actor,state,record.getRowVersion())!=1) conflict();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("recordPid",record.getPid());result.put("pid",record.getPid());result.put("versionPid",version);
        result.put("releaseAttemptPid",release);result.put("row_version",record.getRowVersion()+1);
        result.put("status",state);result.put("operationState","accepted");
        return result;
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
        Map<String,Object> proof=new TreeMap<>();
        proof.put("memberPid",member.get("member_pid"));proof.put("scopeType",resolution.scopeType());
        proof.put("departments",resolution.allowedDepartmentPids());
        proof.put("grants",codec.object(releases.authorizationEvidence(MetaContext.getCurrentTenantId(),((Number)member.get("member_id")).longValue())));
        return codec.hash(proof);
    }

    @Transactional
    public Map<String,Object> claim(){return releases.claim();}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void perform(Map<String,Object> claim) {
        Long tenant=((Number)claim.get("tenant_id")).longValue(),actor=((Number)claim.get("actor_user_id")).longValue();
        String version=(String)claim.get("version_pid"),release=(String)claim.get("pid");
        long fence=((Number)claim.get("fencing_token")).longValue();
        Map<String,Object> member=releases.actor(tenant,actor);
        if(member==null) { fail(claim,"blocked","ACTOR_INACTIVE");return; }
        MetaContext.Snapshot previous=MetaContext.snapshot();
        try {
            Long memberId=((Number)member.get("member_id")).longValue();
            MetaContext.setContext(tenant,actor,(String)member.get("user_pid"),(String)member.get("username"),releases.actorRoles(tenant,memberId));
            MetaContext.setMemberId(memberId);
            // The aggregate lock is always first, matching cancel/withdraw and avoiding inverted locks.
            Map<String,Object> frozen=releases.version(tenant,version);
            if(frozen==null) throw new IllegalStateException("Release version does not exist");
            String pid=(String)frozen.get("announcement_pid");
            Announcement record=access.manage(pid,"publish",true);
            Map<String,Object> job=releases.lock(tenant,version);
            if(job==null || !"running".equals(job.get("status")) || ((Number)job.get("fence")).longValue()!=fence) return;
            if(!version.equals(record.getCurrentVersionPid()) || record.getActiveVersionPid()!=null) { fail(claim,"canceled","VERSION_SUPERSEDED");return; }
            Instant now=Instant.now(),latest=time(job.get("latest_allowed_at"));
            if(now.isAfter(latest)) {
                releases.stopControl(tenant,version,"missed");releases.stopRecord(tenant,pid,version,"missed");fail(claim,"missed","SCHEDULE_MISSED");return;
            }
            Map<String,Object> snapshot=codec.object(frozen.get("snapshot").toString());
            @SuppressWarnings("unchecked") var policy=policies.parse((Map<String,Object>)snapshot.get("policy"));
            if(!"exempt".equals(frozen.get("authorization_basis"))) invalid("approval_not_ready");
            access.actor("exempt");
            var recipients=audiences.resolve(policy.audience());
            if(!job.get("actor_member_pid").equals(member.get("member_pid")) || !job.get("authorization_hash").equals(authorization(recipients,member))) invalid("authorization_changed");
            if(recipients.recipients().isEmpty()) invalid("audience_empty");
            policies.verifyAttachments(policy,pid,true);
            Instant expiry=snapshot.get("expiresAt")==null ? null : Instant.parse(snapshot.get("expiresAt").toString());
            Instant due=Boolean.TRUE.equals(policy.acknowledgementRequired()) ? now.plus(Duration.ofDays(policy.acknowledgementDays())) : null;
            if(expiry!=null && (!now.isBefore(expiry) || (due!=null && due.isAfter(expiry)))) invalid("expiry_invalid");
            String recipientSnapshot=UniqueIdGenerator.generate();
            if(releases.seal(tenant,recipientSnapshot,version,now,recipients.recipients().size())!=1) throw new IllegalStateException("Recipient snapshot was not sealed");
            List<AnnouncementAudienceService.Recipient> targets=recipients.recipients();
            for(int start=0;start<targets.size();start+=500) {
                List<AnnouncementAudienceService.Recipient> batch=targets.subList(start,Math.min(start+500,targets.size()));
                if(releases.recipients(tenant,version,batch)!=batch.size()) throw new IllegalStateException("Recipient facts were not persisted");
            }
            if(releases.publishControl(tenant,version,now,due)!=1 || releases.publishRecord(tenant,pid,version,actor,now)!=1
                || releases.finish(tenant,release,fence,"succeeded",null)!=1) conflict();
            outbox.enqueueIdempotent(new IntegrationEventEnvelope("1.0",release,RELEASED_EVENT,"auraboot.announcement",version,now,tenant,
                release,null,"announcement:"+version,1,Map.of("versionPid",version,"batchPid",release,"recipientSnapshotPid",recipientSnapshot,"announcementPid",pid),Map.of()));
        } finally {MetaContext.clear();MetaContext.restore(previous);}
    }

    @Transactional
    public void fail(Map<String,Object> claim,String state,String error) {
        releases.finish(((Number)claim.get("tenant_id")).longValue(),(String)claim.get("pid"),
            ((Number)claim.get("fencing_token")).longValue(),state,error);
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
