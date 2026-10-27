package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.*;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Frozen business submission and fenced synchronization; only an authoritative provider result can authorize release. */
@Service
@RequiredArgsConstructor
public class AnnouncementApprovalService {
    public static final String SUBMIT="announcement:submit_approval",WITHDRAW="announcement:withdraw_approval",RECONCILE="announcement:reconcile_approval";
    private final AnnouncementAccessService access;
    private final AnnouncementApprovalMapper submissions;
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementReleaseMapper releases;
    private final AnnouncementPreparationService preparation;
    private final AnnouncementContentSealService contentSeals;
    private final AnnouncementPolicyService policies;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementTenantPolicyService tenantPolicies;
    private final AnnouncementApprovalCatalog catalog;
    private final WorkflowCapabilityRegistry workflows;
    private final AnnouncementCodec codec;
    private final ObjectMapper json;
    private final AnnouncementApprovalBindings bindings;
    private final com.auraboot.framework.i18n.service.I18nService messages;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean deliveryEnabled;

    public long authorizeTarget(String command,String pid) {
        var record=access.manage(pid,action(command),false);
        if(SUBMIT.equals(command)) access.actor("publish");
        return record.getRowVersion();
    }

    @Transactional
    public Map<String,Object> execute(String command,String pid,Map<String,Object> payload,String operation,Integer expected) {
        if(SUBMIT.equals(command)) contentSeals.lockActor();
        var record=access.manage(pid,action(command),true);
        if(SUBMIT.equals(command)) access.actor("publish");
        if(!Set.of(SUBMIT,WITHDRAW,RECONCILE).contains(command)) throw new IllegalArgumentException("Unsupported announcement approval command");
        if(operation==null || operation.isBlank() || operation.length()>100) invalid("operation_required");
        Map<String,Object> input=payload==null ? Map.of() : payload;
        if(!input.isEmpty() && !(WITHDRAW.equals(command) && input.keySet().equals(Set.of("reason")))) invalid("protected_input");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        Map<String,Object> intent=new TreeMap<>();intent.put("recordPid",pid);intent.put("revision",expected);intent.put("payload",input);
        String hash=codec.hash(intent);
        if(records.claim(tenant,actor,command,operation,hash)==0) {
            var prior=records.operation(tenant,actor,command,operation);
            if(prior==null || !hash.equals(prior.get("request_hash"))) throw new ConflictException("IDEMPOTENCY_CONFLICT","Announcement approval intent changed",Map.of());
            return codec.object(prior.get("result").toString());
        }
        if(expected==null) throw new CasVersionRequiredException("Announcement revision required",Map.of("recordPid",pid));
        if(!expected.equals(record.getRowVersion())) conflict();
        var result=SUBMIT.equals(command) ? submit(record,operation) : RECONCILE.equals(command) ? reconcile(record) : requestWithdrawal(record,input);
        result.put("operationId",operation);
        records.audit(UniqueIdGenerator.generate(),tenant,actor,pid,(String)result.get("versionPid"),command,operation,expected,expected+1);
        if(records.finish(tenant,actor,command,operation,pid,codec.encode(result))!=1) throw new IllegalStateException("Approval operation ledger lost");
        return result;
    }

    private static String action(String command) {
        return WITHDRAW.equals(command) ? "withdraw" : RECONCILE.equals(command) ? "manage" : "submit";
    }

    private Map<String,Object> reconcile(Announcement record) {
        if (!"pending_approval".equals(record.getStatus()) || record.getCurrentVersionPid()==null) invalid("not_editable");
        if (!catalog.available()) invalid("approval_not_ready");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        var control=releases.version(tenant,record.getCurrentVersionPid());
        var row=control==null ? null : submissions.lock(tenant,(String)control.get("current_submission_pid"));
        if (row==null || !Set.of("pending_dispatch","running","withdraw_requested").contains(row.get("state"))) invalid("not_editable");
        binding(row);
        if (!Objects.equals(row.get("provider_id"),workflows.providerId(ApprovalContract.STATUS))) invalid("approval_provider_changed");
        if (submissions.reconcile(tenant,(String)row.get("pid"))!=1
            || submissions.bump(tenant,record.getPid(),actor,record.getRowVersion())!=1) conflict();
        return new LinkedHashMap<>(Map.of("recordPid",record.getPid(),"pid",record.getPid(),"versionPid",record.getCurrentVersionPid(),
            "submissionId",row.get("pid"),"row_version",record.getRowVersion()+1,"status","pending_approval","operationState","queued"));
    }

