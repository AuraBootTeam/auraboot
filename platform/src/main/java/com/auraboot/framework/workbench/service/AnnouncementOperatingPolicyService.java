package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementOperatingPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Authorized tenant policy writer with immutable audit and durable operation replay. */
@Service
@RequiredArgsConstructor
public class AnnouncementOperatingPolicyService {
    public static final String UPDATE = "announcement:update_policy";
    private static final Set<String> FIELDS = Set.of("expectedPolicyRevision","forceApproval","defaultDefinitionPid",
            "lateWindowMinutes","reminderAt","automaticReminderLimit","reminderDedupeMinutes","quietStart","quietEnd");
    private final AnnouncementTenantPolicyService policies;
    private final AnnouncementOperatingPolicyMapper store;
    private final AnnouncementLifecycleMapper operations;
    private final AnnouncementAccessService access;
    private final IdentityDirectoryAccessor identities;
    private final AnnouncementApprovalCatalog catalog;
    private final AnnouncementCodec codec;

    public AnnouncementTenantPolicyService.Policy view() { member(); return policies.current(); }
    public java.util.List<AnnouncementAudienceService.Option> definitions() {
        member();
        var choices=new ArrayList<>(catalog.choices());
        String selected=policies.current().defaultDefinitionPid();
        if(selected!=null && choices.stream().noneMatch(option->selected.equals(option.value())))
            choices.add(new AnnouncementAudienceService.Option(selected,Map.of("zh-CN","已配置流程当前不可用，请重新选择",
                "en-US","The configured workflow is unavailable; choose a replacement")));
        return List.copyOf(choices);
    }

    public long authorizeTarget() { member(); return Math.max(1,policies.current().revision()); }

    @Transactional
    public Map<String,Object> execute(Map<String,Object> payload,String operation) {
        var membership=member();
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        if(operation==null || operation.isBlank() || operation.length()>100) invalid("operation_required");
        if(payload==null || !FIELDS.containsAll(payload.keySet())) invalid("protected_input");
        int expected=integer(payload.get("expectedPolicyRevision"),0,Integer.MAX_VALUE-1);
        String hash=codec.hash(payload);
        if(operations.claim(tenant,actor,UPDATE,operation,hash)==0) {
            var prior=operations.operation(tenant,actor,UPDATE,operation);
            if(prior==null || !hash.equals(prior.get("request_hash")))
                throw new ConflictException("IDEMPOTENCY_CONFLICT","Announcement policy intent changed",Map.of());
            if(prior.get("result")==null) throw new IllegalStateException("Policy operation has no committed result");
            return codec.object(prior.get("result").toString());
        }
        var row=store.lock(tenant);
        var before=row==null ? new AnnouncementTenantPolicyService.Policy(0,false,null,30) : AnnouncementTenantPolicyService.fromRow(row);
        if(before.revision()!=expected) conflict();
        Map<String,Object> values=codec.object(codec.encode(before));
        payload.forEach((key,value)->{if(!"expectedPolicyRevision".equals(key)) values.put(key,value);});
        if(!(values.get("forceApproval") instanceof Boolean)) invalid("invalid_input");
        boolean force=(Boolean)values.get("forceApproval");
        String definition=definition(values.get("defaultDefinitionPid"));
        if(definition!=null && !Objects.equals(definition,before.defaultDefinitionPid())
                && catalog.choices().stream().noneMatch(option->definition.equals(option.value()))) invalid("approval_not_ready");
        int late=integer(values.get("lateWindowMinutes"),1,120),limit=integer(values.get("automaticReminderLimit"),1,3),
            dedupe=integer(values.get("reminderDedupeMinutes"),60,1440);
        String at=time(values.get("reminderAt"),false),start=time(values.get("quietStart"),true),end=time(values.get("quietEnd"),true);
        if((start==null)!=(end==null) || (start!=null && start.equals(end))) invalid("invalid_input");
        var after=new AnnouncementTenantPolicyService.Policy(expected+1,force,definition,late,
                before.securityRevision()+(force!=before.forceApproval()?1:0),at,limit,dedupe,start,end);
        var persisted=codec.object(codec.encode(after));
        int changed=expected==0 ? store.insert(tenant,actor,persisted) : store.update(tenant,actor,expected,persisted);
        if(changed!=1) conflict();
        if(store.audit(UniqueIdGenerator.generate(),tenant,actor,membership.memberPid(),operation,expected,
                codec.encode(before),codec.encode(after))!=1) throw new IllegalStateException("Policy audit was not persisted");
        Map<String,Object> result=new LinkedHashMap<>();result.put("policy",persisted);result.put("operationId",operation);
        if(operations.finish(tenant,actor,UPDATE,operation,null,codec.encode(result))!=1)
            throw new IllegalStateException("Policy operation ledger was not completed");
        return result;
    }
    private IdentityDirectoryAccessor.TenantUserIdentity member() {
        access.actor("policy_manage");
        var member=identities.findEnabledTenantMembership(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserPid());
        if(member==null || member.user()==null || member.memberPid()==null
                || !Objects.equals(member.user().id(),MetaContext.getCurrentUserId())
                || member.memberId()==null || !member.memberId().equals(MetaContext.getCurrentMemberId()))
            throw new AccessDeniedException("Current enabled policy administrator membership is required");
        return member;
    }
    private static String definition(Object value) {
        if(value==null || "".equals(value)) return null;
        if(!(value instanceof String text) || text.isBlank() || text.length()>36) {invalid("invalid_input");return null;}
        return (String)value;
    }
    private static String time(Object value,boolean nullable) {
        if(nullable && (value==null || "".equals(value))) return null;
        if(!(value instanceof String text) || !text.matches("[0-2][0-9]:[0-5][0-9]")) {invalid("invalid_input");return null;}
        try {return LocalTime.parse((String)value).toString();}
        catch(DateTimeException invalidTime) {invalid("invalid_input");return null;}
    }
    private static int integer(Object value,int min,int max) {
        if(!(value instanceof Number n) || n.longValue()<min || n.longValue()>max || n.doubleValue()!=n.longValue()) {
            invalid("invalid_input");return 0;
        }
        return ((Number)value).intValue();
    }
    private static void conflict(){throw new ConflictException("ANNOUNCEMENT_POLICY_CONFLICT","Announcement policy revision changed",Map.of());}
    private static void invalid(String reason){throw BusinessException.i18n("announcement.error."+reason);}
}
