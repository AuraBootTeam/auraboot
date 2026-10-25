package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Strict policy input; uploaded files remain subject to their owner and finalized state. */
@Service
@RequiredArgsConstructor
public class AnnouncementPolicyService {
    public static final int MAX_ATTACHMENTS=10;
    public static final Map<String,String> FORM_FIELDS=Map.ofEntries(
        Map.entry("audience_kind","kind"),Map.entry("audience_users","userPids"),Map.entry("audience_departments","departmentPids"),
        Map.entry("audience_roles","rolePids"),Map.entry("ack_required","acknowledgementRequired"),Map.entry("ack_days","acknowledgementDays"),
        Map.entry("attachments","attachmentPids"),Map.entry("approval_mode","approvalMode"),Map.entry("approval_definition","definitionPid"),
        Map.entry("scheduled_at","scheduledAt"),Map.entry("exemption_reason","exemptionReason"));
    private static final Set<String> KEYS=Set.of("audience","acknowledgementRequired","acknowledgementDays",
        "attachmentPids","approvalMode","definitionPid","scheduledAt","exemptionReason");
    private final ObjectMapper json;
    private final FileService files;
    private final AnnouncementLifecycleMapper records;

    public record Policy(AnnouncementAudienceService.Rule audience,Boolean acknowledgementRequired,
        int acknowledgementDays,List<String> attachmentPids,String approvalMode,String definitionPid,
        Instant scheduledAt,String exemptionReason) {
        public boolean acknowledgementRequired(String importance) {
            return acknowledgementRequired==null ? "important".equals(importance) : acknowledgementRequired;
        }
    }