    private Map<String,Object> submit(Announcement record,String operation) {
        if(!deliveryEnabled) invalid("delivery_disabled");
        if(!catalog.available()) invalid("approval_not_ready");
        if(!"draft".equals(record.getStatus()) || record.getActiveVersionPid()!=null) invalid("not_editable");
        var policy=policies.load(record.getPid());
        if(!"approval".equals(policy.approvalMode())) invalid("approval_mode_required");
        AnnouncementPolicyService.requireTenantDefault(policy);
        String definition=tenantPolicies.current().defaultDefinitionPid();
        if(definition==null || definition.isBlank()) invalid("approval_definition_required");
        var readiness=call(ApprovalContract.READINESS,Map.of("businessType","announcement","definitionPid",definition));
        if(!Boolean.TRUE.equals(readiness.get("ready"))) invalid("approval_not_ready");
        Object label=readiness.get("definitionName");
        if(!(label instanceof String name) || name.isBlank() || name.length()>300) throw new IllegalStateException("Approval definition label is missing");
        var definitionRef=json.convertValue(readiness.get("definition"),DefinitionRef.class);
        if(!definition.equals(definitionRef.pid())) throw new SecurityException("Approval readiness returned another definition");
        var prepared=preparation.prepare(record);
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        String version=UniqueIdGenerator.generate(),submission=UniqueIdGenerator.generate();
        var binding=new SubmittedApproval(submission,"announcement:"+submission,new BusinessRef("announcement",record.getPid(),version),
            "announcement:"+version,prepared.snapshotHash(),"announcement.v1",definitionRef,StrictPolicy.required(),
            MetaContext.getCurrentUserPid(),record.getTitle(),prepared.acceptedAt());
        if(releases.freeze(tenant,record.getPid(),version,record.getEditorRevision(),prepared.snapshotHash(),prepared.snapshot(),actor)!=1) conflict();
        contentSeals.captureFrozenVersion(tenant,record.getPid(),version,prepared.snapshotHash(),prepared.snapshot());
        preparation.persistEstimate(tenant,version,prepared);
        if(submissions.insert(tenant,submission,record.getPid(),version,actor,(String)prepared.member().get("member_pid"),binding.applicantUserPid(),
                workflows.providerId(ApprovalContract.START),(String)label,codec.encode(binding),codec.hash(binding))!=1
            || submissions.control(tenant,version,submission,policy.scheduledAt(),record.getExpiresAt())!=1
            || releases.accept(tenant,record.getPid(),version,actor,"pending_approval",record.getRowVersion())!=1) conflict();
        return new LinkedHashMap<>(Map.of("recordPid",record.getPid(),"pid",record.getPid(),"versionPid",version,"submissionId",submission,
            "row_version",record.getRowVersion()+1,"status","pending_approval","operationState","accepted"));
    }

    private Map<String,Object> requestWithdrawal(Announcement record,Map<String,Object> input) {
        if(!"pending_approval".equals(record.getStatus()) || record.getCurrentVersionPid()==null) invalid("not_editable");
        Object reason=input.get("reason");
        if(!(reason instanceof String text) || text.isBlank() || text.length()>1000) invalid("withdraw_reason_required");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        var version=releases.version(tenant,record.getCurrentVersionPid());
        String submission=(String)version.get("current_submission_pid");
        var row=submissions.lock(tenant,submission);var member=releases.actor(tenant,actor);
        if(row==null || member==null || submissions.withdraw(tenant,submission,actor,(String)member.get("member_pid"),(String)reason)!=1
            || submissions.bump(tenant,record.getPid(),actor,record.getRowVersion())!=1) conflict();
        return new LinkedHashMap<>(Map.of("recordPid",record.getPid(),"versionPid",record.getCurrentVersionPid(),"submissionId",submission,
            "row_version",record.getRowVersion()+1,"status","pending_approval","operationState","withdraw_requested"));
    }

