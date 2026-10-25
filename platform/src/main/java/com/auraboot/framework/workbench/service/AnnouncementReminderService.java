package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.*;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.auraboot.framework.workbench.service.AnnouncementReminderEligibility.*;

/** Preview tokens are advisory; acceptance recalculates under the same aggregate lock as withdrawal and reading. */
@Service
@RequiredArgsConstructor
public class AnnouncementReminderService {
    public static final String SUBMIT="announcement:remind",RESUME="announcement:resume_reminders";
    public static final String EXPAND="announcement.reminder.expand.v1";
    private final AnnouncementAccessService access;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementStatisticsMapper facts;
    private final AnnouncementReminderMapper reminders;
    private final AnnouncementLifecycleMapper operations;
    private final AnnouncementCodec codec;
    private final AnnouncementActorContext actors;
    private final ReliableIntegrationAccessor outbox;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean enabled;
    @Value("${auraboot.announcement.statistics-max-recipients:50000}") private int maximumRecipients;

    private record Source(Announcement record,Map<String,Object> version,Map<String,Object> snapshot,
            Map<String,Object> frozen,Policy policy,AnnouncementManagementScope scope,Instant now) {}
    private Source source(String recordPid,String version,boolean lock) {
        var record=access.manage(recordPid,"remind",lock);
        var row=facts.version(MetaContext.getCurrentTenantId(),version);
        if(row==null || !recordPid.equals(row.get("announcement_pid"))) invalid("statistics_unavailable");
        if(!enabled) invalid("delivery_disabled");
        if(!"publication".equals(row.get("origin"))) invalid("reminder_legacy_unknown");
        if(maximumRecipients<1 || ((Number)row.get("recipient_count")).longValue()>maximumRecipients) invalid("statistics_capacity_exceeded");
        var snapshot=codec.object(row.get("snapshot").toString());
        if(!(snapshot.get("reminderPolicy") instanceof Map<?,?>)) invalid("reminder_policy_unavailable");
        var frozen=object(snapshot.get("reminderPolicy"));var business=object(snapshot.get("policy"));
        int minutes=integer(frozen,"dedupeMinutes");
        var policy=new Policy(true,Boolean.TRUE.equals(row.get("version_active")),Boolean.TRUE.equals(business.get("acknowledgementRequired")),
            time(row.get("ack_due_at")),time(row.get("expires_at")),Duration.ofMinutes(minutes));
        var now=facts.databaseNow();
        if(!policy.versionActive() || (policy.expiresAt()!=null && !now.isBefore(policy.expiresAt()))) invalid("reminder_version_inactive");
        return new Source(record,row,snapshot,frozen,policy,audiences.managementScope("remind"),now);
    }
    private String currentMember() {
        String member=facts.currentMember(MetaContext.getCurrentTenantId(),MetaContext.getCurrentMemberId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentUserPid());
        if(member==null) throw new AccessDeniedException("Announcement reminder membership is unavailable");return member;
    }
    private Selection selection(Source source,AnnouncementStatistics.Filters filters,boolean automatic) {
        var raw=facts.facts(MetaContext.getCurrentTenantId(),(String)source.version().get("version_pid"));
        if(raw.size()!=((Number)source.version().get("recipient_count")).longValue()) throw new IllegalStateException("Sealed reminder recipient count changed");
        var guards=new HashMap<String,Instant>();
        reminders.guards(MetaContext.getCurrentTenantId(),(String)source.version().get("version_pid"))
            .forEach(row->guards.put((String)row.get("user_pid"),time(row.get("last_intention_at"))));
        List<Recipient> recipients=new ArrayList<>();
        for(var row:raw) {
            String user=(String)row.get("user_pid");var organization=codec.object(row.get("organization_snapshot").toString());
            if(!source.scope().contains(user,organization)) throw new AccessDeniedException("Current reminder scope no longer covers the sealed audience");
            var fact=new AnnouncementStatistics.Fact(user,(String)row.get("member_pid"),(String)row.get("display_name_snapshot"),organization,
                time(row.get("first_read_at")),time(row.get("acknowledged_at")),Boolean.TRUE.equals(row.get("member_eligible")),(String)row.get("notification_state"));
            var view=AnnouncementStatistics.capture(List.of(fact),"publication",source.policy().acknowledgementRequired(),source.policy().acknowledgementDeadline(),
                true,source.now(),source.scope(),filters).rows().getFirst();
            if(view.matchesFilters()) recipients.add(new Recipient(user,fact.memberPid(),fact.firstReadAt(),fact.acknowledgedAt(),fact.memberEligible(),guards.get(user)));
        }
        return select(source.policy(),target(filters),recipients,source.now(),automatic);
    }
    private static Target target(AnnouncementStatistics.Filters filters) {
        return switch(filters.state()) {
            case "unread" -> Target.UNREAD;
            case "pending_ack","overdue" -> Target.UNACKNOWLEDGED;
            case "all" -> Target.OUTSTANDING;
            default -> throw new IllegalArgumentException("Unsupported reminder state");
        };
    }
    @Transactional
    public Map<String,Object> preview(String record,String version,AnnouncementStatistics.Filters filters) {
        var source=source(record,version,true);var selected=selection(source,filters,false);
        String token=UniqueIdGenerator.generate();
        if(reminders.preview(MetaContext.getCurrentTenantId(),token,version,MetaContext.getCurrentUserId(),currentMember(),source.record().getRowVersion(),
            codec.encode(filters),Math.toIntExact(selected.eligible()),source.now())!=1) throw new IllegalStateException("Reminder preview was not persisted");
        return Map.of("previewToken",token,"recordPid",record,"versionPid",version,"revision",source.record().getRowVersion(),"filters",filters,
            "eligible",selected.eligible(),"throttled",selected.throttled(),"evaluatedAt",source.now(),"expiresAt",source.now().plus(Duration.ofMinutes(30)));
    }
    public long authorizeTarget(String record) {return access.manage(record,"remind",false).getRowVersion();}