    public Policy parse(Map<String,Object> input) {
        if(!KEYS.containsAll(input.keySet())) invalid();
        Object audienceInput=input.get("audience");
        AnnouncementAudienceService.Rule audience=new AnnouncementAudienceService.Rule("selected",List.of(),List.of(),List.of());
        if(audienceInput!=null) {
            if(!(audienceInput instanceof Map<?,?>)) invalid();
            Map<?,?> a=(Map<?,?>)audienceInput;
            if(!Set.of("kind","userPids","departmentPids","rolePids").containsAll(a.keySet())) invalid();
            audience=new AnnouncementAudienceService.Rule(string(a.get("kind"),"selected",20),
                strings(a.get("userPids")),strings(a.get("departmentPids")),strings(a.get("rolePids")));
        }
        Object required=input.get("acknowledgementRequired");
        if(required!=null && !(required instanceof Boolean)) invalid();
        Object days=input.getOrDefault("acknowledgementDays",7);
        if(!(days instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<1 || n.intValue()>30) invalid();
        String mode=string(input.get("approvalMode"),"exempt",20);
        if(!Set.of("exempt","approval").contains(mode)) invalid();
        String planned=string(input.get("scheduledAt"),null,50);
        Instant schedule=null;
        if(planned!=null && !planned.isBlank()) {
            // CATCH: expected input validation; never substitutes another schedule.
            try { schedule=Instant.parse(planned); } catch(DateTimeParseException error) { invalid(); }
        }
        List<String> attachments=strings(input.get("attachmentPids"));
        if(attachments.size()>MAX_ATTACHMENTS) invalid();
        return new Policy(audience,(Boolean)required,((Number)days).intValue(),attachments,mode,
            string(input.get("definitionPid"),null,100),schedule,string(input.get("exemptionReason"),null,1000));
    }

    public Policy load(String pid) {
        String value=records.draftSettings(MetaContext.getCurrentTenantId(),pid);
        if(value==null) throw BusinessException.i18n("announcement.error.not_found");
        try { return parseStored(json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})); }
        catch(JsonProcessingException error) { throw new IllegalStateException("Invalid stored announcement policy",error); }
    }

    public String fromForm(Map<String,Object> input,String recordPid) {
        boolean flat=input.keySet().stream().anyMatch(FORM_FIELDS::containsKey);
        if(input.containsKey("policy") && flat) invalid();
        if(input.containsKey("policy")) return validateDraft(input.get("policy"),recordPid);
        if(!flat) return null;
        Map<String,Object> values=recordPid==null ? new LinkedHashMap<>() : new LinkedHashMap<>(decode(encode(load(recordPid))));
        @SuppressWarnings("unchecked") Map<String,Object> oldAudience=(Map<String,Object>)values.getOrDefault("audience",Map.of());
        Map<String,Object> audience=new LinkedHashMap<>(oldAudience);
        values.remove("attachmentMetadata");
        input.forEach((field,value)->{
            String key=FORM_FIELDS.get(field);
            if(key==null) return;
            if(field.startsWith("audience_")) audience.put(key,value);
            else if("attachments".equals(field)) values.put(key,uploadedFilePids(value));
            else if("scheduled_at".equals(field) && value instanceof Instant instant) values.put(key,instant.toString());
            else values.put(key,value);
        });
        if("all".equals(input.get("audience_kind"))) {
            for(String field:List.of("audience_users","audience_departments","audience_roles")) {
                if(!input.containsKey(field)) audience.put(FORM_FIELDS.get(field),List.of());
            }
        }
        values.put("audience",audience);
        return validateDraft(values,recordPid);
    }

    private Map<String,Object> decode(String value) {
        try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){}); }
        catch(JsonProcessingException error) { throw new IllegalStateException("Invalid persisted announcement policy",error); }
    }

    public String validateDraft(Object input,String recordPid) {
        if(!(input instanceof Map<?,?>)) invalid();
        @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)input;
        Policy policy=parse(values);
        verifyAttachments(policy,recordPid,false);
        return encode(policy);
    }

    public void linkDraftAttachments(String recordPid,String settings) {
        Policy policy=parseStored(decode(settings));
        Set<String> linked=new HashSet<>();
        files.getFilesByEntityAndField("ab_announcement",recordPid,"attachments").forEach(file->linked.add(file.getPid()));
        List<String> append=policy.attachmentPids().stream().filter(pid->!linked.contains(pid)).toList();
        if(append.isEmpty()) return;
        var request=new com.auraboot.framework.file.dto.FileRelationRequestDTO();
        request.setEntityType("ab_announcement");request.setEntityId(recordPid);request.setFieldName("attachments");
        request.setFileIds(append.toArray(String[]::new));
        if(!files.appendFileRelation(request,MetaContext.getCurrentUserId()))
            throw new IllegalStateException("Announcement attachment relation was not persisted");
    }

    public void verifyAttachments(Policy policy,String recordPid,boolean retain) {
        for(String pid:policy.attachmentPids()) {
            if(!records.finalizedFileInTenant(MetaContext.getCurrentTenantId(),pid))
                throw BusinessException.i18n("announcement.error.attachment_unavailable");
            FileEntity file=files.findByPid(pid);
            if(file==null || !pid.equals(file.getPid())
                || Boolean.TRUE.equals(file.getDeletedFlag()) || !"success".equals(file.getStatus()))
                throw BusinessException.i18n("announcement.error.attachment_unavailable");
            boolean owned=Objects.equals(file.getCreatedBy(),MetaContext.getCurrentUserId());
            boolean linked=recordPid!=null && files.getFilesByEntityAndField("ab_announcement",recordPid,"attachments")
                .stream().anyMatch(f->pid.equals(f.getPid()));
            if(!owned && !linked) throw new AccessDeniedException("Announcement attachment is not owned or linked");
            if(retain && !files.lockRetention(pid)) throw new IllegalStateException("Announcement file retention was not persisted");
        }
    }

    public Policy parseStored(Map<String,Object> value) {
        Map<String,Object> policy=new LinkedHashMap<>(value);
        policy.remove("attachmentMetadata");
        return parse(policy);
    }

    private List<String> uploadedFilePids(Object input) {
        if(input instanceof String encoded) {
            // Native JSON-backed form values may retain their database representation until changed.
            try { input=json.readValue(encoded,Object.class); }
            catch(JsonProcessingException error) { invalid(); }
        }
        if(!(input instanceof List<?>)) invalid();
        List<String> pids=new ArrayList<>();
        for(Object item:(List<?>)input) {
            if(!(item instanceof Map<?,?> file) || !Set.of("fileId","name","url","size","type").containsAll(file.keySet())
                || !(file.get("fileId") instanceof String)) invalid();
            pids.add((String)((Map<?,?>)item).get("fileId"));
        }
        return strings(pids);
    }

    public String encode(Policy policy) {
        Map<String,Object> stored=json.convertValue(policy,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        stored.put("scheduledAt",policy.scheduledAt()==null ? null : policy.scheduledAt().toString());
        List<Map<String,Object>> metadata=new ArrayList<>();
        for(String pid:policy.attachmentPids()) {
            FileEntity file=files.findByPid(pid);
            if(file==null || !pid.equals(file.getPid())) throw BusinessException.i18n("announcement.error.attachment_unavailable");
            Map<String,Object> presentation=new LinkedHashMap<>();
            presentation.put("fileId",pid);presentation.put("name",file.getOriginalName());
            presentation.put("size",file.getFileSize());presentation.put("type",file.getMimeType());
            presentation.put("url","/api/file/download/"+pid);metadata.add(presentation);
        }
        stored.put("attachmentMetadata",metadata);
        try { return json.writeValueAsString(stored); }
        catch(JsonProcessingException error) { throw new IllegalArgumentException("Invalid announcement policy",error); }
    }
    private static List<String> strings(Object value) {
        if(value==null) return List.of();
        if(!(value instanceof List<?> list) || list.size()>1000) invalid();
        List<?> list=(List<?>)value;
        if(list.stream().anyMatch(x->!(x instanceof String s) || s.isBlank() || s.length()>36)) invalid();
        return list.stream().map(String.class::cast).distinct().sorted().toList();
    }
    private static String string(Object value,String defaultValue,int limit) {
        if(value==null) return defaultValue;
        if(!(value instanceof String text) || text.length()>limit) invalid();
        return (String)value;
    }
    private static void invalid(){throw BusinessException.i18n("announcement.error.invalid_input");}
}