    @Transactional
    public Map<String,Object> claim(){return MetaContext.runWithoutTenantFilter(submissions::claim);}

    @Transactional
    public void perform(Map<String,Object> claim) {
        Long tenant=((Number)claim.get("tenant_id")).longValue();String submission=(String)claim.get("pid");
        var previous=MetaContext.snapshot();
        try {
            MetaContext.setContext(tenant,0L,"announcement-approval-worker","announcement-approval-worker");
            // Root -> submission -> provider is the same lock order as user cancellation.
            var record=records.lock(tenant,(String)claim.get("announcement_pid"));
            var row=submissions.lock(tenant,submission);long fence=((Number)claim.get("fence")).longValue();
            if(row==null || !"polling".equals(row.get("sync_state")) || ((Number)row.get("fence")).longValue()!=fence) return;
            var binding=binding(row);
            if(!Objects.equals(row.get("provider_id"),workflows.providerId(ApprovalContract.STATUS))) invalid("approval_provider_changed");
            var status=call(ApprovalContract.STATUS,Map.of("submissionId",submission));
            if(Boolean.FALSE.equals(status.get("found"))) {
                if("withdraw_requested".equals(row.get("state"))) {
                    if(submissions.withdrawBeforeDispatch(tenant,submission,fence)!=1) conflict();
                    applyControl(record,row,"withdrawn","none","withdrawn","approval_withdrawn",null);return;
                }
                status=asActor(row,false,()->{
                    access.manage(record.getPid(),"submit",false);access.actor("publish");
                    if(!deliveryEnabled) invalid("delivery_disabled");
                    var frozen=codec.object(releases.version(tenant,binding.businessRef().versionPid()).get("snapshot").toString());
                    @SuppressWarnings("unchecked") var policy=policies.parseStored((Map<String,Object>)frozen.get("policy"));
                    var member=releases.actor(tenant,MetaContext.getCurrentUserId());
                    if(!row.get("actor_member_pid").equals(member.get("member_pid"))
                        || !frozen.get("authorizationHash").equals(preparation.authorization(audiences.resolve(policy.audience()),member))) invalid("authorization_changed");
                    return call(ApprovalContract.START,Map.of("submissionId",submission));
                });
            } else if(!Boolean.TRUE.equals(status.get("found"))) throw new IllegalStateException("Approval status omitted its found discriminator");
            if(!binding.submissionId().equals(status.get("submissionId")) || !(status.get("stateRevision") instanceof Number revision)
                || revision.longValue()<1 || revision.doubleValue()!=revision.longValue()) throw new IllegalStateException("Approval provider status identity/revision is invalid");
            if(revision.longValue()<number(row.get("provider_revision"))
                || (row.get("instance_pid")!=null && !Objects.equals(row.get("instance_pid"),status.get("instancePid"))))
                throw new SecurityException("Approval provider identity or revision regressed");
            if("withdraw_requested".equals(row.get("state")) && !ApprovalContract.TERMINAL_STATES.contains(status.get("state"))) {
                status=asActor(row,true,()->{
                    access.manage(record.getPid(),"withdraw",false);
                    return call(ApprovalContract.WITHDRAW,Map.of("submissionId",submission,"reason",row.get("withdraw_reason")));
                });
            }
            if(ApprovalContract.TERMINAL_STATES.contains(status.get("state"))) {
                var result=json.convertValue(status.get("terminalResult"),TerminalResult.class);
                verify(binding,row,result);
                if(!result.terminalState().equals(status.get("state"))) throw new SecurityException("Approval terminal state differs from its result");
                if(submissions.terminal(tenant,submission,fence,result.instancePid(),result.resultId(),codec.encode(result),result.stateRevision(),result.terminalState())!=1) conflict();
                row.put("terminal_result_pid",result.resultId());
                applyTerminal(record,row,result);return;
            }
            if(!Set.of("starting","running","blocked").contains(status.get("state"))) throw new IllegalStateException("Unknown approval provider execution state");
            if(submissions.waiting(tenant,submission,fence,(String)status.get("instancePid"),number(status.get("stateRevision")),
                (String)status.get("state"),(String)status.get("reasonCode"))!=1) conflict();
        } finally {MetaContext.restore(previous);}
    }

