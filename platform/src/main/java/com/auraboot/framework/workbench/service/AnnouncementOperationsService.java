package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Current authorization and minimal facts for uncertain requests and exact-version channel history. */
@Service
@RequiredArgsConstructor
public class AnnouncementOperationsService {
    private static final Map<String,String> ACTIONS=Map.ofEntries(
        Map.entry("create_announcement","edit"),Map.entry("update_announcement","edit"),Map.entry("delete_announcement","delete"),
        Map.entry("republish","edit"),Map.entry("archive","withdraw"),Map.entry("set_pinned","edit"),
        Map.entry("publish","publish"),Map.entry("cancel_schedule","publish"),Map.entry("retry_publication","publish"),
        Map.entry("submit_approval","submit"),Map.entry("withdraw_approval","withdraw"),Map.entry("reconcile_approval","manage"),
        Map.entry("remind","remind"),Map.entry("resume_reminders","remind"),Map.entry("export_statistics","export"),
        Map.entry("update_policy","policy_manage"));
    private static final Set<String> REFERENCES=Set.of("recordPid","versionPid","submissionId","releaseAttemptPid","batchPid","exportTaskPid");
    private static final Set<String> DELIVERY_REASONS=Set.of("VERSION_INACTIVE","RECIPIENT_OR_VERSION_INACTIVE",
        "BATCH_BLOCKED","ACTOR_OR_VERSION_UNAVAILABLE","LEGACY_UNKNOWN","MEMBERSHIP_INACTIVE","ALREADY_COMPLETE","THROTTLED","AUTOMATIC_DEADLINE");
    private final AnnouncementOperationsMapper operations;
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementStatisticsMapper statistics;
    private final AnnouncementAccessService access;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementCodec codec;
    @Value("${auraboot.announcement.statistics-max-recipients:50000}") private int maximumRecipients;

    @Transactional(readOnly=true)
    public Map<String,Object> operation(String command,String operation) {
        if(command==null || !command.startsWith("announcement:") || !ACTIONS.containsKey(command.substring(13))
            || operation==null || operation.isBlank() || operation.length()>100) invalid();
        String action=ACTIONS.get(command.substring(13));Long actor=access.actor(action);
        var row=operations.operation(MetaContext.getCurrentTenantId(),actor,MetaContext.getCurrentMemberId(),command,operation);
        if(row==null) return Map.of("found",false,"operationState","unknown");
        if(row.get("result")==null) throw new IllegalStateException("Committed announcement operation has no result");
        String record=(String)row.get("announcement_pid");
        if(record!=null) {
            var root=records.readTarget(MetaContext.getCurrentTenantId(),record);
            if(root==null || !Objects.equals(root.getOwnerUserId(),actor)) access.manage(record,action,false);
        }
        if("submit".equals(action)) access.actor("publish");
        if("export".equals(action)) access.actor("stats");
        var stored=codec.object(row.get("result").toString());
        var result=new LinkedHashMap<String,Object>();result.put("found",true);result.put("commandCode",command);
        result.put("operationId",operation);result.put("acceptedAt",row.get("created_at"));
        String state=Objects.toString(stored.get("operationState"),"completed");
        result.put("operationState",Set.of("accepted","queued","withdraw_requested").contains(state) ? "accepted" : "completed");
        REFERENCES.forEach(key->{if(stored.get(key) instanceof String value) result.put(key,value);});
        return result;
    }

    private record Version(Map<String,Object> row,String users) {}
    private Version version(String pid) {
        access.actor("stats");var version=statistics.version(MetaContext.getCurrentTenantId(),pid);
        if(version==null) throw BusinessException.i18n("announcement.error.statistics_unavailable");
        access.manage((String)version.get("announcement_pid"),"stats",false);
        long expected=((Number)version.get("recipient_count")).longValue();
        if(maximumRecipients<1 || expected>maximumRecipients) throw BusinessException.i18n("announcement.error.statistics_capacity_exceeded");
        var scope=audiences.managementScope("stats");var rows=statistics.facts(MetaContext.getCurrentTenantId(),pid);
        if(rows.size()!=expected) throw new IllegalStateException("Sealed delivery audience count changed");
        var visible=rows.stream().filter(row->scope.contains((String)row.get("user_pid"),codec.object(row.get("organization_snapshot").toString())))
            .map(row->(String)row.get("user_pid")).toList();
        return new Version(version,codec.encode(visible));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> deliverySummary(String pid) {
        var version=version(pid).row();var snapshot=codec.object(version.get("snapshot").toString());
        return Map.of("pid",pid,"versionPid",pid,"recordPid",version.get("announcement_pid"),"title",snapshot.get("title"),
            "versionNumber",version.get("version_no"),"asOf",statistics.databaseNow());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> deliveries(String pid,int page,int size) {
        if(page<1 || !Set.of(20,50,100).contains(size)) invalid();
        var version=version(pid);Long tenant=MetaContext.getCurrentTenantId();
        var rows=operations.deliveries(tenant,pid,version.users(),size,(page-1L)*size).stream().map(row->{
            var view=new LinkedHashMap<String,Object>();
            view.put("pid",row.get("batch_pid")+":"+row.get("user_pid")+":"+row.get("channel"));
            view.put("recipientName",row.get("display_name_snapshot"));
            for(String key:List.of("channel","kind","status")) view.put(key,row.get(key));
            view.put("createdAt",row.get("created_at"));view.put("completedAt",row.get("delivered_at"));
            Object reason=row.get("error_code");view.put("reasonCode",reason==null ? null : DELIVERY_REASONS.contains(reason) ? reason : "DELIVERY_UNAVAILABLE");
            return view;
        }).toList();
        return Map.of("recordPid",version.row().get("announcement_pid"),"versionPid",pid,"records",rows,
            "pageNum",page,"pageSize",size,"total",operations.deliveryCount(tenant,pid,version.users()),"asOf",statistics.databaseNow());
    }
    private static void invalid(){throw BusinessException.i18n("announcement.error.invalid_input");}
}
