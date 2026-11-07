package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.service.FieldPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;

import java.util.LinkedHashSet;
import java.util.Set;

/** Prevent raw-value inference through conditional mutation outcomes. */
@RequiredArgsConstructor
public final class FieldPredicateAuthorizationSupport {
    private final FieldPermissionService fields;
    private final DataPermissionEngine policies;
    private final FieldMaskService masks;

    void requireReadable(ModelDefinition model, Set<String> fieldCodes) {
        if (MachineDataAccessContext.isActive()) {
            var decision = MachineDataAccessContext.requireDecision(model.getCode());
            if (!decision.readableFields().containsAll(fieldCodes)) deny();
            return;
        }
        // Trusted internal execution has already checked its separate owner resource contract.
        if (MetaContext.isCommandPermitBoundTo(model.getCode())) return;
        Set<String> aliases = new LinkedHashSet<>(fieldCodes);
        for (var field : model.getFields()) {
            if (fieldCodes.contains(field.getCode()) && field.getColumnName() != null)
                aliases.add(field.getColumnName());
        }
        Long userId = MetaContext.getCurrentUserId();
        Long memberId = MetaContext.getCurrentMemberId();
        if (!com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()) {
            var permission = fields.getFieldPermissions(memberId, model.getCode());
            if (permission == null || permission.viewableFields() == null || permission.hiddenFields() == null)
                throw new MetaServiceException("Field permissions unavailable for conditional mutation");
            if (!permission.viewableFields().containsAll(fieldCodes)
                    || fieldCodes.stream().anyMatch(permission.hiddenFields()::contains)) deny();
        }
        requireUnmasked(model, fieldCodes);
    }

    /** Complete business grants supply positive field rights; mandatory masks still apply. */
    public void requireBusinessReadable(ModelDefinition model, Set<String> fieldCodes,
            com.auraboot.framework.permission.engine.model.BusinessGrantDecision decision) {
        NativeHumanActor.requireCompleteIdentity();
        if (decision == null || fieldCodes.stream().anyMatch(field -> !decision.canRead(field))) deny();
        requireUnmasked(model, fieldCodes);
    }

    private void requireUnmasked(ModelDefinition model, Set<String> fieldCodes) {
        Set<String> aliases = new LinkedHashSet<>(fieldCodes);
        for (var field : model.getFields()) {
            if (fieldCodes.contains(field.getCode()) && field.getColumnName() != null) aliases.add(field.getColumnName());
        }
        Long userId = MetaContext.getCurrentUserId();
        var rules = policies.getFieldMaskRules(MetaContext.getCurrentTenantId(), model.getCode(), userId);
        if (rules == null) throw new MetaServiceException("Field masks unavailable for conditional mutation");
        if (rules.stream().anyMatch(rule -> aliases.contains(rule.getFieldCode()))) deny();
        var configurations = masks.getEffectiveConfigs(model.getCode(), userId, "detail");
        if (configurations == null) throw new MetaServiceException("Field masks unavailable for conditional mutation");
        if (configurations.stream().anyMatch(config -> aliases.contains(config.getFieldCode()))) deny();
    }

    private static void deny() {
        throw new AccessDeniedException("FIELD_READ_DENIED: conditional mutation requires unmasked readable fields");
    }
}
