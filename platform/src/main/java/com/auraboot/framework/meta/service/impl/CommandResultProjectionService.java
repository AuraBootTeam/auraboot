package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.BusinessResultField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** Capture current read policy with the business grant; project both fresh and cached public outcomes. */
@Service
@RequiredArgsConstructor
public class CommandResultProjectionService {
    private final MetaModelService metadata;
    private final FieldPermissionService fields;
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;
    private final PermissionSnapshotCache snapshots;

    /** The native writer returns identity and affected-row count, never unclassified model data. */
    public BusinessResultProjectionPlan prepareNative(CommandPipelineContext ctx,
            com.auraboot.framework.meta.security.NativeBusinessCommandContract contract) {
        requireActor(ctx);
        if (!Objects.equals(contract.model(), ctx.getBusinessTargetAuthorization().modelCode())
                || !Objects.equals(contract.action(), ctx.getBusinessTargetAuthorization().action()))
            throw BusinessResultProjectionPlan.denied();
        String count = contract.model() + ("update".equals(contract.action()) ? "_updated" : "_deleted");
        return new BusinessResultProjectionPlan(ctx.getBusinessTargetAuthorization(), ctx.getBusinessGrantDecision(),
                Map.of("recordPid", BusinessResultField.rootIdentity(), count, BusinessResultField.count(1)),
                Map.of(), Set.of(), List.of(), List.of());
    }

    public BusinessResultProjectionPlan prepare(CommandPipelineContext ctx, BusinessResourceAuthorization owner) {
        return snapshots.withFreshReads(()->prepareSnapshot(ctx,owner));
    }

    private BusinessResultProjectionPlan prepareSnapshot(CommandPipelineContext ctx,BusinessResourceAuthorization owner) {
        requireActor(ctx);
        var definition=Map.copyOf(owner.businessResultProjection());
        var expected=Set.copyOf(owner.businessResultFields());
        if(expected.isEmpty() || expected.size()>128 || !definition.keySet().equals(expected))throw BusinessResultProjectionPlan.denied();
        expected.forEach(BusinessResourceAuthorization::identifier);
        if(definition.containsKey("recordPid") && definition.get("recordPid").kind()!=BusinessResultField.Kind.ROOT_ID)
            throw BusinessResultProjectionPlan.denied();
        Map<String,String> sources=new LinkedHashMap<>();Set<String> readable=new LinkedHashSet<>();
        var refs=definition.values().stream().filter(field->field.kind()==BusinessResultField.Kind.ROOT_FIELD).toList();
        List<BusinessResultProjectionPlan.PolicyMask> policyMasks=new ArrayList<>();
        List<BusinessResultProjectionPlan.DetailMask> detailMasks=new ArrayList<>();
        if(!refs.isEmpty()) {
            String model=ctx.getBusinessTargetAuthorization().modelCode();
            var modelFields=metadata.getModelFields(model);
            if(modelFields==null)throw BusinessResultProjectionPlan.denied();
            Map<String,Set<String>> aliases=new HashMap<>();
            for(var field:modelFields) {
                aliases.computeIfAbsent(field.getCode(),key->new LinkedHashSet<>()).add(field.getCode());
                if(field.getColumnName()!=null)aliases.computeIfAbsent(field.getColumnName(),key->new LinkedHashSet<>()).add(field.getCode());
            }
            var permission=fields.getFieldPermissions(MetaContext.getCurrentMemberId(),model);
            if(permission==null || permission.viewableFields()==null || permission.hiddenFields()==null)
                throw BusinessResultProjectionPlan.denied();
            for(var entry:definition.entrySet()) {
                var ref=entry.getValue();if(ref.kind()!=BusinessResultField.Kind.ROOT_FIELD)continue;
                var field=modelFields.stream().filter(f->ref.sourceField().equals(f.getCode())).findFirst()
                        .orElseThrow(BusinessResultProjectionPlan::denied);
                if(Set.of("json","jsonb","object","array","list").contains(Objects.toString(field.getDataType(),"").toLowerCase(Locale.ROOT)))
                    throw BusinessResultProjectionPlan.denied();
                sources.put(entry.getKey(),field.getCode());
                if(ctx.getBusinessGrantDecision().canRead(field.getCode()) && permission.viewableFields().contains(field.getCode())
                        && !permission.hiddenFields().contains(field.getCode()))readable.add(field.getCode());
            }
            var rules=policies.getFieldMaskRules(MetaContext.getCurrentTenantId(),model,MetaContext.getCurrentUserId());
            var configs=masks.getEffectiveConfigs(model,MetaContext.getCurrentUserId(),"detail");
            if(rules==null || configs==null)throw BusinessResultProjectionPlan.denied();
            for(var rule:rules) {
                for(String canonical:aliases.getOrDefault(rule.getFieldCode(),Set.of())) {
                    if(!readable.contains(canonical))continue;
                    if(!Set.of("hide","partial","hash","custom").contains(Objects.toString(rule.getMaskType(),"").toLowerCase(Locale.ROOT)))throw BusinessResultProjectionPlan.denied();
                    policyMasks.add(new BusinessResultProjectionPlan.PolicyMask(canonical,rule.getMaskType(),rule.getMaskExpression()));
                }
            }
            for(var config:configs) {
                for(String canonical:aliases.getOrDefault(config.getFieldCode(),Set.of())) {
                    if(!readable.contains(canonical))continue;
                    if(!Set.of("PHONE","EMAIL","ID_CARD","BANK_CARD","NAME","PARTIAL","FULL","CUSTOM").contains(Objects.toString(config.getMaskType(),"").toUpperCase(Locale.ROOT)))throw BusinessResultProjectionPlan.denied();
                    detailMasks.add(new BusinessResultProjectionPlan.DetailMask(canonical,config.getMaskType(),config.getMaskPattern(),config.getReplacementChar()));
                }
            }
        }
        return new BusinessResultProjectionPlan(ctx.getBusinessTargetAuthorization(),ctx.getBusinessGrantDecision(),definition,sources,readable,policyMasks,detailMasks);
    }