    private <T>T asActor(Map<String,Object> row,boolean withdrawal,java.util.function.Supplier<T> action) {
        Long tenant=MetaContext.getCurrentTenantId();String field=withdrawal ? "withdraw_actor_user_id" : "actor_user_id";
        Object actorValue=row.get(field);if(!(actorValue instanceof Number)) throw new SecurityException("Persisted approval actor is missing");
        Long actor=((Number)actorValue).longValue();var member=releases.actor(tenant,actor);
        if(member==null || !Objects.equals(row.get(withdrawal ? "withdraw_member_pid" : "actor_member_pid"),member.get("member_pid"))) invalid("authorization_changed");
        var previous=MetaContext.snapshot();
        try {
            Long memberId=((Number)member.get("member_id")).longValue();
            MetaContext.setContext(tenant,actor,(String)member.get("user_pid"),(String)member.get("username"));
            MetaContext.setContext(tenant,actor,(String)member.get("user_pid"),(String)member.get("username"),releases.actorRoles(tenant,memberId));
            MetaContext.setMemberId(memberId);return action.get();
        } finally {MetaContext.restore(previous);}
    }

    private void applyTerminal(Announcement record,Map<String,Object> row,TerminalResult result) {
        // An old submission keeps its terminal history but cannot resurrect another current version.
        if(record==null || !"pending_approval".equals(record.getStatus()) || !row.get("version_pid").equals(record.getCurrentVersionPid())) return;
        boolean canceled="withdraw_requested".equals(row.get("state"));
        if(!"approved".equals(result.terminalState()) || canceled) {
            String status=canceled || "withdrawn".equals(result.terminalState()) ? "approval_withdrawn"
                : "rejected".equals(result.terminalState()) ? "rejected" : "approval_failed";
            applyControl(record,row,result.terminalState(),"approved".equals(result.terminalState()) ? "approved" : "none",
                canceled || "withdrawn".equals(result.terminalState()) ? "withdrawn" : "unpublished",status,null);return;
        }
        Long tenant=MetaContext.getCurrentTenantId();String version=(String)row.get("version_pid");
        var frozen=codec.object(releases.version(tenant,version).get("snapshot").toString());
        @SuppressWarnings("unchecked") var policy=policies.parseStored((Map<String,Object>)frozen.get("policy"));
        Instant now=Instant.now(),planned=policy.scheduledAt()==null ? now : policy.scheduledAt();
        Instant latest=planned.plus(Duration.ofMinutes(((Number)frozen.get("lateWindowMinutes")).longValue()));
        if((policy.scheduledAt()!=null && (now.isAfter(planned) || result.decidedAt().isAfter(planned))) || now.isAfter(latest)) {
            applyControl(record,row,"approved","approved","missed","missed",null);return;
        }
        String release=UniqueIdGenerator.generate(),state=policy.scheduledAt()==null ? "unpublished" : "scheduled";
        if(releases.queue(tenant,release,record.getPid(),version,((Number)row.get("actor_user_id")).longValue(),(String)row.get("actor_member_pid"),
            (String)frozen.get("authorizationHash"),planned,latest,(String)frozen.get("timezone"))!=1) conflict();
        applyControl(record,row,"approved","approved",state,state,release);
    }

