package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.semantic.compiler.CompiledQuery;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Reject aggregate inference from protected source fields before SQL leaves the compiler. */
@Service
@RequiredArgsConstructor
public class SemanticFieldProtection {
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;
    private final MetaModelService models;
    private final TenantMemberService members;

    public void enforce(String modelCode, CompiledQuery query, UserContext user) {
        // Effective mask configuration is tenant-context scoped. Never consult another
        // request's context or silently create an identity for a background caller.
        if (!MetaContext.exists() || user.tenantId() == null || user.userId() == null
                || !Objects.equals(user.tenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(user.userId(), MetaContext.getCurrentUserId())) {
            throw new AccessDeniedException("Semantic field protection requires matching caller context");
        }
        var member = members.findByTenantIdAndUserId(user.tenantId(), user.userId());
        if (member == null || !"ACTIVE".equals(member.getStatus()) || Boolean.TRUE.equals(member.getDeletedFlag())
                || !Objects.equals(user.tenantId(), member.getTenantId())
                || !Objects.equals(user.userId(), member.getUserId()) || member.getId() == null
                || !Objects.equals(member.getId(), MetaContext.getCurrentMemberId())) {
            throw new AccessDeniedException("Semantic field protection requires active caller membership");
        }
        Set<String> fields = new LinkedHashSet<>();
        policies.getFieldMaskRules(user.tenantId(), modelCode, user.userId())
                .forEach(rule -> fields.add(rule.getFieldCode()));
        // Aggregates can be displayed or exported. Include every enabled context,
        // while retaining the canonical role and permission exemption evaluation.
        masks.getEffectiveConfigs(modelCode, user.userId(), "semantic")
                .forEach(config -> fields.add(config.getFieldCode()));
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw new AccessDeniedException("Protected field has no physical column mapping");
            }
            String column = models.getColumnName(modelCode, field);
            if (column == null || column.isBlank()) {
                throw new AccessDeniedException("Protected field has no physical column mapping");
            }
            if (query.getReferencedColumns().stream().anyMatch(column::equalsIgnoreCase)) {
                // Masking the final aggregate cannot hide the contribution of source
                // values, including values used only in predicates or derived metrics.
                throw new AccessDeniedException("Protected source fields cannot be used in semantic queries");
            }
        }
    }
}
