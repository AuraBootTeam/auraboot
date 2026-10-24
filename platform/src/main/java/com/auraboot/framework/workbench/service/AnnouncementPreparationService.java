package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** One immutable content/authorization preparation path for exemption and approval. */
@Service
@RequiredArgsConstructor
public class AnnouncementPreparationService {
    private final AnnouncementPolicyService policies;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementReleaseMapper releases;
    private final AnnouncementCodec codec;
    private final TenantClock clock;
    private final AnnouncementTenantPolicyService tenantPolicies;
    private final com.auraboot.framework.workbench.mapper.AnnouncementEstimateMapper estimates;

    public record Prepared(AnnouncementPolicyService.Policy policy,Map<String,Object> member,
        String timezone,String authorizationHash,Instant acceptedAt,Instant plannedAt,String snapshot,String snapshotHash,
        AnnouncementAudienceService.Resolution audience) {}

    public Prepared prepare(Announcement record) {
        var policy=policies.load(record.getPid());
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
        var member=releases.actor(tenant,actor);
        if(member==null) throw new AccessDeniedException("Announcement submission actor is inactive");
        String zone=clock.getZoneId(tenant).getId(),authorization=authorization(audience,member);
        Map<String,Object> frozenPolicy=codec.object(policies.encode(policy));
        frozenPolicy.put("acknowledgementRequired",policy.acknowledgementRequired(record.getAnnouncementPriority()));
        Map<String,Object> snapshot=new TreeMap<>();
        snapshot.put("title",record.getTitle());snapshot.put("content",record.getContent());
        snapshot.put("importance",record.getAnnouncementPriority());snapshot.put("pinned",Boolean.TRUE.equals(record.getPinned()));
        snapshot.put("expiresAt",record.getExpiresAt());snapshot.put("policy",frozenPolicy);
        snapshot.put("lateWindowMinutes",tenantPolicies.current().lateWindowMinutes());
        var operating=tenantPolicies.current();
        Map<String,Object> reminders=new TreeMap<>();
        reminders.put("at",operating.reminderAt());reminders.put("limit",operating.automaticReminderLimit());
        reminders.put("dedupeMinutes",operating.reminderDedupeMinutes());
        reminders.put("quietStart",operating.quietStart());reminders.put("quietEnd",operating.quietEnd());
        snapshot.put("reminderPolicy",reminders);
        snapshot.put("timezone",zone);snapshot.put("authorizationHash",authorization);
        snapshot.put("estimatedRecipientCount",audience.recipients().size());snapshot.put("estimatedAt",audience.resolvedAt());
        return new Prepared(policy,member,zone,authorization,now,planned,codec.encode(snapshot),codec.hash(snapshot),audience);
    }

    public void persistEstimate(Long tenant,String version,Prepared prepared) {
        var recipients=prepared.audience().recipients();
        if(estimates.header(tenant,version,prepared.audience().resolvedAt(),recipients.size())!=1)
            throw new IllegalStateException("Audience estimate header was not persisted");
        for(int offset=0;offset<recipients.size();offset+=500) {
            var batch=recipients.subList(offset,Math.min(offset+500,recipients.size())).stream()
                .map(recipient->Map.<String,Object>of("user",recipient.userPid(),"member",recipient.memberPid(),"name",recipient.displayName(),"rules",codec.encode(recipient.matchedRuleRefs()))).toList();
            if(estimates.rows(tenant,version,batch)!=batch.size()) throw new IllegalStateException("Audience estimate rows were not persisted");
        }
    }

    public String authorization(AnnouncementAudienceService.Resolution resolution,Map<String,Object> member) {
        Map<String,Object> proof=new TreeMap<>();proof.put("memberPid",member.get("member_pid"));
        proof.put("tenantPolicyRevision",tenantPolicies.current().securityRevision());
        proof.put("scopeType",resolution.scopeType());proof.put("departments",resolution.allowedDepartmentPids());
        proof.put("grants",codec.object(releases.authorizationEvidence(MetaContext.getCurrentTenantId(),((Number)member.get("member_id")).longValue())));
        return codec.hash(proof);
    }
    private static void invalid(String reason){throw BusinessException.i18n("announcement.error."+reason);}
}
