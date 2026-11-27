package com.auraboot.framework.permission.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rbac.entity.RolePermission;

/** Configuration ownership only; it neither grants access nor proves a migration equivalent. */
public enum RoleGrantSourceOwnership {
    DIRECT, DETACHED_COPY, PUBLISHED_SNAPSHOT, EXTERNAL_SOURCE;

    public static RoleGrantSourceOwnership of(RolePermission binding) {
        if (binding.getSnapshotId() != null) return PUBLISHED_SNAPSHOT;
        if (binding.getSourceRef() == null) return DIRECT;
        String[] parts = binding.getSourceRef().split(":", -1);
        if (parts.length == 3 && "copy".equals(parts[0])
                && UniqueIdGenerator.isValid(parts[1]) && UniqueIdGenerator.isValid(parts[2])) return DETACHED_COPY;
        return EXTERNAL_SOURCE;
    }
}
