package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.IdentityProviderSaveRequest;
import com.auraboot.framework.auth.dto.IdentityProviderSummary;

import java.util.List;

public interface IdentityProviderManagementService {
    List<IdentityProviderSummary> list(String applicationCode, Long tenantId);
    IdentityProviderSummary save(IdentityProviderSaveRequest request, Long tenantId);
    void setStatus(String pid, String status, Long tenantId);
}
