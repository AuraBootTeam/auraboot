package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Missing tenant configuration has explicit product defaults, without startup writes. */
@Service
@RequiredArgsConstructor
public class AnnouncementTenantPolicyService {
    private final AnnouncementApprovalMapper store;
    public record Policy(int revision,boolean forceApproval,String defaultDefinitionPid,int lateWindowMinutes,
            int securityRevision,String reminderAt,int automaticReminderLimit,int reminderDedupeMinutes,
            String quietStart,String quietEnd) {
        public Policy(int revision,boolean forceApproval,String defaultDefinitionPid,int lateWindowMinutes) {
            this(revision,forceApproval,defaultDefinitionPid,lateWindowMinutes,revision,"09:00",3,60,null,null);
        }
    }
    public Policy current() {
        var row=store.policy(MetaContext.getCurrentTenantId());
        if(row==null) return new Policy(0,false,null,30);
        return fromRow(row);
    }
    public static Policy fromRow(java.util.Map<String,Object> row) {
        return new Policy(((Number)row.get("revision")).intValue(),Boolean.TRUE.equals(row.get("force_approval")),
            (String)row.get("default_definition_pid"),((Number)row.get("late_window_minutes")).intValue(),
            ((Number)row.get("security_revision")).intValue(),time(row.get("reminder_at")),
            ((Number)row.get("automatic_reminder_limit")).intValue(),((Number)row.get("reminder_dedupe_minutes")).intValue(),
            time(row.get("quiet_start")),
            time(row.get("quiet_end")));
    }
    private static String time(Object value) { return value==null ? null : java.time.LocalTime.parse(value.toString()).toString(); }
}
