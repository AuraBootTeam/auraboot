package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;

/** Identifies the physical concurrency primitive independently of actor and custom table name. */
final class PhysicalRowVersionSupport {
    private PhysicalRowVersionSupport() { }

    static boolean isVersioned(ModelDefinition model, DynamicDataMapper mapper) {
        if ((model.getSourceType() != null && !"physical".equals(model.getSourceType()))
                || "view".equalsIgnoreCase(model.getModelType()) || model.getTableName() == null) return false;
        String table = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        // Platform-generated mt_ schemas always contain the primitive. Custom physical schemas,
        // including ab_* bindings, use their actual column contract rather than an actor shortcut.
        return table.startsWith(SystemFieldConstants.DYNAMIC_TABLE_PREFIX)
                || mapper.checkColumnExists(table, "row_version") > 0;
    }
}
