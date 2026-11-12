package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.FieldPermissionService;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/** Shared user-input checks; trusted derived writes keep their separate owner contracts. */
@RequiredArgsConstructor
public final class FieldWriteAuthorizationSupport {
    private final MetaModelService metadata;
    private final FieldPermissionService fields;
    private final DataPermissionEngine policies;
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "pid", "tenant_id", "created_at", "created_by", "updated_at", "updated_by");

    /** Return submitted keys, including column aliases, without mutating the submitted input. */
    public Set<String> nonWritableInputKeys(String modelCode, Set<String> inputKeys) {
        return restrictedInputKeys(modelCode, inputKeys);
    }

    /** Complete business grants replace model authority, never metadata field roles or mandatory restrictions. */
    public Set<String> nonWritablePolicyInputKeys(String modelCode, Set<String> inputKeys) {
        return restrictedInputKeys(modelCode, inputKeys);
    }

    private Set<String> restrictedInputKeys(String modelCode, Set<String> inputKeys) {
        NativeHumanActor.requireCompleteIdentity();
        if (inputKeys == null || inputKeys.isEmpty()) return Set.of();
        var policyFields = policies.getNonWritableFields(
                MetaContext.getCurrentTenantId(), modelCode, MetaContext.getCurrentUserId());
        Set<String> restricted = new LinkedHashSet<>();
        if (policyFields != null) restricted.addAll(policyFields);
        boolean human = MetaContext.getCurrentMemberId() != null && MetaContext.getCurrentUserId() != null
                && !com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active();
        if (!human && restricted.isEmpty()) return Set.of();
        var definitions = metadata.getModelFields(modelCode);
        if (definitions == null) throw new MetaServiceException("Field metadata unavailable for write authorization: " + modelCode);
        if (human && !definitions.isEmpty()) {
            var permissions = fields.getFieldPermissions(MetaContext.getCurrentMemberId(), modelCode);
            if (permissions == null || permissions.editableFields() == null
                    || permissions.viewableFields() == null || permissions.hiddenFields() == null)
                throw new MetaServiceException("Field permissions unavailable for write authorization: " + modelCode);
            for (var field : definitions) {
                if (!SYSTEM_FIELDS.contains(field.getCode()) && (!permissions.editableFields().contains(field.getCode())
                        || !permissions.viewableFields().contains(field.getCode()) || permissions.hiddenFields().contains(field.getCode())))
                    restricted.add(field.getCode());
            }
        }
        for (var field : definitions) {
            if (restricted.contains(field.getCode()) || restricted.contains(field.getColumnName())) {
                restricted.add(field.getCode());
                if (field.getColumnName() != null) restricted.add(field.getColumnName());
            }
        }
        Set<String> blocked = new LinkedHashSet<>();
        for (String key : inputKeys) {
            if (restricted.contains(key)) blocked.add(key);
        }
        return blocked;
    }
}
