package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.engine.model.PermissionExplanation;

/** Tenant-member permission diagnostics using caller-visible records. */
public interface PermissionExplanationService {
    PermissionExplanation explain(Long memberId, String resource, String action, String recordPid);
}
