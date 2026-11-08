package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.BusinessTargetAuthorization;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.BusinessResultField;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/** Immutable projection decision; internal outcomes and caller views must not share mutable values. */
public record BusinessResultProjectionPlan(BusinessTargetAuthorization target,
        com.auraboot.framework.permission.engine.model.BusinessGrantDecision grant,
        Map<String, BusinessResultField> fields, Map<String,String> sources, Set<String> readable,
        List<PolicyMask> policyMasks, List<DetailMask> detailMasks, Map<String,String> rootDocuments) {
    public BusinessResultProjectionPlan(BusinessTargetAuthorization target,
            com.auraboot.framework.permission.engine.model.BusinessGrantDecision grant,
            Map<String,BusinessResultField> fields, Map<String,String> sources, Set<String> readable,
            List<PolicyMask> policyMasks, List<DetailMask> detailMasks) {
        this(target,grant,fields,sources,readable,policyMasks,detailMasks,Map.of());
    }
    public BusinessResultProjectionPlan {
        fields=Map.copyOf(fields);sources=Map.copyOf(sources);readable=Set.copyOf(readable);
        policyMasks=List.copyOf(policyMasks);detailMasks=List.copyOf(detailMasks);
        rootDocuments=Map.copyOf(rootDocuments);
    }
    public record PolicyMask(String field,String type,String expression) { }
    public record DetailMask(String field,String type,String pattern,String replacement) { }

    public boolean requiresRootDocuments() {
        return fields.values().stream().anyMatch(field -> field.kind()==BusinessResultField.Kind.ROOT_JSON_PATH);
    }

    /** Post-write source refresh may replace documents, never the captured authorization policy. */
    public boolean samePolicy(BusinessResultProjectionPlan other) {
        return other!=null && grant==other.grant && Objects.equals(target,other.target)
                && fields.equals(other.fields) && sources.equals(other.sources) && readable.equals(other.readable)
                && policyMasks.equals(other.policyMasks) && detailMasks.equals(other.detailMasks);
    }

    /** Only the host's target read and active resource scope supply these current stored facts. */
    public BusinessResultProjectionPlan bindRootDocuments(Map<String,Object> root) {
        if (!requiresRootDocuments()) return this;
        if (target==null || root==null || !target.targetRecordId().equals(root.get("pid"))
                || !target.tenantId().toString().equals(String.valueOf(root.get("tenant_id")))
                || Boolean.TRUE.equals(root.get("deleted_flag"))
                || !(root.get("row_version") instanceof Number version) || version.longValue()<0) throw denied();
        Map<String,String> documents=new LinkedHashMap<>();
        for (var field:fields.values()) if(field.kind()==BusinessResultField.Kind.ROOT_JSON_PATH) {
            String source=field.sourceField();
            if(!root.containsKey(source))throw denied();
            // An explicit SQL NULL is a captured empty initial state, never evidence for a result.
            // Exact path verification still requires a real host-loaded post-write document.
            if(root.get(source)!=null)documents.computeIfAbsent(source,key -> BusinessResultJsonSource.document(root.get(key)));
        }
        if(documents.values().stream().mapToInt(String::length).sum()>4_194_304) throw denied();
        return new BusinessResultProjectionPlan(target,grant,fields,sources,readable,policyMasks,detailMasks,documents);
    }

    boolean unmaskedReadable(String source) {
        return readable.contains(source) && policyMasks.stream().noneMatch(mask -> source.equals(mask.field()))
                && detailMasks.stream().noneMatch(mask -> source.equals(mask.field()));
    }

    public void verifyRootDocuments(Map<String,Object> values) {
        for(var entry:values.entrySet()) {
            var field=fields.get(entry.getKey());
            if(field!=null && field.kind()==BusinessResultField.Kind.ROOT_JSON_PATH && unmaskedReadable(field.sourceField()))
                BusinessResultJsonSource.requireExact(rootDocuments.get(field.sourceField()),field,entry.getValue());
        }
    }

    /** Also used before internal completion effects, without applying caller filtering there. */
    public Map<String,Object> validateEnvelope(Object raw) {
        if (!(raw instanceof Map<?,?> map) || map.size()>129) throw denied();
        Map<String,Object> result=new LinkedHashMap<>();
        var jsonBudget=new BusinessResultJsonSource.Budget();
        for (var entry:map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw denied();
            var field=fields.get(key);
            if (field==null && "recordPid".equals(key)) field=BusinessResultField.rootIdentity();
            if (field==null) throw denied();
            Object value=entry.getValue();
            switch(field.kind()) {
                case ROOT_ID -> {if(!Objects.equals(target.targetRecordId(),value))throw denied();}
                case CONSTANT -> {
                    if(field.constant() instanceof Number expected && value instanceof Number actual) {
                        try {
                            if(new java.math.BigDecimal(expected.toString()).compareTo(new java.math.BigDecimal(actual.toString()))!=0)throw denied();
                        } catch(NumberFormatException invalid) {throw denied();}
                    } else if(!Objects.equals(field.constant(),value))throw denied();
                }
                case BOOLEAN -> {if(!(value instanceof Boolean))throw denied();}
                case COUNT -> {
                    if(!(value instanceof Number number))throw denied();
                    try {
                        int count=new java.math.BigDecimal(number.toString()).intValueExact();
                        if(count<0 || count>field.maximum())throw denied();
                    } catch(ArithmeticException | NumberFormatException invalid) {throw denied();}
                }
                case ROOT_FIELD, DERIVED_ROOT_FIELD -> {
                    if (!(value==null || value instanceof String || value instanceof Boolean || value instanceof Number
                            || value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date))throw denied();
                }
                case ROOT_JSON_PATH -> value=jsonBudget.copy(value,0);
            }
            result.put(key,value);
        }
        return BusinessResourceAuthorization.snapshot(result);
    }
    static AccessDeniedException denied() {return new AccessDeniedException("Business result projection is unavailable or outside its contract");}
}
