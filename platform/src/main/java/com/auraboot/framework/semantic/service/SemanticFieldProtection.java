package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.semantic.compiler.CompiledQuery;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;

/** Reject aggregate inference from protected source fields before SQL leaves the compiler. */
@Service
@RequiredArgsConstructor
public class SemanticFieldProtection {
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;
    private final MetaModelService models;
    private final TenantMemberService members;

    public enum Verdict { ALLOW, DENY }
    public enum Reason { NONE, PROTECTED_SOURCE_COLUMN, UNRESOLVED_PROTECTED_FIELD }
    public record Protection(String fieldCode, String physicalColumn, String mechanism, String maskType) { }
    public record Plan(String sourceModelCode, List<String> referencedColumns,
                       List<Protection> protections, Verdict verdict, Reason reason) { }

    public void enforce(String modelCode, CompiledQuery query, UserContext user, Consumer<Plan> audit) {
        Plan plan = prepare(modelCode, query, user);
        audit.accept(plan);
        if (plan.verdict() == Verdict.DENY) {
            throw new AccessDeniedException(plan.reason() == Reason.UNRESOLVED_PROTECTED_FIELD
                    ? "Protected field has no physical column mapping"
                    : "Protected source fields cannot be used in semantic queries");
        }
    }

    private Plan prepare(String modelCode, CompiledQuery query, UserContext user) {
        // Effective mask configuration is tenant-context scoped. Never consult another
        // request's context or silently create an identity for a background caller.
        if (!MetaContext.exists() || user.tenantId() == null || user.userId() == null
                || !Objects.equals(user.tenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(user.userId(), MetaContext.getCurrentUserId())) {
            throw new AccessDeniedException("Semantic field protection requires matching caller context");
        }
        var member = members.findByTenantIdAndUserId(user.tenantId(), user.userId());
        if (member == null || !StatusConstants.ACTIVE.equalsIgnoreCase(member.getStatus()) || Boolean.TRUE.equals(member.getDeletedFlag())
                || !Objects.equals(user.tenantId(), member.getTenantId())
                || !Objects.equals(user.userId(), member.getUserId()) || member.getId() == null
                || !Objects.equals(member.getId(), MetaContext.getCurrentMemberId())) {
            throw new AccessDeniedException("Semantic field protection requires active caller membership");
        }
        List<Protection> protections = new ArrayList<>();
        Map<String, String> columns = new HashMap<>();
        policies.getFieldMaskRules(user.tenantId(), modelCode, user.userId()).forEach(rule ->
                protections.add(protection(modelCode, rule.getFieldCode(), "column-policy", rule.getMaskType(), columns)));
        // Include every context because aggregates can be displayed or exported.
        // Exemptions are still evaluated by the canonical masking provider.
        masks.getEffectiveConfigs(modelCode, user.userId(), "semantic").forEach(config ->
                protections.add(protection(modelCode, config.getFieldCode(), "mask-config", config.getMaskType(), columns)));
        Reason reason = Reason.NONE;
        for (Protection protection : protections) {
            String column = protection.physicalColumn();
            if (column == null || column.isBlank()) {
                reason = Reason.UNRESOLVED_PROTECTED_FIELD;
                break;
            }
            if (query.getReferencedColumns().stream().anyMatch(column::equalsIgnoreCase)) {
                reason = Reason.PROTECTED_SOURCE_COLUMN;
            }
        }
        return new Plan(modelCode, query.getReferencedColumns().stream().sorted().toList(),
                List.copyOf(protections), reason == Reason.NONE ? Verdict.ALLOW : Verdict.DENY, reason);
    }

    private Protection protection(String model, String field, String mechanism, String maskType,
                                  Map<String, String> columns) {
        String column = field == null || field.isBlank() ? null
                : columns.computeIfAbsent(field, code -> physicalColumn(model, code));
        return new Protection(field, column, mechanism, maskType);
    }

    private String physicalColumn(String model, String field) {
        try {
            return models.getColumnName(model, field);
        } catch (MetaServiceException unresolved) {
            // The metadata boundary uses this exact exception for unresolved fields.
            // Keep a denied Plan rather than silently dropping an unresolvable rule.
            return null;
        }
    }
}