    private void applyControl(Announcement record,Map<String,Object> row,String approval,String basis,String publication,String status,String release) {
        if(record==null || !row.get("version_pid").equals(record.getCurrentVersionPid())) return;
        Long tenant=MetaContext.getCurrentTenantId();
        if(submissions.terminalControl(tenant,(String)row.get("version_pid"),(String)row.get("pid"),approval,basis,publication,release)!=1
            || submissions.terminalRecord(tenant,record.getPid(),record.getCurrentVersionPid(),status)!=1) conflict();
        String operation=Objects.toString(row.get("terminal_result_pid"),row.get("pid")+":cancel-before-dispatch");
        records.audit(UniqueIdGenerator.generate(),tenant,0L,record.getPid(),record.getCurrentVersionPid(),
            "announcement:approval_reconciled",operation,record.getRowVersion(),record.getRowVersion()+1);
    }

    public void verify(SubmittedApproval binding,Map<String,Object> row,TerminalResult result) {bindings.verify(binding,row,result);}
    public SubmittedApproval binding(Map<String,Object> row) {return bindings.read(row);}
    @Transactional(readOnly=true)
    public Map<String,Object> summary(String pid) {
        var record=access.manage(pid,"read",false);Long tenant=MetaContext.getCurrentTenantId();
        if(record.getCurrentVersionPid()==null) return Map.of("records",List.of(),"total",0);
        var version=releases.version(tenant,record.getCurrentVersionPid());
        if(version==null || version.get("current_submission_pid")==null) return Map.of("records",List.of(),"total",0);
        var row=submissions.read(tenant,(String)version.get("current_submission_pid"));
        if(row==null) throw new IllegalStateException("Announcement approval mapping is missing");
        binding(row);
        Map<String,Object> view=new LinkedHashMap<>();view.put("pid",pid);view.put("definitionName",row.get("definition_label"));
        view.put("approvalState",row.get("state"));view.put("providerState",row.get("provider_state"));view.put("syncState",row.get("sync_state"));
        view.put("submittedAt",row.get("created_at"));
        String reason=Objects.toString(row.get("last_error"),"");String key=null;
        if(reason.startsWith("$i18n:announcement.error.")) key=reason.substring(6);
        else if("APPROVAL_EMPTY_ASSIGNEES".equals(reason)) key="announcement.status.approval_empty_assignees";
        else if("APPROVAL_ASSIGNEE_INACTIVE".equals(reason)) key="announcement.status.approval_assignee_inactive";
        else if("blocked".equals(row.get("sync_state"))) key="announcement.status.approval_sync_blocked";
        else if("blocked".equals(row.get("provider_state"))) key="announcement.status.approval_provider_blocked";
        if(key!=null) {
            String text=messages.getMessage(org.springframework.context.i18n.LocaleContextHolder.getLocale().toLanguageTag(),key);
            if(text==null || key.equals(text)) throw new IllegalStateException("Required announcement translation is missing");
            view.put("attention",text);
        }
        return Map.of("records",List.of(view),"total",1);
    }

    private Map<String,Object> call(String operation,Map<String,Object> payload) {
        return workflows.execute(operation,new WorkflowCapability.WorkflowRequest(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),payload)).payload();
    }
    @Transactional
    public void fail(Map<String,Object> claim,String reason,boolean blocked) {
        MetaContext.runWithoutTenantFilter(()->{
            int failures=((Number)claim.get("failure_count")).intValue()+1;
            return submissions.fail(((Number)claim.get("tenant_id")).longValue(),(String)claim.get("pid"),((Number)claim.get("fence")).longValue(),
                blocked || failures>=10 ? "blocked" : "waiting",reason,Instant.now().plusSeconds(Math.min(300,1L<<Math.min(failures,8))));
        });
    }
    private static long number(Object value){return value instanceof Number n ? n.longValue() : 0L;}
    private static void invalid(String reason){throw BusinessException.i18n("announcement.error."+reason);}
    private static void conflict(){throw new ConflictException("Announcement approval state changed");}
}
