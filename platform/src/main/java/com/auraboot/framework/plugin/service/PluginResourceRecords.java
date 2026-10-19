package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.common.util.UlidGenerator;
import java.time.Instant;
import java.util.Map;

/** Shared plugin import ledger construction. */
public final class PluginResourceRecords {
    private PluginResourceRecords() {}
    public static PluginResource createResourceRecord(String pluginPid, String importId, Long tenantId,
                                                ResourceType type, String resourcePid, Long resourceId,
                                                String code, String name, ResourceAction action,
                                                Map<String, Object> previousState, Map<String, Object> currentState) {
        return createResourceRecord(pluginPid, importId, tenantId, type, resourcePid, resourceId,
                code, name, action, previousState, currentState, null);
    }

    public static PluginResource createResourceRecord(String pluginPid, String importId, Long tenantId,
                                                ResourceType type, String resourcePid, Long resourceId,
                                                String code, String name, ResourceAction action,
                                                Map<String, Object> previousState, Map<String, Object> currentState,
                                                String pluginVersion) {
        OwnershipType ownership = type.getDefaultOwnership();
        if (action == ResourceAction.SKIP) {
            ownership = null;
        }

        return PluginResource.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .pluginPid(pluginPid)
                .importId(importId)
                .resourceType(type.code())
                .resourcePid(resourcePid)
                .resourceId(resourceId)
                .resourceCode(code)
                .resourceName(name)
                .action(action.code())
                .previousState(previousState)
                .currentState(currentState)
                .ownershipType(ownership != null ? ownership.code() : OwnershipType.SHARED.code())
                .importSnapshot(currentState)
                .userModified(false)
                .lastSyncVersion(pluginVersion)
                .sequence(type.getImportOrder())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

}