    @Transactional
    public Map<String,Object> execute(String command,String record,Map<String,Object> payload,String operation) {
        if(payload==null || !payload.keySet().equals(Set.of(SUBMIT.equals(command) ? "previewToken" : "versionPid"))
            || !(payload.values().iterator().next() instanceof String reference) || reference.isBlank() || reference.length()>36) invalid("protected_input");
        if(operation==null || operation.isBlank() || operation.length()>100) invalid("operation_required");
        var root=access.manage(record,"remind",true);String member=currentMember();Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        String hash=codec.hash(Map.of("recordPid",record,"payload",payload));
        // Current grants and the exact original membership are checked even on replay.
        audiences.managementScope("remind");
        var previous=operations.operation(tenant,actor,command,operation);
        if(previous!=null) {
            if(!hash.equals(previous.get("request_hash"))) conflict("IDEMPOTENCY_CONFLICT");
            var result=codec.object(previous.get("result").toString());
            var source=source(record,(String)result.get("versionPid"),true);selection(source,AnnouncementStatistics.Filters.all(),false);
            if(!member.equals(result.get("actorMemberPid"))) throw new AccessDeniedException("Reminder operation belongs to an earlier membership");
            return publicResult(result);
        }
        if(operations.claim(tenant,actor,command,operation,hash)!=1) conflict("IDEMPOTENCY_CONFLICT");
        Map<String,Object> result;
        if(SUBMIT.equals(command)) {
            var preview=reminders.previewByOwner(tenant,(String)payload.get("previewToken"),actor,member);
            if(preview==null || !facts.databaseNow().isBefore(time(preview.get("expires_at")))) invalid("reminder_preview_expired");
            String version=(String)preview.get("version_pid");var source=source(record,version,true);
            if(root.getRowVersion()!=((Number)preview.get("record_revision")).intValue()) conflict("CAS_VERSION_CONFLICT");
            var filters=filters(preview.get("filter_json").toString());
            result=accept(source,version,filters,false,((Number)preview.get("eligible_count")).intValue());
        } else if(RESUME.equals(command)) {
            String version=(String)payload.get("versionPid");var source=source(record,version,true);selection(source,AnnouncementStatistics.Filters.all(),false);
            var plan=reminders.plan(tenant,version);
            if(plan==null || !actor.equals(((Number)plan.get("actor_user_id")).longValue()) || !member.equals(plan.get("actor_member_pid")))
                throw new AccessDeniedException("Only the original reminder actor may resume the plan");
            if(((Number)plan.get("automatic_batches")).intValue()>=integer(source.frozen(),"limit")
                || (source.policy().acknowledgementDeadline()!=null && !source.now().isBefore(source.policy().acknowledgementDeadline()))) invalid("reminder_version_inactive");
            if(reminders.resume(tenant,version)!=1) invalid("reminder_not_blocked");
            result=new LinkedHashMap<>(Map.of("recordPid",record,"versionPid",version,"operationState","resumed"));
        } else throw new IllegalArgumentException("Unsupported reminder command");
        result.put("actorMemberPid",member);result.put("operationId",operation);
        if(operations.audit(UniqueIdGenerator.generate(),tenant,actor,record,(String)result.get("versionPid"),command,operation,root.getRowVersion(),root.getRowVersion())!=1
            || operations.finish(tenant,actor,command,operation,record,codec.encode(result))!=1) throw new IllegalStateException("Reminder acceptance evidence was not persisted");
        return publicResult(result);
    }
    private Map<String,Object> accept(Source source,String version,AnnouncementStatistics.Filters filters,boolean automatic,Integer preview) {
        var selection=selection(source,filters,automatic);String batch=UniqueIdGenerator.generate();
        Long tenant=MetaContext.getCurrentTenantId();
        var day=source.now().atZone(ZoneId.of((String)source.snapshot().get("timezone"))).toLocalDate();
        if(reminders.batch(tenant,batch,version,MetaContext.getCurrentUserId(),currentMember(),automatic ? "AUTO" : "MANUAL",day,target(filters).name(),codec.encode(filters),
            codec.hash(source.frozen()),scopeHash(source.scope()),selection.eligible()>0 ? "accepted" : "skipped",source.now(),Math.toIntExact(selection.eligible()),Math.toIntExact(selection.throttled()),preview)!=1)
            throw new IllegalStateException("Reminder batch was not persisted");
        var users=selection.recipients().stream().map(Recipient::userPid).toList();
        for(int start=0;start<users.size();start+=500) {
            var chunk=users.subList(start,Math.min(users.size(),start+500));
            if(reminders.guards(tenant,version,chunk,source.now(),batch)!=chunk.size() || reminders.intentions(tenant,batch,version,chunk)!=chunk.size())
                throw new IllegalStateException("Reminder intentions were not persisted");
        }
        if(!users.isEmpty()) outbox.enqueueIdempotent(envelope(tenant,batch,version,"",source.now()));
        var result=new LinkedHashMap<String,Object>();result.put("recordPid",source.record().getPid());result.put("versionPid",version);
        result.put("batchPid",batch);result.put("actualCount",users.size());result.put("throttled",selection.throttled());result.put("previewCount",preview);
        result.put("operationState",users.isEmpty() ? "skipped" : "accepted");return result;
    }
    private String scopeHash(AnnouncementManagementScope scope) {
        return codec.hash(Map.of("type",scope.type(),"actor",scope.actorPid(),"units",new TreeSet<>(scope.unitPids())));
    }
    @Transactional
    public void scan(Map<String,Object> reference) {
        Long tenant=((Number)reference.get("tenant_id")).longValue(),actor=((Number)reference.get("actor_user_id")).longValue();
        String version=(String)reference.get("version_pid"),record=(String)reference.get("announcement_pid");
        actors.call(tenant,actor,(String)reference.get("actor_member_pid"),()->{
            var source=source(record,version,true);var plan=reminders.plan(tenant,version);
            if(plan==null || !"active".equals(plan.get("status"))) return null;
            int count=((Number)plan.get("automatic_batches")).intValue(),limit=integer(source.frozen(),"limit");
            if(count>=limit || (source.policy().acknowledgementDeadline()!=null && !source.now().isBefore(source.policy().acknowledgementDeadline()))) {
                reminders.updatePlan(tenant,version,"completed",null,0);return null;
            }
            reminders.updatePlan(tenant,version,"active",null,0);
            var policy=new AnnouncementReminderSchedule.Policy(ZoneId.of((String)source.snapshot().get("timezone")),LocalTime.parse((String)source.frozen().get("at")),
                localTime(source.frozen().get("quietStart")),localTime(source.frozen().get("quietEnd")));
            var due=AnnouncementReminderSchedule.due(policy,time(source.version().get("published_at")),source.now(),source.policy().acknowledgementDeadline(),source.policy().expiresAt());
            var day=source.now().atZone(policy.zone()).toLocalDate();
            if(due.isEmpty() || due.get().isBefore(time(plan.get("resume_after"))) || reminders.scanned(tenant,version,day)) return null;
            var result=accept(source,version,AnnouncementStatistics.Filters.all(),true,null);
            int increment=((Number)result.get("actualCount")).intValue()>0 ? 1 : 0;
            reminders.updatePlan(tenant,version,count+increment>=limit ? "completed" : "active",null,increment);
            if(operations.audit(UniqueIdGenerator.generate(),tenant,actor,record,version,"announcement:automatic_remind",(String)result.get("batchPid"),
                source.record().getRowVersion(),source.record().getRowVersion())!=1) throw new IllegalStateException("Automatic reminder audit was not persisted");
            return null;
        });
    }
    @Transactional
    public void block(Map<String,Object> plan,String error) {
        MetaContext.runWithoutTenantFilter(()->reminders.updatePlan(((Number)plan.get("tenant_id")).longValue(),(String)plan.get("version_pid"),"blocked",error,0));
    }
    public String deliveryEligibility(Long tenant,String batch,String version,Map<String,Object> recipient) {
        var accepted=reminders.batch(tenant,batch);
        if(accepted==null || !version.equals(accepted.get("version_pid"))) throw new SecurityException("Reminder batch binding is invalid");
        if(!"accepted".equals(accepted.get("status"))) return "BATCH_BLOCKED";
        // This is a nonthrowing denial outcome inside the receipt transaction; no protected notification is written.
        try {
            return actors.call(tenant,((Number)accepted.get("actor_user_id")).longValue(),(String)accepted.get("actor_member_pid"),()->{
                var source=source((String)recipient.get("announcement_pid"),version,false);
                if(!scopeHash(source.scope()).equals(accepted.get("scope_hash"))) throw new AccessDeniedException("Reminder authority changed");
                var fact=new Recipient((String)recipient.get("user_pid"),(String)recipient.get("member_pid"),time(recipient.get("first_read_at")),time(recipient.get("acknowledged_at")),true,null);
                var reason=evaluate(source.policy(),Target.valueOf((String)accepted.get("target")),fact,source.now(),"AUTO".equals(accepted.get("mode")),false);
                return reason==Reason.ELIGIBLE ? null : reason.name();
            });
        } catch(AccessDeniedException | BusinessException denied) {
            reminders.blockBatch(tenant,batch,"ACTOR_OR_VERSION_UNAVAILABLE");
            if("AUTO".equals(accepted.get("mode"))) reminders.updatePlan(tenant,version,"blocked","ACTOR_OR_VERSION_UNAVAILABLE",0);
            return "ACTOR_OR_VERSION_UNAVAILABLE";
        }
    }
    public void expand(IntegrationEventEnvelope event) {
        String version=required(event,"versionPid"),batch=required(event,"batchPid");
        var accepted=reminders.batch(event.tenantId(),batch);
        if(accepted==null || !version.equals(accepted.get("version_pid"))) throw new SecurityException("Reminder expansion binding is invalid");
        if(!"accepted".equals(accepted.get("status"))) return;
        String after=Objects.toString(event.payload().get("after"),"");var users=reminders.users(event.tenantId(),batch,after);
        for(String user:users) outbox.enqueueIdempotent(new IntegrationEventEnvelope("1.0",batch+":"+user,AnnouncementDeliveryConsumer.DELIVER,"auraboot.announcement",version,event.occurredAt(),event.tenantId(),
            batch,event.eventId(),"announcement:"+version,1,Map.of("versionPid",version,"batchPid",batch,"userPid",user),Map.of()));
        if(users.size()==500) outbox.enqueueIdempotent(envelope(event.tenantId(),batch,version,users.getLast(),event.occurredAt()));
    }
    private IntegrationEventEnvelope envelope(Long tenant,String batch,String version,String after,Instant now) {
        return new IntegrationEventEnvelope("1.0",batch+":expand:"+after,EXPAND,"auraboot.announcement",version,now,tenant,batch,null,"announcement:"+version,1,
            Map.of("versionPid",version,"batchPid",batch,"after",after),Map.of());
    }
    private static String required(IntegrationEventEnvelope event,String key) {
        if(!(event.payload().get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Invalid reminder event reference");return value;
    }
    private static Map<String,Object> publicResult(Map<String,Object> result) {var view=new LinkedHashMap<>(result);view.remove("actorMemberPid");return view;}
    private AnnouncementStatistics.Filters filters(String json) {var value=codec.object(json);return new AnnouncementStatistics.Filters((String)value.get("query"),(String)value.get("state"),(String)value.get("departmentPid"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {if(!(value instanceof Map<?,?>)) throw new IllegalStateException("Invalid frozen reminder policy");return (Map<String,Object>)value;}
    private static int integer(Map<String,Object> map,String key) {if(!(map.get(key) instanceof Number value)) throw new IllegalStateException("Invalid frozen reminder number");return value.intValue();}
    private static Instant time(Object value) {return value==null ? null : AnnouncementDeliveryPlanResolver.instant(value);}
    private static LocalTime localTime(Object value) {return value==null ? null : LocalTime.parse(value.toString());}
    private static void invalid(String code) {throw BusinessException.i18n("announcement.error."+code);}
    private static void conflict(String code) {throw new ConflictException(code,"Announcement reminder state changed",Map.of());}
}
