package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Reading and acknowledgement are separate explicit recipient actions; GET never writes either fact. */
@Service
@RequiredArgsConstructor
public class AnnouncementReaderService {
    private final AnnouncementReaderMapper readers;
    private final AnnouncementLifecycleMapper records;
    private final AnnouncementCodec codec;

    @Transactional(readOnly=true)
    public Map<String,Object> list(String query,String filter,int page,int size) {
        context();
        if(page<1 || page>100000 || size<1 || size>100 || query==null || query.length()>200 || !Set.of("all","unread","pending_ack").contains(filter)) invalid("invalid_input");
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();String user=MetaContext.getCurrentUserPid();
        return Map.of("records",readers.list(tenant,actor,user,query,filter,size,(page-1)*size).stream().map(row->view(row,false)).toList(),
            "total",readers.count(tenant,actor,user,query,filter),"page",page,"size",size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(String version) {return view(require(version),true);}
    private Map<String,Object> require(String version) {
        context();var row=readers.visible(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentUserPid(),version);
        if(row==null) invalid("recipient_unavailable");return row;
    }
    private Map<String,Object> view(Map<String,Object> row,boolean body) {
        var frozen=codec.object(row.get("snapshot").toString());
        Map<String,Object> policy=frozen.get("policy") instanceof Map<?,?> value ? (Map<String,Object>)value : Map.of();
        boolean required=Boolean.TRUE.equals(policy.get("acknowledgementRequired"));
        var result=new LinkedHashMap<String,Object>();result.put("pid",row.get("pid"));result.put("versionPid",row.get("pid"));
        result.put("title",frozen.get("title"));result.put("content",body ? frozen.get("content") : null);
        result.put("importance",frozen.get("importance"));result.put("versionNumber",row.get("version_no"));result.put("snapshotHash",row.get("snapshot_hash"));
        for(String field:List.of("published_at","expires_at","ack_due_at","first_read_at","acknowledged_at")) result.put(field,iso(row.get(field)));
        result.put("publisherName",row.get("publisher_name"));result.put("acknowledgementRequired",required);
        result.put("readState",row.get("first_read_at")==null ? "unread" : "read");
        result.put("acknowledgementState",!required ? "not_required" : row.get("acknowledged_at")==null ? "pending" : "completed");
        result.put("lateAcknowledgement",row.get("acknowledged_at")!=null && row.get("ack_due_at")!=null && instant(row.get("acknowledged_at")).isAfter(instant(row.get("ack_due_at"))));
        result.put("ackOverdue",required && row.get("acknowledged_at")==null && row.get("ack_due_at")!=null && Instant.now().isAfter(instant(row.get("ack_due_at"))));
        result.put("snapshotOrigin",row.get("origin"));result.put("pinned",Boolean.TRUE.equals(row.get("pinned")));
        if(body) {
            List<Map<String,Object>> attachments=new ArrayList<>();
            if(policy.get("attachmentMetadata") instanceof List<?> files) for(Object item:files) {
                if(!(item instanceof Map<?,?> file) || !(file.get("fileId") instanceof String pid)) throw new IllegalStateException("Invalid frozen attachment metadata");
                Map<String,Object> attachment=new LinkedHashMap<>();attachment.put("fileId",pid);attachment.put("name",file.get("name"));
                attachment.put("size",file.get("size"));attachment.put("type",file.get("type"));
                attachment.put("url","/api/announcements/versions/"+row.get("pid")+"/attachments/"+pid);attachments.add(attachment);
            }
            result.put("attachments",attachments);
        }
        return result;
    }
    @Transactional
    public Map<String,Object> mark(String version,Map<String,Object> input,boolean acknowledge) {
        if(input==null || !input.keySet().equals(Set.of("snapshotHash")) || !(input.get("snapshotHash") instanceof String)) invalid("protected_input");
        var row=require(version);Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();String user=MetaContext.getCurrentUserPid();
        // Same root lock as withdrawal; recheck eligibility after acquiring it.
        var record=records.lock(tenant,(String)row.get("announcement_pid"));row=require(version);
        if(!row.get("snapshot_hash").equals(input.get("snapshotHash"))) invalid("version_changed");
        if(acknowledge) {
            var detail=view(row,true);
            if(!Boolean.TRUE.equals(detail.get("acknowledgementRequired"))) invalid("ack_not_required");
            if(row.get("first_read_at")==null) invalid("read_required");
            if(readers.acknowledge(tenant,version,user,(String)input.get("snapshotHash"))!=1) throw new IllegalStateException("Acknowledgement fact was not persisted");
            readers.closeAcknowledgement(tenant,actor,version);
        } else if(readers.read(tenant,version,user)!=1) throw new IllegalStateException("Reading fact was not persisted");
        if(row.get(acknowledge ? "acknowledged_at" : "first_read_at")==null) records.audit(UniqueIdGenerator.generate(),tenant,actor,record.getPid(),version,
            acknowledge ? "announcement:acknowledge" : "announcement:mark_read",version+":"+user,record.getRowVersion(),record.getRowVersion());
        return view(require(version),true);
    }
    public boolean attachmentAllowed(String version,String file) {
        var view=detail(version);
        return ((List<Map<String,Object>>)view.get("attachments")).stream().anyMatch(item->file.equals(item.get("fileId")));
    }
    private static Instant instant(Object value){return AnnouncementDeliveryPlanResolver.instant(value);}
    private static String iso(Object value){return value==null ? null : instant(value).toString();}
    private static void context() {
        if(MetaContext.getCurrentTenantId()==null || MetaContext.getCurrentUserId()==null || MetaContext.getCurrentUserPid()==null)
            throw new org.springframework.security.access.AccessDeniedException("Authenticated recipient required");
    }
    private static void invalid(String key){throw BusinessException.i18n("announcement.error."+key);}
}
