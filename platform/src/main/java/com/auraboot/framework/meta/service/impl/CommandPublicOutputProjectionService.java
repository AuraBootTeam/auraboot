package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host-only public projection of owner-approved resource facts; never a generic model read grant. */
@Service
@RequiredArgsConstructor
public class CommandPublicOutputProjectionService {
    private final MetaModelService metadata;
    private final FieldPermissionService fields;
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;
    private final PermissionSnapshotCache snapshots;

    public CommandPublicOutputProjectionPlan prepare(CommandPipelineContext context, BusinessResourceAuthorization owner) {
        return snapshots.withFreshReads(() -> capture(context, owner));
    }

    private CommandPublicOutputProjectionPlan capture(CommandPipelineContext context, BusinessResourceAuthorization owner) {
        CommandResultProjectionService.requireActor(context);
        var declarations = Map.copyOf(owner.publicBusinessResourceFields());
        if (declarations.size() > 16 || !owner.businessResourceModels().containsAll(declarations.keySet()))
            throw BusinessResultProjectionPlan.denied();
        Map<String, CommandPublicOutputProjectionPlan.ModelFields> result = new LinkedHashMap<>();
        for (var entry : declarations.entrySet()) {
            String model = entry.getKey();
            BusinessResourceAuthorization.identifier(model);
            Set<String> declared = Set.copyOf(entry.getValue());
            if (declared.isEmpty() || declared.size() > 128) throw BusinessResultProjectionPlan.denied();
            declared.forEach(BusinessResourceAuthorization::identifier);
            var definitions = metadata.getModelFields(model);
            if (definitions == null) throw BusinessResultProjectionPlan.denied();
            Map<String, Set<String>> aliases = new HashMap<>();
            Map<String, String> types = new HashMap<>();
            for (var definition : definitions) {
                if (definition == null || definition.getCode() == null || types.containsKey(definition.getCode()))
                    throw BusinessResultProjectionPlan.denied();
                types.put(definition.getCode(), Objects.toString(definition.getDataType(), "").toLowerCase(Locale.ROOT));
                aliases.computeIfAbsent(definition.getCode(), key -> new LinkedHashSet<>()).add(definition.getCode());
                if (definition.getColumnName() != null)
                    aliases.computeIfAbsent(definition.getColumnName(), key -> new LinkedHashSet<>()).add(definition.getCode());
            }
            var permission = fields.getFieldPermissions(MetaContext.getCurrentMemberId(), model);
            if (permission == null || permission.viewableFields() == null || permission.hiddenFields() == null)
                throw BusinessResultProjectionPlan.denied();
            Set<String> readable = new LinkedHashSet<>();
            for (String field : declared) {
                String type = types.get(field);
                if (type == null || type.isBlank() || Set.of("json", "jsonb", "object", "array", "list").contains(type))
                    throw BusinessResultProjectionPlan.denied();
                if (permission.viewableFields().contains(field) && !permission.hiddenFields().contains(field)
                        && (!model.equals(context.getBusinessTargetAuthorization().modelCode())
                        || context.getBusinessGrantDecision().canRead(field))) readable.add(field);
            }
            var rules = policies.getFieldMaskRules(MetaContext.getCurrentTenantId(), model, MetaContext.getCurrentUserId());
            var detailConfigs = masks.getEffectiveConfigs(model, MetaContext.getCurrentUserId(), "detail");
            var exportConfigs = masks.getEffectiveConfigs(model, MetaContext.getCurrentUserId(), "export");
            if (rules == null || detailConfigs == null || exportConfigs == null) throw BusinessResultProjectionPlan.denied();
            List<BusinessResultProjectionPlan.PolicyMask> policyMasks = new ArrayList<>();
            for (var rule : rules) {
                if (rule == null) throw BusinessResultProjectionPlan.denied();
                for (String canonical : aliases.getOrDefault(rule.getFieldCode(), Set.of()).stream().sorted().toList()) {
                    if (!readable.contains(canonical)) continue;
                    if (!Set.of("hide", "partial", "hash", "custom").contains(Objects.toString(rule.getMaskType(), "").toLowerCase(Locale.ROOT)))
                        throw BusinessResultProjectionPlan.denied();
                    policyMasks.add(new BusinessResultProjectionPlan.PolicyMask(canonical, rule.getMaskType(), rule.getMaskExpression()));
                }
            }
            List<BusinessResultProjectionPlan.DetailMask> detailMasks = new ArrayList<>();
            for (var config : java.util.stream.Stream.concat(detailConfigs.stream(), exportConfigs.stream()).toList()) {
                if (config == null) throw BusinessResultProjectionPlan.denied();
                for (String canonical : aliases.getOrDefault(config.getFieldCode(), Set.of()).stream().sorted().toList()) {
                    if (!readable.contains(canonical)) continue;
                    if (!Set.of("PHONE", "EMAIL", "ID_CARD", "BANK_CARD", "NAME", "PARTIAL", "FULL", "CUSTOM")
                            .contains(Objects.toString(config.getMaskType(), "").toUpperCase(Locale.ROOT))) throw BusinessResultProjectionPlan.denied();
                    detailMasks.add(new BusinessResultProjectionPlan.DetailMask(canonical, config.getMaskType(), config.getMaskPattern(), config.getReplacementChar()));
                }
            }
            result.put(model, new CommandPublicOutputProjectionPlan.ModelFields(declared, readable, policyMasks, detailMasks.stream().distinct().toList()));
        }
        return new CommandPublicOutputProjectionPlan(context.getBusinessTargetAuthorization(), context.getBusinessGrantDecision(), result);
    }

    /** Only trusted, already owner-authorized rows may be supplied by the host resource accessor. */
    public Map<String, Object> project(CommandPipelineContext context, String model, Map<String, Object> internal) {
        CommandResultProjectionService.requireActor(context);
        var plan = context.getPublicOutputProjection();
        if (plan == null || plan.grant() != context.getBusinessGrantDecision()
                || !Objects.equals(plan.target(), context.getBusinessTargetAuthorization())
                || !Boolean.TRUE.equals(context.getTargetRecordReadable())) throw BusinessResultProjectionPlan.denied();
        var fieldPlan = plan.models().get(model);
        if (fieldPlan == null || internal == null) throw BusinessResultProjectionPlan.denied();
        Map<String, Object> output = new LinkedHashMap<>();
        for (String field : fieldPlan.readable()) {
            if (!internal.containsKey(field)) continue;
            Object value = internal.get(field);
            if (!(value == null || value instanceof String || value instanceof Boolean || value instanceof Number
                    || value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date))
                throw BusinessResultProjectionPlan.denied();
            output.put(field, value);
        }
        if (!output.isEmpty() && !fieldPlan.policyMasks().isEmpty()) {
            var rules = fieldPlan.policyMasks().stream().map(rule -> FieldMaskRule.builder().fieldCode(rule.field())
                    .maskType(rule.type()).maskExpression(rule.expression()).build()).toList();
            var masked = policies.applyFieldMasking(List.of(output), rules);
            if (masked == null || masked.size() != 1 || masked.getFirst() == null) throw BusinessResultProjectionPlan.denied();
            output = new LinkedHashMap<>(masked.getFirst());
            output.keySet().retainAll(fieldPlan.readable());
        }
        for (var config : fieldPlan.detailMasks()) {
            if (output.get(config.field()) != null) output.put(config.field(), masks.maskValue(output.get(config.field()).toString(),
                    config.type(), config.pattern(), config.replacement()));
        }
        return BusinessResourceAuthorization.snapshot(output);
    }
}
