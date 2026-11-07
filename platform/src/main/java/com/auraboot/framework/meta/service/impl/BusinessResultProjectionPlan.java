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
        List<PolicyMask> policyMasks, List<DetailMask> detailMasks) {
    public BusinessResultProjectionPlan {
        fields=Map.copyOf(fields);sources=Map.copyOf(sources);readable=Set.copyOf(readable);
        policyMasks=List.copyOf(policyMasks);detailMasks=List.copyOf(detailMasks);
    }
    public record PolicyMask(String field,String type,String expression) { }
    public record DetailMask(String field,String type,String pattern,String replacement) { }

    /** Also used before internal completion effects, without applying caller filtering there. */
    public Map<String,Object> validateEnvelope(Object raw) {
        if (!(raw instanceof Map<?,?> map) || map.size()>129) throw denied();
        Map<String,Object> result=new LinkedHashMap<>();
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
            }
            result.put(key,value);
        }
        return BusinessResourceAuthorization.snapshot(result);
    }
    static AccessDeniedException denied() {return new AccessDeniedException("Business result projection is unavailable or outside its contract");}
}
