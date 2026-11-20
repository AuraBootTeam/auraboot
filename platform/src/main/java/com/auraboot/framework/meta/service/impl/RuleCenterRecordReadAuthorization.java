package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.service.PermissionFacade;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.function.Supplier;

/** Shared Rule Center stage; callers must still enforce tenant and row-scope authorization. */
public final class RuleCenterRecordReadAuthorization {
    private RuleCenterRecordReadAuthorization() {}

    public static void requireReadable(String modelCode, Map<String, Object> record,
            Supplier<Long> memberResolver, Supplier<PermissionFacade> facadeResolver) {
        Long memberId = memberResolver.get();
        if (memberId == null) {
            throw new MetaServiceException("Permission context missing for model: " + modelCode);
        }
        PermissionFacade facade = facadeResolver.get();
        if (facade == null) {
            throw new MetaServiceException("Permission facade unavailable for model: " + modelCode);
        }
        var result = facade.canOperate(memberId, modelCode, "read", record);
        if (result == null || !result.granted()) {
            throw new AccessDeniedException("Access denied: you do not have permission to view this record");
        }
    }
}