    public Map<String,Object> project(CommandPipelineContext ctx,Map<String,Object> internal) {
        requireActor(ctx);
        var plan=ctx.getBusinessResultProjection();
        if(plan==null || plan.grant()!=ctx.getBusinessGrantDecision() || !Boolean.TRUE.equals(ctx.getTargetRecordReadable())
                || !Objects.equals(plan.target(),ctx.getBusinessTargetAuthorization()))throw BusinessResultProjectionPlan.denied();
        var values=plan.validateEnvelope(internal);
        Map<String,Object> data=new LinkedHashMap<>();
        for(var source:plan.sources().entrySet()) {
            if(!values.containsKey(source.getKey()) || !plan.readable().contains(source.getValue()))continue;
            Object value=values.get(source.getKey());
            if(data.containsKey(source.getValue()) && !Objects.equals(data.get(source.getValue()),value))throw BusinessResultProjectionPlan.denied();
            data.put(source.getValue(),value);
        }
        if(!data.isEmpty() && !plan.policyMasks().isEmpty()) {
            var rules=plan.policyMasks().stream().map(rule->FieldMaskRule.builder().fieldCode(rule.field())
                    .maskType(rule.type()).maskExpression(rule.expression()).build()).toList();
            var masked=policies.applyFieldMasking(List.of(data),rules);
            if(masked==null || masked.size()!=1 || masked.getFirst()==null)throw BusinessResultProjectionPlan.denied();
            data=new LinkedHashMap<>(masked.getFirst());
        }
        for(var config:plan.detailMasks()) {
            if(data.containsKey(config.field()) && data.get(config.field())!=null)
                data.put(config.field(),masks.maskValue(data.get(config.field()).toString(),config.type(),config.pattern(),config.replacement()));
        }
        Map<String,Object> result=new LinkedHashMap<>();
        for(var entry:values.entrySet()) {
            String source=plan.sources().get(entry.getKey());
            if(source==null)result.put(entry.getKey(),entry.getValue());
            else if(plan.readable().contains(source) && data.containsKey(source))result.put(entry.getKey(),data.get(source));
        }
        return BusinessResourceAuthorization.snapshot(result);
    }

    private static void requireActor(CommandPipelineContext ctx) {
        var target=ctx.getBusinessTargetAuthorization();var grant=ctx.getBusinessGrantDecision();
        if(target==null || grant==null || !grant.result().granted() || !MetaContext.exists()
                || ctx.getAuthorizationVerdict()==null || !ctx.getAuthorizationVerdict().isAuthorized()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || !Objects.equals(target.tenantId(),MetaContext.getCurrentTenantId())
                || !Objects.equals(target.userId(),MetaContext.getCurrentUserId())
                || !Objects.equals(target.memberId(),MetaContext.getCurrentMemberId())
                || !Objects.equals(target.commandCode(),ctx.getCommandCode())
                || ctx.getCommand()==null || !Objects.equals(target.modelCode(),ctx.getCommand().getModelCode())
                || ctx.getRequest()==null || !Objects.equals(target.targetRecordId(),ctx.getRequest().getTargetRecordId())
                || !Objects.equals(grant.tenantId(),target.tenantId()) || !Objects.equals(grant.userId(),target.userId())
                || !Objects.equals(grant.memberId(),target.memberId()) || !Objects.equals(grant.modelCode(),target.modelCode())
                || !Objects.equals(grant.recordPid(),target.targetRecordId()))throw BusinessResultProjectionPlan.denied();
    }
}
